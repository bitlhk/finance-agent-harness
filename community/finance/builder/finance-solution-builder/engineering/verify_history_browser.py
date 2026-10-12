"""Real workbench UI/API, imported synthetic historical metadata; no Agent execution."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
from playwright.sync_api import sync_playwright


def verify(base, workspace, source, output, chrome):
    output.mkdir(parents=True,exist_ok=False);checks=[];errors=[]
    def check(name,value):
        assert value,name
        checks.append(name)
    with sync_playwright() as p:
        browser=p.chromium.launch(executable_path=str(chrome),headless=True)
        context=browser.new_context(viewport={'width':1440,'height':1000})
        page=context.new_page();page.on('pageerror',lambda e:errors.append(str(e)))
        page.goto(base);page.locator('[data-nav="history"]').wait_for()
        check('history_icon_visible',page.locator('[data-nav="history"] svg').evaluate('(e)=>e.getBBox().width>0'))
        cookies=context.cookies();owner=next(c['value'].split('.')[0] for c in cookies if c['name']=='fh_local')
        owner_hash=hashlib.sha256(owner.encode()).hexdigest()
        directory=workspace/'execution-history'/owner_hash;directory.mkdir(parents=True,exist_ok=True)
        # Explicit synthetic replay for UI testing: only the test workspace owner header is remapped.
        for src in source.glob('*'):
            if src.is_file() and not src.name.startswith('.'):
                if src.suffix=='.jsonl':
                    rows=src.read_text().splitlines()
                    if not rows:continue
                    header=json.loads(rows[0]);header['ownerRef']=owner_hash
                    rows[0]=json.dumps(header);(directory/src.name).write_text('\n'.join(rows)+'\n')
                else:shutil.copyfile(src,directory/src.name)
        page.locator('[data-nav="history"]').click()
        page.locator('[data-history-id]').first.wait_for()
        page.locator('[data-history-id]').first.click();page.locator('.history-detail').wait_for()
        check('timeline_has_spans',page.locator('.history-water-row').count()>0)
        check('business_step_label',page.locator('.history-water-label strong').filter(has_text='处理本轮请求').count()>0)
        page.locator('.history-water-row').first.click()
        check('original_operation_available',page.locator('.history-step-evidence').first.inner_text().find('host / request')>=0)
        check('timeline_bar_has_actual_width',page.locator('.history-bar').first.bounding_box()['width']>100)
        check('desktop_no_page_overflow',page.evaluate('document.documentElement.scrollWidth<=innerWidth'))
        page.screenshot(path=str(output/'desktop.png'),full_page=True)
        page.locator('[data-history-back]').click()
        page.locator('#history-filters select[name="status"]').select_option('INTERRUPTED')
        page.locator('#history-filters button[type="submit"]').click()
        page.locator('.history-table tbody tr').filter(has_text='已中断').first.wait_for()
        page.locator('[data-history-id]').first.click()
        page.locator('.history-detail p').filter(has_text='终止时刻未记录').wait_for()
        check('interrupted_no_fabricated_end',True)
        page.screenshot(path=str(output/'interrupted.png'),full_page=True)
        page.set_viewport_size({'width':390,'height':844});page.screenshot(path=str(output/'mobile.png'),full_page=True)
        check('mobile_no_page_overflow',page.evaluate('document.documentElement.scrollWidth<=innerWidth'))
        other=browser.new_context();other_page=other.new_page();other_page.goto(base)
        other_page.locator('[data-nav="history"]').click()
        other_page.locator('.history-table').filter(has_text='暂无符合条件').wait_for()
        check('other_owner_empty',True)
        check('no_javascript_errors',not errors)
        browser.close()
    (output/'report.json').write_text(json.dumps({'checks':checks,'errors':errors,'mode':'SYNTHETIC_HISTORY_UI'},indent=2))


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--base',required=True);parser.add_argument('--workspace',type=Path,required=True)
    parser.add_argument('--source',type=Path,required=True);parser.add_argument('--output',type=Path,required=True);parser.add_argument('--chrome',type=Path,required=True)
    args=parser.parse_args();verify(args.base,args.workspace,args.source,args.output,args.chrome)
