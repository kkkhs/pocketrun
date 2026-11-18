const assert = require('node:assert/strict');
const {spawnSync} = require('node:child_process');

const ALLOWED = {
  source: 1247710,
  name: 'picomatch',
  severity: 'high',
  url: 'https://github.com/advisories/GHSA-fc5q-39xc-vjpf',
  range: '<=4.0.7',
  expires: '2026-12-18T00:00:00Z',
};
const severity = {info: 0, low: 1, moderate: 2, high: 3, critical: 4};

function evaluate(report, now = Date.now()) {
  if (report.error) throw new Error(JSON.stringify(report.error));
  const advisories = new Map();
  for (const vulnerability of Object.values(report.vulnerabilities ?? {})) {
    for (const via of vulnerability.via ?? []) {
      if (typeof via === 'object') advisories.set(via.source, via);
    }
  }
  const counts = report.metadata?.vulnerabilities ?? {};
  const allowed = advisories.get(ALLOWED.source);
  const allowedMatches =
    allowed?.name === ALLOWED.name &&
    allowed?.severity === ALLOWED.severity &&
    allowed?.url === ALLOWED.url &&
    allowed?.range === ALLOWED.range;
  const blocking = [...advisories.values()].filter(
    advisory =>
      severity[advisory.severity] >= severity.high &&
      advisory.source !== ALLOWED.source,
  );
  const unaccountedSeverity =
    (counts.critical ?? 0) > 0 || ((counts.high ?? 0) > 0 && !allowed);
  if (blocking.length) {
    throw new Error(
      `Blocked advisories: ${blocking.map(advisory => advisory.url).join(', ')}`,
    );
  }
  if (allowed && !allowedMatches) throw new Error('The picomatch advisory changed.');
  if (unaccountedSeverity) {
    throw new Error('High/critical nodes exist without the exact allowed root advisory.');
  }
  if (allowed && now >= Date.parse(ALLOWED.expires)) {
    throw new Error(`The temporary ${ALLOWED.url} exception expired.`);
  }
  return {allowed: Boolean(allowed), counts};
}

function selfTest() {
  const report = advisory => ({
    vulnerabilities: {pkg: {via: [advisory]}},
    metadata: {vulnerabilities: {high: 1, critical: 0, moderate: 0}},
  });
  assert.equal(evaluate(report(ALLOWED), Date.parse('2026-11-18')).allowed, true);
  assert.throws(() => evaluate(report({...ALLOWED, source: 99, url: 'https://example.invalid'})));
  assert.throws(() => evaluate(report({...ALLOWED, range: '<=5.0.0'})));
  assert.throws(() => evaluate(report(ALLOWED), Date.parse(ALLOWED.expires)));
}

selfTest();
const result = spawnSync(
  process.platform === 'win32' ? 'npm.cmd' : 'npm',
  ['audit', '--json', '--registry=https://registry.npmjs.org'],
  {encoding: 'utf8', maxBuffer: 32 * 1024 * 1024},
);
if (result.error) throw result.error;
if (![0, 1].includes(result.status)) {
  console.error(result.stderr);
  throw new Error(`npm audit exited with unexpected status ${result.status}`);
}

let report;
try {
  report = JSON.parse(result.stdout);
} catch (error) {
  console.error(result.stdout);
  console.error(result.stderr);
  throw new Error(`npm audit did not return JSON: ${error.message}`);
}
const {allowed, counts} = evaluate(report);
if (allowed) {
  console.warn(
    `Temporarily allowing ${ALLOWED.url} until ${ALLOWED.expires.slice(0, 10)}: ` +
      'all published picomatch versions are affected, and PocketRun only evaluates repository-controlled glob patterns in build/test tooling.',
  );
}
console.log(
  'Audit gate passed (0 blocking high/critical root advisories; ' +
    `${counts.high ?? 0} affected high-severity nodes map only to the exact temporary exception; ` +
    `${counts.moderate ?? 0} moderate).`,
);
