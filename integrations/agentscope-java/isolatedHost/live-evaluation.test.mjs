import test from 'node:test';
import assert from 'node:assert/strict';
import { evaluateExecutions } from './live-evaluation.mjs';

const scenario = {calls:[['summary','analysis']],periods:['2026-08'],failedCapability:'analysis'};
const row = (id,status,extra={}) => ({request:{capabilityId:id,parameters:{period:'2026-08'}},
  result:{status,...extra}});
test('coverage does not mistake an explicit retryable failure for a missing goal', () => {
  const failed = row('analysis','FAILED',{error:{retryable:true}});
  const result = evaluateExecutions(scenario,0,[row('summary','COMPLETED'),failed,failed]);
  assert.deepEqual(result.violations,[]);
  assert.equal(result.invocationCounts.analysis,2);
  assert.equal(result.repeatedInvocations.length,1);
});
test('missing or substituted capability still fails', () => {
  assert.ok(evaluateExecutions(scenario,0,[row('summary','COMPLETED')]).violations.includes('TURN_0_TOOL_SET'));
  assert.ok(evaluateExecutions(scenario,0,[row('summary','COMPLETED'),row('balance','COMPLETED')]).violations.includes('TURN_0_TOOL_SET'));
});
test('duplicate success or nonretryable failure is not silently accepted', () => {
  const summary = row('summary','COMPLETED');
  assert.ok(evaluateExecutions(scenario,0,[summary,summary,row('analysis','FAILED')]).violations.includes('TURN_0_UNEXPECTED_DUPLICATE'));
  assert.ok(evaluateExecutions(scenario,0,[summary,row('analysis','FAILED'),row('analysis','FAILED')]).violations.includes('TURN_0_UNEXPECTED_DUPLICATE'));
});
test('every attempt retains period and business outcome checks', () => {
  const wrong = row('analysis','COMPLETED'); wrong.request.parameters.period='2026-07';
  const result = evaluateExecutions(scenario,0,[row('summary','COMPLETED'),wrong]);
  assert.ok(result.violations.includes('TURN_0_PERIOD'));
  assert.ok(result.violations.includes('TURN_0_BUSINESS_STATUS'));
});
