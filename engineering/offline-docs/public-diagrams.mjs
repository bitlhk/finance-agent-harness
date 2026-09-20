import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
export const diagramPaths = ['docs/public/diagrams/architecture.svg', 'docs/public/diagrams/lifecycle.svg', 'docs/public/diagrams/development.svg'];
export const screenshotPaths = ['docs/public/screenshots/cashflow-relations.png', 'docs/public/screenshots/capability-declaration.png'];
const escape = value => String(value).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);

export function readDiagram(relative) {
  if (!diagramPaths.includes(relative)) throw new Error('Diagram is not in the reviewed public allowlist');
  const file = path.join(root, relative);
  if (!fs.lstatSync(file).isFile()) throw new Error('Diagram must be a regular file');
  const text = fs.readFileSync(file, 'utf8');
  const withoutNamespace = text.replace('http://www.w3.org/2000/svg', '');
  if (!text.startsWith('<svg ') || /<script|<foreignObject|<image|<a\s|<!|\b(?:href|on\w+)\s*=|https?:\/\/|\/home\/|\/opt\/|\/etc\/|(?:\d{1,3}\.){3}\d{1,3}/i.test(withoutNamespace)) {
    throw new Error('Public diagram contains unsupported active or external content');
  }
  return { text, source: relative, sha256: createHash('sha256').update(text).digest('hex') };
}

export function renderPublicDiagram(sourcePath, href, alt) {
  const relative = path.posix.normalize(path.posix.join(path.posix.dirname(sourcePath), href));
  if (!diagramPaths.includes(relative)) return null;
  const { text } = readDiagram(relative);
  const name = path.basename(relative, '.svg');
  return `<figure class="harness-figure" data-diagram="${name}"><div class="diagram-scroll" tabindex="0" role="region" aria-label="${escape(alt)}"><img class="harness-diagram" src="data:image/svg+xml;base64,${Buffer.from(text).toString('base64')}" alt="${escape(alt)}"></div><figcaption>${escape(alt)}</figcaption></figure>`;
}

export function readScreenshot(relative) {
  if (!screenshotPaths.includes(relative)) throw new Error('Screenshot is not in the reviewed public allowlist');
  const file = path.join(root, relative);
  if (!fs.lstatSync(file).isFile()) throw new Error('Screenshot must be a regular file');
  const bytes = fs.readFileSync(file);
  if (bytes.length > 2 * 1024 * 1024 || !bytes.subarray(0, 8).equals(Buffer.from([137,80,78,71,13,10,26,10]))) {
    throw new Error('Public screenshot must be a bounded PNG');
  }
  return { bytes, source: relative, sha256: createHash('sha256').update(bytes).digest('hex') };
}

export function renderPublicScreenshot(sourcePath, href, alt) {
  const relative = path.posix.normalize(path.posix.join(path.posix.dirname(sourcePath), href));
  if (!screenshotPaths.includes(relative)) return null;
  const { bytes } = readScreenshot(relative);
  return `<figure class="harness-figure" data-screenshot="${path.basename(relative, '.png')}"><div class="diagram-scroll" tabindex="0" role="region" aria-label="${escape(alt)}"><img class="harness-screenshot" src="data:image/png;base64,${bytes.toString('base64')}" alt="${escape(alt)}"></div><figcaption>${escape(alt)}</figcaption></figure>`;
}

export const diagramCss = `
.harness-figure{margin:24px 0;break-inside:avoid}
.diagram-scroll{overflow-x:auto;max-width:100%;border-top:1px solid #d8dbdf;border-bottom:1px solid #d8dbdf;background:#fff}
.harness-diagram{display:block;width:100%;min-width:760px;height:auto;max-width:none}
.harness-screenshot{display:block;width:100%;min-width:900px;height:auto;max-width:none}
.harness-figure figcaption{font-size:12px;line-height:1.8;color:#60656c;margin:10px 0}
@media print{.diagram-scroll{overflow:visible;border:0}.harness-diagram,.harness-screenshot{min-width:0;width:100%}.harness-figure{break-inside:avoid-page}.harness-figure figcaption{font-size:8pt}}
`;
