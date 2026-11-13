const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const {createRequire} = require('node:module');
const {pathToFileURL} = require('node:url');

// Scoped overrides fix upstream advisories without changing Docusaurus.
// Their parent ranges lag patched roots; check the APIs those parents use.
(async () => {
  const coreRequire = createRequire(require.resolve('@docusaurus/core/package.json'));
  const copyRequire = createRequire(require.resolve('copy-webpack-plugin/package.json'));
  const cssRequire = createRequire(require.resolve('css-minimizer-webpack-plugin/package.json'));
  const sockRequire = createRequire(require.resolve('sockjs/package.json'));
  const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketrun-toolchain-'));
  try {
    assert.equal(coreRequire('tinypool/package.json').version, '2.1.2');
    const {default: Tinypool} = await import(pathToFileURL(coreRequire.resolve('tinypool')).href);
    const worker = path.join(temp, 'worker.mjs');
    fs.writeFileSync(worker, 'export default task => ({value: task * 2, workerId: process.__tinypool_state__.workerId});\n');
    const pool = new Tinypool({
      filename: worker,
      minThreads: 2,
      maxThreads: 2,
      concurrentTasksPerWorker: 1,
      runtime: 'worker_threads',
      isolateWorkers: false,
      workerData: {params: {}},
      maxMemoryLimitBeforeRecycle: undefined,
      resourceLimits: {},
    });
    try {
      const result = await pool.run(21);
      assert.equal(result.value, 42);
      assert(Number.isInteger(result.workerId));
    } finally {
      await pool.destroy();
    }

    for (const parentRequire of [copyRequire, cssRequire]) {
      assert.equal(parentRequire('serialize-javascript/package.json').version, '7.1.3');
      assert.equal(typeof parentRequire('serialize-javascript'), 'function');
    }
    const webpack = copyRequire('webpack');
    const CopyPlugin = require('copy-webpack-plugin');
    fs.writeFileSync(path.join(temp, 'entry.js'), '');
    fs.writeFileSync(path.join(temp, 'owned.txt'), 'owned');
    const compiler = webpack({
      mode: 'none',
      context: temp,
      entry: './entry.js',
      output: {path: path.join(temp, 'output')},
      plugins: [new CopyPlugin({patterns: [{
        from: 'owned.txt',
        to: 'owned.txt',
        transform: {
          transformer: content => Buffer.from(content.toString().toUpperCase()),
          cache: {keys: {check: 'toolchain'}},
        },
      }]})],
    });
    try {
      await new Promise((resolve, reject) => compiler.run((error, stats) => {
        if (error) reject(error);
        else if (stats.hasErrors()) reject(new Error(stats.toString('errors-only')));
        else resolve();
      }));
      assert.equal(fs.readFileSync(path.join(temp, 'output/owned.txt'), 'utf8'), 'OWNED');
    } finally {
      await new Promise((resolve, reject) => compiler.close(error => error ? reject(error) : resolve()));
    }

    // The real CSS worker evaluates only this owned, constant test input.
    const result = await cssRequire('./dist/minify.js').transform(cssRequire('serialize-javascript')({
      name: 'owned.css',
      input: 'a{color:red}',
      minimizer: {
        implementation: async function(inputs, _map, options) {
          return {code: Object.values(inputs)[0].replace('red', options.color)};
        },
        options: {color: 'blue'},
      },
    }));
    assert.equal(result.outputs[0].code, 'a{color:blue}');
    require('sockjs');
    assert.equal(sockRequire('uuid/package.json').version, '11.1.1');
    assert.match(sockRequire('uuid').v4(), /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
    console.log('Toolchain compatibility passed: pooled SSG API, copy cache, CSS worker options, SockJS UUID v4.');
  } finally {
    fs.rmSync(temp, {recursive: true, force: true});
  }
})().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
