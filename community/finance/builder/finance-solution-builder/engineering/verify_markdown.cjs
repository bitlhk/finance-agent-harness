/* Real browser/AG-UI client, controlled API only. No model or banking requests. */
const { chromium } = require('@playwright/test');
const { buildSync } = require('esbuild');
const fs = require('node:fs');
const path = require('node:path');
const { execFileSync } = require('node:child_process');
const assert = require('node:assert/strict');

const project = path.resolve(__dirname, '..');
const output = path.resolve(process.argv[2] || 'build/markdown-browser');
const baseline = process.argv.includes('--baseline');
const baselineIndex = process.argv.indexOf('--baseline-ref');
const baselineRef = baselineIndex < 0 ? null : process.argv[baselineIndex + 1];
assert.ok(!baseline || (baselineRef && /^[A-Za-z0-9][A-Za-z0-9_./-]*$/.test(baselineRef)),
  '--baseline requires --baseline-ref naming an available plain-text UI revision');
const accessIndex = process.argv.indexOf('--public-access-file');
const access = accessIndex < 0 ? null : JSON.parse(fs.readFileSync(process.argv[accessIndex + 1]));
const base = access ? access.url.replace(/\/$/, '') : 'https://studio.test';
assert.ok(!access || (base.startsWith('https://') && !baseline), 'Public mode requires HTTPS and current UI');
const sample = [
  '## 账户与财富概览', '',
  '您的可用余额为 **128,650.32 元**，币种 **CNY**。以下仅描述已查询事实。', '',
  '### 已知事实', '',
  '1. **本行账户**', '   - 工资卡：128,650.32 元', '   - 定期存款：50,000.00 元',
  '2. **仍需核实**', '   1. 其他银行资产', '   2. 风险测评有效期', '',
  '| 账户类型 | 可用余额 | 币种 | 统计口径与观察时间 |',
  '| :--- | ---: | :---: | :--- |',
  '| 工资卡 | 128,650.32 | CNY | 本行可用余额，2026-09-07 |',
  '| 美元账户 | 0.00 | USD | 已查询且为零，不是数据缺失 |', '',
  '> 其他机构资产未覆盖；风险测评有效期尚未确认。不构成投资建议。', '',
  '- [x] 已查询账户', '- [ ] 待补充资料', '',
  '来源：[查询记录](https://example.com/source)。字段：`availableBalance`。', '',
  '```json', '{"amount":"128650.32","currency":"CNY","reference":"' + '0123456789'.repeat(18) + '"}', '```', '',
  '---', '', '普通段落仍可正常换行。\n不推断缺失数据。',
].join('\n');

(async () => {
  fs.mkdirSync(output, { recursive: true });
  const report = { mode: access ? 'PUBLIC_STATIC_WITH_CONTROLLED_API' : 'CONTROLLED_UI_MARKDOWN_ONLY', baseline, checks: [], errors: [], unexpectedRequests: [] };
  const browser = await chromium.launch({ headless: true });
  const context = await browser.newContext(access ? { httpCredentials: { username: access.username, password: access.password } } : {});
  const page = await context.newPage();
  page.setDefaultTimeout(15000);
  page.on('pageerror', error => report.errors.push(error.message));
  const check = (name, ok) => { assert.ok(ok, name); report.checks.push(name); };
  try {
    const module = buildSync({ entryPoints: [path.join(project, 'browser/markdown.js')],
      bundle: true, write: false, format: 'iife', globalName: 'MarkdownTest' }).outputFiles[0].text;
    await page.route('**/*', route => route.fulfill({ contentType: 'text/html', body: '<main id="result"></main>' }));
    await page.goto(base);
    await page.addScriptTag({ content: module });
    for (const [name, input] of [
      ['raw_script', '<script>window.__pwned=1</script>'],
      ['event_handler', '<img src="https://leak.invalid/customer" onerror="window.__pwned=1">'],
      ['svg', '<svg onload="window.__pwned=1"><a href="javascript:alert(1)">x</a></svg>'],
      ['unsafe_links', '[a](javascript:alert%281%29) [b](data:text/html,test) [c](file:///etc/passwd)'],
      ['encoded_link', '[a](javascript&#58;alert%281%29)'],
      ['image', '![private data](https://leak.invalid/private.png)'],
      ['form', '<form action="/api/delete"><input name="x"></form>'],
    ]) {
      const state = await page.evaluate(input => {
        const node = document.querySelector('#result');
        node.innerHTML = MarkdownTest.renderMarkdown(input);
        return { unsafe: node.querySelectorAll('script,img,svg,iframe,form,style,[onerror],[onload]').length,
          links: [...node.querySelectorAll('a[href]')].map(a => a.protocol), pwned: !!window.__pwned };
      }, input);
      check('safe_' + name, !state.unsafe && !state.pwned && state.links.every(p => ['http:', 'https:', 'mailto:'].includes(p)));
    }
    await page.unroute('**/*');
    const catalog = JSON.parse(fs.readFileSync(path.join(project, 'examples/workbench-api-v02/catalog.json')));
    catalog.models = [{ modelRef: 'model-test', displayName: '受控模型', bindingStatus: 'CONFIGURED_UNTESTED' }];
    const candidate = JSON.parse(fs.readFileSync(path.join(project, 'examples/workbench-api-v02/build-succeeded.json')));
    candidate.modelSelection = { selectedModelRefs: ['model-test'], defaultModelRef: 'model-test' };
    candidate.selection.presentation = 'MOBILE_WEB';
    candidate.instance = { status: 'READY', instanceId: 'instance-test', sessionId: 'session-test', modelRef: 'model-test' };
    let responseText = sample;
    await page.route('**/*', async route => {
      const url = new URL(route.request().url());
      if (url.origin !== base) {
        report.unexpectedRequests.push(url.origin); return route.abort();
      }
      const json = body => route.fulfill({ contentType: 'application/json', body: JSON.stringify(body) });
      if (url.pathname === '/api/bootstrap') return json({ csrf: 'test-csrf' });
      if (url.pathname === '/api/v1/catalog') return json(catalog);
      if (url.pathname === '/api/v1/connections') return json({ connections: [] });
      if (url.pathname === '/api/v1/builds') return json({ builds: [candidate] });
      if (url.pathname === '/api/v1/builds/' + candidate.buildId) return json(candidate);
      if (url.pathname.endsWith('/authorization')) return json({ authorized: true });
      if (url.pathname.endsWith('/revoke')) return json({ clearConversation: true });
      if (url.pathname.endsWith('/ag-ui')) {
        const input = route.request().postDataJSON();
        const events = [
          { type: 'RUN_STARTED', threadId: input.threadId, runId: input.runId },
          { type: 'TEXT_MESSAGE_START', messageId: 'message-test', role: 'assistant' },
          { type: 'TEXT_MESSAGE_CONTENT', messageId: 'message-test', delta: responseText },
          { type: 'TEXT_MESSAGE_END', messageId: 'message-test' },
          { type: 'CUSTOM', name: 'finance.evidence', value: { result: { response: responseText, modelRef: 'model-test' } } },
          { type: 'RUN_FINISHED', threadId: input.threadId, runId: input.runId },
        ];
        return route.fulfill({ contentType: 'text/event-stream', body: events.map(e => 'data: ' + JSON.stringify(e) + '\n\n').join('') });
      }
      const filename = url.pathname === '/' ? 'index.html' : url.pathname.slice(1);
      if (!['index.html', 'app.js', 'ag-ui.js', 'style.css', 'catalog.css', 'live.css', 'icons.svg'].includes(filename)) {
        report.unexpectedRequests.push(url.pathname); return route.abort();
      }
      const contentType = { html: 'text/html', js: 'text/javascript', css: 'text/css', svg: 'image/svg+xml' }[filename.split('.').at(-1)];
      if (access) return route.continue();
      const body = baseline && filename === 'app.js' ? execFileSync('git', ['show',
        baselineRef + ':community/finance/builder/finance-solution-builder/examples/local-workbench/app.js'], { cwd: project })
        : fs.readFileSync(path.join(project, 'examples/local-workbench', filename));
      return route.fulfill({ contentType, body });
    });
    await page.goto(base + '/?agent=' + candidate.buildId);
    await page.getByRole('button', { name: '打开 Agent', exact: true }).click();
    const query = page.getByRole('textbox', { name: '消息', exact: true });
    await query.fill('**用户原文** <img src=x onerror=alert(1)>');
    await page.getByRole('button', { name: '发送', exact: true }).click();
    const answer = page.locator('.assistant-content').first();
    await answer.waitFor();
    check('ui_headings', await answer.locator('h2').count() === 1 && await answer.locator('h3').count() === 1);
    check('ui_emphasis', await answer.locator('strong').count() >= 4);
    check('ui_nested_lists', await answer.locator('ol ol').count() === 1 && await answer.locator('ol ul').count() === 1);
    check('ui_table', await answer.locator('thead th').count() === 4 && await answer.locator('tbody tr').count() === 2);
    check('ui_quote_code', await answer.locator('blockquote').count() === 1 && await answer.locator('pre code').count() === 1);
    check('ui_readonly_checkboxes', await answer.locator('input:disabled').count() === 2);
    check('ui_user_text_unchanged', (await page.locator('.user-bubble').innerText()).includes('**用户原文** <img') && await page.locator('.user-bubble img').count() === 0);
    check('ui_amount_preserved', (await answer.innerText()).includes('128,650.32'));
    const link = answer.locator('a[href]').first();
    check('ui_safe_source_link', await link.getAttribute('rel') === 'noopener noreferrer' && await link.getAttribute('referrerpolicy') === 'no-referrer');
    for (const [label, width, height] of [['phone-small', 320, 740], ['phone', 390, 844], ['desktop', 1440, 1000]]) {
      await page.setViewportSize({ width, height });
      await page.locator('#chat-messages').evaluate(node => { node.scrollTop = 0; });
      check('no_page_overflow_' + label, await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
      check('bounded_heading_' + label, await answer.locator('h2').evaluate(node => parseFloat(getComputedStyle(node).fontSize) <= 20));
      check('table_readable_' + label, await answer.locator('.markdown-table-scroll').evaluate(node =>
        node.clientWidth > 100 && node.scrollWidth >= node.clientWidth && getComputedStyle(node).overflowX === 'auto'));
      check('code_scroll_' + label, await answer.locator('pre').evaluate(node => node.scrollWidth > node.clientWidth));
      await page.screenshot({ path: path.join(output, label + '.png'), fullPage: true });
      await answer.locator('.markdown-table-scroll').scrollIntoViewIfNeeded();
      if (width < 500) {
        check('table_can_scroll_independently_' + label, await answer.locator('.markdown-table-scroll').evaluate(node => {
          node.scrollLeft = 90;
          const moved = node.scrollLeft > 0 && window.scrollX === 0;
          node.scrollLeft = 0;
          return moved;
        }));
      }
      await page.screenshot({ path: path.join(output, label + '-table.png'), fullPage: true });
    }
    await page.getByRole('button', { name: '来源与执行证据', exact: true }).click();
    check('original_evidence_preserved', JSON.parse(await page.locator('dialog pre').innerText()).response === sample);
    await page.keyboard.press('Escape');
    responseText = '纯文本回复\n第二行';
    await query.fill('下一轮'); await page.getByRole('button', { name: '发送', exact: true }).click();
    await page.waitForFunction(() => document.querySelectorAll('.assistant-content').length === 2);
    check('plain_text_linebreak', await page.locator('.assistant-content').nth(1).locator('br').count() === 1);
    check('no_browser_errors_or_external_requests', report.errors.length === 0 && report.unexpectedRequests.length === 0);
    await page.getByRole('button', { name: '撤销本次授权', exact: true }).click();
    check('revocation_clears_rendered_markdown', await page.locator('.assistant-content').count() === 0);
    report.status = 'PASSED';
  } catch (error) {
    report.status = 'FAILED'; report.failure = error.message;
    report.visibleText = await page.locator('body').innerText();
    throw error;
  } finally {
    fs.writeFileSync(path.join(output, 'report.json'), JSON.stringify(report, null, 2));
    await browser.close();
  }
  console.log(JSON.stringify({ status: report.status, checks: report.checks.length }));
})().catch(error => { console.error(error.message); process.exitCode = 1; });
