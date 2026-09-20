import { isDeepStrictEqual } from 'node:util';

// Business coverage is distinct from the number of explicit model-selected tool calls.
export function evaluateExecutions(scenario, turn, executions) {
  const violations = [], invocationCounts = {}, repeatedInvocations = [];
  const expected = [...new Set(scenario.calls[turn])].sort();
  const actual = [...new Set(executions.map(e => e.request.capabilityId))].sort();
  if (!isDeepStrictEqual(actual, expected)) violations.push(`TURN_${turn}_TOOL_SET`);
  const previous = new Map();
  for (const execution of executions) {
    const id = execution.request.capabilityId;
    invocationCounts[id] = (invocationCounts[id] ?? 0) + 1;
    if (scenario.periods && execution.request.parameters.period !== scenario.periods[turn])
      violations.push(`TURN_${turn}_PERIOD`);
    const wanted = id === scenario.failedCapability ? 'FAILED' : 'COMPLETED';
    if (execution.result.status !== wanted) violations.push(`TURN_${turn}_BUSINESS_STATUS`);
    if (previous.has(id)) {
      const prior = previous.get(id);
      const retryableFailure = id === scenario.failedCapability && prior.result.status === 'FAILED'
        && prior.result.error?.retryable === true
        && isDeepStrictEqual(prior.request.parameters, execution.request.parameters);
      repeatedInvocations.push({ capabilityId:id, invocation:invocationCounts[id],
        precedingRetryableFailure:retryableFailure, review:'VERIFY_EXPLICIT_MODEL_SELECTION_AND_ADDITIONAL_READS' });
      if (!retryableFailure) violations.push(`TURN_${turn}_UNEXPECTED_DUPLICATE`);
    }
    previous.set(id, execution);
  }
  return { violations, invocationCounts, repeatedInvocations };
}
