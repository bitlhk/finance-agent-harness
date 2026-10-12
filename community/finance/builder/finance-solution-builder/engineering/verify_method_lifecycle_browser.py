"""Opt-in browser check against an isolated empty local workbench; no model/bank calls."""
import argparse
import json
from pathlib import Path
from urllib.parse import urlsplit

from playwright.sync_api import sync_playwright


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--chromium", required=True)
    args = parser.parse_args()
    address = urlsplit(args.url)
    if address.hostname not in {"localhost", "127.0.0.1"} or address.scheme != "http":
        parser.error("Only an isolated loopback workbench is supported")
    args.output.mkdir(parents=True, exist_ok=False)
    checks = []
    with sync_playwright() as pw:
        browser = pw.chromium.launch(executable_path=args.chromium, headless=True, args=["--no-sandbox"])
        try:
            for label, viewport in (("desktop", {"width":1440,"height":1000}),
                                    ("mobile", {"width":390,"height":844})):
                context = browser.new_context(viewport=viewport)
                page = context.new_page()
                errors = []
                page.on("pageerror", lambda error: errors.append(str(error)))
                page.on("dialog", lambda dialog: dialog.accept())
                page.goto(args.url)
                page.locator('[data-module-detail="agent.account"]').click()
                drawer = page.locator('#drawer')
                drawer.get_by_text('技术详情', exact=True).click()
                assert 'Capability Pack' in drawer.inner_text()
                assert 'Provider' in drawer.inner_text()
                assert '无独立 Domain Pack 声明' in drawer.inner_text()
                assert '待新制品 Lock 核验' in drawer.inner_text()
                page.screenshot(path=str(args.output / (label + '-component.png')), full_page=True)
                assert page.evaluate("document.documentElement.scrollWidth <= innerWidth + 1")
                drawer.get_by_role('button',name='关闭',exact=True).click()
                checks.append(label + ': catalog references and installation boundary visible')
                page.locator('[data-module-detail="agent.finance_assistant"]').click()
                assert drawer.locator('[data-module-capability]').count() == 1
                visible = drawer.inner_text()
                assert '收支分析组合' in visible and '收支分类与月度数据' in visible
                assert 'finance_transaction_summary' not in visible and 'cashflow:read' not in visible
                page.screenshot(path=str(args.output / (label + '-cashflow.png')), full_page=True)
                assert page.evaluate("document.documentElement.scrollWidth <= innerWidth + 1")
                drawer.get_by_text('技术详情', exact=True).click()
                assert 'finance_transaction_summary' in drawer.inner_text()
                assert 'cashflow:read' in drawer.inner_text()
                drawer.get_by_role('button',name='关闭',exact=True).click()
                checks.append(label + ': one business group with expandable technical declarations')
                page.get_by_role("button", name="业务资产", exact=True).first.click()
                page.locator('[data-method-action="create"]').wait_for()

                def count():
                    return page.evaluate("async()=> (await (await fetch('/api/v1/assets')).json()).assets.length")

                assert count() == 0, "Use a fresh private workbench, not existing customer assets"
                page.locator('[data-method-action="create"]').click()
                page.locator('[data-method-field="name"]').fill("Discarded synthetic method")
                assert count() == 0
                page.locator('[data-method-action="back"]').click()
                assert count() == 0
                checks.append(label + ": cancel new creates no record")

                page.locator('[data-method-action="copy-balance"]').click()
                page.locator('[data-method-field="name"]').wait_for()
                assert count() == 0
                page.locator('[data-method-field="name"]').fill("Lifecycle synthetic method")
                page.locator('[data-method-field="content"]').fill("# Verify facts\nCheck currency and source.")
                page.screenshot(path=str(args.output / (label + "-editor.png")), full_page=True)
                assert page.evaluate("document.documentElement.scrollWidth <= innerWidth + 1")
                page.locator('[data-method-action="save"]').click()
                page.locator('[data-method-action="archive"]').wait_for()
                assert count() == 1
                checks.append(label + ": example saved once after editing")

                page.locator('[data-method-action="archive"]').click()
                page.locator('[data-method-action="create"]').wait_for()
                assert page.locator('[data-method-action="open"]').count() == 0
                assert count() == 1
                page.locator('[data-method-archived]').check()
                page.locator('[data-method-action="open"]').click()
                page.locator('[data-method-action="restore"]').click()
                page.locator('[data-method-action="open"]').click()
                page.locator('[data-method-action="delete"]').click()
                page.locator('[data-method-action="create"]').wait_for()
                assert count() == 0
                checks.append(label + ": archive, restore and unreferenced draft deletion")

                page.locator('[data-method-import]').set_input_files({"name":"SKILL.md",
                    "mimeType":"text/markdown", "buffer":b"# Imported synthetic method\nCheck missing data."})
                page.locator('[data-method-field="content"]').wait_for()
                assert count() == 0
                page.locator('[data-method-field="content"]').fill("# Edited import\nNever replace missing with zero.")
                page.locator('[data-method-action="save"]').click()
                page.locator('[data-method-action="archive"]').wait_for()
                assert count() == 1
                page.reload()
                page.get_by_role("button", name="业务资产", exact=True).first.click()
                page.locator('[data-method-action="open"]').click()
                assert "Edited import" in page.locator('[data-method-field="content"]').input_value()
                page.screenshot(path=str(args.output / (label + "-import.png")), full_page=True)
                checks.append(label + ": edited import persists after reload")
                assert errors == [], errors
                checks.append(label + ": no browser errors")
                context.close()
        finally:
            browser.close()
    (args.output / "report.json").write_text(json.dumps({"mode":"ISOLATED_SYNTHETIC_UI", "checks":checks,
        "modelCalls":"NOT_RUN", "bankCalls":"NOT_RUN", "humanAcceptance":"NOT_RUN"}, indent=2) + "\n")
    print(json.dumps({"checksPassed":len(checks), "output":str(args.output)}))


if __name__ == "__main__":
    main()
