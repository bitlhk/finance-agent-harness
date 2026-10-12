import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import {
  buildAuditPrompt,
  validateBundle,
} from "../browser/smart-audit-bundle.js";

const root = new URL("../", import.meta.url);

async function demo(name) {
  return JSON.parse(await readFile(new URL(`examples/agent-experience/demo-materials/${name}`, root), "utf8"));
}

const personal = await demo("personal-loan-demo.json");
assert.equal(personal.schemaVersion, "smart-audit-demo-materials.v1");
assert.equal(personal.scenario, "personal-loan-audit");
assert.deepEqual(personal.materials.map(({ category }) => category), [
  "身份证", "户口本", "结婚证", "贷款申请表", "收入证明", "流水明细",
  "个人所得税完税证明", "社会保险参保证明", "不动产权证书", "不动产信息查询结果", "贷款结清证明",
]);
assert.deepEqual(validateBundle(personal, "personal-loan-audit"), { ok: true, missing: [] });

const missing = { ...personal, materials: personal.materials.slice(0, 3) };
assert.deepEqual(validateBundle(missing, "personal-loan-audit"), {
  ok: false,
  missing: ["贷款申请表", "收入证明", "流水明细", "个人所得税完税证明", "社会保险参保证明", "不动产权证书", "不动产信息查询结果", "贷款结清证明"],
});
assert.equal(validateBundle({ ...personal, scenario: "enterprise-credit-audit" }, "personal-loan-audit").ok, false);
assert.equal(buildAuditPrompt(personal).includes(personal.materials[0].content), true);
assert.match(buildAuditPrompt(personal), /四项已安装的个人贷款审核能力均须实际调用一次/);

const experienceSource = await readFile(new URL("browser/experience.js", root), "utf8");
assert.match(experienceSource, /finance\.subagents/);
assert.match(experienceSource, /已开启/);
assert.match(experienceSource, /data-subagent/);
assert.match(experienceSource, /audit-material-summary/);
assert.match(experienceSource, /audit-live-brief/);
assert.match(experienceSource, /材料数/);
assert.match(experienceSource, /规则数/);
assert.match(experienceSource, /耗时/);
assert.match(experienceSource, /证据/);
assert.match(experienceSource, /系统核验已完成/);
assert.match(experienceSource, /待人工复核交接/);
assert.match(experienceSource, /正在分派审核任务/);
assert.match(experienceSource, /const responseText = value =>/);
assert.match(experienceSource, /text: responseText\(value\.response\)/);
assert.match(experienceSource, /const queueDelta = delta =>/);
assert.match(experienceSource, /await deliveryComplete/);

const styleSource = await readFile(new URL("examples/agent-experience/style.css", root), "utf8");
assert.match(styleSource, /\.audit-three-pane/);
assert.match(styleSource, /300px minmax\(0, 1fr\) 360px/);

const enterprise = await demo("enterprise-credit-demo.json");
assert.equal(enterprise.scenario, "enterprise-credit-audit");
assert.equal(validateBundle(enterprise, "enterprise-credit-audit").ok, true);

console.log("smart-audit-experience fixtures: PASS");
