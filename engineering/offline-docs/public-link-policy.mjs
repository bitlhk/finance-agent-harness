// Reviewed references are navigation links, never automatically fetched assets.
export const reviewedPublicLinks = new Set([
  'https://bian.org/deliverables/service-landscape/',
  'https://www.iso20022.org/iso20022-repository/business-model',
  'https://std.samr.gov.cn/gb/search/gbDetailed?id=C8394B1AA0EF4558E05397BE0A0A8876',
  'https://www.w3.org/TR/WCAG22/',
]);

export function isSafePublicText(text) {
  const urls = text.match(/https?:\/\/[^\s)<>"\]]*/g) || [];
  if (urls.some(url => !reviewedPublicLinks.has(url))) return false;
  return !/(?:\d{1,3}\.){3}\d{1,3}|\/home\/|\/opt\/|\/etc\/|\/var\/|BEGIN.*PRIVATE KEY|\bsk-[A-Za-z0-9_-]{20,}/.test(text);
}
