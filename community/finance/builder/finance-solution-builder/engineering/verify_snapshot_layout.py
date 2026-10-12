"""Read-only browser checks against saved synthetic model snapshots."""
import argparse
import json
from pathlib import Path
from urllib.parse import urlsplit
from playwright.sync_api import sync_playwright, expect


def verify(review, output, chrome, request_id):
    output.mkdir(parents=True, exist_ok=False)
    info = json.loads((review / 'review.json').read_text())
    checks = []
    with sync_playwright() as p:
        browser = p.chromium.launch(executable_path=str(chrome), headless=True)
        context = browser.new_context(storage_state=str(review / 'private/browser-state.json'),
                                      viewport={'width':1440, 'height':1000})
        page = context.new_page()
        calls = []
        page.on('request', lambda r: calls.append((r.method, r.url)))
        page.goto(info['url'])
        page.locator('[data-nav="history"]').click()
        page.locator('input[name="requestId"]').fill(request_id)
        page.locator('#history-filters button[type="submit"]').click()
        page.locator(f'[data-history-id="{request_id}"]').click()
        expect(page.locator('.history-detail header code')).to_have_text(request_id)
        panels = page.locator('[data-model-snapshots]')
        panel = panels.nth(1)
        span = panel.get_attribute('data-model-snapshots')
        panel.locator(':scope > summary').click()
        for part in ('request', 'response'):
            panel = page.locator(f'[data-model-snapshots="{span}"]')
            panel.locator(f'[data-snapshot-part="{part}"]').click()
            body = page.locator('[data-snapshot-body]')
            expect(body).to_have_count(1)
            expect(body).to_be_visible()
            text = body.inner_text()
            parsed = json.loads(text)
            assert isinstance(parsed, dict) and '\n' in text
            metadata = panel.locator('.snapshot-content > details')
            assert metadata.get_attribute('open') is None
            checks.append(part + '_parsed_single_body_metadata_collapsed')
            for width in (1440, 768, 390):
                page.set_viewport_size({'width':width, 'height':1000})
                sizes = panel.evaluate('el => {const row=el.parentElement; const body=el.querySelector("[data-snapshot-body]"); return {panel:el.getBoundingClientRect().width,row:row.getBoundingClientRect().width,body:body.getBoundingClientRect().width,height:body.getBoundingClientRect().height,overflow:getComputedStyle(body).overflowX,wrap:getComputedStyle(body).whiteSpace,horizontalOverflow:body.scrollWidth>body.clientWidth+1};}')
                assert sizes['panel'] >= sizes['row'] - 2, sizes
                assert sizes['body'] >= sizes['panel'] - 2, sizes
                assert sizes['height'] <= 510 and sizes['overflow'] == 'auto', sizes
                assert sizes['wrap'] == 'pre-wrap' and not sizes['horizontalOverflow'], sizes
                checks.append(f'{part}_full_width_bounded_{width}')
                panel.locator('..').screenshot(path=str(output / f'{part}-{width}.png'))
        assert all(method == 'GET' and not urlsplit(url).path.endswith(('/chat', '/ag-ui'))
                   for method, url in calls)
        checks.append('history_view_zero_mutating_or_agent_requests')
        browser.close()
    report = {'requestId':request_id, 'checks':checks, 'scope':'saved synthetic history; no model or bank execution'}
    (output / 'report.json').write_text(json.dumps(report, indent=2))
    print(json.dumps(report))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    for name in ('review', 'output', 'chrome'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--request-id', required=True)
    args = parser.parse_args()
    verify(args.review, args.output, args.chrome, args.request_id)
