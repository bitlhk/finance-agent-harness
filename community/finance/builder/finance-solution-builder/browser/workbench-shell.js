const collapsedKey = 'finance-studio.sidebar-collapsed';
let collapsed = false;
try { collapsed = localStorage.getItem(collapsedKey) === 'true'; } catch {}
let drawerOpen = false;
const mobile = () => matchMedia('(max-width: 640px)').matches;
const symbol = name => `<svg class="icon" aria-hidden="true"><use href="/icons.svg#${name}"></use></svg>`;

function updateNavigation(app) {
  const shell = app.querySelector('.app-shell');
  document.documentElement.classList.toggle('studio-drawer-open', !!shell && drawerOpen && mobile());
  if (!shell) return;
  const side = shell.querySelector('.sidebar');
  side.id = 'workspace-sidebar';
  if (!side.querySelector('[data-sidebar-toggle]')) {
    side.insertAdjacentHTML('afterbegin', '<button type="button" class="sidebar-toggle icon-button" data-sidebar-toggle></button>');
  }
  const topbar = shell.querySelector('.topbar');
  if (!topbar.querySelector('[data-sidebar-open]')) {
    topbar.insertAdjacentHTML('afterbegin', `<button type="button" class="sidebar-open icon-button" data-sidebar-open aria-label="打开导航" title="打开导航" aria-controls="workspace-sidebar">${symbol('menu')}</button>`);
  }
  if (!shell.querySelector('.sidebar-backdrop')) {
    shell.insertAdjacentHTML('beforeend', '<button type="button" class="sidebar-backdrop" data-sidebar-close aria-label="关闭导航" tabindex="-1"></button>');
  }
  shell.classList.toggle('sidebar-collapsed', collapsed && !mobile());
  shell.classList.toggle('sidebar-drawer-open', drawerOpen && mobile());
  const toggle = side.querySelector('[data-sidebar-toggle]');
  const label = mobile() ? '关闭导航' : collapsed ? '展开导航' : '收起导航';
  toggle.setAttribute('aria-label', label);
  toggle.title = label;
  toggle.setAttribute('aria-expanded', String(mobile() ? drawerOpen : !collapsed));
  const icon = mobile() ? 'x' : collapsed ? 'panel-left-open' : 'panel-left-close';
  if (toggle.dataset.icon !== icon) { toggle.innerHTML = symbol(icon); toggle.dataset.icon = icon; }
  shell.querySelector('[data-sidebar-open]').setAttribute('aria-expanded', String(drawerOpen && mobile()));
  shell.querySelector('.main').inert = mobile() && drawerOpen;
  side.inert = mobile() && !drawerOpen;
  if (mobile() && drawerOpen) { side.setAttribute('role', 'dialog'); side.setAttribute('aria-modal', 'true'); side.setAttribute('aria-label', '工作空间导航'); }
  else { side.removeAttribute('role'); side.removeAttribute('aria-modal'); side.removeAttribute('aria-label'); }
}

export function installNavigation(app) {
  document.addEventListener('pointerdown', () => { document.documentElement.dataset.inputModality = 'pointer'; }, true);
  document.addEventListener('keydown', event => {
    if (['Tab', 'ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight'].includes(event.key)) document.documentElement.dataset.inputModality = 'keyboard';
  }, true);
  const close = () => { drawerOpen = false; updateNavigation(app); app.querySelector('[data-sidebar-open]')?.focus(); };
  app.addEventListener('click', event => {
    if (event.target.closest('[data-sidebar-open]')) { drawerOpen = true; updateNavigation(app); app.querySelector('[data-sidebar-toggle]')?.focus(); }
    else if (event.target.closest('[data-sidebar-close]')) close();
    else if (event.target.closest('[data-sidebar-toggle]')) {
      if (mobile()) close();
      else { collapsed = !collapsed; try { localStorage.setItem(collapsedKey, String(collapsed)); } catch {} updateNavigation(app); }
    } else if (drawerOpen && event.target.closest('.sidebar [data-nav]')) close();
  });
  app.addEventListener('keydown', event => {
    if (!mobile() || !drawerOpen) return;
    if (event.key === 'Escape') { event.preventDefault(); close(); }
    if (event.key === 'Tab') {
      const nodes = [...app.querySelectorAll('.sidebar button,.sidebar a[href]')].filter(node => !node.disabled && node.getClientRects().length);
      const first = nodes[0], last = nodes.at(-1);
      if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus(); }
      else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus(); }
    }
  });
  matchMedia('(max-width: 640px)').addEventListener('change', () => { drawerOpen = false; updateNavigation(app); });
}

export function renderWorkbench(app, html) {
  const template = document.createElement('template');
  template.innerHTML = html;
  const current = app.querySelector(':scope > .app-shell');
  const next = template.content.firstElementChild?.matches('.app-shell') ? template.content.firstElementChild : null;
  if (!current || !next) {
    app.replaceChildren(template.content);
    drawerOpen = false;
    updateNavigation(app);
    return;
  }
  // Keep navigation and its SVG instances mounted across polling and page changes.
  for (const button of current.querySelectorAll('.sidebar .nav-item[data-nav]')) {
    const replacement = next.querySelector(`.sidebar .nav-item[data-nav="${button.dataset.nav}"]`);
    if (!replacement) continue;
    button.className = replacement.className;
    if (replacement.classList.contains('active')) button.setAttribute('aria-current', 'page');
    else button.removeAttribute('aria-current');
  }
  const footer = current.querySelector('.sidebar-footer');
  const nextFooter = next.querySelector('.sidebar-footer');
  if (footer && nextFooter && footer.innerHTML !== nextFooter.innerHTML) {
    // Environment-dependent links can change after the initial catalog response.
    footer.replaceChildren(...nextFooter.childNodes);
  }
  current.querySelector('.main').replaceChildren(...next.querySelector('.main').childNodes);
  updateNavigation(app);
}
