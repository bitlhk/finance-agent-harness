"""Opt-in browser import/publication/build proof using a configured model and synthetic bank."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
from urllib.parse import unquote

from playwright.sync_api import sync_playwright
from verify_local_service import Endpoints, expiry


def verify(harness, output, chromium):
    output.mkdir(parents=True, exist_ok=False);output.chmod(0o700)
    project = Path(__file__).resolve().parents[1]
    bank = Endpoints();child = None;checks = [];builds = []
    def save(name, value):
        path = output / name;path.write_text(json.dumps(value, ensure_ascii=False, indent=2));path.chmod(0o600)
    def check(name, condition):
        checks.append({"name": name, "passed": bool(condition)});save("checks.json", checks);assert condition, name
    required = ["FH_MODEL_URL", "FH_MODEL_KEY", "FH_MODEL_NAME"]
    if not all(os.environ.get(k) for k in required):raise ValueError("LIVE_MODEL_CONFIGURATION_REQUIRED")
    save("catalog.json", {"schemaVersion":"finance-model-catalog.v1","source":{"kind":"ACCEPTANCE"},"models":[{
        "modelRef":"model-package-live","displayName":os.environ["FH_MODEL_NAME"],"provider":"OpenAI",
        "modelName":os.environ["FH_MODEL_NAME"],"eaCatalogId":"acceptance","eaConfigured":True}]})
    save("registry.json", {"schemaVersion":"finance-model-bindings.v1","bindings":[{"modelRef":"model-package-live",
        "revision":"v02","enabled":True,"protocol":"OPENAI_CHAT_COMPLETIONS","provider":"OpenAI",
        "modelName":os.environ["FH_MODEL_NAME"],"urlEnvironment":"FH_CONN_KIT_URL","keyEnvironment":"FH_CONN_KIT_KEY"}]})
    save("policy.json", {"schemaVersion":"local-simulator-access.v0.1","bankUrl":bank.base,
        "subjects":{bank.customer:{"enabled":True,"expiresAt":expiry(),"scopes":["wealth:facts:read","wealth:customer:read","account:read"]}}})
    env=dict(os.environ,FH_MODEL_CATALOG=str(output/'catalog.json'),FH_MODEL_REGISTRY=str(output/'registry.json'),
        FH_CONN_KIT_URL=os.environ['FH_MODEL_URL'],FH_CONN_KIT_KEY=os.environ['FH_MODEL_KEY'],FH_MODEL_MODE='LIVE',
        FH_BANK_URL=bank.base,FH_BANK_LOGIN='synthetic',FH_BANK_SECRET='synthetic',FH_ACCESS_POLICY=str(output/'policy.json'),
        PYTHONPATH=str(project/'src')+os.pathsep+str(project.parent/'base/src'))
    log=output/'workbench.log'
    try:
        with log.open('wb') as stream:
            child=subprocess.Popen([sys.executable,'-m','finance_solution_builder.workbench','--harness-repo',str(harness),
                '--port','0','--data-dir',str(output/'jobs')],cwd=project,env=env,stdout=stream,stderr=stream)
        deadline=time.monotonic()+45;base=None
        while time.monotonic()<deadline:
            match=re.search(r'http://127\.0\.0\.1:\d+',log.read_text())
            if match:base=match.group();break
            if child.poll() is not None:raise RuntimeError('WORKBENCH_START_FAILED')
            time.sleep(.1)
        check('isolated-workbench',bool(base))
        with sync_playwright() as pw:
            browser=pw.chromium.launch(executable_path=chromium,headless=True)
            try:
                page=browser.new_page(viewport={'width':1440,'height':1000},accept_downloads=True);errors=[]
                page.on('pageerror',lambda e:errors.append(str(e)))
                with page.expect_response(lambda r:r.url.endswith('/api/v1/catalog') and r.status==200):
                    page.goto(base)
                page.locator('[data-nav="assets"]').click();page.wait_for_load_state('networkidle')
                catalog=page.request.get(base+'/api/v1/catalog').json()['unifiedDirectory']
                component=next(c for c in catalog['components'] if c.get('methodCustomizationSupported') and c['capabilityIds']==['wealth.facts.describe'])
                page.locator('[data-method-action="business-detail"][data-component="'+component['id']+'"]').click()
                page.get_by_role('tab',name='检查与生成').click()
                with page.expect_response(lambda r:r.url.endswith('/api/v1/assets/customize-preview')) as imported:
                    page.locator('.package-checks [data-method-action="customize-business"]').click()
                save('import-preview.json', imported.value.json())
                check('detail-customize-preview', imported.value.status == 200)
                original_method=imported.value.json()['data']['content']
                page.locator('[data-method-field="name"]').fill('V0.2.1 财富信息核验')
                page.locator('[data-method-action="mode-edit"]').click()
                page.locator('[data-method-field="content"]').fill(original_method + '\n最终分为“已确认事实”和“尚缺资料”两段，保留来源与数据缺口。\n')
                page.locator('[data-method-action="save"]').click()
                page.locator('[data-method-action="archive"]').wait_for()
                saved_asset=page.request.get(base+'/api/v1/assets').json()['assets'][0]
                saved_value=page.request.get(base+'/api/v1/assets/'+saved_asset['assetId']).json()
                check('browser-save-retains-default-case-source',
                      saved_value.get('source',{}).get('caseProvenance',{}).get('kind')=='COMPONENT_DECLARATION')
                page.locator('[data-method-action="continue-check"]').click()
                if page.locator('[data-method-case][data-key="query"]').count()==0:
                    page.locator('[data-method-action="add-case"]').click()
                page.locator('[data-method-case="0"][data-key="name"]').fill('正常财富查询')
                page.locator('[data-method-case="0"][data-key="query"]').fill('请查询我的资产概览，列出风险测评和信息缺口。')
                page.locator('[data-method-action="save"]').click()
                page.locator('[data-method-action="validate"]').click()
                page.locator('[data-method-action="trial"]').click()
                deadline=time.monotonic()+1200
                while time.monotonic()<deadline:
                    if '当前草稿执行检查已通过' in page.locator('.method-step-status').inner_text():break
                    time.sleep(1)
                check('page-upload-save-trial', '当前草稿执行检查已通过' in page.locator('.method-step-status').inner_text())
                page.locator('[data-method-action="continue-publish"]').click()
                page.locator('[data-method-binding-confirm]').check()
                page.locator('[data-method-action="publish"]').click()
                page.locator('[data-method-action="package-contents"]').first.wait_for(timeout=30000)
                asset=page.request.get(base+'/api/v1/assets').json()['assets'][0]
                release=asset['versions'][0];save('release.json',release)
                check('immutable-version-published', release['businessPackageReady'])
                component_path=base+'/api/v1/assets/components/'+component['id']+'/'+catalog['revision']+'/contents'
                component_after=page.request.get(component_path).json();save('component-after-publication.json',component_after)
                support=component_after['developmentSupport']
                check('base-component-not-claimed-as-derived-pass',support['validation']['status']=='NOT_RUN')
                related=[r for r in support['derivedMethodVerifications'] if r['assetRef']['releaseDigest']==release['releaseDigest']]
                check('exact-owner-release-report',len(related)==1 and related[0]['assetRef']['assetId']==release['assetId'])
                check('actual-runtime-report-check',related[0]['validation']['status']=='RUNTIME_CHECK_PASSED'
                      and related[0]['actualRuntime']['family']=='openjiuwen'
                      and related[0]['actualRuntime']['language']=='java'
                      and related[0]['baseMatch']=='MATCHED')
                isolated=browser.new_context()
                try:
                    other=isolated.new_page();other.goto(base)
                    other.request.get(base+'/api/bootstrap')
                    other_contents=other.request.get(component_path).json()
                    check('cross-owner-no-release-report',not other_contents['developmentSupport']['derivedMethodVerifications'])
                finally:isolated.close()
                page.locator('[data-method-action="package-contents"]').first.click()
                page.locator('.package-studio-board').wait_for()
                page.screenshot(path=str(output/'published.png'),full_page=True)
                page.get_by_role('tab',name='检查与生成').click()
                check('single-published-action-area',page.locator('[data-method-action="export"]').count()==1)
                check('verification-summary-visible',page.locator('.package-verification').is_visible())
                with page.expect_download() as downloaded:page.locator('[data-method-action="export"]').click()
                downloaded.value.save_as(output/'business-package.zip')
                page.locator('[data-nav="agents"]').click()
                page.locator('[data-nav="assets"]').click()
                page.locator('[data-method-action="business-detail"][data-component="'+component['id']+'"]').click()
                page.get_by_role('tab',name='检查与生成').click()
                report_link=page.locator('[data-method-action="verification-version"][data-digest="'+release['releaseDigest']+'"]')
                report_link.locator('xpath=..').evaluate('node=>node.open=true')
                page.screenshot(path=str(output/'component-bound-report.png'),full_page=True)
                with page.expect_response(lambda r:unquote(r.url).endswith('/packages/'+release['version']+'/'+release['releaseDigest']+'/contents')) as bound_response:
                    report_link.click()
                page.locator('.package-studio-board').wait_for()
                check('bound-version-navigation',bound_response.value.status==200
                      and bound_response.value.json()['identity']['releaseDigest']==release['releaseDigest'])
                page.get_by_role('tab',name='检查与生成').click()
                for runtime in ['openjiuwen-java-0.1.15','openjiuwen-python-0.1.18']:
                    if builds:
                        page.locator('[data-nav="assets"]').click();page.locator('[data-method-action="private-methods"]').click()
                        page.locator('[data-method-action="open"]').first.click();page.locator('[data-method-action="package-contents"]').first.click()
                        page.get_by_role('tab',name='检查与生成').click()
                    page.locator('[data-method-action="choose"]').click()
                    page.locator(f'[data-runtime="{runtime}"]').check()
                    with page.expect_response(lambda r:r.url.endswith('/api/v1/builds') and r.request.method=='POST',timeout=30000) as response:
                        page.locator('[data-action="build"]').click()
                    accepted=response.value;check(runtime+'-build-accepted',accepted.status==202)
                    build_id=accepted.json()['buildId'];selection=accepted.request.post_data_json
                    check(runtime+'-exact-release',selection['methodRef']['releaseDigest']==release['releaseDigest'])
                    def get():return page.request.get(base+'/api/v1/builds/'+build_id).json()
                    deadline=time.monotonic()+1200
                    while time.monotonic()<deadline:
                        job=get()
                        if job['buildStatus'] not in ['QUEUED','RUNNING']:break
                        time.sleep(1)
                    save(runtime+'-build.json',job)
                    check(runtime+'-built',job['buildStatus']=='SUCCEEDED')
                    def post(action,body):
                        return page.evaluate('''async ({path,body})=>{const boot=await(await fetch('/api/bootstrap')).json();const r=await fetch(path,{method:'POST',headers:{'Content-Type':'application/json','X-CSRF-Token':boot.csrf},body:JSON.stringify(body)});return {status:r.status,body:await r.json()}}''',{'path':f'/api/v1/builds/{build_id}/{action}','body':body})
                    started=post('start',{'artifactId':job['artifact']['artifactId']});check(runtime+'-start',started['status']==200)
                    instance=started['body']['instance']['instanceId']
                    try:
                        ticket=post('experience',{'instanceId':instance});check(runtime+'-experience',ticket['status']==200)
                        chat=browser.new_page(viewport={'width':1440,'height':1000});chat.goto(base+ticket['body']['url'])
                        chat.locator('[data-action="start"]').click();chat.locator('#query').wait_for(timeout=60000)
                        before=len(bank.reads)
                        chat.locator('#query').fill('请查询我的资产概览，列出风险测评和信息缺口。')
                        chat.locator('#chat-form').evaluate('form=>form.requestSubmit()')
                        chat.locator('.assistant-message').first.wait_for(timeout=180000)
                        chat.locator('.run-progress').wait_for(state='detached',timeout=180000)
                        answer=chat.locator('.assistant-message').first.inner_text()
                        check(runtime+'-actual-bank-read',len(bank.reads)>before)
                        check(runtime+'-model-answer',bool(answer.strip()))
                        chat.screenshot(path=str(output/(runtime+'-chat.png')),full_page=True)
                        final=get();save(runtime+'-job.json',final)
                        check(runtime+'-installation-lock',final.get('installation',{}).get('status')=='RECORDED_FOR_ARTIFACT')
                        history_url=base+'/api/v1/execution-history?buildId='+build_id
                        deadline=time.monotonic()+10;records=[]
                        while time.monotonic()<deadline:
                            records=page.request.get(history_url).json()['records']
                            records=[r for r in records if str(r.get('requestType','')).startswith('CONVERSATION_TURN')]
                            if records and all(r['status']!='UNFINISHED' for r in records):break
                            time.sleep(.05)
                        check(runtime+'-conversation-history-present',len(records)==1)
                        before_history=len(bank.reads)
                        history_response=page.request.get(base+'/api/v1/execution-history/'+records[0]['requestId'])
                        detail=history_response.json();save(runtime+'-history.json',detail)
                        check(runtime+'-history-complete',history_response.status==200
                              and detail['status']=='COMPLETED' and detail['complete'])
                        check(runtime+'-history-installation-verified',all(detail['versions'].get(k,{}).get('verified')
                              for k in ('service','runtime')))
                        check(runtime+'-history-bank-send-evidence',any(s['component']=='provider-http'
                              and s['dispatch']=='SENT' for s in detail['spans']))
                        isolated=browser.new_context()
                        try:
                            other=isolated.new_page();other.goto(base)
                            other.request.get(base+'/api/bootstrap')
                            denied=other.request.get(base+'/api/v1/execution-history/'+records[0]['requestId'])
                            check(runtime+'-history-owner-isolated',denied.status==403)
                        finally:isolated.close()
                        check(runtime+'-history-read-zero-bank-calls',len(bank.reads)==before_history)
                        builds.append({'runtime':runtime,'buildId':build_id,'methodRef':selection['methodRef'],'answer':answer,
                            'bankReads':len(bank.reads)-before,'installation':final.get('installation'),
                            'requestId':detail['requestId'],'historyComplete':detail['complete']})
                        chat.close()
                    finally:check(runtime+'-stopped',post('stop',{'instanceId':instance})['status']==200)
                page.locator('[data-nav="assets"]').click();page.locator('[data-method-action="private-methods"]').click()
                page.locator('[data-method-action="open"]').first.click();page.locator('[data-method-action="package-contents"]').first.click()
                package_path='/api/v1/assets/'+release['assetId']+'/packages/'+release['version']+'/'+release['releaseDigest']
                original_zip=page.request.get(base+package_path).json()['base64']
                page.get_by_role('tab',name='检查与生成').click()
                page.locator('[data-method-action="fork-version"]').click()
                check('fork-requires-fresh-validation','未保存' in page.locator('.method-step-status').inner_text())
                page.locator('[data-method-action="mode-edit"]').click()
                page.locator('[data-method-field="content"]').fill('# 新草稿\n只修改工作方法，已发布版本不得改变。')
                with page.expect_response(lambda r:r.url.endswith('/api/v1/assets/package-import')) as forked:
                    page.locator('[data-method-action="save"]').click()
                draft=forked.value.json();save('forked-draft.json',draft)
                check('fork-created-new-asset',forked.value.status==201 and draft['assetId']!=release['assetId'])
                check('published-zip-unchanged',page.request.get(base+package_path).json()['base64']==original_zip)
                page.screenshot(path=str(output/'forked-draft.png'),full_page=True)
                check('zero-bank-writes',not bank.writes);check('no-browser-errors',not errors)
                save('report.json',{'status':'PASS','checks':checks,'builds':builds,'model':'CONFIGURED_REAL_MODEL',
                    'bank':'SYNTHETIC_HTTP','humanAcceptance':'NOT_RUN','professionalQuality':'REQUIRES_HUMAN_REVIEW',
                    'builderCommit':subprocess.check_output(['git','rev-parse','HEAD'],cwd=project,text=True).strip(),
                    'harnessCommit':subprocess.check_output(['git','rev-parse','HEAD'],cwd=harness,text=True).strip()})
            except Exception:
                if 'page' in locals():
                    page.screenshot(path=str(output/'failure.png'),full_page=True)
                    (output/'failure.txt').write_text(page.locator('body').inner_text())
                    save('browser-errors.json', errors)
                raise
            finally:browser.close()
    finally:
        if child and child.poll() is None:child.terminate();child.wait(timeout=30)
        bank.close()


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--harness',type=Path,required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--chromium',required=True)
    a=p.parse_args();verify(a.harness.resolve(),a.output.resolve(),a.chromium)
