import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';

const [rootArg, artifactArg, outputArg] = process.argv.slice(2);
if (!rootArg || !artifactArg || !outputArg) throw new Error('ROOT QUALIFIED_OUTPUT NEW_ROOT_BOUNDED_OUTPUT required');
const root = fs.realpathSync(rootArg), artifact = fs.realpathSync(artifactArg), output = path.resolve(outputArg);
assert(!fs.existsSync(output), 'OUTPUT_ALREADY_EXISTS');
assert(output.startsWith(root + path.sep), 'TEST_OUTPUT_MUST_BE_ROOT_BOUNDED');
fs.mkdirSync(output, { recursive: true });
assert(fs.realpathSync(output).startsWith(root + path.sep), 'TEST_OUTPUT_MUST_BE_ROOT_BOUNDED');
const read = file => JSON.parse(fs.readFileSync(file, 'utf8'));
const write = (file, value) => fs.writeFileSync(file, JSON.stringify(value, null, 2) + '\n', { flag: 'wx' });
const classes = path.join(output, 'host-classes');
fs.mkdirSync(classes);
const originalClasspath = fs.readFileSync(path.join(artifact, 'classpath.txt'), 'utf8').trim();
const source = path.join(root, 'integrations/agentscope-java/isolatedHost/IsolatedHost.java');
const compile = spawnSync('javac', ['--release', '21', '-cp', originalClasspath, '-d', classes, source], { encoding: 'utf8' });
assert.equal(compile.status, 0, compile.stderr);
const classpath = classes + path.delimiter + originalClasspath;
fs.writeFileSync(path.join(output, 'classpath.txt'), classpath + '\n', { flag: 'wx' });
const qualification = path.join(artifact, 'qualification/agentscope-observation-qualification.json');
const digest = fs.readFileSync(path.join(artifact, 'qualification/qualification.sha256'), 'utf8').trim();
const reference = path.join(root, 'integrations/agentscope-java/reference');
const results = [];
function invoke(id, solution, support) {
  const out = path.join(output, id);
  const child = spawnSync('java', ['-cp', classpath,
    'io.github.bitlhk.finance.harness.integrations.agentscope.isolatedhost.IsolatedHost',
    'controlled', root, qualification, digest, out], {
    env: { ...process.env, FH_ISOLATED_SOLUTION: solution, FH_ISOLATED_SUPPORT: support, FH_ISOLATED_FIXTURE: 'normal' },
    encoding: 'utf8', timeout: 150000, maxBuffer: 4 * 1024 * 1024,
  });
  fs.writeFileSync(path.join(output, id + '.stdout.txt'), child.stdout ?? '', { flag: 'wx' });
  fs.writeFileSync(path.join(output, id + '.stderr.txt'), child.stderr ?? '', { flag: 'wx' });
  return { child, out };
}
for (const balance of [true, false]) {
  const id = balance ? 'balance-only' : 'cashflow-only-reject-balance';
  const solution = read(path.join(reference, 'solution.json'));
  const support = read(path.join(reference, 'support.json'));
  solution.solutionId = 'isolated-' + id;
  solution.capabilityPackRefs = solution.capabilityPackRefs.filter(ref => ref.id === (balance ? 'account-balance-readonly' : 'cashflow-readonly'));
  if (balance) solution.domainPackRefs = [];
  support.descriptions = support.descriptions.filter(ref => ref.id === (balance ? 'account-balance-routing-zh-cn' : 'cashflow-routing-zh-cn'));
  support.observationRequirements = support.observationRequirements.filter(ref => ref.id === (balance ? 'readonly-balance-observation' : 'readonly-cashflow-observation'));
  support.terminology = [];
  const solutionFile = path.join(output, id + '.solution.json'), supportFile = path.join(output, id + '.support.json');
  write(solutionFile, solution); write(supportFile, support);
  const { child, out } = invoke(id, balance ? path.relative(root, solutionFile) : solutionFile, supportFile);
  const result = read(path.join(out, 'result.json'));
  assert.equal(result.status, balance ? 'PASS' : 'RUNTIME_REJECTED', child.stderr);
  assert.equal(child.status === 0, balance, child.stderr);
  const model = read(path.join(out, 'model-http.json'));
  assert.equal(model.length, balance ? 2 : 1);
  assert.equal(result.bankCustomerReadCount, balance ? 1 : 0);
  const expectedTools = balance ? ['account_balance_query'] : ['finance_spending_analysis', 'finance_transaction_summary'];
  assert.deepEqual(model[0].body.tools.map(t => t.function.name).sort(), expectedTools);
  const lock = read(path.join(out, 'solution-lock.json'));
  assert.deepEqual(lock.installations.flatMap(p => p.capabilities.map(c => c.toolName)).sort(), expectedTools);
  assert.deepEqual(lock.assets.filter(a => a.kind === 'CAPABILITY_PACK').map(({ kind, ...ref }) => ref), solution.capabilityPackRefs);
  results.push({ id, status: 'PASS', modelHttp: model.length, bankCustomerReads: result.bankCustomerReadCount, installedTools: expectedTools });
}
const outside = path.resolve(root, '..');
const link = path.join(output, 'outside-link');
fs.symlinkSync(outside, link, 'dir');
for (const [id, solution, support] of [
  ['outside-absolute', outside, path.join(reference, 'support.json')],
  ['outside-relative', '..', path.join(reference, 'support.json')],
  ['outside-symlink', link, path.join(reference, 'support.json')],
  ['outside-support', path.join(reference, 'solution.json'), outside],
]) {
  const { child, out } = invoke(id, solution, support);
  assert.notEqual(child.status, 0);
  assert.match(child.stderr, /ISOLATED_REFERENCE_OUTSIDE_ROOT/);
  assert(!fs.existsSync(out), 'Rejected reference must fail before output creation or fixture startup');
  results.push({ id, status: 'PASS', boundary: 'REJECTED_BEFORE_FIXTURE_STARTUP' });
}
write(path.join(output, 'summary.json'), { status: 'PASS', qualificationDigest: digest, results });
console.log(JSON.stringify({ status: 'PASS', checks: results.length, output, qualificationDigest: digest }));
