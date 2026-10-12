"""Use the public creation controls; never synthesize a build selection in JS."""
import json


def history_offsets(directory):
    return {path: len(path.read_text().splitlines()) for path in directory.rglob('*.jsonl')}


def observed_authorization_denial(directory, before):
    for path in directory.rglob('*.jsonl'):
        for line in path.read_text().splitlines()[before.get(path, 0):]:
            event = json.loads(line)
            if (event.get('component'), event.get('operation'), event.get('phase'), event.get('outcome')) == (
                    'host', 'authorization', 'END', 'DENIED'):
                return True
    return False


def blank_selection(page):
    assert page.locator('[data-assembly-mode]').count() == 0
    domain = page.locator('[data-business-domain="business-processing"]')
    if domain.count():
        domain.click()
        page.locator('[data-custom-composition]').click()
        assert page.locator('[data-action="build"]').is_disabled()
        return
    direct = page.locator('[data-scenario="customer-custom"]')
    if direct.count():
        direct.click()
    else:
        page.locator('[data-action="more-templates"]').click()
        page.locator('[data-pending-scenario="customer-custom"]').click()
    assert page.locator('[data-action="build"]').is_disabled()


def select_pack(page, catalog, pack_id, checked=True):
    directory = catalog['unifiedTemplateModuleDirectory']
    component = next(c for c in directory['components']
                     if any(b['pack']['id'] == pack_id for b in c['packBindings']))
    module = next(m for m in directory['modules'] if component['id'] in m['componentIds'])
    # Search exposes modules outside the initial compact list without selecting them.
    page.locator('#directory-search').fill(module['label'])
    page.locator('[data-module-detail="' + module['id'] + '"]').click()
    page.locator('#drawer [data-pack-id="' + pack_id + '"]').set_checked(checked)
    page.locator('#drawer [data-module-confirm]').click()
    page.locator('#directory-search').fill('')


def assert_same_packages(catalog):
    creation = catalog['unifiedTemplateModuleDirectory']['components']
    assets = catalog['unifiedDirectory']['components']
    assert {c['id']: c['capabilityPackRefs'] for c in creation} == {
        c['id']: c['capabilityPackRefs'] for c in assets}
