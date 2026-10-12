export function runtimePresentation(build, runtimes = []) {
  const id = build?.selection?.runtimeId;
  const frozen = build?.runtimePresentation;
  const runtime = frozen?.runtimeId === id ? frozen : runtimes.find(item => item.id === id);
  return {
    label: runtime?.label || id || '运行框架未记录',
    detail: [runtime?.language, runtime?.version].filter(Boolean).join(' · ') || id || '版本未记录',
    architecture: runtime?.architectureLabel || '独立 Agent 制品',
    frozen: runtime === frozen && Boolean(frozen),
  };
}

export function selectionSchemaVersion(runtime) {
  return runtime?.selectionSchemaVersion || '0.9';
}

export function runtimeStatusLabel(runtime) {
  if (runtime?.candidateEnabled && runtime.status === 'AVAILABLE') return '试点接入';
  if (runtime?.status === 'CANDIDATE') return '待验证';
  return runtime?.status === 'AVAILABLE' ? '已接入' : '待接入';
}

const sameAsset = (left, right) => Boolean(left && right && left.id === right.id && left.version === right.version);
const sameProfile = (left, right) => sameAsset(left, right) && left.source === right.source;

export function runtimeHostBinding(runtime, bindings, entries, presentation, applicationTemplateId) {
  if (selectionSchemaVersion(runtime) === '0.9') return {fields: {}, error: null};
  let options = (runtime?.hostOptions || []).filter(host =>
    host.declarationStatus !== 'UNAVAILABLE' && host.hostId && host.profileRef?.source &&
    host.applicationTemplateIds?.includes(applicationTemplateId) && host.presentations?.includes(presentation) &&
    bindings.every(binding => {
      const matching = entries.filter(entry => sameAsset(entry.pack, binding.pack));
      return matching.length === 1 && matching[0].runtimeHostOptions?.some(option =>
        option.runtimeId === runtime.id && option.hostId === host.hostId &&
        sameProfile(option.profileRef, host.profileRef) && sameAsset(option.provider, binding.provider));
    }));
  if (options.length > 1 && options.every(host => Array.isArray(host.operations) && host.operations.length)) {
    // Prefer a strictly narrower supported operation set; equal or incomparable hosts remain ambiguous.
    const minimal = options.filter(host => !options.some(other =>
      new Set(other.operations).size < new Set(host.operations).size &&
      other.operations.every(operation => host.operations.includes(operation))));
    if (minimal.length === 1) options = minimal;
  }
  if (options.length !== 1) return {fields: {}, error: options.length > 1 ? 'MANAGED_HOST_AMBIGUOUS' : 'MANAGED_HOST_BINDING_MISMATCH'};
  const host = options[0];
  return {fields: {hostId: host.hostId, profileRef: {...host.profileRef}}, error: null};
}
