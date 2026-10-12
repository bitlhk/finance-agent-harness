export function requestTiming(clock = () => performance.now()) {
  const began = clock();
  let firstStatusMs = null, bodyDisplayedMs = null;
  const elapsed = () => Math.max(0, clock() - began);
  return {
    event(event) {
      if (firstStatusMs === null && ['RUN_STARTED', 'STEP_STARTED', 'RUN_ERROR'].includes(event.type)) firstStatusMs = elapsed();
    },
    displayed() { if (bodyDisplayedMs === null) bodyDisplayedMs = elapsed(); },
    result() { return { firstStatusMs, bodyDisplayedMs, completedMs: elapsed() }; },
  };
}
