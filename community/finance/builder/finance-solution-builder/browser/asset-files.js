export const fileKey = file => file.fileId || file.path;
export const nodeFileKeys = node => node.fileIds || node.files || [];

export function resolveFile(value, key) {
  if (!key) return null;
  let matches = value.files.filter(file => fileKey(file) === key);
  if (!matches.length) matches = value.files.filter(file => file.path === key);
  if (matches.length > 1) throw Error('此路径对应多个来源或版本，请在文件列表中选择具体来源。');
  return matches[0] || null;
}

export function fileSource(file) {
  const ref = file.reference || {};
  return [ref.id, ref.version && 'v' + ref.version, ref.source].filter(Boolean).join(' · ') || '来源待核对';
}
