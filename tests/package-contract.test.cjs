'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ts = require('typescript');
const root = path.resolve(__dirname, '..');

test('the TypeScript build emits the JavaScript and declaration entry points declared by the package', () => {
  const pkg = JSON.parse(fs.readFileSync(path.join(root, 'package.json'), 'utf8'));
  const configFile = path.join(root, 'tsconfig.json');
  const config = ts.readConfigFile(configFile, ts.sys.readFile);
  assert.equal(config.error, undefined);
  const parsed = ts.parseJsonConfigFileContent(config.config, ts.sys, root, undefined, configFile);
  assert.deepEqual(parsed.errors, []);
  const output = ts.getOutputFileNames(parsed, path.join(root, 'src/index.tsx'), false);
  assert.ok(output.includes(path.resolve(root, pkg.main)), 'Build must produce package.main, not only an unreferenced JSX file');
  assert.ok(output.includes(path.resolve(root, pkg.types)));
});

test('ordinary public library mounting has no app capture-admission or recovery dependency', () => {
  const nativeRoot = path.join(root, 'android/src/main/java/com/webcamstream');
  for (const file of ['BrowserCameraView.kt', 'BrowserCameraModule.kt']) {
    const source = fs.readFileSync(path.join(nativeRoot, file), 'utf8');
    assert.doesNotMatch(source, /CaptureProcessRegistry|captureProcessAdmission|auditCaptureQuiescence|admitCaptureView/);
  }
  const source = fs.readFileSync(path.join(root, 'src/index.tsx'), 'utf8');
  assert.doesNotMatch(source, /releaseCapture|onCaptureRelease|captureReleaseIdentity/);
});

test('managed command and audio callbacks remain wired in both native managers', () => {
  const android = fs.readFileSync(path.join(root, 'android/src/main/java/com/webcamstream/BrowserCameraViewManager.kt'), 'utf8');
  const objc = fs.readFileSync(path.join(root, 'ios/BrowserCameraViewManager.m'), 'utf8');
  for (const source of [android, objc]) {
    for (const name of ['configureAndStartStreaming', 'stopStreamingAndWait', 'onStreamCommandResult', 'onAudioLevel']) {
      assert.ok(source.includes(name), 'Missing native bridge contract: ' + name);
    }
  }
});
