"""Portable artifact + HTTP timing + browser checks; explicit synthetic model/source only."""
import argparse
import hashlib
from concurrent.futures import ThreadPoolExecutor
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import threading
import time
import urllib.request
import urllib.error
from verify_local_service import Endpoints, expiry


def verify(candidate, output, browser=True):
    candidate=candidate.resolve(); output.mkdir(parents=True,exist_ok=True)
    fixture=Endpoints(); checks=[]
    def check(name, value):
        if not value: raise AssertionError(name)
        checks.append(name)
    lock = json.loads((candidate / 'service-lock.json').read_text())
    for path, digest in lock['interface']['resources'].items():
        check('locked_' + path, hashlib.sha256((candidate / path).read_bytes()).hexdigest() == digest)
    policy=candidate/"terminal-test-access.json"
    def grant(enabled=True, scopes=None):
        policy.write_text(json.dumps({"schemaVersion":"local-simulator-access.v0.1","bankUrl":fixture.base,
            "subjects":{fixture.customer:{"enabled":enabled,"expiresAt":expiry(),
            "scopes":scopes if scopes is not None else ["wealth:facts:read","wealth:customer:read","account:read"]}}}))
        policy.chmod(0o600)
    grant()
    env=dict(os.environ,FH_BANK_URL=fixture.base,FH_BANK_LOGIN="demo",FH_BANK_SECRET="demo123",FH_ACCESS_POLICY=str(policy),
        FH_MODEL_URL=fixture.base+"/v1",FH_MODEL_KEY="controlled-not-key",FH_MODEL_NAME="explicit-boundary-test",FH_MODEL_MODE="CONTROLLED_TEST")
    info=candidate/"ui-instance.json"; info.unlink(missing_ok=True)
    log=(output/"portable.log").open("wb")
    child=subprocess.Popen([sys.executable,str(candidate/"run_ui.py")],cwd=output,env=env,stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
    def request(action, body=None, ticket=None):
        req=urllib.request.Request(base+"api/"+action,data=json.dumps(body or {}).encode(),
            headers={"Content-Type":"application/json","X-Agent-Token":token if ticket is None else ticket})
        try: res=urllib.request.urlopen(req,timeout=60)
        except urllib.error.HTTPError as error: res=error
        with res: return res.code,json.load(res)
    try:
        limit=time.monotonic()+45
        while not info.exists():
            if child.poll() is not None or time.monotonic()>limit: raise AssertionError("PORTABLE_START_FAILED")
            time.sleep(.1)
        url=json.loads(info.read_text())["url"]; base,token=url.split("#")
        code,configuration=request("config"); check("portable_frozen_config",code==200 and configuration["configuration"]["terminal"] in {"MOBILE_WEB","DESKTOP_WEB","ATM_WEB"})
        check("ticket_required",request("config",ticket="wrong")[0]==403)
        check("no_management_route",request("stop")[0]!=200)
        check("no_identity_override",request("start",{"principalId":"other"})[0]!=200 and len(fixture.reads)==0)
        check("session_started",request("start")[0]==200)
        balance="account.balance.query" in configuration["capabilities"]
        action_label = '查询余额' if balance else '查看资产'
        code,result=request("chat",{"query":"balance-probe" if balance else "wealth-probe","runId":"first"})
        check("actual_display",code==200 and result["display"]["schemaVersion"]=="finance-display.v0.1")
        check("display_no_internal_identity",fixture.customer not in json.dumps(result["display"]))
        if balance: check("exact_balance", "12345.67" in json.dumps(result["display"]))
        check("snapshot_history",len(request("snapshot")[1]["messages"])==2)
        check("end_clears_session",request("end")[0]==200 and request("snapshot")[0]==403)
        request("start")
        check("new_session_empty",request("snapshot")[1]["messages"]==[])
        if balance:
            ready,release=threading.Event(),threading.Event(); fixture.pause_source=(ready,release)
            with ThreadPoolExecutor() as pool:
                pending=pool.submit(request,"chat",{"query":"balance-probe","runId":"cancel-source"})
                check("source_inflight",ready.wait(10)); before=len(fixture.models)
                check("cancel_acknowledged",request("cancel",{"runId":"cancel-source"})[1]["status"]=="CANCEL_REQUESTED")
                release.set(); code,result=pending.result(30)
                check("cancel_stops_next_model",code==409 and result["error"]["code"]=="RUN_CANCELLED" and len(fixture.models)==before)
            fixture.pause_source=None
            check("cancel_clears_history_keeps_session",request("snapshot")[1]["messages"]==[])
            check("new_turn_after_cancel",request("chat",{"query":"balance-probe","runId":"after-cancel"})[0]==200)
            ready,release=threading.Event(),threading.Event(); fixture.pause_source=(ready,release)
            with ThreadPoolExecutor() as pool:
                pending=pool.submit(request,"chat",{"query":"balance-probe","runId":"revoke-source"})
                check("revocation_source_inflight",ready.wait(10)); before=len(fixture.models); grant(False); release.set()
                code,result=pending.result(30)
                check("revocation_stops_next_model",code==403 and len(fixture.models)==before)
            fixture.pause_source=None
            check("revocation_clears_snapshot",request("snapshot")[0]==403)
        grant(); request("reset")
        if browser:
            fixture.final_text = '# 受控查询结果\n\n' + ('这是用于验证滚动和排版的合成解释，银行事实请核对结果与来源。\n\n' * 32)
            from playwright.sync_api import sync_playwright
            with sync_playwright() as p:
                executable=os.environ.get("PLAYWRIGHT_CHROMIUM") or next(Path.home().glob(".cache/ms-playwright/chromium-*/chrome-linux64/chrome"), None)
                b=p.chromium.launch(executable_path=str(executable) if executable else None,headless=True,args=["--no-sandbox"])
                context=b.new_context(viewport={"width":390 if configuration['configuration']['terminal']=='DESKTOP_WEB' else 1440,"height":1000})
                page=context.new_page(); errors=[]; page.on("pageerror",lambda error:errors.append(str(error)))
                page.goto(url); page.get_by_role("button",name="开始服务").click()
                page.get_by_role("button",name="查询余额" if balance else "查看资产",exact=True).wait_for()
                if configuration['configuration']['terminal']=='DESKTOP_WEB':
                    check('small_desktop_initial_panel_closed',not page.locator('.results-panel').is_visible())
                page.wait_for_function("() => [...document.querySelectorAll('svg.icon')].filter(n=>n.getBoundingClientRect().width>0).every(n=>n.getBBox().width>0)")
                page.screenshot(path=str(output/'service-home.png'),full_page=True)
                page.get_by_role("button",name="查询余额" if balance else "查看资产",exact=True).click()
                page.locator(".assistant-message").wait_for(timeout=45000)
                terminal=configuration["configuration"]["terminal"]
                sizes=[(320,760),(390,844),(1366,768),(1920,1080),(1440,900)]
                for width,height in sizes:
                    page.set_viewport_size({"width":width,"height":height})
                    page.wait_for_function('() => Math.abs(parseFloat(document.documentElement.style.getPropertyValue("--visible-height"))-innerHeight)<2')
                    check(f"layout_no_overflow_{width}",page.evaluate("document.documentElement.scrollWidth <= window.innerWidth"))
                    check(f"surface_visible_{width}", page.locator('#terminal-shell').evaluate('n => {const r=n.getBoundingClientRect();return r.x>=0 && r.y>=0 && r.right<=innerWidth+1 && r.bottom<=innerHeight+1;}'))
                    check(f"messages_scroll_{width}", page.locator('.messages').evaluate('n => n.clientHeight>30 && n.scrollHeight>n.clientHeight && getComputedStyle(n).overflowY==="auto"'))
                    if terminal == 'MOBILE_WEB':
                        check(f'phone_frame_{width}', page.locator('#terminal-shell').evaluate('n=>parseFloat(getComputedStyle(n).borderTopWidth)') == (0 if width<=600 else 2))
                        if width<=600:
                            check(f'mobile_fills_width_{width}',page.locator('#experience').evaluate('n=>Math.abs(n.getBoundingClientRect().width-innerWidth)<2'))
                        page.locator('#query').focus()
                        page.locator('#query').fill('未发送的问题')
                        check(f'composer_visible_{width}', page.locator('#query').evaluate('n=>{const r=n.getBoundingClientRect();return r.y>=0 && r.bottom<=innerHeight;}'))
                    elif terminal == 'DESKTOP_WEB':
                        check(f'desktop_fills_window_{width}',page.locator('#experience').evaluate('n=>n.clientWidth===innerWidth && n.clientHeight===innerHeight'))
                        if page.locator('.results-panel').is_visible():
                            page.get_by_role('button',name='折叠面板').click()
                        page.locator('.source-button').last.click()
                        check(f'reopen_sources_{width}',page.locator('.results-panel').is_visible() and page.locator('.results-panel .fact-card').count()>0 and (not balance or '12,345.67' in page.locator('.results-panel').inner_text()))
                        if width<=1000:
                            page.get_by_role('button',name='折叠面板').click()
                            check(f'panel_focus_return_{width}',page.locator('.source-button').last.evaluate('n=>n===document.activeElement'))
                    else:
                        check(f'atm_touch_{width}',page.locator('.atm-footer button').evaluate_all('ns=>ns.every(n=>n.getBoundingClientRect().height>=56)'))
                        check(f'atm_frame_{width}', page.locator('#terminal-shell').evaluate('n=>parseFloat(getComputedStyle(n).borderTopWidth)') == 2)
                    page.wait_for_function("() => [...document.querySelectorAll('svg.icon')].filter(n=>n.getBoundingClientRect().width>0).every(n=>n.getBBox().width>0)")
                    page.locator('.messages').evaluate('n=>n.scrollTop=n.scrollHeight')
                    page.screenshot(path=str(output/f"{terminal}-{width}.png"),full_page=True)
                if terminal == 'MOBILE_WEB':
                    page.set_viewport_size({'width':390,'height':430})
                    page.wait_for_function('() => Math.abs(parseFloat(document.documentElement.style.getPropertyValue("--visible-height"))-innerHeight)<2')
                    page.locator('#query').focus()
                    check('keyboard_reduced_viewport_input',page.locator('#query').input_value()=='未发送的问题' and page.locator('#query').evaluate('n=>n.getBoundingClientRect().bottom<=innerHeight'))
                    page.screenshot(path=str(output/'keyboard-reduced-viewport.png'))
                    page.set_viewport_size({'width':390,'height':844})
                else:
                    page.get_by_role('button',name='进入全屏',exact=True).click()
                    page.wait_for_function('() => Boolean(document.fullscreenElement)')
                    check('fullscreen_entered',page.get_by_role('button',name='退出全屏',exact=True).get_attribute('aria-pressed')=='true')
                    before = len(fixture.reads)
                    page.get_by_role('button',name=action_label,exact=True).click()
                    page.locator('.assistant-message').nth(1).wait_for(timeout=45000)
                    check('fullscreen_real_query',len(fixture.reads)>before)
                    page.get_by_role('button',name='退出全屏',exact=True).click()
                    page.wait_for_function('() => !document.fullscreenElement')
                    check('fullscreen_exited',True)
                if terminal == 'ATM_WEB':
                    page.get_by_role('button',name='首页',exact=True).click()
                    page.locator('.atm-home').wait_for()
                    check('home_hides_previous_results',not page.locator('.messages').is_visible())
                    page.get_by_role('button',name=action_label,exact=True).click()
                    page.locator('.assistant-message').nth(2).wait_for(timeout=45000)
                    check('home_real_query',not page.locator('.atm-home').count())
                end_label = '结束服务' if terminal == 'ATM_WEB' else '结束会话'
                page.get_by_role('button',name=end_label,exact=True).click()
                page.get_by_role('button',name='开始服务').wait_for()
                check('ui_end_clears_data',not page.locator('.assistant-message,.user-message,.fact-card').count())
                check('backend_end_clears_data',request('snapshot')[0]==403)
                page.get_by_role('button',name='开始服务').click()
                page.get_by_role('button',name=action_label,exact=True).wait_for()
                check('ui_restart_is_empty',not page.locator('.assistant-message,.user-message,.fact-card').count() and request('snapshot')[1]['messages']==[])
                fixture.final_text = '## 查询完成\n\n已查询本次授权范围内的账户信息。金额、币种和数据来源见事实卡片。\n\n这是一条受控模型样例，不涉及转账或其他办理。'
                page.get_by_role('button',name=action_label,exact=True).click()
                page.locator('.assistant-message').wait_for(timeout=45000)
                page.set_viewport_size({'width':390 if terminal=='MOBILE_WEB' else 1440,'height':844 if terminal=='MOBILE_WEB' else 900})
                if terminal=='DESKTOP_WEB': page.locator('.source-button').last.click()
                page.wait_for_function("() => [...document.querySelectorAll('svg.icon')].filter(n=>n.getBoundingClientRect().width>0).every(n=>n.getBBox().width>0)")
                page.locator('.messages').evaluate('n=>n.scrollTop=n.scrollHeight')
                page.screenshot(path=str(output/'normal-facts.png'),full_page=True)
                check("browser_no_script_error",not errors)
                check("no_admin_controls",page.get_by_role("button",name="停止实例").count()==0)
                if terminal=="ATM_WEB":
                    page.reload(); page.get_by_role("button",name="开始服务").wait_for()
                    check("atm_refresh_clears",page.locator(".assistant-message").count()==0)
                else:
                    page.reload(); page.locator(".assistant-message").wait_for()
                    check("authorized_refresh_restores",page.locator(".assistant-message").count()==1)
                    page.get_by_role("button",name="结束会话").click()
                    page.get_by_role("button",name="开始服务").wait_for()
                b.close()
        check("no_business_write",not fixture.writes)
        report={"status":"PASS","model":"CONTROLLED_TEST","source":"CONTROLLED_HTTP","checks":checks,
            "sourceReads":len(fixture.reads),"modelCalls":len(fixture.models),"configuration":configuration["configuration"],
            "artifactLockSha256":configuration["artifactLockSha256"],"humanPhone":"NOT_RUN","realASR":"SEPARATE"}
        (output/"report.json").write_text(json.dumps(report,ensure_ascii=False,indent=2))
        print(json.dumps({"status":"PASS","checks":len(checks),"terminal":configuration["configuration"]["terminal"]}),flush=True)
    finally:
        if child.poll() is None:
            os.killpg(child.pid,signal.SIGINT)
            try: child.wait(timeout=20)
            except subprocess.TimeoutExpired: os.killpg(child.pid,signal.SIGKILL); child.wait()
        fixture.close(); log.close()


if __name__=="__main__":
    parser=argparse.ArgumentParser(); parser.add_argument("candidate",type=Path);parser.add_argument("output",type=Path)
    args=parser.parse_args(); verify(args.candidate,args.output)
