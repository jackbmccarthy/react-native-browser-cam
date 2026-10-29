'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');

function harness({ commands = {}, modules = {}, platform = 'ios' } = {}) {
  const calls = [], timers = new Map(), effects = [], cache = new Map();
  let timerID = 0, view, dispatchError;
  const ref = { current: null };
  const names = ['startStreaming', 'stopStreaming', 'configureAndStartStreaming', 'stopStreamingAndWait',
    'startRecording', 'stopRecording', 'reloadWebView', 'focusAtPoint', 'switchCamera', 'setMuted'];
  const commandIDs = Object.fromEntries(names.map((name, index) => [name, index + 1]));
  Object.assign(commandIDs, commands);
  const react = {
    forwardRef: render => render,
    useRef: value => ({ current: value }),
    useCallback: callback => callback,
    useImperativeHandle: (target, create) => { target.current = create(); },
    useEffect: create => { effects.push(create); },
    createElement(type, props) {
      if (props.ref) props.ref.current = { nativeTag: 17 };
      view = { type, props }; return view;
    },
  };
  const native = {
    Platform: { OS: platform },
    NativeModules: { BrowserCameraModule: modules },
    requireNativeComponent: () => 'NativeBrowserCameraView',
    findNodeHandle: target => target?.nativeTag ?? null,
    UIManager: {
      getViewManagerConfig: () => ({ Commands: commandIDs }),
      dispatchViewManagerCommand: (tag, command, args) => {
        if (dispatchError) throw dispatchError;
        calls.push({ tag, command, args });
      },
    },
  };
  const context = vm.createContext({ console,
    setTimeout(callback, ms) { const id = ++timerID; timers.set(id, { callback, ms }); return id; },
    clearTimeout: id => timers.delete(id),
  });
  function load(file) {
    if (cache.has(file)) return cache.get(file).exports;
    const module = { exports: {} }; cache.set(file, module);
    const code = ts.transpileModule(fs.readFileSync(file, 'utf8'), { fileName: file, compilerOptions: {
      target: ts.ScriptTarget.ES2020, module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.React, esModuleInterop: true,
    } }).outputText;
    const requireLocal = name => {
      if (name === 'react') return react;
      if (name === 'react-native') return native;
      if (name === './StreamCommands' || name === './types') return load(path.resolve(path.dirname(file), name + '.ts'));
      throw new Error('Unexpected dependency: ' + name);
    };
    vm.runInContext(`(function(require,module,exports){${code}\n})`, context, { filename: file })(requireLocal, module, module.exports);
    return module.exports;
  }
  const library = load(path.resolve(__dirname, '../src/index.tsx'));
  const props = { cameraConfig: { facing: 'back' } };
  library.BrowserCamView(props, ref);
  const cleanup = effects.map(create => create()).filter(Boolean);
  return { ref, calls, timers, library, commands: commandIDs,
    setDispatchError(error) { dispatchError = error; },
    event(name, payload) { view.props[name]({ nativeEvent: payload }); },
    deadline() { const [id, timer] = timers.entries().next().value; timers.delete(id); timer.callback(); },
    unmount() { for (const dispose of cleanup.splice(0)) dispose(); },
  };
}

test('legacy ref operations still dispatch to the mounted native view without app admission', async () => {
  const h = harness();
  try {
    await h.ref.current.startStreaming(); await h.ref.current.stopStreaming();
    h.ref.current.reloadWebView(); h.ref.current.focusAtPoint(0.25, 0.75); h.ref.current.setMuted(true);
    assert.deepEqual(h.calls.map(call => call.command), [1, 2, 7, 8, 10]);
    assert.ok(h.calls.every(call => call.tag === 17));
    assert.deepEqual(Array.from(h.calls[3].args), [0.25, 0.75]);
    assert.equal(h.timers.size, 0);
  } finally { h.unmount(); }
});

test('managed ref promises require matching native command events, not stream state', async () => {
  const h = harness();
  try {
    const started = h.ref.current.configureAndStartStreaming({ url: 'rtmp://fixture.invalid/live/key', width: 1280, height: 720, bitrate: 2500000 }, 'session-one');
    const call = h.calls[0]; let settled = false; started.then(() => { settled = true; });
    h.event('onStreamStateChange', { state: 'streaming' }); await Promise.resolve();
    assert.equal(settled, false);
    h.event('onStreamCommandResult', { operation: 'start', sessionId: 'session-one', commandId: call.args[2], success: true });
    await started;
    const stopped = h.ref.current.stopStreamingAndWait('session-one');
    const stop = h.calls.at(-1);
    h.event('onStreamCommandResult', { operation: 'stop', sessionId: 'session-one', commandId: stop.args[1], success: true });
    await stopped; assert.equal(h.timers.size, 0);
  } finally { h.unmount(); }
});

test('managed commands reject when native command installation is missing', async () => {
  const h = harness({ commands: { configureAndStartStreaming: undefined } });
  try {
    await assert.rejects(h.ref.current.configureAndStartStreaming({ url: 'rtmp://fixture.invalid/live/key', width: 1280, height: 720, bitrate: 2500000 }, 'session-one'), /not installed/);
    assert.equal(h.calls.length, 0); assert.equal(h.timers.size, 0);
  } finally { h.unmount(); }
});

test('recording stop requires a nonempty native completion path', async () => {
  for (const file of ['/recordings/one.mp4', '', '   ']) {
    const h = harness();
    try {
      const pending = h.ref.current.stopRecording();
      const expected = file.trim() ? assert.doesNotReject(pending) : assert.rejects(pending, error => error.code === 'RECORDING_FAILED');
      h.event('onRecordingFinished', { path: file }); await expected;
      if (file.trim()) assert.equal(await pending, file);
      assert.equal(h.timers.size, 0);
    } finally { h.unmount(); }
  }
});

test('recording timeout and unmount cannot report a previous recording as saved', async () => {
  for (const action of ['deadline', 'unmount']) {
    const h = harness();
    try {
      h.event('onRecordingFinished', { path: '/recordings/previous.mp4' });
      const pending = h.ref.current.stopRecording();
      const checked = assert.rejects(pending, error => error.code === (action === 'deadline' ? 'RECORDING_STOP_TIMEOUT' : 'RECORDING_STOP_CANCELLED'));
      assert.equal([...h.timers.values()][0].ms, 60000);
      if (action === 'deadline') h.deadline(); else h.unmount();
      await checked; assert.equal(h.timers.size, 0);
    } finally { h.unmount(); }
  }
});

test('duplicate recording stops cannot dispatch a second command while native save is pending', async () => {
  const h = harness();
  try {
    const first = h.ref.current.stopRecording();
    await assert.rejects(h.ref.current.stopRecording(), /already pending/);
    assert.equal(h.calls.length, 1);
    h.event('onRecordingFinished', { path: '/recordings/current.mp4' }); await first;
  } finally { h.unmount(); }
});

test('recording dispatch failure clears its own timer and permits an explicit stop retry', async () => {
  const h = harness();
  try {
    h.setDispatchError(new Error('Bridge unavailable'));
    await assert.rejects(h.ref.current.stopRecording(), /Bridge unavailable/);
    assert.equal(h.timers.size, 0, 'A rejected bridge dispatch must not leave recording stop pending');
    h.setDispatchError(undefined);
    const retry = h.ref.current.stopRecording();
    h.event('onRecordingFinished', { path: '/recordings/retry.mp4' });
    assert.equal(await retry, '/recordings/retry.mp4');
    assert.equal(h.calls.length, 1); assert.equal(h.timers.size, 0);
  } finally { h.unmount(); }
});

test('a native cancellation of queued recording start rejects Stop promptly', async () => {
  const h = harness();
  try {
    const pending = h.ref.current.stopRecording();
    let outcome;
    pending.then(value => { outcome = { value }; }, error => { outcome = { error }; });
    h.event('onError', { code: 'RECORDING_CANCELLED', message: 'Recording start was cancelled before capture began' });
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(h.timers.size, 0, 'A known native cancellation must not wait for the uncertain Stop timeout');
    assert.equal(outcome?.error?.code, 'RECORDING_CANCELLED');
  } finally { h.unmount(); }
});

test('iOS-only tap-to-focus never sends an unsupported Android command', () => {
  const h = harness({ platform: 'android' });
  try { h.ref.current.focusAtPoint(0.5, 0.5); assert.equal(h.calls.length, 0); }
  finally { h.unmount(); }
});

test('native utility exports select hardware APIs and retain fallback behavior', async () => {
  const cameras = [{ id: 'physical-back', label: 'Back', position: 'back' }];
  const h = harness({ modules: { getAvailableCameras: async () => cameras } });
  try {
    assert.equal(await h.library.CameraDeviceUtils.getAvailableCameras(), cameras);
    assert.equal((await h.library.AudioDeviceUtils.getAvailableAudioInputs())[0].id, 'default');
    assert.deepEqual(Array.from(await h.library.FileSystemUtils.listRecordings()), []);
  } finally { h.unmount(); }
});
