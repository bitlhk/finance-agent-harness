// Extend a fixed exported reader without modifying an unrelated documentation worktree.
import fs from 'node:fs';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { marked } from 'marked';

const [input, output] = process.argv.slice(2);
if (!input || !output) throw Error('Usage: node engineering/extend_public_guide.mjs INPUT_HTML NEW_DIRECTORY');
const original = fs.readFileSync(input, 'utf8');
const pattern = /<script type="application\/json" id="book-data">([\s\S]*?)<\/script>/;
const match = original.match(pattern);
if (!match) throw Error('EXPORTED_READER_REQUIRED');
const book = JSON.parse(match[1]);
if (book.baseline !== 'V0.8' || book.pages.length !== 1) throw Error('GUIDE_BASELINE_MISMATCH');
const page = book.pages[0];
const appendix = fs.readFileSync(new URL('../docs/public/python-runtime-v01.md', import.meta.url), 'utf8');
if (/https?:\/\/|\/home\/|\/opt\/|password\s*[:=]|Bearer\s/i.test(appendix)) throw Error('PUBLIC_CONTENT_REVIEW_REQUIRED');
const esc = s => String(s).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'})[c]);
const renderer = new marked.Renderer();
renderer.html = token => esc(token.text);
renderer.link = function(token) { return this.parser.parseInline(token.tokens); };
renderer.image = () => { throw Error('NO_NEW_DIAGRAMS_IN_THIS_APPENDIX'); };
renderer.heading = function(token) {
  const id = `section-${page.headings.length}`;
  page.headings.push({id, depth: token.depth, text: token.text});
  return `<h${token.depth} id="${id}">${this.parser.parseInline(token.tokens)}</h${token.depth}>`;
};
renderer.table = function(token) { return `<div class="table-scroll" tabindex="0">${marked.Renderer.prototype.table.call(this, token)}</div>`; };
const additions = marked.parse(appendix, {renderer, gfm:true});
const oldText = '当前实际适配的是 **OpenJiuwen Java 0.1.13**。';
const newText = '当前只读工作台支持 **OpenJiuwen Java 0.1.15 与 Python 0.1.18（通过 Java 金融桥）**，详见第 13 章。';
if (!page.text.includes(oldText)) throw Error('GUIDE_WORDING_CHANGED');
page.text = page.text.replace(oldText, newText).replace('| OpenJiuwen Java 0.1.13 | 已适配框架 |','| OpenJiuwen Java 0.1.15 | 当前只读框架 |')
  .replace('| OpenJiuwen Python | 金融接线待接入 | 独立框架标识，页面和后端均拒绝构建，不回退 Java |','| OpenJiuwen Python 0.1.18 | 已接三单元七组合 | 原生 Python 循环 + Java 金融桥；非纯 Python SDK |');
page.html = page.html.replace('当前实际适配的是 <strong>OpenJiuwen Java 0.1.13</strong>。', marked.parseInline(newText))
  .replace('<td>OpenJiuwen Java 0.1.13</td>','<td>OpenJiuwen Java 0.1.15</td>')
  .replace('<td>OpenJiuwen Python</td>','<td>OpenJiuwen Python 0.1.18</td>')
  .replace('<td>金融接线待接入</td>','<td>已接三单元七组合</td>')
  .replace('<td>独立框架标识，页面和后端均拒绝构建，不回退 Java</td>','<td>原生 Python 循环 + Java 金融桥；非纯 Python SDK</td>');
page.text = page.text.replace('V0.8', 'V0.9').replace('2026-09-10', '2026-09-12')+'\n\n'+appendix;
page.html = page.html.replace('V0.8', 'V0.9').replace('2026-09-10', '2026-09-12')+additions;
book.baseline='V0.9';book.date='2026-09-12';
const nav=page.headings.filter(h=>h.depth===2).map(h=>`<a href="#doc=${encodeURIComponent(page.id)}&section=${h.id}">${esc(h.text)}</a>`).join('');
let html=original.replace(pattern,()=>'<script type="application/json" id="book-data">'+JSON.stringify(book).replace(/</g,'\\u003c')+'</script>')
  .replace(/<nav class="sidebar-chapters"[\s\S]*?<\/nav>/,()=>`<nav class="sidebar-chapters" aria-label="开发指导章节"><h2>开发指导</h2>${nav}</nav>`)
  .replaceAll('指南 V0.8','指南 V0.9').replaceAll('公开开发版 · V0.8','公开开发版 · V0.9')
  .replace('12 个章节 · 公开开发版','13 个章节 · 公开开发版').replace('2026-09-10 · 指南 V0.9','2026-09-12 · 指南 V0.9');
fs.mkdirSync(output,{recursive:false});fs.writeFileSync(path.join(output,'index.html'),html);
const style=original.match(/<style>([\s\S]*?)<\/style>/)?.[1];if (!style) throw Error('READER_STYLE_MISSING');
fs.writeFileSync(path.join(output,'print-guide.html'),`<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>Finance Harness 开发指导 V0.9</title><style>${style}</style><body class="print-edition">${page.html}</body></html>`);
fs.writeFileSync(path.join(output,'manifest.json'),JSON.stringify({version:'0.9',sourceHtmlSha256:createHash('sha256').update(original).digest('hex'),htmlSha256:createHash('sha256').update(html).digest('hex'),appendixSha256:createHash('sha256').update(appendix).digest('hex'),preservedDiagrams:true},null,2));
console.log(JSON.stringify({version:book.baseline,chapters:page.headings.filter(h=>h.depth===2).length}));
