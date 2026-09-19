import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { marked } from 'marked';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const source = fs.readFileSync(path.join(root, 'docs/public/developer-guide.md'), 'utf8');
const headings = marked.lexer(source).filter(token => token.type === 'heading' && token.depth === 2);

test('public guide has concrete paths from upload to a newly installed Agent', () => {
  assert.equal(headings.length, 17);
  for (const text of [
    '0. 从哪条路径开始', '7. 路径 A', '14. 上传一个组件包',
    '15. 怎样让 Agent 准确使用你的能力', '导入组件包',
    '运行受控检查', '安装 Lock', '旧 Agent 不自动升级',
    '目标工作区重新导入', '不支持的 Runtime',
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
  for (const text of ['16. 从看懂资产到开发一个新特性', '不是完整正文依赖分析',
    '后续金融新特性统一交付卡', '不允许目标测试缺失被当通过', 'ServiceLoader 发现不等于目录准入',
    '尚不代表真人已独立完成', 'Builder PR #179 等待主线合入']) assert.ok(source.includes(text), text);
});

test('guide keeps package admission and runtime authorization separate', () => {
  for (const text of [
    '上传是开始，不是自动授权', '只验证 Java 只读执行要求',
    'Provider 代码仍需受管安装', '能力声明不会授予客户权限',
    '不是运行时强制完成检查器', '不能把工具 HTTP 200 当整项任务完成',
  ]) assert.ok(source.includes(text), text);
});
