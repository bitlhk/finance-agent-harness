(() => {
  'use strict';
  const book = JSON.parse(document.getElementById('book-data').textContent);
  const byId = new Map(book.pages.map(page => [page.id, page]));
  const $ = id => document.getElementById(id);
  const groupOrder = ['开发指导', '专题与接入规范', '组件与示例说明', 'Builder 跨仓附录', '架构决策 ADR', '历史与验证快照', '源码与配置附录'];
  const route = (id, section = '') => `#doc=${encodeURIComponent(id)}${section ? `&section=${encodeURIComponent(section)}` : ''}`;
  let current = book.mainId;
  let query = '';
  const labels = new Map([
    ['harness/docs/developer-guide.md', '设计、架构与 SOP 开发手册'],
    ['harness/docs/README.md', '文档导航与有效性清单'],
    ['harness/docs/sop-contribution-template.md', 'SOP 接入交接模板'],
    ['harness/examples/solutions/wealth-facts-kit/README.md', '财富 Kit · 快速运行入口'],
  ]);
  const searchIndex = book.pages.filter(page => page.kind !== 'directory').map(page => ({ ...page, haystack: `${page.title}\n${page.path}\n${page.text}`.toLocaleLowerCase() }));
  const label = page => (labels.get(page.id) || page.title).replace(/[`*_]/g, '');
  $('edition-count').textContent = `${book.documentCount} 份文档 · ${book.sourceCount} 份实现参考`;
  $('baseline').textContent = book.baseline.slice(0, 12);
  $('brand').href = route(book.mainId);
  $('home-link').href = route(book.mainId);
  $('help').onclick = () => { location.hash = route(book.prefaceId); };
  $('print').onclick = () => window.print();

  function setMenu(open) {
    document.body.classList.toggle('nav-open', open);
    $('menu').setAttribute('aria-expanded', String(open));
    $('shade').hidden = !open;
  }
  $('menu').onclick = () => setMenu(!document.body.classList.contains('nav-open'));
  $('shade').onclick = () => setMenu(false);
  document.addEventListener('keydown', event => {
    if (event.key === 'Escape') { setMenu(false); if ($('reference-dialog').open) $('reference-dialog').close(); }
  });
  $('close-dialog').onclick = () => $('reference-dialog').close();
  document.addEventListener('click', event => {
    const button = event.target.closest('[data-reference]');
    if (!button) return;
    $('reference-path').textContent = button.dataset.reference;
    $('reference-dialog').showModal();
  });

  function renderNav() {
    const terms = query.toLocaleLowerCase().trim().split(/\s+/).filter(Boolean);
    const matches = searchIndex.filter(page => (page.kind === 'document' || $('include-source').checked) && terms.every(term => page.haystack.includes(term)));
    const groups = new Map(groupOrder.map(group => [group, []]));
    matches.forEach(page => groups.get(page.group).push(page));
    const frag = document.createDocumentFragment();
    for (const [name, pages] of groups) {
      if (!pages.length) continue;
      const group = document.createElement('details');
      group.open = Boolean(terms.length) || ['开发指导', '专题与接入规范'].includes(name) || byId.get(current)?.group === name;
      const summary = document.createElement('summary');
      summary.textContent = `${name} · ${pages.length}`;
      group.append(summary);
      for (const page of pages) {
        const link = document.createElement('a');
        link.href = route(page.id);
        link.textContent = label(page);
        link.title = page.path;
        if (page.id === current) link.setAttribute('aria-current', 'page');
        if (terms.length) {
          const small = document.createElement('small');
          const at = page.text.toLocaleLowerCase().indexOf(terms[0]);
          small.textContent = at < 0 ? page.path : page.text.slice(Math.max(0, at - 28), at + 80).replace(/\s+/g, ' ');
          link.append(small);
        }
        link.onclick = () => setMenu(false);
        group.append(link);
      }
      frag.append(group);
    }
    if (!matches.length) {
      const p = document.createElement('p');
      p.textContent = '没有匹配文档';
      p.className = 'search-status';
      frag.append(p);
    }
    $('navigation').replaceChildren(frag);
    $('search-status').textContent = terms.length ? `${matches.length} 份匹配资料` : `全部资料 · 离线全文检索`;
    $('clear').hidden = !query;
  }
  let timer;
  $('search').oninput = () => { query = $('search').value; clearTimeout(timer); timer = setTimeout(renderNav, 120); };
  $('clear').onclick = () => { $('search').value = ''; query = ''; renderNav(); $('search').focus(); };
  $('include-source').onchange = renderNav;

  function renderToc(page) {
    for (const container of [$('toc'), $('compact-toc')]) {
      const frag = document.createDocumentFragment();
      for (const heading of page.headings.filter(h => h.depth === 2 || h.depth === 3)) {
        const a = document.createElement('a');
        a.href = route(page.id, heading.id);
        a.className = `depth-${heading.depth}`;
        a.textContent = heading.text;
        frag.append(a);
      }
      container.replaceChildren(frag);
    }
    $('mobile-toc').hidden = !page.headings.some(h => h.depth >= 2);
    $('mobile-toc').open = false;
  }

  function openRoute() {
    if (location.hash === '#main') { $('main').focus(); return; }
    const params = new URLSearchParams(location.hash.slice(1));
    const requested = params.get('doc') || book.mainId;
    const page = byId.get(requested);
    if (!page) {
      $('category').textContent = '未找到资料';
      $('source-path').textContent = requested;
      $('notice').hidden = true;
      $('article').replaceChildren();
      const p = document.createElement('p');
      p.textContent = '这个文档不在本次离线快照中。请从左侧目录重新选择。';
      $('article').append(p);
      return;
    }
    const changed = page.id !== current || !$('article').childElementCount;
    current = page.id;
    document.title = `${label(page)} · Finance Harness`;
    if (changed) {
      $('article').innerHTML = page.html;
      $('category').textContent = page.group;
      $('source-path').textContent = `${page.repo === 'builder' ? 'Builder · 固定参考副本 / ' : page.repo === 'edition' ? '离线编辑说明 / ' : 'Harness / '}${page.path}`;
      $('read-time').textContent = page.kind === 'document' ? `约 ${Math.max(1, Math.ceil(page.text.length / 850))} 分钟` : '只读实现参考';
      const warnings = {
        '历史与验证快照': '历史记录：日期、端口、PR 状态和测试数字仅对应原文版本，不是当前部署或新的验收结论。',
        '架构决策 ADR': '请先核对原文的 Accepted / Proposed 状态；候选方向不等于已交付能力。',
        'Builder 跨仓附录': `Builder 参考副本 ${book.builderSha?.slice(0, 12)}；原维护者接纳未核实，不属于 Harness 单仓实现。`,
        '源码与配置附录': '实现参考来自本次源码快照；不包含构建依赖、服务端配置或真实客户资料。',
      };
      $('notice').textContent = warnings[page.group] || '';
      $('notice').hidden = !warnings[page.group];
      $('doc-count').textContent = `${book.date} 资料快照 · ${page.kind === 'document' ? '正文已完整收录' : '源码只读附录'}`;
      renderToc(page);
      renderNav();
    }
    const section = params.get('section');
    requestAnimationFrame(() => {
      let target = section && $('article').querySelector(`[id="${CSS.escape(section)}"]`);
      if (section && /^L\d+/.test(section)) target = $('article').querySelector(`[id="${section.match(/^L\d+/)[0]}"]`);
      if (target) target.scrollIntoView({ block: 'start' });
      else if (changed) window.scrollTo(0, 0);
    });
  }
  window.addEventListener('hashchange', openRoute);
  renderNav();
  openRoute();
})();
