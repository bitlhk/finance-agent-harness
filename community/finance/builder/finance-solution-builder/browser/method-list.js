export function visibleMethods(items, filter = 'ALL', showArchived = false) {
  return items.filter(item => {
    if (item.status === 'ARCHIVED') return showArchived;
    if (filter === 'DRAFT') return !item.versions.length || item.hasUnpublishedChanges;
    if (filter === 'PUBLISHED') return item.versions.length > 0;
    return true;
  });
}

export function methodListFilters(filter, showArchived) {
  return `<div class="method-list-filters"><div class="method-mode" role="group" aria-label="SOP 状态">${[
    ['ALL', '全部'], ['DRAFT', '草稿与修改'], ['PUBLISHED', '已发布']
  ].map(([id, label]) => `<button type="button" data-method-filter="${id}" aria-pressed="${filter === id}">${label}</button>`).join('')}</div>
  <label><input type="checkbox" data-method-archived ${showArchived ? 'checked' : ''}>同时显示已归档</label></div>`;
}
