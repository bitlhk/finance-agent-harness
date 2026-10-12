"""Check the real Builder preview; no builds, models or bank calls are made."""

import argparse
import hashlib
import json
import os
from pathlib import Path
from urllib.parse import urlparse

from playwright.sync_api import sync_playwright


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("base")
    parser.add_argument("output", type=Path)
    parser.add_argument("--chromium", help="Explicit locally installed Chromium executable")
    parser.add_argument("--widths", type=int, nargs="+", default=[1440, 1024, 736, 390, 320])
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    checks, errors, requests = [], [], []
    passed = False

    def check(name, condition):
        assert condition, name
        checks.append(name)

    def icons_ready():
        # External SVG references can finish after the DOM and layout are ready.
        page.wait_for_function("""() => [...document.querySelectorAll('svg.icon')]
            .filter(n => n.getBoundingClientRect().width > 0)
            .every(n => n.getBBox().width > 0 && n.getBBox().height > 0)""")

    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(headless=True, executable_path=args.chromium)
        credentials = {"http_credentials": {"username": os.environ["STUDIO_TEST_USER"],
                       "password": os.environ["STUDIO_TEST_PASSWORD"]}} if os.environ.get("STUDIO_TEST_USER") else {}
        page = browser.new_page(viewport={"width": 1440, "height": 1080}, **credentials)
        page.on("pageerror", lambda error: errors.append(str(error)))
        page.on("request", lambda request: requests.append((request.method, request.url)))
        try:
            page.goto(args.base)
            page.locator('[data-module="agent.account"]').wait_for()
            check("one_terminal_selector", page.locator('[role="radiogroup"][aria-label="应用终端"]').count() == 1)
            check("eight_featured_modules", page.locator("[data-module-card]").count() == 8)
            check("two_selected_readonly_modules", page.locator("[data-module]:checked").count() == 2)
            check("only_two_selectable", page.locator("[data-module]:enabled").count() == 2)
            for width in args.widths:
                page.set_viewport_size({"width": width, "height": 1080})
                preview_heights = []
                for terminal, ratio in (("MOBILE_WEB", 390 / 844), ("DESKTOP_WEB", 16 / 10), ("ATM_WEB", 4 / 3)):
                    page.locator(f'[data-template="{terminal}"]').click()
                    stage = page.locator(".builder-preview .device-stage")
                    page.wait_for_function("() => document.querySelector('.device-scale-box')?.style.width")
                    for tab in ("RESULT", "HOME"):
                        page.locator(f'[data-preview-page="{tab}"]').click()
                        page.wait_for_function("() => document.querySelector('.device-scale-box')?.style.width")
                        check(f"{width}_{terminal}_{tab}_selected", page.locator(f'[data-template="{terminal}"]').get_attribute("aria-checked") == "true")
                        check(f"{width}_{terminal}_{tab}_page_width", page.evaluate("() => document.documentElement.scrollWidth <= innerWidth"))
                        check(f"{width}_{terminal}_{tab}_card_width", page.locator(".business-module,.scene-option,.connection-button").evaluate_all("nodes => nodes.every(n => n.scrollWidth <= n.clientWidth + 1)"))
                        screen = stage.locator(".device-screen").bounding_box()
                        check(f"{width}_{terminal}_{tab}_screen_ratio", abs(screen["width"] / screen["height"] - ratio) < .005)
                        check(f"{width}_{terminal}_{tab}_screen_nonblank", screen["width"] > 80 and stage.locator(".preview-body").inner_text().strip())
                        check(f"{width}_{terminal}_{tab}_body_scrollable", stage.locator(".preview-body").evaluate("n => getComputedStyle(n).overflowY === 'auto' && n.clientHeight > 0"))
                        icons_ready()
                        check(f"{width}_{terminal}_{tab}_icons_drawn", True)
                        page.screenshot(path=str(args.output / f"{width}-{terminal.lower()}-{tab.lower()}.png"), full_page=True)
                        page.locator(".builder-preview").screenshot(path=str(args.output / f"{width}-{terminal.lower()}-{tab.lower()}-preview.png"))
                    page.locator('[data-preview-page="RESULT"]').click()
                    preview_heights.append(stage.bounding_box()["height"])
                    page.locator('[data-action="enlarge-preview"]').click()
                    dialog = page.locator("#drawer")
                    check(f"{width}_{terminal}_enlarged", dialog.is_visible() and dialog.locator(f'[data-device-preview="{terminal}"]').count() == 1)
                    check(f"{width}_{terminal}_exact_sample", "128,650.32" in dialog.inner_text() and "CNY" in dialog.inner_text())
                    dialog.locator(".fact-source summary").first.click()
                    check(f"{width}_{terminal}_source_accessible", dialog.locator(".fact-source[open]").count() == 1)
                    icons_ready()
                    check(f"{width}_{terminal}_enlarged_icons_drawn", True)
                    dialog.screenshot(path=str(args.output / f"{width}-{terminal.lower()}-enlarged.png"))
                    page.keyboard.press("Escape")
                    check(f"{width}_{terminal}_focus_restored", page.locator('[data-action="enlarge-preview"]').evaluate("n => n === document.activeElement"))
                if width > 600:
                    check(f"{width}_stable_preview_height", max(preview_heights) - min(preview_heights) < 2)

            page.set_viewport_size({"width": 1024, "height": 1080})
            page.locator('[data-template="MOBILE_WEB"]').click()
            page.locator('[data-template="MOBILE_WEB"]').focus()
            page.keyboard.press("ArrowRight")
            check("keyboard_changes_real_terminal", page.locator('[data-template="DESKTOP_WEB"]').evaluate("n => n === document.activeElement && n.getAttribute('aria-checked') === 'true'"))
            page.locator('[data-action="customize"]').click()
            page.locator("#ui-card").select_option("balance-ledger-v1")
            page.locator("#ui-theme").select_option("EXAMPLE_TEAL")
            page.locator("#ui-theme").select_option("FINANCE_RED")
            check("theme_does_not_replace_card_choice", page.locator("#ui-card").input_value() == "balance-ledger-v1")
            page.locator("#ui-name").fill("资产核验助手")
            page.locator("#ui-save").click()
            check("customization_applies", page.locator('.builder-preview [data-component="balance-ledger-v1"]').count() == 1 and "资产核验助手" in page.locator(".builder-preview").inner_text())
            page.locator('[data-action="expand-modules"]').click()
            check("full_26_module_catalog", page.locator("[data-module-card]").count() == 26)
            page.locator("#directory-search").fill("安全")
            check("pending_search_stays_disabled", page.locator('[data-module="agent.security_service"]').is_disabled())
            page.locator("#directory-search").fill("")
            page.locator('[data-module-detail="agent.account"]').click()
            check("details_remain_connected", "account_balance_query" in page.locator("#drawer").inner_text())
            page.keyboard.press("Escape")
            page.locator('[data-module="agent.account"]').uncheck()
            check("removed_balance_absent_from_preview", page.locator('.builder-preview [data-component^="balance-"]').count() == 0)
            page.locator('[data-module="agent.wealth_aggregate"]').uncheck()
            check("empty_selection_blocks_build", page.locator('[data-action="build"]').is_disabled())
            check("empty_preview_has_no_money", page.locator(".builder-preview .money").count() == 0)
            check("no_backend_mutation", all(method == "GET" for method, _ in requests))
            check("no_external_requests", all(urlparse(url).netloc == urlparse(args.base).netloc for _, url in requests))
            check("no_browser_errors", not errors)
            passed = True
        finally:
            browser.close()
            project = Path(__file__).resolve().parents[1]
            assets = ("examples/local-workbench/app.js", "examples/local-workbench/terminal.css",
                      "examples/local-workbench/icons.svg", "examples/agent-experience/app.js",
                      "examples/agent-experience/style.css")
            result = {"status": "PASS" if passed else "FAIL", "checksPassed": len(checks), "checks": checks, "errors": errors,
                      "assets": {path: hashlib.sha256((project / path).read_bytes()).hexdigest() for path in assets},
                      "scope": "LOCAL_BUILDER_PREVIEW", "actualBuild": "NOT_RUN",
                      "realModel": "NOT_RUN", "deployment": "NOT_RUN", "humanAcceptance": "NOT_RUN"}
            (args.output / "verification.json").write_text(json.dumps(result, ensure_ascii=False, indent=2))
    print(json.dumps({"checksPassed": len(checks), "errors": errors}))


if __name__ == "__main__":
    main()
