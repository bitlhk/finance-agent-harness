import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import { pathToFileURL } from 'node:url';
import { diagramPaths, readDiagram, screenshotPaths } from './public-diagrams.mjs';
import { isSafePublicText, reviewedPublicLinks } from './public-link-policy.mjs';

const { chromium } = await import(process.env.PLAYWRIGHT_MODULE || 'playwright');
const [directory, liveUrl] = process.argv.slice(2);
const output = path.resolve(directory);
const edition = JSON.parse(fs.readFileSync(new URL('../../docs/public/edition.json', import.meta.url), 'utf8'));
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
    if (request.resourceType() !== 'document' && !/^data:image\/(svg\+xml|png);base64,/.test(request.url())) evidence.extraRequests.push(request.url());
  });
  for (const [name, width, height] of [['desktop',1440,1000], ['tablet',820,1000], ['mobile',390,844], ['mobile375',375,812], ['narrow',320,740]]) {
    await page.setViewportSize({ width, height });
    const response = await page.goto(url, { waitUntil: 'networkidle' });
    if (liveUrl) check(`${name}:anonymous_200`, response.status() === 200);
    await page.locator('#article h1').waitFor();
    const book = await page.locator('#book-data').textContent().then(JSON.parse);
    check(`${name}:three_reviewed_public_documents`, book.pages.length === 3 && book.sourceCount === 0 && book.pages[0].id === 'public/start.md');
    check(`${name}:no_internal_addresses`, book.pages.every(p => isSafePublicText(p.text)));
    check(`${name}:home_fits`, await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
    const cta = page.locator('#article a.guide-start').first();
    check(`${name}:primary_task_visible`, (await cta.boundingBox()).y < height);
    await page.screenshot({path:path.join(output,`${name}-home.png`)});
    await cta.click();
    await page.locator('#step-1').waitFor();
    await page.locator('#materials').scrollIntoViewIfNeeded();
    await page.waitForTimeout(250);
    check(`${name}:seven_steps`, await page.locator('#article h2[id^="step-"]').count() === 7);
    check(`${name}:tutorial_fits`, await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
    await page.screenshot({path:path.join(output,`${name}-tutorial.png`)});
    if (name === 'desktop' && !liveUrl) {
      const pending = page.waitForEvent('download');
      await page.locator('#article a[download]').click();
      const download = await pending;
      await download.saveAs(path.join(output,'downloaded-cases.json'));
      check('download:cases_match', fs.readFileSync(path.join(output,'downloaded-cases.json')).equals(fs.readFileSync(path.join(output,'first-agent-cases.json'))));
    }
    await page.locator('[data-reference]').first().click();
    check(`${name}:source_location`, await page.locator('#reference-path').textContent().then(t => t.includes(edition.harnessExecutionCommit)));
    await page.locator('#close-dialog').click();
    if (width <= 880) await page.locator('#menu').click();
    await page.locator('#search').fill('cashflow-review');
    await page.waitForTimeout(200);
    check(`${name}:full_text_search`, await page.locator('#navigation a').count() >= 1);
    await page.locator('#clear').click();
    await page.locator('#navigation a[href*="developer-guide.md"]').click();
    await page.waitForFunction(() => document.querySelector('#article h1')?.textContent.includes('开发指导'));
    const externalLinks = await page.locator('#article a[href^="https://"]').evaluateAll(links => links.map(link => ({href:link.getAttribute('href'),rel:link.rel})));
    check(`${name}:only_four_reviewed_external_links`, externalLinks.length === 4 && externalLinks.every(link => reviewedPublicLinks.has(link.href) && link.rel.includes('noreferrer')));
    check(`${name}:page_fits`, await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
    check(`${name}:seventeen_chapters`, await page.locator('#article h2').count() === 17);
    check(`${name}:three_embedded_diagrams`, await page.locator('img.harness-diagram').count() === diagramPaths.length);
    check(`${name}:edition_matches`, book.baseline === edition.version && book.date === edition.date);
    check(`${name}:real_ui_screenshots`, await page.locator('img.harness-screenshot').count() === screenshotPaths.length);
    check(`${name}:screenshots_render`, await page.locator('img.harness-screenshot').evaluateAll(images => images.every(img => img.complete && img.naturalWidth >= 1280 && img.alt)));
    check(`${name}:diagrams_render`, await page.locator('img.harness-diagram').evaluateAll(images => images.every(img => img.complete && img.naturalWidth > 1000 && img.getBoundingClientRect().height > 100 && img.alt)));
    check(`${name}:granularity_and_self_test_guidance`, ['沉淀粒度', 'Profile', 'Integrator', 'Provider', 'run-controlled.sh', 'verify_wealth_kit.py', '独立接入'].every(term => book.pages[2].text.includes(term)));
    check(`${name}:internal_link_separate`, await page.locator('a[href="/developer-guide/internal/"]').count() === 1);
    await page.screenshot({ path: path.join(output, `${liveUrl ? 'live-' : ''}${name}.png`) });
    const reference = book.pages[2];
    const chapter = reference.headings.filter(h => h.depth === 2)[7];
    await page.evaluate(id => {location.hash = `#doc=public%2Fdeveloper-guide.md&section=${id}`;}, chapter.id);
    await page.waitForFunction(() => location.hash.includes('section='));
    check(`${name}:chapter_navigation`, (await page.locator('#article h2').nth(7).textContent()).includes('路径 A'));
    check(`${name}:menu_closes`, await page.locator('#menu').getAttribute('aria-expanded') === 'false');
    await page.evaluate(id => {location.hash = `#doc=public%2Fdeveloper-guide.md&section=${id}`;}, reference.headings.filter(h => h.depth === 2)[16].id);
    if (width <= 880) await page.waitForFunction(() => document.getElementById('sidebar').getBoundingClientRect().right <= 1);
    await page.getByRole('heading', { name: '16. 从看懂资产到开发一个新特性', exact: true }).scrollIntoViewIfNeeded();
    check(`${name}:development_navigation`, book.pages[2].text.includes('后续金融新特性统一交付卡'));
    check(`${name}:development_no_overflow`, await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
    await page.screenshot({ path: path.join(output, `${liveUrl ? 'live-' : ''}${name}-development.png`) });
    await page.locator('[data-screenshot="cashflow-relations"]').scrollIntoViewIfNeeded();
    await page.screenshot({ path: path.join(output, `${liveUrl ? 'live-' : ''}${name}-relationships.png`) });
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
        const bounds = svg.getBoundingClientRect();
        return [...svg.querySelectorAll('text')].filter(text => {
          const b = text.getBoundingClientRect();
          const {x,y} = new DOMPoint(Number(text.getAttribute('x')), Number(text.getAttribute('y'))).matrixTransform(text.getScreenCTM());
          const containers = [...svg.querySelectorAll('rect')].map(r => r.getBoundingClientRect()).filter(r => x >= r.x && x < r.x + r.width && y > r.y && y <= r.y + r.height).sort((a,b) => a.width*a.height - b.width*b.height);
          const parent = containers[0] || bounds;
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
      footerTemplate: `<div style="width:100%;font-size:9px;color:#777;text-align:center">Finance Harness · 公开开发版 ${edition.version} · <span class="pageNumber"></span> / <span class="totalPages"></span></div>` });
  }
  fs.writeFileSync(path.join(output, `${liveUrl ? 'live-' : ''}verification.json`), JSON.stringify(evidence, null, 2));
  console.log(JSON.stringify({ mode: evidence.mode, checks: evidence.checks.length, errors: evidence.errors, extraRequests: evidence.extraRequests }));
} catch (error) {
  evidence.errors.push(error.stack);
  throw error;
} finally {
  fs.writeFileSync(path.join(output, `${liveUrl ? 'live-' : ''}verification.json`), JSON.stringify(evidence, null, 2));
  await browser.close();
}
