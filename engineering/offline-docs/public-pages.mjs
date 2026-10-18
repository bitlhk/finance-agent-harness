import fs from 'node:fs';
import path from 'node:path';
import { marked } from 'marked';
import { isSafePublicText, reviewedPublicLinks } from './public-link-policy.mjs';
import { renderPublicDiagram, renderPublicScreenshot } from './public-diagrams.mjs';

export const pageSpecs = [
  ['start', 'Finance Harness', '开发入口'],
  ['first-agent', '完成第一个 Agent', '七步实践与交接'],
  ['developer-guide', '架构、API 与版本参考', '原 17 章参考'],
];
export const sourceReferences = {
  skill: ['Harness', 'domain-packs/cashflow/customer-self-service/skills/cashflow-review/SKILL.md'],
  method: ['Harness', 'domain-packs/cashflow/customer-self-service/pack.yaml'],
  capability: ['Harness', 'capability-packs/cashflow-readonly/pack.yaml'],
  contract: ['Harness', 'contracts/json-schema/cashflow/cashflow-facts.v0.1.schema.json'],
  assembly: ['Harness', 'capability-packs/cashflow-readonly/assembly.v0.1.json'],
  guard: ['Harness', 'capability-packs/cashflow-readonly/query-guard.v1.json'],
  provider: ['Harness', 'providers/non-ai-banking/src/main/java/io/github/bitlhk/finance/harness/providers/nonaibanking/NonAiBankingFactoryV01.java'],
  'provider-test': ['Harness', 'providers/non-ai-banking/src/test/java/io/github/bitlhk/finance/harness/providers/nonaibanking/CashflowProviderTest.java'],
  'release-guide': ['Builder project', 'docs/r51-release-qualification.md'],
  'local-guide': ['Builder project', 'docs/local-workbench-v01.md'],
  ...Object.fromEntries(Object.entries({host:'LocalAgentHost',wiring:'ReadOnlyProviders',bank:'BankAccess',runtime:'NativeConversation'}).map(([key, name]) => [key, ['Builder project', `templates/local-readonly-service/src/main/java/io/github/bitlhk/finance/builder/local/${name}.java`]])),
};
const escape = s => String(s).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));

export function loadPublicPages(root, edition) {
  const pages = pageSpecs.map(([slug, title, label]) => {
    const source = `docs/public/${slug}.md`;
    const text = fs.readFileSync(path.join(root, source), 'utf8');
    if (!isSafePublicText(text)) throw new Error(`Unsafe public content: ${source}`);
    const headings = marked.lexer(text).filter(t => t.type === 'heading').map((h, i) => {
      let id = `section-${i}`;
      if (slug === 'first-agent' && h.depth === 2) {
        if (/^[1-7]\. /.test(h.text)) id = `step-${h.text[0]}`;
        if (h.text === '取得材料与环境') id = 'materials';
        if (h.text.startsWith('缺零件时')) id = 'roles';
      }
      return {id, depth:h.depth, text:h.text};
    });
    return {id:`public/${slug}.md`, source, title, path:label, repo:'edition', group:'开发指导', kind:'document', text, headings};
  });
  for (const page of pages) {
    let heading = 0;
    const renderer = new marked.Renderer();
    renderer.html = t => escape(t.text);
    renderer.heading = function(t) { return `<h${t.depth} id="${page.headings[heading++].id}">${this.parser.parseInline(t.tokens)}</h${t.depth}>`; };
    renderer.link = function(t) {
      const label = this.parser.parseInline(t.tokens);
      if (t.href === 'first-agent-cases.json') {
        const bytes = fs.readFileSync(path.join(root, 'docs/public/first-agent-cases.json'));
        return `<a href="data:application/json;base64,${bytes.toString('base64')}" download="first-agent-cases.json">${label}</a>`;
      }
      if (t.href.startsWith('source:')) {
        const ref = sourceReferences[t.href.slice(7)];
        if (!ref) throw new Error(`Unknown source: ${t.href}`);
        const sha = ref[0] === 'Harness' ? edition.harnessExecutionCommit : edition.builderCommit;
        return `<button type="button" class="unavailable" data-reference="${escape(`${ref[0]} @ ${sha}\n${ref[1]}`)}" aria-haspopup="dialog">${label}</button>`;
      }
      if (reviewedPublicLinks.has(t.href)) return `<a href="${escape(t.href)}" target="_blank" rel="noopener noreferrer">${label}</a>`;
      const [file, section = ''] = t.href.split('#');
      const target = pages.find(p => p.id === `public/${file}`);
      if (!target || (section && !target.headings.some(h => h.id === section))) throw new Error(`Broken public link: ${t.href}`);
      const prominent = /^(完成第一个 Agent|开始七步实践)$/.test(t.text);
      return `<a ${prominent ? 'class="guide-start" ' : ''}href="#doc=${encodeURIComponent(target.id)}${section ? `&amp;section=${encodeURIComponent(section)}` : ''}">${label}</a>`;
    };
    renderer.image = t => {
      const figure = renderPublicDiagram(edition.source, t.href, t.text) || renderPublicScreenshot(edition.source, t.href, t.text);
      if (!figure) throw new Error(`Unreviewed image: ${t.href}`);
      return figure;
    };
    renderer.paragraph = function(t) {
      if (t.tokens.length === 1 && t.tokens[0].type === 'image') return this.parser.parseInline(t.tokens);
      return marked.Renderer.prototype.paragraph.call(this, t);
    };
    renderer.table = function(t) { return `<div class="table-scroll" tabindex="0">${marked.Renderer.prototype.table.call(this,t)}</div>`; };
    page.html = marked.parse(page.text, {renderer, gfm:true});
  }
  return pages;
}
