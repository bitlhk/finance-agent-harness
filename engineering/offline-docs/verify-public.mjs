import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import { pathToFileURL } from 'node:url';
import { diagramPaths, readDiagram } from './public-diagrams.mjs';
import { isSafePublicText, reviewedPublicLinks } from './public-link-policy.mjs';

const { chromium } = await import(process.env.PLAYWRIGHT_MODULE || 'playwright');
const [directory, liveUrl] = process.argv.slice(2);
const output = path.resolve(directory);
const url = liveUrl || pathToFileURL(path.join(output, 'index.html')).href;
const evidence = { mode: liveUrl ? 'ANONYMOUS_PUBLIC_READER' : 'LOCAL_PUBLIC_READER', checks: [], errors: [], extraRequests: [] };
const check = (name, passed) => { assert.ok(passed, name); evidence.checks.push(name); };
const browser = await chromium.launch({ headless: true, ...(process.env.CHROMIUM_PATH ? { executablePath: process.env.CHROMIUM_PATH } : {}) });
try {
  const context = await browser.newContext();
  if (!liveUrl) await context.setOffline(true);
  const page = await context.newPage();
  page.on('pageerror', error => evidence.errors.push(error.message));
  page.on('request', request => {
    if (request.resourceType() !== 'document' && !request.url().startsWith('data:image/svg+xml;base64,')) evidence.extraRequests.push(request.url());
  });
  for (const [name, width, height] of [['desktop',1440,1000], ['tablet',820,1000], ['mobile',390,844], ['narrow',320,740]]) {
    await page.setViewportSize({ width, height });
    const response = await page.goto(url, { waitUntil: 'networkidle' });
    if (liveUrl) check(`${name}:anonymous_200`, response.status() === 200);
    await page.locator('#article h1').waitFor();
    const book = await page.locator('#book-data').textContent().then(JSON.parse);
    check(`${name}:only_reviewed_public_document`, book.pages.length === 1 && book.sourceCount === 0 && book.pages[0].id === 'public/developer-guide.md');
    check(`${name}:no_internal_addresses`, isSafePublicText(book.pages[0].text));
    const externalLinks = await page.locator('#article a[href^="https://"]').evaluateAll(links => links.map(link => ({href:link.getAttribute('href'),rel:link.rel})));
    check(`${name}:only_four_reviewed_external_links`, externalLinks.length === 4 && externalLinks.every(link => reviewedPublicLinks.has(link.href) && link.rel.includes('noreferrer')));
    check(`${name}:page_fits`, await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
    check(`${name}:sixteen_chapters`, await page.locator('.sidebar-chapters a').count() === 16);
    check(`${name}:two_embedded_diagrams`, await page.locator('img.harness-diagram').count() === 2);
    check(`${name}:diagrams_render`, await page.locator('img.harness-diagram').evaluateAll(images => images.every(img => img.complete && img.naturalWidth > 1000 && img.getBoundingClientRect().height > 100 && img.alt)));
    check(`${name}:granularity_and_self_test_guidance`, ['沉淀粒度', 'Profile', 'Integrator', 'Provider', 'run-controlled.sh', 'verify_wealth_kit.py', '独立接入'].every(term => book.pages[0].text.includes(term)));
    check(`${name}:internal_link_separate`, await page.locator('.internal-guide').getAttribute('href') === '/developer-guide/internal/');
    await page.screenshot({ path: path.join(output, `${liveUrl ? 'live-' : ''}${name}.png`) });
    if (width <= 880) {
      await page.locator('#menu').click();
      check(`${name}:menu_opens`, await page.locator('#menu').getAttribute('aria-expanded') === 'true');
    }
    await page.locator('.sidebar-chapters a').nth(7).click();
    await page.waitForFunction(() => location.hash.includes('section='));
    check(`${name}:chapter_navigation`, (await page.locator('#article h2').nth(7).textContent()).includes('路径 A'));
    check(`${name}:menu_closes`, await page.locator('#menu').getAttribute('aria-expanded') === 'false');
    await page.locator('.harness-figure').first().scrollIntoViewIfNeeded();
    await page.screenshot({ path: path.join(output, `${liveUrl ? 'live-' : ''}${name}-architecture.png`) });
    if (width <= 880) {
      check(`${name}:diagram_overflow_scoped`, await page.locator('.diagram-scroll').first().evaluate(el => { const needsScroll = el.scrollWidth > el.clientWidth; el.scrollLeft = 200; return (!needsScroll || el.scrollLeft > 0) && document.documentElement.scrollWidth <= innerWidth; }));
    }
  }
  check('no_page_errors', evidence.errors.length === 0);
  check('no_subresource_or_api_requests', evidence.extraRequests.length === 0);
  if (!liveUrl) {
    const diagramContext = await browser.newContext({ viewport: { width: 1200, height: 1240 }, deviceScaleFactor: 2, offline: true });
    const diagramPage = await diagramContext.newPage();
    for (const source of diagramPaths) {
      const { text } = readDiagram(source);
      await diagramPage.setContent(`<style>body{margin:0}</style>${text}`);
      await diagramPage.evaluate(() => document.fonts.ready);
      const overflow = await diagramPage.locator('svg').evaluate(svg => {
        const { width, height } = svg.viewBox.baseVal;
        return [...svg.querySelectorAll('text')].filter(text => {
          const b = text.getBBox();
          const x = Number(text.getAttribute('x')); const y = Number(text.getAttribute('y'));
          const containers = [...svg.querySelectorAll('rect')].map(r => r.getBBox()).filter(r => x >= r.x && x < r.x + r.width && y > r.y && y <= r.y + r.height).sort((a,b) => a.width*a.height - b.width*b.height);
          const parent = containers[0] || { x:0, y:0, width, height };
          return b.x < parent.x || b.x + b.width > parent.x + parent.width || b.y < parent.y || b.y + b.height > parent.y + parent.height;
        }).map(t => t.textContent);
      });
      check(`${source}:text_inside_nodes`, overflow.length === 0 || (console.error(overflow), false));
      await diagramPage.locator('svg').screenshot({ path: path.join(output, `${path.basename(source, '.svg')}-full.png`) });
      fs.writeFileSync(path.join(output, path.basename(source)), text);
    }
    await diagramContext.close();
    await page.goto(pathToFileURL(path.join(output, 'print-guide.html')).href);
    await page.evaluate(() => document.fonts.ready);
    await page.emulateMedia({ media: 'print' });
    check('print:diagrams_fit', await page.locator('.harness-diagram').evaluateAll(images => images.every(img => img.getBoundingClientRect().width <= document.documentElement.clientWidth)));
    await page.pdf({ path: path.join(output, 'handbook.pdf'), preferCSSPageSize: true, printBackground: true,
      displayHeaderFooter: true, headerTemplate: '<span></span>',
      footerTemplate: '<div style="width:100%;font-size:9px;color:#777;text-align:center">Finance Harness · 公开开发版 V1.0 · <span class="pageNumber"></span> / <span class="totalPages"></span></div>' });
  }
  fs.writeFileSync(path.join(output, `${liveUrl ? 'live-' : ''}verification.json`), JSON.stringify(evidence, null, 2));
  console.log(JSON.stringify({ mode: evidence.mode, checks: evidence.checks.length, errors: evidence.errors, extraRequests: evidence.extraRequests }));
} finally { await browser.close(); }
