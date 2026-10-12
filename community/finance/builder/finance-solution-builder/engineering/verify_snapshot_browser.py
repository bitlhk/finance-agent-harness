"""Isolated real workbench HTTP/browser; explicitly owner-remapped synthetic history fixture."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import socket
import threading
import time
from unittest.mock import patch
from playwright.sync_api import sync_playwright
from finance_solution_builder.workbench import serve


def verify(harness, source, output, chrome):
    output.mkdir(parents=True, exist_ok=False)
    workspace=output/'workspace'
    with socket.socket() as s: s.bind(('127.0.0.1',0)); port=s.getsockname()[1]
    def launch():
        # This test-only in-process server cannot install process signal handlers.
        with patch('finance_solution_builder.workbench.signal.signal'):
            serve(harness,port,data_dir=workspace)
    threading.Thread(target=launch,daemon=True).start()
    checks=[]
    def check(name, value):
        assert value, name
        checks.append(name)
    with sync_playwright() as p:
        browser=p.chromium.launch(executable_path=str(chrome),headless=True)
        context=browser.new_context(viewport={'width':1440,'height':1000});page=context.new_page()
        base=f'http://127.0.0.1:{port}'
        for attempt in range(30):
            try: page.goto(base);break
            except Exception:
                if attempt==29:raise
                time.sleep(.1)
        page.locator('[data-nav="history"]').wait_for()
        owner=next(c['value'].split('.')[0] for c in context.cookies() if c['name']=='fh_local')
        owner_ref=hashlib.sha256(owner.encode()).hexdigest()
        target=workspace/'execution-history'/owner_ref;shutil.copytree(source,target,dirs_exist_ok=True)
        for path in target.glob('*.jsonl'):
            rows=[json.loads(line) for line in path.read_text().splitlines()]
            rows[0]['ownerRef']=owner_ref
            for ref in rows:
                if ref.get('recordType')!='model-snapshot':continue
                bodypath=target/'model-snapshots'/f"{ref['requestId']}.{ref['callId']}.{ref['part']}.json"
                body=json.loads(bodypath.read_text());body['ownerRef']=owner_ref
                # Loss rendering is a labelled UI fixture, not a claim about this model call.
                body['state']='TRUNCATED'
                body['changes']=[{'reason':'COLLECTION_LIMIT','path':'/messages','originalCount':129,'retainedCount':128},
                                 {'reason':'REDACTED','path':'/messages/0/content'},
                                 {'reason':'DEPTH_LIMIT','path':'/tools/0/function/parameters/items'},
                                 {'reason':'BYTE_LIMIT','path':'','originalCount':60000,'retainedCount':0}]
                body['changesOmitted']=2
                # Synthetic malicious text is deliberately inserted in a copy, not original evidence.
                body['body'] += '<img src=x onerror="window.snapshotInjected=true">'
                raw=json.dumps(body).encode();bodypath.write_bytes(raw);ref['digest']=hashlib.sha256(raw).hexdigest()
            path.write_text('\n'.join(map(json.dumps,rows))+'\n')
        page.locator('[data-nav="history"]').click();page.locator('[data-history-id]').first.click()
        page.locator('summary').filter(has_text='模型交互快照').click()
        panel=page.locator('details').filter(has=page.locator('summary').filter(has_text='模型交互快照'))
        button=panel.locator('[data-snapshot-call]').first;button.wait_for()
        with page.expect_response(lambda r:'/snapshots/' in r.url) as denied:
            button.click()
        check('body_api_denies_trace_only_owner',denied.value.status==403)
        page.locator('[role="alert"]').wait_for()
        os.environ['FH_SNAPSHOT_VIEW_OWNERS']=owner_ref
        button.click();page.locator('pre').filter(has_text='CLIENT_REQUEST_PREPARED').last.wait_for()
        summary=panel.locator('[data-snapshot-summary]')
        check('loss_reasons_paths_counts_visible',all(text in summary.inner_text() for text in
              ('TRUNCATED','集合截断','/messages','原始 129，保留 128','已脱敏','深度限制','正文大小限制','另有 2 项')))
        check('body_is_text_not_html',page.evaluate('window.snapshotInjected !== true') and page.locator('.history-detail img').count()==0)
        check('desktop_no_overflow',page.evaluate('document.documentElement.scrollWidth<=innerWidth'))
        page.screenshot(path=str(output/'desktop.png'),full_page=True)
        page.set_viewport_size({'width':390,'height':844})
        check('mobile_no_overflow',page.evaluate('document.documentElement.scrollWidth<=innerWidth'))
        page.screenshot(path=str(output/'mobile.png'),full_page=True)
        other=browser.new_context();response=other.request.get(base+'/api/v1/execution-history')
        check('anonymous_history_denied',response.status==403)
        browser.close()
    lock_hashes=sorted({hashlib.sha256(p.read_bytes()).hexdigest() for p in source.glob('*.service-lock.json')})
    check('source_installation_locks_recorded',bool(lock_hashes))
    (output/'report.json').write_text(json.dumps({'mode':'OWNER_REMAPPED_SYNTHETIC_HISTORY_UI_WITH_INJECTED_LOSS_METADATA',
        'source':str(source),'sourceVersionLockHashes':lock_hashes,'checks':checks},indent=2))
    print(json.dumps({'checks':checks}))


if __name__=='__main__':
    parser=argparse.ArgumentParser()
    for name in ('harness','source','output','chrome'):parser.add_argument('--'+name,type=Path,required=True)
    args=parser.parse_args();verify(args.harness,args.source,args.output,args.chrome)
