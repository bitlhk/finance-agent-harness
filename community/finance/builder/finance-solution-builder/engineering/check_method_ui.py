"""Explicit browser checks against an isolated local workbench; never production data."""
import argparse
import json
from pathlib import Path
from playwright.sync_api import sync_playwright


def verify(base, output, chromium):
    output.mkdir(parents=True, exist_ok=True)
    checks = []
    with sync_playwright() as p:
        browser = p.chromium.launch(executable_path=chromium, headless=True, args=["--no-sandbox"])
        page = browser.new_page(viewport={"width":1440,"height":1000})
        failures=[];page.on("pageerror",lambda e:failures.append(str(e)))
        page.goto(base)
        page.get_by_role("heading", name="创建金融 Agent").wait_for()
        page.screenshot(path=str(output/"desktop-create.png"),full_page=True)
        page.locator('[data-module-detail="agent.account"]').click()
        page.get_by_role("heading",name="可选业务能力").wait_for()
        page.locator('[data-module-capability="agent.account"]').uncheck()
        assert not page.locator('[data-module-capability="agent.account"]').is_checked()
        page.get_by_role("button",name="关闭",exact=True).click()
        assert not page.locator('[data-module="agent.account"]').is_checked()
        checks.append("module_and_capability_share_selection")
        page.get_by_role("button",name="业务资产",exact=True).first.click()
        page.get_by_role("button",name="复制财富默认方法",exact=True).click()
        page.locator('[data-method-field="name"]').wait_for()
        page.locator('[data-method-field="name"]').fill("UI资产核验")
        page.locator('[data-method-action="save"]').click()
        page.wait_for_function("document.querySelector('[data-method-action=save]')?.disabled === false")
        page.screenshot(path=str(output/"desktop-editor.png"),full_page=True)
        page.locator('[data-method-field="content"]').fill("# 尚未保存的新方法")
        published=[]
        page.on('request',lambda r:published.append(r.url) if r.url.endswith('/publish') else None)
        page.locator('[data-method-action="publish"]').evaluate("b=>{b.disabled=false;b.click()}")
        page.get_by_role("alert").filter(has_text="请先保存当前修改").wait_for()
        assert not published
        checks.append("publish_action_rejects_unsaved_even_if_dom_enabled")
        page.on('dialog',lambda d:d.dismiss())
        page.locator('[data-nav="create"]').first.click()
        assert page.locator('[data-method-field="content"]').input_value()=="# 尚未保存的新方法"
        checks.append("navigation_preserves_unsaved_on_cancel")
        page.set_viewport_size({"width":390,"height":844})
        page.screenshot(path=str(output/"mobile-editor.png"),full_page=True)
        assert page.evaluate("document.documentElement.scrollWidth<=window.innerWidth")
        checks.append("mobile_no_horizontal_overflow")
        assert not failures,failures
        browser.close()
    report={"checks":checks,"pageErrors":failures,"realModel":"NOT_RUN","production":"NOT_TOUCHED"}
    (output/'browser-checks.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
    print(json.dumps(report,ensure_ascii=False))


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--base',default='http://127.0.0.1:18178')
    parser.add_argument('--output',type=Path,required=True);parser.add_argument('--chromium',required=True)
    args=parser.parse_args();verify(args.base,args.output,args.chromium)
