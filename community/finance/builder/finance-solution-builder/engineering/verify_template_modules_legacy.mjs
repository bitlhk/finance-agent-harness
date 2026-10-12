import assert from 'node:assert/strict';
import fs from 'node:fs';
const {chromium}=await import(process.env.PLAYWRIGHT_MODULE || 'playwright');
const [base,output]=process.argv.slice(2);
const browser=await chromium.launch({headless:true});
const checks=[];
try {
  const context=await browser.newContext({viewport:{width:1440,height:1000},
    ...(process.env.STUDIO_TEST_USER ? {httpCredentials:{username:process.env.STUDIO_TEST_USER,password:process.env.STUDIO_TEST_PASSWORD}} : {})});
  const page=await context.newPage();
  await page.goto(base);
  await page.locator('[data-module="agent.account"]').waitFor();
  const before=await page.evaluate(()=>fetch('/api/v1/builds').then(r=>r.json()));
  const legacy=before.builds.filter(b=>b.selection.schema_version!=='0.5');
  assert.ok(legacy.length,'No legacy records available for this operator');
  for(const old of legacy) {
    await page.locator('[data-nav="agents"]').click();
    await page.locator(`[data-edit="${old.buildId}"]`).click();
    const ids=await page.locator('[data-module]:checked').evaluateAll(ns=>ns.map(n=>n.dataset.module));
    assert.deepEqual(ids.sort(),old.selection.capabilityIds.map(c=>c==='account.balance.query' ? 'agent.account':'agent.wealth_aggregate').sort());
    assert.ok(await page.locator('[data-runtime="openjiuwen-java-0.1.13"]').isChecked());
    checks.push({buildId:old.buildId,schema:old.selection.schema_version,edit:'RESTORED_AS_MODULES'});
  }
  const after=await page.evaluate(()=>fetch('/api/v1/builds').then(r=>r.json()));
  assert.deepEqual(after,before,'Editing UI modified existing records');
  fs.writeFileSync(output,JSON.stringify({checks,recordsUnchanged:true,newBuilds:0,oldArtifactStart:'NOT_RUN'},null,2));
} finally {await browser.close();}
