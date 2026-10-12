"""Exercise the built Studio UI against an isolated Workbench, without creating Agents."""
import argparse
import hashlib
import json
from pathlib import Path
from urllib.parse import urlparse

from playwright.sync_api import sync_playwright


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('base')
    parser.add_argument('output', type=Path)
    parser.add_argument('--chromium', required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    checks, errors, requests = [], [], []
    success = False
    project = Path(__file__).resolve().parents[1]
    references = json.loads((project / 'browser/industry-standards.v1.json').read_text())['references']

    def check(name, condition):
        assert condition, name
        checks.append(name)

    with sync_playwright() as pw:
        browser = pw.chromium.launch(executable_path=args.chromium, headless=True)
        try:
            for server_menu in (False, True):
                page = browser.new_page(viewport={'width': 1440, 'height': 1080})
                page.on('pageerror', lambda error: errors.append(str(error)))
                page.on('request', lambda request: requests.append((request.method, request.url)))
                if server_menu:
                    # Only the deployment label is simulated; all catalog/API data uses the local server.
                    def show_server_menu(route):
                        response = route.fetch()
                        body = response.json()
                        body['deploymentMode'] = 'AUTHENTICATED_SERVER'
                        route.fulfill(response=response, json=body)
                    page.route('**/api/bootstrap', show_server_menu)
                page.goto(args.base)
                page.locator('[data-module="agent.account"]').wait_for()
                check(f'{server_menu}_two_enabled_modules', page.locator('[data-module]:enabled').count() == 2)
                for module, label in [('agent.account', '账户'), ('agent.transfer', '转账'), ('agent.creditcard', '信用卡')]:
                    page.locator('#directory-search').fill(label + '助手')
                    card = page.locator(f'[data-module-card="{module}"]')
                    check(f'{server_menu}_{module}_old_alias_search', card.count() == 1 and label + '服务' in card.inner_text())
                page.locator('#directory-search').fill('')
                for width, height in [(1440, 1080), (1024, 768), (390, 844), (320, 640)]:
                    page.set_viewport_size({'width': width, 'height': height})
                    button = page.locator('[data-nav="standards"]')
                    button.focus()
                    page.keyboard.press('Enter')
                    page.locator('#standards-title').wait_for()
                    prefix = f'{server_menu}_{width}'
                    check(prefix + '_active', page.locator('[data-nav="standards"]').get_attribute('aria-current') == 'page')
                    check(prefix + '_four_records', page.locator('[data-standard]').count() == 4)
                    check(prefix + '_no_page_overflow', page.evaluate('() => document.documentElement.scrollWidth <= innerWidth'))
                    check(prefix + '_text_fits', page.locator('.standards-table tbody td,.standards-table tbody th,.sidebar-footer .nav-item').evaluate_all('nodes => nodes.every(n => n.scrollWidth <= n.clientWidth + 1)'))
                    check(prefix + '_navigation_four_items', page.locator('.sidebar-nav [data-nav]').count() == 4)
                    check(prefix + '_guide_scope', page.locator('.developer-guide-link').count() == int(server_menu))
                    if width <= 640:
                        check(prefix + '_touch_targets', page.locator('.sidebar-footer .nav-item').evaluate_all('nodes => nodes.every(n => n.getBoundingClientRect().height >= 44)'))
                        check(prefix + '_separate_navigation', page.locator('.sidebar-footer').evaluate("n => n.getBoundingClientRect().top >= document.querySelector('.sidebar-nav').getBoundingClientRect().bottom"))
                    for record in references:
                        row = page.locator(f'[data-standard="{record["id"]}"]')
                        link = row.locator('a')
                        check(prefix + '_' + record['id'], link.get_attribute('href') == record['url'] and link.get_attribute('rel') == 'noopener noreferrer' and record['verification'] in row.inner_text() and row.locator('time').get_attribute('datetime') == record['verifiedAt'])
                    page.locator('.standards-table a').first.focus()
                    check(prefix + '_link_keyboard_focus', page.locator('.standards-table a').first.evaluate('n => n === document.activeElement'))
                    page.evaluate('window.scrollTo(0,0)')
                    page.wait_for_function("() => [...document.querySelectorAll('svg.icon')].filter(n=>n.getBoundingClientRect().width>0).every(n=>n.getBBox().width>0)")
                    page.screenshot(path=str(args.output / f'{"server-menu" if server_menu else "local"}-{width}.png'), full_page=True)
                    page.locator('.sidebar-nav [data-nav="create"]').click()
                    check(prefix + '_selection_retained', page.locator('[data-module]:checked').count() == 2)
                page.close()
            check('no_browser_errors', not errors)
            check('no_mutating_calls', all(method == 'GET' for method, _ in requests))
            check('no_external_calls', all(urlparse(url).netloc == urlparse(args.base).netloc for _, url in requests))
            success = True
        finally:
            browser.close()
            files = ['examples/local-workbench/app.js', 'examples/local-workbench/terminal.css', 'examples/local-workbench/icons.svg', 'browser/industry-standards.v1.json']
            report = {'status': 'PASS' if success else 'FAIL', 'checksPassed': len(checks), 'checks': checks, 'errors': errors,
                      'assets': {name: hashlib.sha256((project / name).read_bytes()).hexdigest() for name in files},
                      'scope': 'LOCAL_STUDIO_UI; server footer simulated via deployment label only',
                      'build': 'NOT_RUN', 'realModel': 'NOT_RUN', 'deployment': 'NOT_RUN'}
            (args.output / 'verification.json').write_text(json.dumps(report, ensure_ascii=False, indent=2))
    print(json.dumps({'status': 'PASS', 'checksPassed': len(checks)}))


if __name__ == '__main__':
    main()
