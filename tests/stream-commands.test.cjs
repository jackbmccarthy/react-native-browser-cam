'use strict';

const nodeTest = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const { loadTypeScript } = require('./helpers/load-ts.cjs');
const test = (name, run) => nodeTest(name, { timeout: 2000 }, run);

const source = path.resolve(__dirname, '../src/StreamCommands.ts');
const config = Object.freeze({
  url: 'rtmp://example.invalid/live/test', width: 1280, height: 720, bitrate: 2500000,
});

function harness(t, timeoutMs) {
  const timers = new Map();
  const observations = [];
  const outcomes = new Map();
  let timerId = 0;
  let tag = 17;
  let dispatchError;
  const { StreamCommands } = loadTypeScript(source, {
    Error, Promise,
    setTimeout(callback, delay) {
      const id = ++timerId;
      timers.set(id, { callback, delay });
      return id;
    },
    clearTimeout(id) { timers.delete(id); },
  });
  const calls = [];
  const controller = new StreamCommands(() => tag, (viewTag, command, args) => {
    if (dispatchError !== undefined) throw dispatchError;
    calls.push({ tag: viewTag, command, args });
  }, timeoutMs);

  // Observe every returned promise immediately, including paths interrupted by a failed assertion.
  function track(promise) {
    if (!outcomes.has(promise)) {
      outcomes.set(promise, { state: 'pending' });
      observations.push(promise.then(
        value => outcomes.set(promise, { state: 'fulfilled', value }),
        error => outcomes.set(promise, { state: 'rejected', error }),
      ));
    }
    return promise;
  }

  t.after(async () => {
    controller.dispose();
    await Promise.resolve();
    for (const outcome of outcomes.values()) {
      assert.notEqual(outcome.state, 'pending', 'dispose must settle every observed command');
    }
    await Promise.all(observations);
    assert.equal(timers.size, 0, 'dispose must clear all fake timers');
  });

  return {
    controller, calls, timers,
    start(value, session = 'session-1') {
      return track(controller.configureAndStartStreaming(arguments.length ? value : config, session));
    },
    stop(session = 'session-1') { return track(controller.stopStreamingAndWait(session)); },
    setTag(value) { tag = value; },
    throwDispatch(value) { dispatchError = value; },
    expireNext() {
      const entry = timers.entries().next().value;
      assert.ok(entry, 'expected a pending native-command deadline');
      const [id, timer] = entry;
      timers.delete(id);
      timer.callback();
      return timer.delay;
    },
    result(call = calls.at(-1), overrides = {}) {
      assert.ok(call, 'expected a dispatched command');
      const start = call.command === 'configureAndStartStreaming';
      controller.handleResult({
        sessionId: call.args[start ? 1 : 0],
        commandId: call.args[start ? 2 : 1],
        operation: start ? 'start' : 'stop',
        success: true,
        ...overrides,
      });
    },
    async pending(promise) {
      await Promise.resolve();
      assert.equal(outcomes.get(promise)?.state, 'pending');
    },
  };
}

const rejected = (promise, code) => assert.rejects(promise, error => error.code === code);

test('invalid sessions reject both commands before transport or timers', async t => {
  const h = harness(t);
  for (const session of ['', '   ', '\n\t', null, undefined]) {
    await rejected(h.controller.configureAndStartStreaming(config, session), 'INVALID_SESSION');
    await rejected(h.controller.stopStreamingAndWait(session), 'INVALID_SESSION');
  }
  assert.equal(h.calls.length, 0);
  assert.equal(h.timers.size, 0);
});

test('incomplete RTMP configurations reject before reserving a session', async t => {
  const h = harness(t);
  const invalid = [null, undefined, {}, { ...config, url: '' }];
  for (const url of ['https://example.invalid/live/key', 'rtmp://example.invalid', 'rtmp://example.invalid/live', 'rtmp://bad host.invalid/live/key']) {
    invalid.push({ ...config, url });
  }
  for (const field of ['width', 'height', 'bitrate']) {
    for (const value of [undefined, 0, -1, 1.5, NaN, Infinity, Number.MAX_SAFE_INTEGER + 1, '720']) {
      invalid.push({ ...config, [field]: value });
    }
  }
  for (const value of invalid) {
    const start = h.start(value);
    assert.equal(h.calls.length, 0, 'invalid configuration must not reach the transport');
    await rejected(start, 'INVALID_CONFIG');
  }
  assert.equal(h.calls.length, 0);
  assert.equal(h.timers.size, 0);
  const start = h.start();
  h.result();
  await start;
});

test('RTMP and RTMPS dispatch a configuration snapshot and generated correlation ID', async t => {
  for (const scheme of ['rtmp', 'rtmps']) {
    await t.test(scheme, async t => {
      const h = harness(t);
      const mutable = { ...config, url: `${scheme}://example.invalid/live/key`, audio: true };
      const snapshot = { ...mutable };
      const start = h.start(mutable);
      const call = h.calls[0];
      assert.equal(call.tag, 17);
      assert.equal(call.command, 'configureAndStartStreaming');
      assert.equal(call.args.length, 3);
      assert.notEqual(call.args[0], mutable);
      assert.deepEqual({ ...call.args[0] }, snapshot);
      assert.equal(call.args[1], 'session-1');
      assert.equal(typeof call.args[2], 'string');
      assert.ok(call.args[2].length > 0);
      mutable.url = 'rtmp://changed.invalid/live/key';
      mutable.width = 1;
      assert.deepEqual({ ...call.args[0] }, snapshot);
      await h.pending(start);
      h.result(call);
      assert.equal(await start, undefined);
      assert.equal(h.timers.size, 0);
    });
  }
});

test('start needs its exact native session, command and operation acknowledgment', async t => {
  const h = harness(t);
  const start = h.start();
  for (const wrong of [{ sessionId: 'stale-session' }, { commandId: 'stale-command' }, { operation: 'stop' }]) {
    h.result(h.calls[0], wrong);
    await h.pending(start);
    assert.equal(h.timers.size, 1);
  }
  await rejected(h.start(config, 'session-2'), 'STREAM_BUSY');
  assert.equal(h.calls.length, 1);
  h.result(h.calls[0]);
  await start;
  h.result(h.calls[0], { success: false, code: 'LATE_ERROR' });
  await rejected(h.start(config, 'session-2'), 'STREAM_BUSY');
  assert.equal(h.calls.length, 1);
});

test('view lookup failures do not consume sessions; stop lookup failures retain ownership', async t => {
  const h = harness(t);
  for (const missing of [null, 0]) {
    h.setTag(missing);
    await rejected(h.start(), 'VIEW_NOT_FOUND');
  }
  assert.equal(h.calls.length, 0);
  assert.equal(h.timers.size, 0);
  h.setTag(23);
  const start = h.start();
  h.result();
  await start;
  h.setTag(null);
  await rejected(h.stop(), 'VIEW_NOT_FOUND');
  await rejected(h.start(config, 'session-2'), 'STREAM_BUSY');
  assert.equal(h.timers.size, 0);
  h.setTag(29);
  const stop = h.stop();
  assert.equal(h.calls.at(-1).tag, 29);
  h.result();
  await stop;
});

test('stop during start cancels start and returns the same promise for duplicate stops', async t => {
  const h = harness(t);
  const start = h.start();
  const cancellation = rejected(start, 'STREAM_CANCELLED');
  const startCall = h.calls[0];
  const stop = h.stop();
  assert.equal(h.stop(), stop);
  await cancellation;
  assert.equal(h.calls.length, 2);
  const stopCall = h.calls[1];
  assert.equal(stopCall.command, 'stopStreamingAndWait');
  assert.equal(stopCall.args.length, 2);
  assert.equal(stopCall.args[0], 'session-1');
  assert.notEqual(stopCall.args[1], startCall.args[2]);
  assert.equal(h.timers.size, 1);
  h.result(startCall);
  h.controller.handleResult({ state: 'disconnected' });
  for (const wrong of [{ sessionId: 'other' }, { commandId: startCall.args[2] }, { operation: 'start' }]) {
    h.result(stopCall, wrong);
    await h.pending(stop);
  }
  h.result(stopCall);
  assert.equal(await stop, undefined);
  assert.equal(h.timers.size, 0);
  await h.stop();
  assert.equal(h.calls.length, 2);
});

test('stop for an unknown or different session cannot cancel a pending start', async t => {
  const h = harness(t);
  await rejected(h.stop(), 'SESSION_MISMATCH');
  const start = h.start();
  await rejected(h.stop('other'), 'SESSION_MISMATCH');
  assert.equal(h.calls.length, 1);
  await h.pending(start);
  h.result();
  await start;
});

test('acknowledged stop releases ownership, but sessions and correlation IDs cannot be reused', async t => {
  const h = harness(t);
  const first = h.start();
  const oldStart = h.calls[0];
  h.result(oldStart);
  await first;
  const stop = h.stop();
  const oldStop = h.calls[1];
  h.result(oldStop);
  await stop;
  await rejected(h.start(), 'SESSION_REUSED');
  const next = h.start(config, 'session-2');
  const newStart = h.calls[2];
  assert.equal(new Set([oldStart.args[2], oldStop.args[1], newStart.args[2]]).size, 3);
  h.result(oldStart);
  h.result(oldStop);
  await h.pending(next);
  await rejected(h.stop(), 'SESSION_MISMATCH');
  await rejected(h.start(config, 'session-3'), 'STREAM_BUSY');
  h.result(newStart);
  await next;
});

test('default start deadline commands teardown; teardown timeout is not an acknowledgment', async t => {
  const h = harness(t);
  const start = h.start();
  const expired = rejected(start, 'STREAM_TIMEOUT');
  const startCall = h.calls[0];
  assert.equal(h.expireNext(), 60000);
  await expired;
  assert.equal(h.calls.length, 2);
  assert.equal(h.calls[1].command, 'stopStreamingAndWait');
  const stop = h.stop();
  const stopExpired = rejected(stop, 'STREAM_TIMEOUT');
  assert.equal(h.expireNext(), 60000);
  await stopExpired;
  assert.equal(h.timers.size, 0);
  assert.equal(h.stop(), stop);
  assert.equal(h.calls.length, 2);
  h.result(startCall);
  await rejected(h.start(config, 'session-2'), 'STREAM_BUSY');
  h.result(h.calls[1], { commandId: 'stale' });
  await rejected(h.start(config, 'session-2'), 'STREAM_BUSY');
  h.result(h.calls[1]);
  await rejected(stop, 'STREAM_TIMEOUT');
  await h.stop();
  const next = h.start(config, 'session-2');
  h.result();
  await next;
});

test('explicit stop timeout retains the same pending command until a late correlated ACK', async t => {
  const h = harness(t, 9);
  const start = h.start();
  h.result();
  await start;
  const stop = h.stop();
  const expired = rejected(stop, 'STREAM_TIMEOUT');
  assert.equal(h.expireNext(), 9);
  await expired;
  for (const wrong of [{ sessionId: 'other' }, { commandId: 'other' }, { operation: 'start' }]) {
    h.result(h.calls[1], wrong);
    await rejected(h.start(config, 'session-2'), 'STREAM_BUSY');
  }
  assert.equal(h.stop(), stop);
  assert.equal(h.calls.length, 2);
  h.result(h.calls[1]);
  const next = h.start(config, 'session-2');
  h.result();
  await next;
});

test('native start failure rejects with its diagnostic and requests acknowledged teardown', async t => {
  for (const details of [{}, { code: 'PUBLISH_FAILED', message: 'Publisher could not connect' }]) {
    await t.test(details.code || 'default diagnostic', async t => {
      const h = harness(t);
      const start = h.start();
      const failure = assert.rejects(start, error => {
        assert.equal(error.code, details.code || 'STREAM_COMMAND_FAILED');
        assert.equal(error.message, details.message || 'Native streaming command failed');
        return true;
      });
      h.result(h.calls[0], { success: false, ...details });
      await failure;
      assert.equal(h.calls.length, 2);
      assert.equal(h.calls[1].command, 'stopStreamingAndWait');
      await rejected(h.start(config, 'session-2'), 'STREAM_BUSY');
      const stop = h.stop();
      h.result(h.calls[1]);
      await stop;
      const next = h.start(config, 'session-2');
      h.result();
      await next;
    });
  }
});

test('native admission refusals release local ownership without dispatching teardown', async t => {
  for (const code of ['STREAM_BUSY', 'INVALID_CONFIG', 'SESSION_REUSED', 'VIEW_NOT_FOUND']) {
    await t.test(code, async t => {
      const h = harness(t);
      const start = h.start();
      const failure = rejected(start, code);
      h.result(h.calls[0], { success: false, code });
      await failure;
      assert.equal(h.calls.length, 1);
      assert.equal(h.timers.size, 0);
      await rejected(h.start(), 'SESSION_REUSED');
      const next = h.start(config, 'session-2');
      h.result();
      await next;
    });
  }
});

test('native stop failure retains ownership and retry needs a fresh command ACK', async t => {
  const h = harness(t);
  const start = h.start();
  h.result();
  await start;
  const stop = h.stop();
  const oldStop = h.calls[1];
  const failed = rejected(stop, 'TEARDOWN_FAILED');
  h.result(oldStop, { success: false, code: 'TEARDOWN_FAILED', message: 'Output remains open' });
  await failed;
  assert.equal(h.timers.size, 0);
  await rejected(h.start(config, 'session-2'), 'STREAM_BUSY');
  const retry = h.stop();
  assert.notEqual(retry, stop);
  assert.notEqual(h.calls[2].args[1], oldStop.args[1]);
  h.result(oldStop);
  await h.pending(retry);
  h.result(h.calls[2]);
  await retry;
  const next = h.start(config, 'session-2');
  h.result();
  await next;
});

test('start dispatch throws reject without leaking timers or retaining active ownership', async t => {
  for (const thrown of [new Error('Bridge unavailable'), 'non-Error bridge failure']) {
    await t.test(typeof thrown, async t => {
      const h = harness(t);
      h.throwDispatch(thrown);
      await assert.rejects(h.start(), error => {
        if (thrown instanceof Error) assert.equal(error, thrown);
        else assert.equal(error.code, 'COMMAND_FAILED');
        return true;
      });
      assert.equal(h.calls.length, 0);
      assert.equal(h.timers.size, 0);
      h.throwDispatch(undefined);
      await rejected(h.start(), 'SESSION_REUSED');
      const next = h.start(config, 'session-2');
      h.result();
      await next;
    });
  }
});

test('stop dispatch throws cancel a pending start but preserve ownership for a stop retry', async t => {
  for (const thrown of [new Error('Stop bridge unavailable'), null]) {
    await t.test(thrown === null ? 'non-Error' : 'Error', async t => {
      const h = harness(t);
      const start = h.start();
      const cancelled = rejected(start, 'STREAM_CANCELLED');
      h.throwDispatch(thrown);
      await assert.rejects(h.stop(), error => {
        if (thrown instanceof Error) assert.equal(error, thrown);
        else assert.equal(error.code, 'COMMAND_FAILED');
        return true;
      });
      await cancelled;
      assert.equal(h.timers.size, 0);
      await rejected(h.start(config, 'session-2'), 'STREAM_BUSY');
      h.throwDispatch(undefined);
      const retry = h.stop();
      h.result();
      await retry;
      assert.equal(h.calls.length, 2);
    });
  }
});

test('dispose rejects pending start or stop and sends one best-effort teardown', async t => {
  for (const operation of ['start', 'stop']) {
    await t.test(operation, async t => {
      const h = harness(t);
      const start = h.start();
      let pending = start;
      if (operation === 'stop') {
        h.result();
        await start;
        pending = h.stop();
      }
      const cancelled = rejected(pending, 'STREAM_CANCELLED');
      const pendingCall = h.calls.at(-1);
      const before = h.calls.length;
      h.controller.dispose();
      await cancelled;
      assert.equal(h.timers.size, 0);
      assert.equal(h.calls.length, before + 1);
      const cleanup = h.calls.at(-1);
      assert.equal(cleanup.command, 'stopStreamingAndWait');
      assert.equal(cleanup.args[0], 'session-1');
      assert.equal(typeof cleanup.args[1], 'string');
      assert.notEqual(cleanup.args[1], pendingCall.args[operation === 'start' ? 2 : 1]);
      h.result(pendingCall);
      await rejected(pending, 'STREAM_CANCELLED');
      h.controller.dispose();
      assert.equal(h.calls.length, before + 1);
    });
  }
});

test('dispose still cancels pending work when view is gone or teardown dispatch throws', async t => {
  for (const unavailable of ['tag', 'dispatch']) {
    await t.test(unavailable, async t => {
      const h = harness(t);
      const start = h.start();
      const cancelled = rejected(start, 'STREAM_CANCELLED');
      if (unavailable === 'tag') h.setTag(null);
      else h.throwDispatch(new Error('View already removed'));
      assert.doesNotThrow(() => h.controller.dispose());
      await cancelled;
      assert.equal(h.calls.length, 1);
      assert.equal(h.timers.size, 0);
    });
  }
});
