"""Actual page build and read-only historical inspection; no mocked build API."""
import argparse
import hashlib
import json
from pathlib import Path
from urllib.parse import urlsplit
from playwright.sync_api import sync_playwright, expect


def verify(review, output, chrome, slow, interrupted, truncated):
    output.mkdir(parents=True,exist_ok=False);info=json.loads((review/'review.json').read_text());checks=[]
    def check(name,value):
        assert value,name;checks.append(name)
    with sync_playwright() as p:
        browser=p.chromium.launch(executable_path=str(chrome),headless=True)
        context=browser.new_context(viewport={'width':1440,'height':1000})
        page=context.new_page();page.goto((review/'private/login-url.txt').read_text().strip())
        page.locator('[data-module="agent.account"]').wait_for()
        check('runtime_catalog_015',page.locator('[data-runtime="openjiuwen-java-0.1.15"]').is_checked())
        page.locator('[data-runtime="openjiuwen-python"]').check()
        check('python_build_unavailable',page.locator('[data-action="build"]').is_disabled())
        page.locator('[data-runtime="openjiuwen-java-0.1.15"]').check()
        page.locator('[data-module="agent.wealth_aggregate"]').uncheck()
        page.locator('[data-module="agent.account"]').check()
        catalog=page.request.get(info['url']+'api/v1/catalog').json()
        model=next(m['modelRef'] for m in catalog['models'] if m['bindingStatus']=='CONFIGURED_UNTESTED')
        page.locator('[data-catalog="models"]').click()
        page.locator(f'[data-pick="{model}"]').check()
        page.locator(f'input[name="default-model"][value="{model}"]').check()
        page.locator('#catalog-save').click()
        with page.expect_response(lambda r:r.url.endswith('/api/v1/builds') and r.request.method=='POST') as built:
            page.locator('[data-action="build"]').click()
        response=built.value;check('actual_build_api_accepted',response.status in (200,201,202))
        data=response.json();build_id=data['buildId']
        expect(page.locator('#build-title')).to_have_text('构建完成',timeout=240000)
        check('build_page_uses_catalog_version','0.1.15' in page.locator('.assembly-node').first.inner_text())
        candidate=review/'workspace/workbench'/build_id/'candidate'
        lock=json.loads((candidate/'service-lock.json').read_text())
        check('actual_lock_015',lock['runtime']['templateRef'].endswith(':0.1.15'))
        check('balance_only',lock['capabilities']==['account.balance.query'])
        page.screenshot(path=str(output/'actual-balance-build.png'),full_page=True)
        outgoing=[];page.on('request',lambda r:outgoing.append((r.method,r.url)))
        page.locator('[data-nav="history"]').click()
        for label,identity in [('slow',slow),('interrupted',interrupted),('truncated',truncated)]:
            page.locator('input[name="requestId"]').fill(identity)
            page.locator('#history-filters button[type="submit"]').click()
            page.locator(f'[data-history-id="{identity}"]').click()
            page.locator('.history-detail').wait_for()
            if label=='truncated':
                page.locator('summary').filter(has_text='模型交互快照').click()
                panel=page.locator('details').filter(has=page.locator('summary').filter(has_text='模型交互快照'))
                panel.locator('[data-snapshot-call][title="TRUNCATED"]').first.click()
                page.locator('[data-snapshot-summary]').filter(has_text='正文大小限制').first.wait_for()
                check('actual_truncation_visible',True)
            check(label+'_history_visible',identity in page.locator('.history-detail').inner_text())
            page.screenshot(path=str(output/(label+'.png')),full_page=True)
        check('history_requests_do_not_invoke_agent',not any(method!='GET' or urlsplit(url).path.endswith(('/ag-ui','/chat')) for method,url in outgoing))
        browser.close()
    result={'checks':checks,'buildId':build_id,'serviceLockSha256':hashlib.sha256((candidate/'service-lock.json').read_bytes()).hexdigest(),
            'candidate':str(candidate),'historyRequestIds':{'slow':slow,'interrupted':interrupted,'truncated':truncated}}
    (output/'report.json').write_text(json.dumps(result,indent=2));print(json.dumps(result))


if __name__=='__main__':
    parser=argparse.ArgumentParser()
    for name in ('review','output','chrome'):parser.add_argument('--'+name,type=Path,required=True)
    for name in ('slow','interrupted','truncated'):parser.add_argument('--'+name,required=True)
    a=parser.parse_args();verify(a.review,a.output,a.chrome,a.slow,a.interrupted,a.truncated)
