import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { marked } from 'marked';
import { loadPublicPages, sourceReferences } from './public-pages.mjs';
const root = path.resolve(new URL('../..', import.meta.url).pathname);
const edition = JSON.parse(fs.readFileSync(path.join(root,'docs/public/edition.json')));
const pages = loadPublicPages(root,edition);
test('three public documents with validated internal links and seven task steps', () => {
  assert.equal(pages.length,3);
  assert.deepEqual(pages[1].headings.filter(h=>h.id.startsWith('step-')).map(h=>h.id),Array.from({length:7},(_,i)=>`step-${i+1}`));
  assert.match(pages[0].html,/class="guide-start"/);
  assert.ok(pages.slice(0,2).every(p=>!p.html.includes('**')), 'new pages must render emphasis, not literal markdown');
});
test('all old reference headings retain their IDs and text', () => {
  const old = execFileSync('git',['show',`${edition.harnessExecutionCommit}:docs/public/developer-guide.md`],{cwd:root,encoding:'utf8'});
  const headings = marked.lexer(old).filter(t=>t.type==='heading').map((h,i)=>({id:`section-${i}`,depth:h.depth,text:h.text}));
  assert.deepEqual(pages[2].headings,headings);
});
test('fixed source references resolve without embedding private contents', () => {
  for (const [repo,file] of Object.values(sourceReferences)) {
    const base = repo === 'Harness' ? root : process.env.BUILDER_ROOT;
    assert.ok(base, 'BUILDER_ROOT is required to check real Host source references');
    assert.ok(fs.statSync(path.join(base,file)).isFile(),file);
  }
  assert.match(pages[1].html,/data-reference=/);
  assert.equal(pages[1].html.includes('github.com'),false);
});
test('tutorial distinguishes fixed binding, acquisition and actual security scope', () => {
  for (const phrase of ['CASHFLOW_COMPATIBILITY_INVALID','NEED_USER','UNKNOWN_REQUIRES_REVIEW','查询授权','历史查看权','不是生产银行默认','未参与开发者的独立接入尚待验收','未发布对照']) assert.ok(pages[1].text.includes(phrase),phrase);
});
test('downloadable cases are unexecuted manual records, not invented Eval imports', () => {
  const cases = JSON.parse(fs.readFileSync(path.join(root,'docs/public/first-agent-cases.json')));
  assert.equal(cases.recordType,'MANUAL_ACCEPTANCE_RECORD');
  assert.equal(cases.harnessCommit,edition.harnessExecutionCommit);
  assert.equal(cases.builderCommit,edition.builderCommit);
  assert.equal(cases.cases.length,3);
  assert.ok(cases.cases.every(c=>c.status==='NOT_RUN' && c.observed===null));
});
