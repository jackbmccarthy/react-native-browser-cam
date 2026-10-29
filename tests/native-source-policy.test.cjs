const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');

const root = path.resolve(__dirname, '..');
const android = 'android/src/main/java/com/webcamstream';
const sources = [android, 'ios'].flatMap(directory =>
  fs.readdirSync(path.join(root, directory))
    .filter(name => /\.(?:kt|swift|m)$/.test(name))
    .map(name => ({
      file: `${directory}/${name}`,
      lines: fs.readFileSync(path.join(root, directory, name), 'utf8').split('\n'),
    }))
);

function locations(predicate) {
  return sources.flatMap(({ file, lines }) => lines.flatMap((line, index) =>
    !line.trimStart().startsWith('//') && predicate(file, line)
      ? [`${file}:${index + 1}`] : []
  ));
}

// Deliberately bounded source checks, not language parsing or runtime taint analysis.
const diagnostic = /\b(?:Log\.[vdiew]|print|NSLog)\s*\(|\bdidLog\s*:|\b(?:errorMsg|configMsg|parseMsg|connectMsg)\s*=/;
const rawPayload = /\$(?:\{(?:config|options|url|urlString|normalizedUrl|connectUrl|rtmpUrl|serverUrl|streamKey|key|js|result|response)\}|(?:config|options|url|urlString|normalizedUrl|connectUrl|rtmpUrl|serverUrl|streamKey|key|js|result|response)\b)|\\\((?:config|options|url|urlString|normalizedUrl|connectUrl|rtmpUrl|serverUrl|streamKey|key|js|result|response)\)/;

test('native diagnostics do not dump configurations, URLs, JS results, or responses', () => {
  const violations = locations((file, line) => diagnostic.test(line) && rawPayload.test(line));
  assert.deepEqual(violations, [], `Sensitive diagnostic payloads at:\n${violations.join('\n')}`);
});

test('browser and publisher diagnostics do not forward raw error payloads', () => {
  const sensitiveFile = /\/(?:BrowserCameraView|WebOverlayService|GpuBrowserOverlayService|RtmpClient|StreamService)\.(?:kt|swift)$/;
  const rawError = /\$(?:\{error(?:\.message)?\}|error\b)|\\\((?:error|String\(describing:\s*error\))\)|\bLog\.[we]\([^\n]*,\s*(?:error|e)\s*\)/;
  const violations = locations((file, line) => sensitiveFile.test(file) && diagnostic.test(line) && (
    rawError.test(line) || (file.endsWith('/RtmpClient.kt') && /\bLog\.[we]\(TAG,\s*message\s*[,)]/.test(line))
  ));
  assert.deepEqual(violations, [], `Raw browser/publisher error diagnostics at:\n${violations.join('\n')}`);
});

test('recording destinations and virtual displays use library labels, not app labels', () => {
  const violations = locations((file, line) => /"[^"\n]*openscoreboard/i.test(line));
  assert.deepEqual(violations, [], `App-specific native string labels at:\n${violations.join('\n')}`);

  const recorder = fs.readFileSync(path.join(root, android, 'RtmpClient.kt'), 'utf8');
  const browser = fs.readFileSync(path.join(root, android, 'GpuBrowserOverlayService.kt'), 'utf8');
  assert.ok(recorder.includes('"browsercam-recordings"'));
  assert.ok(recorder.includes('"browsercam-$stamp.mp4"'));
  assert.ok(recorder.includes('"${Environment.DIRECTORY_MOVIES}/BrowserCam"'));
  assert.ok(recorder.includes('"BrowserCam"'));
  assert.ok(browser.includes('"BrowserCam-${input.id}"'));
});

test('Android native sources retain the existing library package namespace', () => {
  for (const { file, lines } of sources.filter(source => source.file.startsWith(`${android}/`))) {
    assert.equal(lines[0], 'package com.webcamstream', file);
  }
});

test('audio meter source fences worker acquisition and releases its local recorder in finally', () => {
  const source = fs.readFileSync(path.join(root, android, 'AudioLevelMonitor.kt'), 'utf8');
  const start = source.slice(source.indexOf('    fun start()'), source.indexOf('    fun stop()'));
  const stop = source.slice(source.indexOf('    fun stop()'), source.indexOf('    @SuppressLint'));
  const loop = source.slice(source.indexOf('    private fun readLoop('), source.indexOf('    private fun emitLevel('));

  assert.doesNotMatch(source, /private var audioRecord\s*:/, 'The recorder must be owned locally by its worker');
  assert.match(start, /synchronized\(stateLock\)/);
  assert.match(start, /val workerGeneration = \+\+generation/);
  assert.match(start, /readLoop\(worker, workerGeneration\)/);
  assert.match(stop, /synchronized\(stateLock\)[\s\S]*generation\+\+/);
  assert.doesNotMatch(stop, /\.release\(|\.stop\(/, 'Stop invalidates intent; the worker releases its own recorder');
  assert.match(source, /synchronized\(stateLock\) \{ running && thread === worker && generation == workerGeneration \}/);
  assert.match(loop, /synchronized\(recorderLock\)[\s\S]*AudioRecord\(/);
  assert.match(loop, /synchronized\(stateLock\)\s*\{\s*if \(!isCurrent\(worker, workerGeneration\)\) return\s*activeRecorder\.startRecording\(\)/);
  assert.match(loop, /while \(isCurrent\(worker, workerGeneration\)\)/);
  assert.match(loop, /AudioRecord\.READ_NON_BLOCKING/);
  assert.match(loop, /finally\s*\{[\s\S]*recorder\?\.let[\s\S]*\.stop\(\)[\s\S]*\.release\(\)/);
  assert.match(loop, /if \(thread === worker && generation == workerGeneration\)/);
  assert.match(loop, /worker\.quitSafely\(\)/);
  assert.doesNotMatch(source, /captureRelease|CaptureProcessRegistry/, 'Generic worker cleanup must not reintroduce app admission');
});

test('loadUrl source routes GPU navigation without replacing primary geometry or creating a legacy host', () => {
  const view = fs.readFileSync(path.join(root, android, 'BrowserCameraView.kt'), 'utf8');
  const load = view.slice(view.indexOf('    fun loadUrl('), view.indexOf('    /** Inject JavaScript'));
  assert.match(load, /if \(gpuScene != null\)[\s\S]*gpuBrowsers\?\.loadUrl\(url\)[\s\S]*return\s*\}[\s\S]*ensureWebOverlayService\(\)\.loadUrl\(url\)/);

  const host = fs.readFileSync(path.join(root, android, 'GpuBrowserOverlayService.kt'), 'utf8');
  assert.match(host, /fun loadUrl\(url: String\) = onMain/);
  const primary = host.slice(host.indexOf('    fun loadUrl('), host.indexOf('    fun injectJavaScript('));
  assert.match(primary, /overlays\.firstOrNull\(\)/);
  assert.match(primary, /primary\.copy\(url = normalizedUrl\)/);
  assert.match(primary, /overlays = listOf\(updated\) \+ overlays\.drop\(1\)/);
  assert.match(primary, /entry\.overlay = updated[\s\S]*navigate\(entry\)/);
  assert.match(primary, /lastConfig - "overlays" \+ \("url" to normalizedUrl\)/);
  assert.match(host, /lastConfig = snapshot/);
  assert.match(host, /\?: primaryUrl/);
  assert.match(host, /if \(parsed\.isNotEmpty\(\) \|\| config\["overlays"\] is List<\*>\) return/);
});

test('stopping an unstarted recording emits an explicit cancellation error instead of only idle', () => {
  const source = fs.readFileSync(path.join(root, android, 'BrowserCameraView.kt'), 'utf8');
  const stop = source.slice(source.indexOf('    fun stopRecording()'), source.indexOf('    /** Take a snapshot'));
  const pending = stop.slice(stop.indexOf('        if (pendingStartRecording)'), stop.indexOf('        recordingStopPending = true'));
  assert.match(pending, /pendingStartRecording = false/);
  assert.match(pending, /pendingRecordingOverlayWaitStartedAtMs = 0L/);
  assert.match(pending, /sendRecordingState\("idle"\)/);
  assert.match(pending, /sendError\("RECORDING_CANCELLED",[^\n]+\)[\s\S]*return/);
  assert.match(stop, /sendError\("RECORDING_FAILED", error \?: "Recording has no completed local file"\)/);
  assert.match(stop, /client\.stopRecording\(finish\)/);
});
