"""Explicit live-model/full synthetic-bank browser acceptance; never run by ordinary CI."""
import argparse
import json
from pathlib import Path
import time
from playwright.sync_api import sync_playwright


def verify(base, output, browser_path, auth_file=None, terminals=None):
    output.mkdir(parents=True, exist_ok=True)
    checks = []
    def save(name, value):
        (output / (name + ".json")).write_text(json.dumps(value, ensure_ascii=False, indent=2))
    def check(name, value):
        assert value, name
        checks.append(name); save("checks", checks); print(name, flush=True)
    with sync_playwright() as p:
        browser = p.chromium.launch(executable_path=browser_path, headless=True)
        options = {"viewport": {"width":1440,"height":1000}}
        if auth_file:
            auth = json.loads(auth_file.read_text())
            options["http_credentials"] = {k:auth[k] for k in ("username","password")}
        context = browser.new_context(**options)
        page = context.new_page(); page.goto(base)
        page.locator('[data-module="agent.finance_assistant"]').wait_for(timeout=60000)
        def api(path, body=None):
            value = page.evaluate('''async x=>{
                const boot=await(await fetch('/api/bootstrap')).json();
                const r=await fetch(x.path,x.body===null?{}:{method:'POST',headers:{'Content-Type':'application/json','X-CSRF-Token':boot.csrf},body:JSON.stringify(x.body)});
                return {status:r.status,data:await r.json()};
            }''', {"path":path,"body":body})
            assert value["status"] < 300, (path,value)
            return value["data"]
        catalog = api('/api/v1/catalog')
        check('directory_26_preserved', len(catalog['templateModuleDirectory']['modules']) == 26)
        cashflow = next(m for m in catalog['templateModuleDirectory']['modules'] if m['id']=='agent.finance_assistant')
        check('cashflow_module_available_pair', cashflow['status']=='PARTIAL' and set(cashflow['capabilityIds'])=={
            'finance.transaction-summary.query','finance.spending-analysis.query'})
        for name, terminal, combined in [('mobile','MOBILE_WEB',False),('desktop','DESKTOP_WEB',True),('atm','ATM_WEB',False)]:
            if terminals and terminal not in terminals: continue
            ep = None; bp = None; instance = None
            try:
                page.locator('.sidebar-nav [data-nav="create"]').click()
                page.locator('[data-template="'+terminal+'"]').click()
                page.locator('[data-module="agent.account"]').set_checked(combined)
                page.locator('[data-module="agent.wealth_aggregate"]').set_checked(combined)
                page.locator('[data-module="agent.finance_assistant"]').check()
                page.locator('#selected-method').select_option('')
                page.locator('[data-catalog="models"]').click()
                page.locator('[data-pick="model-ea-002"]').check()
                for item in page.locator('[data-pick]:checked').all():
                    if item.get_attribute('data-pick')!='model-ea-002': item.uncheck()
                page.locator('input[name="default-model"][value="model-ea-002"]').check()
                page.locator('#catalog-save').click()
                with page.expect_response(lambda r:r.url.endswith('/api/v1/builds') and r.request.method=='POST',timeout=60000) as response:
                    page.locator('[data-action="build"]').click()
                check(name+'_page_build_queued',response.value.status==202)
                built = response.value.json(); bp='/api/v1/builds/'+built['buildId']
                save(name+'-created',built)
                deadline=time.monotonic()+900
                while True:
                    built=api(bp)
                    if built['buildStatus']=='SUCCEEDED': break
                    assert built['buildStatus'] not in ('FAILED','INTERRUPTED'),built['buildStatus']
                    assert time.monotonic()<deadline,'BUILD_TIMEOUT'
                    time.sleep(2)
                save(name+'-build',built)
                started=api(bp+'/start',{'artifactId':built['artifact']['artifactId']})
                instance=started['instance']['instanceId']; save(name+'-started',started)
                ticket=api(bp+'/experience',{'instanceId':instance})
                ep=context.new_page()
                ep.add_init_script('''(()=>{window.acceptanceStreams=[];const original=window.fetch.bind(window);window.fetch=async(...args)=>{
                    const response=await original(...args);if(String(args[0]).endsWith('/api/ag-ui')){
                        const entry={status:response.status,text:null};window.acceptanceStreams.push(entry);
                        response.clone().text().then(text=>entry.text=text).catch(()=>entry.error='STREAM_READ_FAILED');}return response;};})();''')
                ep.goto(base+ticket['url'])
                ep.set_viewport_size({'width':390 if name=='mobile' else 1440,'height':1000})
                ep.locator('[data-action="start"]').click()
                ep.locator('#cashflow-period').wait_for(timeout=60000)
                check(name+'_month_not_prefilled', ep.locator('#cashflow-period').input_value()=='')
                ep.locator('[data-query="CASHFLOW_SUMMARY"]').click()
                check(name+'_missing_month_no_request', len(ep.evaluate('window.acceptanceStreams'))==0)
                ep.locator('#cashflow-period').fill('2026-08')
                observed = {}
                def select_cashflow_source(component):
                    if name != 'desktop': return
                    selector = '.results-panel [data-component="cashflow-'+component+'-v1"]'
                    if ep.locator(selector).count(): return
                    for index in range(ep.locator('[data-source]').count()):
                        ep.locator('[data-source]').nth(index).click()
                        if ep.locator(selector).count(): return
                    raise AssertionError('NO_RECORDED_CASHFLOW_SOURCE')
                for key, component in [('CASHFLOW_SUMMARY','summary'),('CASHFLOW_ANALYSIS','analysis')]:
                    before = len(ep.evaluate('window.acceptanceStreams'))
                    ep.locator('[data-query="'+key+'"]').click()
                    ep.wait_for_function("n => window.acceptanceStreams.length === n + 1 && window.acceptanceStreams.at(-1).text !== null", arg=before, timeout=180000)
                    events = [json.loads(line[6:]) for line in ep.evaluate('window.acceptanceStreams.at(-1).text').splitlines() if line.startswith('data: ')]
                    result = next(event['value'] for event in events if event.get('name') == 'finance.display')
                    for card in result['display']['cards']:
                        if card.get('type') == 'cashflow': observed[(card['section'],card['period'])] = card
                    check(name+'_'+component+'_new_turn_completed', result['status'] == 'COMPLETED' and bool(result['text']))
                    check(name+'_'+component+'_observed_source_fact', (component.upper(),'2026-08') in observed)
                    save(name+'-'+component+'-source-use', {'freshFactThisTurn':any(card.get('section') == component.upper() for card in result['display']['cards']),
                         'note':'A follow-up may explain facts read in the preceding turn; it is not counted as another source read.'})
                    select_cashflow_source(component)
                    ep.locator('[data-component="cashflow-'+component+'-v1"]').first.wait_for(timeout=180000)
                    check(name+'_'+component+'_real_result', ep.locator('[data-component="cashflow-'+component+'-v1"]').count()>0)
                    check(name+'_'+component+'_period_gap_present',ep.locator('[data-gap="PERIOD_COVERAGE_NOT_VERIFIED"]').count()>0)
                    ep.screenshot(path=str(output/(name+'-'+component+'.png')),full_page=True)
                if combined:
                    before = len(ep.evaluate('window.acceptanceStreams'))
                    ep.locator('[data-query="BALANCE"]').click()
                    ep.wait_for_function("n => window.acceptanceStreams.length === n + 1 && window.acceptanceStreams.at(-1).text !== null", arg=before, timeout=180000)
                    ep.locator('[data-component="balance-summary-v1"]').first.wait_for(timeout=180000)
                    check(name+'_combined_balance_result',ep.locator('[data-component="balance-summary-v1"]').count()>0)
                    check(name+'_balance_does_not_replay_cashflow',ep.locator('.results-panel [data-component^="cashflow-"]').count()==0)
                    select_cashflow_source('analysis')
                    check(name+'_selected_cashflow_retains_gap',ep.locator('.results-panel [data-gap="PERIOD_COVERAGE_NOT_VERIFIED"]').count()>0)
                streams=ep.evaluate('window.acceptanceStreams'); save(name+'-streams',streams)
                check(name+'_distinct_turns', len(streams) == (3 if combined else 2) and len({json.loads(line[6:])['runId'] for s in streams for line in s['text'].splitlines() if line.startswith('data: ') and json.loads(line[6:]).get('type') == 'RUN_STARTED'}) == len(streams))
                check(name+'_no_page_overflow',ep.evaluate('document.documentElement.scrollWidth<=innerWidth'))
                check(name+'_all_streams_finished',all('RUN_FINISHED' in s['text'] and 'RUN_ERROR' not in s['text'] for s in streams))
                ep.locator('[data-action="end"]').click()
                check(name+'_session_ended',ep.locator('[data-action="start"]').is_visible())
            except Exception as error:
                if ep:
                    save(name+'-failure-streams',ep.evaluate('window.acceptanceStreams || []'))
                    ep.screenshot(path=str(output/(name+'-failure.png')),full_page=True)
                    (output/(name+'-failure.html')).write_text(ep.content())
                save(name+'-failure',{'type':type(error).__name__,'message':str(error)})
                raise
            finally:
                if instance and bp: api(bp+'/stop',{'instanceId':instance})
                if ep: ep.close()
        browser.close()


if __name__=='__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('--base',required=True)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--browser',required=True)
    parser.add_argument('--auth-file',type=Path)
    parser.add_argument('--terminals',nargs='+',choices=['MOBILE_WEB','DESKTOP_WEB','ATM_WEB'])
    args=parser.parse_args()
    verify(args.base.rstrip('/'),args.output,args.browser,args.auth_file,args.terminals)
