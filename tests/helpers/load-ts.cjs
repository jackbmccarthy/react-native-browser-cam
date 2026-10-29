'use strict';

const fs = require('node:fs');
const vm = require('node:vm');

function loadTypeScript(filename, globals = {}) {
  const source = fs.readFileSync(filename, 'utf8');
  const ts = require('typescript');
  const result = ts.transpileModule(source, {
    fileName: filename,
    reportDiagnostics: true,
    compilerOptions: {
      module: ts.ModuleKind.CommonJS,
      target: ts.ScriptTarget.ES2020,
      strict: true,
    },
  });
  const errors = (result.diagnostics || []).filter(item => item.category === ts.DiagnosticCategory.Error);
  if (errors.length) {
    throw new Error(errors.map(item => ts.flattenDiagnosticMessageText(item.messageText, '\n')).join('\n'));
  }
  const module = { exports: {} };
  vm.runInNewContext(result.outputText, {
    ...globals,
    module,
    exports: module.exports,
    require(name) { throw new Error(`Unexpected runtime import in ${filename}: ${name}`); },
  }, { filename, timeout: 1000 });
  return module.exports;
}

module.exports = { loadTypeScript };
