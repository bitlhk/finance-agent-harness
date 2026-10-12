// Preserve reading position across DOM replacement; follow only a new send or the bottom edge.
export function captureScroll(element, force = false) {
  return { top: element?.scrollTop || 0,
    follow: force || !element || element.scrollHeight - element.clientHeight - element.scrollTop < 48 };
}
export function restoreScroll(element, position) {
  if (!element) return;
  const restore = () => {
    if (element.isConnected) element.scrollTo({top: position.follow ? element.scrollHeight : position.top, behavior: 'instant'});
  };
  restore();
  requestAnimationFrame(restore);
}
