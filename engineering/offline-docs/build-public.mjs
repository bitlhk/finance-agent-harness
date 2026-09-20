import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { marked } from 'marked';
import { icons } from 'lucide';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { diagramCss, diagramPaths, readDiagram, renderPublicDiagram, screenshotPaths, readScreenshot, renderPublicScreenshot } from './public-diagrams.mjs';
import { isSafePublicText, reviewedPublicLinks } from './public-link-policy.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '../..');
const output = path.resolve(process.argv[2]);
fs.mkdirSync(output, { recursive: false });
const text = fs.readFileSync(path.join(root, 'docs/public/developer-guide.md'), 'utf8');
const edition = JSON.parse(fs.readFileSync(path.join(root, 'docs/public/edition.json'), 'utf8'));
const guideVersion = edition.version;
const guideDate = edition.date;
if (!/^V\d+\.\d+$/.test(guideVersion) || !/^\d{4}-\d{2}-\d{2}$/.test(guideDate)
    || !text.includes(`公开开发版 ${guideVersion} · 接入套件候选 V0.1 · ${guideDate}`)) throw new Error('Edition and source heading disagree');
if (!isSafePublicText(text)) throw new Error('Public guide contains an unreviewed URL/address/path or secret-like text');
const escape = s => String(s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
const headings = [];
const renderer = new marked.Renderer();
renderer.html = token => escape(token.text);
renderer.link = function(token) {
  const label = this.parser.parseInline(token.tokens);
  return reviewedPublicLinks.has(token.href)
    ? `<a href="${escape(token.href)}" target="_blank" rel="noopener noreferrer">${label}</a>` : label;
};
renderer.image = token => {
  const figure = renderPublicDiagram(edition.source, token.href, token.text) || renderPublicScreenshot(edition.source, token.href, token.text);
  if (!figure) throw new Error('Public guide references an unreviewed image');
  return figure;
};
renderer.paragraph = function(token) {
  if (token.tokens.length === 1 && token.tokens[0].type === 'image') return this.parser.parseInline(token.tokens);
  if (token.tokens.length === 1 && token.tokens[0].type === 'strong' && token.tokens[0].text.startsWith('文件：'))
    return `<p class="file-caption">${this.parser.parseInline(token.tokens)}</p>`;
  return marked.Renderer.prototype.paragraph.call(this, token);
};
renderer.heading = function(token) {
  const id = `section-${headings.length}`;
  headings.push({ id, depth: token.depth, text: token.text });
  return `<h${token.depth} id="${id}">${this.parser.parseInline(token.tokens)}</h${token.depth}>`;
};
renderer.table = function(token) { return `<div class="table-scroll" tabindex="0">${marked.Renderer.prototype.table.call(this, token)}</div>`; };
const content = marked.parse(text, { renderer, gfm: true });
const chapterCount = headings.filter(h => h.depth === 2).length;
const id = 'public/developer-guide.md';
const book = { title: 'Finance Harness 开发指导 · 公开版', date: guideDate, mainId: id, prefaceId: id,
  baseline: guideVersion, builderSha: null, documentCount: 1, sourceCount: 0,
  pages: [{ id, title: 'Finance Harness 开发指导', path: '公开开发版 · 架构、沉淀粒度与自助接入', repo: 'edition',
    group: '开发指导', kind: 'document', text, html: content, headings }] };
const icon = name => {
  const render = ([tag, attrs, children = []]) => `<${tag} ${Object.entries(attrs).filter(([k]) => k !== 'key').map(([k,v]) => `${k}="${escape(v)}"`).join(' ')}>${children.map(render).join('')}</${tag}>`;
  return render(icons[name]).replace('<svg ', '<svg aria-hidden="true" ');
};
let html = fs.readFileSync(path.join(here, 'reader.html'), 'utf8')
  .replace('/* INLINE_STYLE */', `/* INLINE_STYLE */\n${diagramCss}`)
  .replace('/* INLINE_STYLE */', () => fs.readFileSync(path.join(here, 'reader.css'), 'utf8') + '\n#navigation{display:none}.sidebar-chapters{overflow:auto;flex:1;margin-top:18px;font-size:12px}.sidebar-chapters h2{border:0;font-size:12px;margin:0 0 14px;padding:0;color:#777}.sidebar-chapters a{display:block;padding:7px 10px;line-height:1.7;color:#555}.sidebar-chapters a:hover{color:var(--accent);background:#f7e9eb;text-decoration:none}.internal-guide{display:block;color:#646a72;margin-bottom:14px;font-size:12px}.public-links{display:flex;flex-wrap:wrap;gap:12px;margin-bottom:12px}')
  .replace('/* INLINE_SCRIPT */', () => fs.readFileSync(path.join(here, 'reader.js'), 'utf8') + `
document.getElementById('edition-count').textContent = '${chapterCount} 个章节 · 公开开发版';
document.getElementById('source-path').textContent = '设计原则、组件架构、贡献路径与自助验证';
document.getElementById('doc-count').textContent = '${guideDate} · 指南 ${guideVersion}';
document.querySelectorAll('.sidebar-chapters a').forEach(link => link.addEventListener('click', () => {
  if (document.body.classList.contains('nav-open')) document.getElementById('menu').click();
}));
`)
  .replace('BOOK_DATA', () => JSON.stringify(book).replace(/</g, '\\u003c'))
  .replace('Finance Harness · 离线开发指导', 'Finance Harness · 公开开发指导')
  .replace('离线版 · 2026.09', `公开开发版 · ${guideVersion}`)
  .replace('候选 V0.1 · 团队内部资料', `指南 ${guideVersion} · 套件候选 V0.1`)
  .replace('代码基线', '指南版本')
  .replace('原报告保留历史口径。', '指南不代替实施验收。')
  .replace('此阅读器需要浏览器启用 JavaScript。可改用包内 PDF 或 sources/ 中的 Markdown 原文。无需联网。', '此阅读器需要浏览器启用 JavaScript。也可访问本指南的 PDF 版。')
  .replace('<div class="search-box">', '<div class="search-box" hidden>')
  .replace('<label class="source-option">', '<label class="source-option" hidden>')
  .replace('<p id="search-status"', '<p hidden id="search-status"')
  .replace('<div class="sidebar-footer">', `<nav class="sidebar-chapters" aria-label="开发指导章节"><h2>开发指导</h2>${headings.filter(h=>h.depth===2).map(h=>`<a href="#doc=${encodeURIComponent(id)}&section=${h.id}">${escape(h.text)}</a>`).join('')}</nav><div class="sidebar-footer"><a class="internal-guide" href="/developer-guide/internal/" target="_blank" rel="noopener noreferrer">内部完整版 · 需登录</a>`)
  .replace('<div class="document-bar">', '<div class="public-links"><a href="/">返回工作台（需登录）</a><a href="/developer-guide/handbook.pdf" target="_blank" rel="noopener noreferrer">PDF 版</a></div><div class="document-bar">')
  .replace(/ICON_([A-Za-z]+)/g, (_, name) => icon(name));
fs.writeFileSync(path.join(output, 'index.html'), html);
fs.writeFileSync(path.join(output, 'print-guide.html'), `<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>Finance Harness 公开开发指导</title><style>${fs.readFileSync(path.join(here, 'reader.css'),'utf8')}${diagramCss}@media print{body{font-size:9.5pt;line-height:1.65}pre{break-inside:avoid-page}.file-caption{break-after:avoid-page}}</style><body class="print-edition"><p class="print-kicker">FINANCE HARNESS / 公开开发版 ${guideVersion} / ${guideDate}</p>${content}</body></html>`);
fs.writeFileSync(path.join(output, 'manifest.json'), JSON.stringify({ audience: 'PUBLIC_REDACTED', guideVersion: guideVersion.slice(1), chapterCount,
  source: edition.source, sourceCommit: execFileSync('git', ['rev-parse', 'HEAD'], {cwd:root,encoding:'utf8'}).trim(),
  sourceDirty: Boolean(execFileSync('git', ['status', '--porcelain'], {cwd:root,encoding:'utf8'}).trim()), implementationBaseline: edition,
  sourceSha256: createHash('sha256').update(text).digest('hex'),
  diagrams: diagramPaths.map(p => { const { text, ...meta } = readDiagram(p); return meta; }),
  screenshots: screenshotPaths.map(p => { const { bytes, ...meta } = readScreenshot(p); return meta; }),
  htmlSha256: createHash('sha256').update(html).digest('hex'), exclusions: ['private source appendices', 'internal deployment details', 'historic validation reports', 'credentials and live configuration'] }, null, 2));
console.log(JSON.stringify({ output, chapters: headings.filter(h=>h.depth===2).length, bytes: Buffer.byteLength(html) }));
