"""Isolated synthetic browser acceptance. Does not access Linggan/EA production."""
import argparse
import json
import socket
import subprocess
import time
from pathlib import Path
from playwright.sync_api import sync_playwright

parser=argparse.ArgumentParser()
parser.add_argument('--output',required=True)
parser.add_argument('--chromium',required=True)
args=parser.parse_args()
module=Path(__file__).resolve().parents[1]
out=Path(args.output).resolve();out.mkdir(parents=True,exist_ok=False)
out.chmod(0o700)
with socket.socket() as s:
    s.bind(('127.0.0.1',0));port=s.getsockname()[1]
cp=str(module/'target/classes')+':'+(module/'target/demo-classpath.txt').read_text().strip()
log=(out/'host.log').open('w')
process=subprocess.Popen(['java','-cp',cp,'io.github.bitlhk.finance.harness.examples.wealth.ServicePreparationDemo',
    '--synthetic',str(port),str(out/'records')],cwd=module,stdout=log,stderr=subprocess.STDOUT)
report={'mode':'CONTROLLED_HTTP_SYNTHETIC_BROWSER','realCRM':False,'realModel':False,'checks':[],
        'businessWrites':0,'httpEvidence':[]}
def check(name,value):
    report['checks'].append({'name':name,'passed':bool(value)})
    assert value,name
try:
    import urllib.request
    deadline=time.monotonic()+25
    while True:
        if process.poll() is not None:raise RuntimeError('HOST_START_FAILED')
        try:
            with urllib.request.urlopen(f'http://127.0.0.1:{port}/',timeout=1) as r:
                if r.status==200:break
        except OSError:
            if time.monotonic()>deadline:raise
            time.sleep(.1)
    with sync_playwright() as p:
        browser=p.chromium.launch(executable_path=args.chromium,headless=True,args=['--no-sandbox'])
        page=browser.new_page(viewport={'width':1365,'height':1000})
        errors=[];page.on('pageerror',lambda e:errors.append(str(e)))
        def evidence(r):
            if '/api/' in r.url and not r.url.endswith('/bootstrap'):
                report['httpEvidence'].append({'action':r.url.rsplit('/',1)[-1],'status':r.status,'response':r.json()})
        page.on('response',evidence)
        page.goto(f'http://127.0.0.1:{port}/')
        page.locator('#customer option[value="合成客户甲"]').wait_for(state='attached')
        def probe(action,body,csrf=True):
            return page.evaluate('''async ({action,body,csrf})=>{const b=await(await fetch('/api/bootstrap')).json();
                const r=await fetch('/api/'+action,{method:'POST',headers:{'Content-Type':'application/json',...(csrf?{'X-CSRF-Token':b.csrf}:{})},body:JSON.stringify(body)});
                return {status:r.status,body:await r.json()}}''',{'action':action,'body':body,'csrf':csrf})
        check('csrf-required',probe('select',{'customer':'合成客户甲','goal':'回访'},False)['status']==403)
        check('unmapped-customer-denied',probe('select',{'customer':'未授权客户','goal':'回访'})['status']==403)
        check('owner-injection-rejected',probe('select',{'customer':'合成客户甲','goal':'回访','ownerRef':'admin'})['status']==409)
        check('mapping-failure-no-report',not list((out/'records').glob('*.json')))
        page.locator('#customer').select_option(label='合成客户甲')
        page.locator('#goal').fill('核实资金情况，准备持有期回访')
        with page.expect_response(lambda r:r.url.endswith('/api/prepare')) as response:
            page.locator('#prepare').click()
        first=response.value.json();check('query-http-success',response.value.status==200)
        page.locator('#facts .fact').first.wait_for()
        check('customer-and-goal',first['customerAlias']=='合成客户甲' and first['goal']=='核实资金情况，准备持有期回访')
        check('facts-and-missing-retained',first['facts']['customer']['totalAssets']==125000 and first['missingEvidence'])
        check('partial-not-false-complete',first['status']=='PARTIAL')
        check('receipt-and-business-date',bool(first['sourceReceipt']['receiptId']) and first['dataAsOf']=='2026-10-08T00:00:00Z')
        page.screenshot(path=str(out/'desktop-report.png'),full_page=True)
        page.locator('#opinion').fill('合成事实已核实；收支明细缺失需补充。未写入CRM。')
        with page.expect_response(lambda r:r.url.endswith('/api/review')) as saved:
            page.locator('#save').click()
        review=saved.value.json();check('review-author-and-version',review['reviewerRef']=='synthetic-manager' and review['reportRevision']==2)
        check('stale-review-version-rejected',probe('review',{'reportId':review['reportId'],'revision':1,'opinion':'旧版本重发'})['status']==409)
        check('review-does-not-upgrade-partial',review['status']=='PARTIAL')
        with page.expect_response(lambda r:r.url.endswith('/api/read')) as replay:
            page.locator('#replay').click()
        check('replay-same-run-and-evidence',replay.value.json()==review)
        check('only-one-query',sum(e['action']=='prepare' for e in report['httpEvidence'])==1)
        page.locator('#customer').select_option(label='合成客户乙')
        check('customer-switch-clears-old-report',page.locator('#report').is_hidden())
        with page.expect_response(lambda r:r.url.endswith('/api/prepare')) as second:
            page.locator('#prepare').click()
        second=second.value.json();check('new-customer-new-run',second['customerRef']=='syn-client-b' and second['facts']['customer']['totalAssets']==238000 and second['runId']!=first['runId'])
        page.set_viewport_size({'width':390,'height':844})
        page.screenshot(path=str(out/'mobile-report.png'),full_page=True)
        check('mobile-no-horizontal-overflow',page.evaluate('document.documentElement.scrollWidth<=innerWidth'))
        page.locator('#clear').click();check('clear-hides-current-facts',page.locator('#report').is_hidden())
        check('no-javascript-errors',not errors)
        browser.close()
    report['status']='PASS'
except BaseException as e:
    report['status']='FAIL';report['failureType']=type(e).__name__;raise
finally:
    process.terminate()
    try:process.wait(timeout=10)
    except subprocess.TimeoutExpired:process.kill();process.wait(timeout=5)
    log.close();report['testHostStopped']=process.poll() is not None
    (out/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
    print(json.dumps({'status':report['status'],'checks':len(report['checks']),'evidence':str(out)}))
