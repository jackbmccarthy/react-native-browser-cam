import type { StreamConfig } from './types';

export interface StreamCommandResult {
  sessionId: string;
  commandId: string;
  operation: 'start' | 'stop';
  success: boolean;
  code?: string;
  message?: string;
}

type Pending = {
  sessionId: string;
  commandId: string;
  promise: Promise<void>;
  resolve: () => void;
  reject: (error: Error) => void;
  timer?: ReturnType<typeof setTimeout>;
};

let sequence = 0;
const failure = (code: string, message: string) => Object.assign(new Error(message), { code });

/** Private ref controller. Only tagged command results can settle successful commands. */
export class StreamCommands {
  private active: string | null = null;
  private stopped: string | null = null;
  private used = new Set<string>();
  private startPending: Pending | null = null;
  private stopPending: Pending | null = null;

  constructor(
    private getTag: () => number | null,
    private dispatch: (tag: number, command: string, args: unknown[]) => void,
    private timeoutMs = 60000,
  ) {}

  configureAndStartStreaming(config: StreamConfig, sessionId: string): Promise<void> {
    if (!sessionId?.trim()) return Promise.reject(failure('INVALID_SESSION', 'Session ID is required'));
    if (!config || !/^rtmps?:\/\/[^/\s]+\/[^/\s]+\/[^\s]+/.test(config.url ?? '') ||
        !['width', 'height', 'bitrate'].every(key => {
          const value = config[key as 'width' | 'height' | 'bitrate'];
          return Number.isSafeInteger(value) && value > 0;
        })) return Promise.reject(failure('INVALID_CONFIG', 'A complete RTMP stream configuration is required'));
    if (this.active || this.stopPending) return Promise.reject(failure('STREAM_BUSY', 'Stop the current session before starting'));
    if (this.used.has(sessionId)) return Promise.reject(failure('SESSION_REUSED', 'Use a fresh session ID for every start'));
    const tag = this.getTag();
    if (!tag) return Promise.reject(failure('VIEW_NOT_FOUND', 'View not available'));
    this.active = sessionId;
    this.used.add(sessionId);
    const pending = this.makePending(sessionId, 'start');
    this.startPending = pending;
    try {
      this.dispatch(tag, 'configureAndStartStreaming', [{ ...config }, sessionId, pending.commandId]);
    } catch (error) {
      this.clear(pending);
      this.startPending = null;
      this.active = null;
      pending.reject(error instanceof Error ? error : failure('COMMAND_FAILED', 'Command dispatch failed'));
    }
    return pending.promise;
  }

  stopStreamingAndWait(sessionId: string): Promise<void> {
    if (!sessionId?.trim()) return Promise.reject(failure('INVALID_SESSION', 'Session ID is required'));
    if (this.stopPending?.sessionId === sessionId) return this.stopPending.promise;
    if (this.stopped === sessionId && !this.active) return Promise.resolve();
    if (this.active !== sessionId) return Promise.reject(failure('SESSION_MISMATCH', 'No matching active session'));
    const tag = this.getTag();
    if (!tag) return Promise.reject(failure('VIEW_NOT_FOUND', 'View not available'));
    const start = this.startPending;
    if (start) {
      this.clear(start);
      this.startPending = null;
      start.reject(failure('STREAM_CANCELLED', 'Streaming start was cancelled by stop'));
    }
    const pending = this.makePending(sessionId, 'stop');
    this.stopPending = pending;
    try {
      this.dispatch(tag, 'stopStreamingAndWait', [sessionId, pending.commandId]);
    } catch (error) {
      this.clear(pending);
      this.stopPending = null;
      pending.reject(error instanceof Error ? error : failure('COMMAND_FAILED', 'Command dispatch failed'));
    }
    return pending.promise;
  }

  handleResult(result: StreamCommandResult): void {
    const pending = result.operation === 'start' ? this.startPending : this.stopPending;
    if (!pending || pending.sessionId !== result.sessionId || pending.commandId !== result.commandId) return;
    this.clear(pending);
    if (result.operation === 'start') this.startPending = null;
    else this.stopPending = null;
    if (result.success) {
      if (result.operation === 'stop') {
        this.active = null;
        this.stopped = result.sessionId;
      }
      pending.resolve();
    } else {
      pending.reject(failure(result.code ?? 'STREAM_COMMAND_FAILED', result.message ?? 'Native streaming command failed'));
      if (result.operation === 'start') {
        if (['STREAM_BUSY', 'INVALID_CONFIG', 'SESSION_REUSED', 'VIEW_NOT_FOUND'].includes(result.code ?? '')) this.active = null;
        else void this.stopStreamingAndWait(result.sessionId).catch(() => {});
      }
    }
  }

  dispose(): void {
    const session = this.active;
    const tag = this.getTag();
    for (const pending of [this.startPending, this.stopPending]) {
      if (pending) {
        this.clear(pending);
        pending.reject(failure('STREAM_CANCELLED', 'Camera view was unmounted'));
      }
    }
    this.startPending = null;
    this.stopPending = null;
    if (session && tag) {
      try { this.dispatch(tag, 'stopStreamingAndWait', [session, `dispose-${++sequence}`]); } catch { /* Native view cleanup also stops output. */ }
    }
    this.active = null;
  }

  private makePending(sessionId: string, operation: 'start' | 'stop'): Pending {
    let resolve!: () => void;
    let reject!: (error: Error) => void;
    const promise = new Promise<void>((yes, no) => { resolve = yes; reject = no; });
    const pending: Pending = { sessionId, commandId: `stream-${Date.now()}-${++sequence}`, promise, resolve, reject };
    pending.timer = setTimeout(() => {
      pending.timer = undefined;
      if (operation === 'start' && this.startPending === pending) {
        this.startPending = null;
        reject(failure('STREAM_TIMEOUT', 'Native streaming start timed out'));
        void this.stopStreamingAndWait(sessionId).catch(() => {});
      } else if (operation === 'stop' && this.stopPending === pending) {
        // Keep ownership until a late teardown acknowledgment arrives. Timeout is not teardown.
        reject(failure('STREAM_TIMEOUT', 'Native streaming teardown timed out'));
      }
    }, this.timeoutMs);
    return pending;
  }

  private clear(pending: Pending): void {
    if (pending.timer !== undefined) clearTimeout(pending.timer);
    pending.timer = undefined;
  }
}
