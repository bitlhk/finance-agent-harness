import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { icons } from 'lucide';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { diagramCss, diagramPaths, readDiagram, screenshotPaths, readScreenshot } from './public-diagrams.mjs';
import { loadPublicPages } from './public-pages.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '../..');
const output = path.resolve(process.argv[2]);
const edition = JSON.parse(fs.readFileSync(path.join(root, 'docs/public/edition.json'), 'utf8'));
const pages = loadPublicPages(root, edition);
if (!pages[2].text.includes(`公开开发版 ${edition.version} · 接入套件候选 V0.1 · ${edition.date}`)) throw new Error('Edition/source mismatch');
fs.mkdirSync(output, {recursive:false});
const book = {title:'Finance Harness 开发指导', date:edition.date, mainId:pages[0].id, prefaceId:pages[1].id,
  baseline:edition.version, builderSha:edition.builderCommit, documentCount:pages.length, sourceCount:0, pages};
const escape = s => String(s).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const icon = name => {
  const render = ([tag, attrs, children = []]) => `<${tag} ${Object.entries(attrs).filter(([k]) => k !== 'key').map(([k,v]) => `${k}="${escape(v)}"`).join(' ')}>${children.map(render).join('')}</${tag}>`;
  return render(icons[name]).replace('<svg ', '<svg aria-hidden="true" ');
};
const css = fs.readFileSync(path.join(here,'reader.css'),'utf8') + diagramCss + `
.source-option{display:none}.public-links{display:flex;flex-wrap:wrap;gap:16px;margin-bottom:16px}
a.guide-start{display:inline-flex;align-items:center;min-height:44px;padding:8px 16px;background:#b51f3a;color:white;border-radius:4px;font-weight:600}
#reference-path{white-space:pre-wrap;overflow-wrap:anywhere}article code{overflow-wrap:anywhere}
@media(max-width:600px){main{padding:20px 16px}.public-links{font-size:13px}}
`;
let html = fs.readFileSync(path.join(here,'reader.html'),'utf8')
  .replace('/* INLINE_STYLE */', () => css)
  .replace('/* INLINE_SCRIPT */', () => fs.readFileSync(path.join(here,'reader.js'),'utf8'))
  .replace('BOOK_DATA', () => JSON.stringify(book).replace(/</g,'\\u003c'))
  .replace('离线版 · 2026.09', `公开开发版 · ${edition.version}`)
  .replace('候选 V0.1 · 团队内部资料', `指南 ${edition.version} · 固定实现参考`)
  .replace('代码基线', '指南版本')
  .replace('原报告保留历史口径。','参考章节保留历史口径。')
  .replace('这项资料未收录','定位受限源码')
  .replace('它可能是外部仓库文件或本机运行证据。离线包没有访问该路径；请向对应组件负责人索取。','以下为固定版本中的真实文件位置，不是公开下载链接。请先按“取得材料与环境”获得授权源码；此页面不会读取私有仓库。')
  .replace('<div class="document-bar">','<div class="public-links"><a href="/">返回工作台（需登录）</a><a href="handbook.pdf">完整 PDF</a><a href="/developer-guide/internal/">内部参考（需登录）</a></div><div class="document-bar">')
  .replace(/ICON_([A-Za-z]+)/g,(_,name) => icon(name));
fs.writeFileSync(path.join(output,'index.html'),html);
const caseBytes = fs.readFileSync(path.join(root,'docs/public/first-agent-cases.json'));
fs.writeFileSync(path.join(output,'first-agent-cases.json'),caseBytes);
fs.writeFileSync(path.join(output,'print-guide.html'),`<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>Finance Harness 开发指导</title><style>${css}@media print{body{font-size:9.5pt;line-height:1.65}pre{break-inside:avoid-page}.print-chapter{break-before:page}}</style><body class="print-edition"><p>FINANCE HARNESS / ${edition.version} / ${edition.date}</p>${pages.map(p=>`<section class="print-chapter">${p.html}</section>`).join('')}</body></html>`);
const sha = data => createHash('sha256').update(data).digest('hex');
fs.writeFileSync(path.join(output,'manifest.json'),JSON.stringify({audience:'PUBLIC_REDACTED',guideVersion:edition.version.slice(1),
  sourceCommit:execFileSync('git',['rev-parse','HEAD'],{cwd:root,encoding:'utf8'}).trim(),
  sourceDirty:Boolean(execFileSync('git',['status','--porcelain'],{cwd:root,encoding:'utf8'}).trim()), implementationBaseline:edition,
  sources:pages.map(p=>({source:p.source,sha256:sha(p.text),headings:p.headings.length})),
  diagrams:diagramPaths.map(p=>{const {text,...meta}=readDiagram(p);return meta;}),
  screenshots:screenshotPaths.map(p=>{const {bytes,...meta}=readScreenshot(p);return meta;}),
  htmlSha256:sha(html),casesSha256:sha(caseBytes),exclusions:['private source contents','credentials','live configuration','internal deployment details']},null,2));
console.log(JSON.stringify({output,pages:pages.length,bytes:Buffer.byteLength(html)}));
