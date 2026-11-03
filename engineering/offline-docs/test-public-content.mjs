import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { marked } from 'marked';
import { loadPublicPages } from './public-pages.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const source = fs.readFileSync(path.join(root, 'docs/public/developer-guide.md'), 'utf8');
const headings = marked.lexer(source).filter(token => token.type === 'heading' && token.depth === 2);

test('every public page renders with the publication link policy', () => {
  const edition = JSON.parse(fs.readFileSync(path.join(root, 'docs/public/edition.json'), 'utf8'));
  const pages = loadPublicPages(root, edition);
  assert.equal(pages.length, 3);
  assert.ok(pages.every(page => typeof page.html === 'string' && page.html.length > 0));
});

test('public guide has concrete paths from upload to a newly installed Agent', () => {
  assert.equal(headings.length, 17);
  for (const text of [
    '0. ������·����ʼ', '7. ·�� A', '14. �ϴ�һ�������',
    '15. ������ Agent ׼ȷʹ���������', '���������',
    '�����ܿؼ��', '��װ Lock', '�� Agent ���Զ�����',
    'Ŀ�깤�������µ���', '��֧�ֵ� Runtime',
  ]) assert.ok(source.includes(text), text);
  assert.ok(!source.includes('asset-check-tutorial-devkit'));
});

test('all repository paths used by the new walkthrough exist on this baseline', () => {
  for (const relative of [
    'domain-packs/wealth/shared-facts/skills/wealth-facts-description/SKILL.md',
    'engineering/business_package.py',
    'contracts/json-schema/package/finance-business-component-package.v0.1.schema.json',
    'routing/README.md',
    'routing/capability-descriptions/cashflow-readonly.v0.1.json',
    'routing/terminology/demo-bank-zh-cn.v0.1.json',
    'evaluation/capability-description-ab-v0.1/README.md',
    'domain-packs/cashflow/customer-self-service/skills/cashflow-review/SKILL.md',
    'capability-packs/cashflow-readonly/assembly.v0.1.json',
    'providers/non-ai-banking/src/main/java/io/github/bitlhk/finance/harness/providers/nonaibanking/NonAiBankingFactoryV01.java',
    'providers/non-ai-banking/src/main/resources/META-INF/services/io.github.bitlhk.finance.harness.core.spi.ManagedProviderFactoryV01',
    'providers/non-ai-banking/src/test/java/io/github/bitlhk/finance/harness/providers/nonaibanking/CashflowProviderTest.java',
    'providers/non-ai-banking/src/test/java/io/github/bitlhk/finance/harness/providers/nonaibanking/NonAiBankingFactoryTest.java',
  ]) assert.ok(fs.statSync(path.join(root, relative)).isFile(), relative);
});

test('development handoff distinguishes declarations, execution and new test evidence', () => {
  for (const text of ['16. �ӿ����ʲ�������һ��������', '��������������������',
    '��������������ͳһ������', '������Ŀ�����ȱʧ����ͨ��', 'ServiceLoader ���ֲ�����Ŀ¼׼��',
    '�в����������Ѷ������', 'MR186 ����ǰ�ô�ά���߽���']) assert.ok(source.includes(text), text);
});

test('current UI, source rules and support levels replace historical instructions', () => {
  for (const text of ['������������ļ����޸ģ����������', '���붨��λ��', '����ʾ����ÿ�� Skill',
    '����ͬһ�����ͬ', '��汾��ʶ�������Ǻ���������', 'schema_version: 0.9',
    '�Ѿ�����', 'ָ���汾��֤ͨ��', '��ǰ�������Թ���', 'FH_OBSERVATION_QUALIFICATION_FILE',
    '14.4 �Լ����һ���޸�', '��ȷ����ʵ', '��ȱ����']) assert.ok(source.includes(text), text);
  for (const stale of ['����23ģ��', '0.5������0.2', '���ֲ�����ʲ������ṩ��������ļ���']) assert.ok(!source.includes(stale), stale);
  const edition = JSON.parse(fs.readFileSync(path.join(root, 'docs/public/edition.json'), 'utf8'));
  assert.ok(source.includes(`���������� ${edition.version} �� �����׼���ѡ V0.1 �� ${edition.date}`));
  for (const file of ['docs/current-architecture.md', 'docs/INDEX.md']) assert.ok(fs.statSync(path.join(root, file)).isFile());
});

test('guide keeps package admission and runtime authorization separate', () => {
  for (const text of [
    '�ϴ��ǿ�ʼ�������Զ���Ȩ', 'ֻ��֤ Java ֻ��ִ��Ҫ��',
    'Provider ���������ܹܰ�װ', '����������������ͻ�Ȩ��',
    '��������ʱǿ����ɼ����', '���ܰѹ��� HTTP 200 �������������',
  ]) assert.ok(source.includes(text), text);
});
