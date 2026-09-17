import test from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { diagramPaths, readDiagram, renderPublicDiagram } from './public-diagrams.mjs';

test('both diagrams have accessible descriptions and frozen source hashes', () => {
  for (const source of diagramPaths) {
    const diagram = readDiagram(source);
    assert.match(diagram.text, /<title id=/);
    assert.match(diagram.text, /<desc id=/);
    assert.match(diagram.sha256, /^[a-f0-9]{64}$/);
  }
});
test('unknown paths and external images are never loaded', () => {
  for (const reference of ['../../.git/config', '/etc/passwd', 'https://example.invalid/tracker.svg']) {
    assert.equal(renderPublicDiagram('docs/public/developer-guide.md', reference, 'example'), null);
    assert.throws(() => readDiagram(reference), /allowlist/);
  }
});
test('the internal and public guide share identical embedded images', () => {
  const publicHtml = renderPublicDiagram('docs/public/developer-guide.md', 'diagrams/architecture.svg', '<script>');
  const internalHtml = renderPublicDiagram('docs/developer-guide.md', 'public/diagrams/architecture.svg', '<script>');
  assert.equal(publicHtml, internalHtml);
  assert.match(publicHtml, /src="data:image\/svg\+xml;base64,/);
  assert.ok(!publicHtml.includes('<script>'));
});

test('the overview has outward boundary interfaces and no internal execution arrows', () => {
  const { text } = readDiagram(diagramPaths[0]);
  execFileSync(process.env.FH_DOCS_PYTHON || 'python3', ['-c', `
import sys, xml.etree.ElementTree as E
svg = E.fromstring(sys.stdin.read())
nodes = {e.get('id'): e for e in svg.iter() if e.get('id')}
assert len(nodes) == sum(bool(e.get('id')) for e in svg.iter()), 'Duplicate component identity'
suite = nodes['finance-harness']
inside = {e.get('id') for e in suite.iter()}
assert {'integrator', 'core', 'provider', 'assembly-support', 'asset-foundation-relation', 'key-assets'} <= inside
assert not {'builder', 'agent-environment', 'enterprise-systems'} & inside, 'External systems must stay outside suite'
assets = {e.get('id') for e in nodes['key-assets'].iter() if e.get('data-kind') == 'asset'}
assert assets == {'domain-pack', 'capability-pack', 'profile', 'domain-contract'}
assert nodes['integrator'].get('data-owner') == 'harness' and nodes['integrator'].get('data-deployment') == 'host'
assert nodes['core'].get('data-role') == 'shared-foundation'
edges = [(e.get('data-relation'), e.get('data-from'), e.get('data-to')) for e in svg.iter() if e.get('data-relation')]
assert set(edges) == {('interface', 'finance-harness', 'agent-environment'), ('interface', 'finance-harness', 'enterprise-systems'), ('assembly', 'builder', 'assembly-support')}
assert len(edges) == 3 and not any(e.get('data-relation') for e in suite.iter()), 'No internal execution edges'
marked = [e for e in svg.iter() if e.get('marker-end')]
assert len(marked) == 2 and all(e.get('data-relation') == 'interface' for e in marked), 'Only boundary interfaces have arrows'
assert not any(e.get('marker-start') or e.get('marker-mid') for e in svg.iter())
assert all(e.get('marker-end') == 'url(#interface-arrow)' for e in marked)
assert nodes['interface-arrow'].get('orient') == 'auto'
assert nodes['interface-arrow'].find('{http://www.w3.org/2000/svg}path') is not None
assert 'Contract' in [e.text for e in nodes['domain-contract'].iter()], 'Fourth content has an English component label'
area = lambda e: float(e.get('width')) * float(e.get('height'))
assert area(nodes['suite-boundary']) > area(svg) * .45
builder = nodes['builder-boundary']
assert area(nodes['suite-boundary']) > area(builder) * 3, 'Harness remains the dominant boundary'
assert float(builder.get('width')) < float(nodes['suite-boundary'].get('width')) * .22
boundary = nodes['suite-boundary']
rect = lambda key: nodes[key].find('{http://www.w3.org/2000/svg}rect')
assert nodes['builder'].get('data-scope') == 'cross-layer-build'
assert float(builder.get('x')) > float(boundary.get('x')) + float(boundary.get('width'))
assert builder.get('y') == rect('agent-environment').get('y')
assert float(builder.get('y')) + float(builder.get('height')) == float(rect('enterprise-systems').get('y')) + float(rect('enterprise-systems').get('height')), 'Builder spans Agent, Harness and enterprise connection configuration'
assert (rect('integrator').get('fill'), rect('integrator').get('stroke')) == (rect('provider').get('fill'), rect('provider').get('stroke')), 'North/south adapters share a palette'
assert rect('core').get('fill') != rect('integrator').get('fill'), 'Core has a distinct foundation color'
support = nodes['assembly-support']
assert support.get('data-scope') == 'suite'
assert {'installation-support', 'verification-support'} <= {e.get('id') for e in support.iter()}
installation, verification = rect('installation-support'), rect('verification-support')
assert installation.get('x') == verification.get('x') and installation.get('width') == verification.get('width'), 'Engineering support uses one vertical column'
assert float(installation.get('y')) + float(installation.get('height')) < float(verification.get('y'))
support_rect = nodes['support-boundary']
divider = nodes['support-divider']
divider_x = float(divider.get('x1'))
assert divider.get('x1') == divider.get('x2'), 'Vertical divider separates components and support'
assert support_rect.get('y') == rect('integrator').get('y')
assert float(support_rect.get('y')) + float(support_rect.get('height')) == float(rect('provider').get('y')) + float(rect('provider').get('height'))
for key in ('integrator', 'core', 'provider', 'domain-pack', 'capability-pack', 'profile', 'domain-contract'):
    assert float(rect(key).get('x')) + float(rect(key).get('width')) < divider_x < float(support_rect.get('x'))
assert float(support_rect.get('x')) + float(support_rect.get('width')) < float(boundary.get('x')) + float(boundary.get('width')), 'Support remains inside Harness'
sx, sy, sw, sh = [float(boundary.get(k)) for k in ('x', 'y', 'width', 'height')]
for destination, direction in [('agent-environment', -1), ('enterprise-systems', 1)]:
    edge = next(e for e in marked if e.get('data-to') == destination)
    points = [tuple(map(float, pair.split(','))) for pair in edge.get('points').split()]
    assert len(points) == 2 and points[0][0] == points[1][0], 'Interfaces are vertical boundary connections'
    start, end = points
    assert edge.get('data-from') == 'finance-harness'
    assert sx < start[0] < sx + sw and start[1] == (sy if direction < 0 else sy + sh), 'Arrows start on the suite boundary, not internal components'
    x, y, w, h = [float(rect(destination).get(k)) for k in ('x', 'y', 'width', 'height')]
    assert x < end[0] < x+w and end[1] == (y+h if direction < 0 else y)
    assert (end[1] - start[1]) * direction > 0, 'North points up; south points down'
    assert not sy < (start[1] + end[1]) / 2 < sy + sh, 'Interface stays outside suite interior'
`], { input: text, stdio: ['pipe', 'pipe', 'pipe'] });
  for (const phrase of ['四类可复用内容', '专业工作方法', '业务能力与工具', '使用角色与范围', '统一业务数据定义', 'required ≠ granted', '标签不授予权限', '补充通用请求／结果外壳', 'Core｜公共合同与执行基础', 'Harness 提供参考实现，支持客户扩展', '选择方法与能力包', '引用适用 Profile', '校验合同与版本', '记录安装 Lock', '构建记录', '套件工程支撑', '覆盖左侧组件与内容', '装配与验证集成', '生成 Host 初始化时', '跨层构建与配置', '不生成或接管企业系统', '南向企业适配', '企业 IT', '不表示执行顺序', '不参与每次金融工具调用']) assert.ok(text.includes(phrase), phrase);
  for (const phrase of ['稳定的公共基础', '本图只画一份组件', '不是四个执行引擎', '不把所有领域语义塞入 Core', 'Profile / 合同']) assert.ok(!text.includes(phrase), phrase);
});

test('the call diagram has explicit wrapped invocation and reverse result paths', () => {
  const { text } = readDiagram(diagramPaths[1]);
  const nodes = ['tool', 'bridge', 'bound-provider', 'adapter', 'source-bank'];
  for (let i = 0; i < nodes.length - 1; i++) {
    assert.ok(text.includes(`data-relation="call" data-from="${nodes[i]}" data-to="${nodes[i+1]}"`));
    assert.ok(text.includes(`data-relation="return" data-from="${nodes[i+1]}" data-to="${nodes[i]}"`));
  }
  assert.match(text, /内容检测通过 ≠ 业务授权/);
  assert.match(text, /当前 Grant Supplier/);
  assert.match(text, /不是 Core 调度引擎/);
});
