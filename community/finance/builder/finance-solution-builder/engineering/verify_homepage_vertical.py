"""Real isolated homepage/API regression; never builds or calls a model/bank.

Run after the owner's normal UI bundling step. --ui-root optionally selects an
already bundled workbench. Every API response is served by the real backend.
Only old saved selections/private releases are synthetic storage fixtures; their
trial metadata is NOT runtime qualification. Draft persistence means retention
through UI navigation/re-rendering, not an undocumented reload-storage promise.

Example (from the project directory):
  PYTHONPATH=src:../base/src python engineering/verify_homepage_vertical.py \
    --harness /path/to/harness --chrome /path/to/chrome --output /new/evidence
"""

import argparse
from contextlib import ExitStack
from copy import deepcopy
import hashlib
from http.server import ThreadingHTTPServer
import json
import os
from pathlib import Path
import subprocess
import threading
import time
import traceback
from unittest.mock import patch
from urllib.parse import urlsplit

from playwright.sync_api import expect, sync_playwright

from finance_solution_builder import workbench
from finance_solution_builder.model_registry import CATALOG
from finance_solution_builder.workbench_contracts import parse_saved_selection


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def identities(bindings):
    return sorted(json.dumps(b, sort_keys=True) for b in bindings)


def source_info(root):
    def git(*args):
        return subprocess.check_output(["git", "-C", str(root), *args], text=True).strip()
    return {"commit": git("rev-parse", "HEAD"), "dirty": git("status", "--short")}


def model_environment(output):
    # Reuse real model identities; these inert endpoints cannot reach a provider.
    models = json.loads(CATALOG.read_text())["models"]
    candidates = [m for m in models if m["provider"] in {"OpenAI", "OpenRouter"}][:4]
    if len(candidates) != 4:
        raise AssertionError("real model catalog must contain four supported identities to test the three-choice cap")
    bindings = [{"modelRef": m["modelRef"], "revision": "ui-only-inert", "enabled": True,
                 "protocol": "OPENAI_CHAT_COMPLETIONS", "provider": m["provider"],
                 "modelName": m["modelName"], "urlEnvironment": "FH_CONN_UI_ONLY_URL",
                 "keyEnvironment": "FH_CONN_UI_ONLY_KEY"} for m in candidates]
    path = output / "inert-model-bindings.json"
    write_json(path, {"schemaVersion": "finance-model-bindings.v1", "bindings": bindings})
    policy = output / "inert-access-policy.json"
    write_json(policy, {})
    environment = {k: v for k, v in os.environ.items() if not k.startswith("FH_")}
    environment.update(FH_MODEL_REGISTRY=str(path), FH_CONN_UI_ONLY_URL="http://127.0.0.1:1/v1",
                       FH_CONN_UI_ONLY_KEY="UI_ONLY_NOT_A_CREDENTIAL",
                       FH_BANK_URL="http://127.0.0.1:1", FH_BANK_LOGIN="ui-only",
                       FH_BANK_SECRET="UI_ONLY_NOT_A_CREDENTIAL", FH_ACCESS_POLICY=str(policy))
    return environment


def seed_legacy(jobs, owner, catalog):
    entries = catalog["unifiedManagedDirectory"]["entries"]
    entry = next(e for e in entries if e["methodCustomizationSupported"]
                 and len(e["capabilities"]) == 1)
    capability = entry["capabilities"][0]["capabilityId"]
    store = jobs.method_assets
    content = {"name": "Isolated legacy SOP fixture", "content": "# UI-only fixture\nRetain this version.",
               "resources": {}, "requiredResources": [], "capabilityIds": [capability],
               "cases": [{"name": "metadata-only", "query": "UI fixture, never execute", "expectedTerms": []}]}
    draft = store.create(owner, content)
    releases = []
    for version in ("1.0.0", "1.1.0"):
        if releases:
            content = dict(content, content="# UI-only fixture\nNew draft, not the old release.")
            draft = store.update(owner, draft["assetId"], draft["revision"], content)
        # Deliberately synthetic evidence solely to exercise immutable release UI.
        trial = store.record_trial(owner, draft["assetId"], draft,
                                   {"status": "PASS", "runtimeVerified": True,
                                    "model": "SYNTHETIC_UI_METADATA_NOT_EXECUTED"})
        release = store.publish(owner, draft["assetId"], draft["revision"], version, trial["trialId"])
        releases.append({k: release[k] for k in ("assetId", "version", "releaseDigest")})
    model = next(m["modelRef"] for m in catalog["models"] if m["bindingStatus"] == "CONFIGURED_UNTESTED")
    base = {"extension_type": "finance-workbench.selection", "schema_version": "0.8",
            "runtimeId": "openjiuwen-java-0.1.15", "capabilityIds": [capability],
            "packBindings": deepcopy(entry["packBindings"]), "selectedModelRefs": [model],
            "defaultModelRef": model, "presentation": "MOBILE_WEB",
            "applicationTemplateId": "banking-readonly-web-v1", "bankConnectionId": "local-simulator",
            "modelCatalogRevision": catalog["modelCatalogRevision"]}
    private = dict(base, schema_version="0.9", scenarioTemplateId="customer-custom",
                   directoryRevision=catalog["unifiedTemplateModuleDirectory"]["revision"],
                   moduleIds=entry["moduleIds"], methodRef=releases[0])
    missing = dict(private, methodRef=dict(releases[0], version="0.0.1", releaseDigest="sha256:" + "0" * 64))
    retired = deepcopy(base)
    retired["packBindings"][0]["pack"]["version"] = "0.0.0-ui-retired"
    provider = deepcopy(base)
    provider["packBindings"][0]["provider"]["version"] = "0.0.0-ui-retired"
    implicit = dict(base, schema_version="0.6", scenarioTemplateId="mobile-banking",
                    moduleIds=entry["moduleIds"], directoryRevision=directory_revision(catalog))
    implicit.pop("packBindings")
    old_private = dict(implicit, schema_version="0.7", methodRef=releases[0])
    records = {}
    for index, (label, selection) in enumerate((("explicit", base), ("private", private),
            ("missing-method", missing), ("retired-pack", retired),
            ("retired-provider", provider), ("implicit", implicit), ("old-private", old_private)), 1):
        parse_saved_selection(selection)
        job_id = "agent-" + f"{index:016x}"
        job = {"id": job_id, "owner": owner, "status": "INTERRUPTED", "createdAt": index,
               "capabilities": [capability], "selection": selection}
        jobs.jobs[job_id] = job
        jobs.save(job)
        records[label] = job
    paths = [*jobs.records.glob("agent-*.json"), *store.root.rglob("*.json")]
    return {"records": records, "releases": releases, "entry": entry,
            "files": {str(p): sha(p) for p in paths}}


def directory_revision(catalog):
    return catalog["templateModuleDirectory"]["revision"]


class Regression:
    def __init__(self, page, base, output, catalog, fixture, report):
        self.page, self.base, self.output = page, base, output
        self.catalog, self.fixture, self.report = catalog, fixture, report
        self.modules = catalog["unifiedTemplateModuleDirectory"]["modules"]
        self.entries = catalog["unifiedManagedDirectory"]["entries"]

    def check(self, name, condition, evidence=None):
        self.report["checks"].append({"name": name, "passed": bool(condition), "evidence": evidence})
        assert condition, name

    def case(self, name, action):
        started = time.perf_counter()
        try:
            self.page.set_viewport_size({"width": 1440, "height": 1000})
            self.load()
            action()
            self.report["cases"].append({"name": name, "status": "PASS"})
        except Exception as error:
            self.report["cases"].append({"name": name, "status": "FAIL", "error": str(error),
                                         "traceback": traceback.format_exc()})
            self.page.screenshot(path=str(self.output / (name + "-failure.png")), full_page=True)
        finally:
            self.report["timingsMs"][name] = round((time.perf_counter() - started) * 1000, 1)

    def load(self):
        self.page.goto(self.base)
        self.page.locator('[data-action="build"]').wait_for()
        self.page.locator(".planned-selection pre").wait_for(state="attached")

    def plan(self):
        return json.loads(self.page.locator(".planned-selection pre").text_content())

    def bindings(self):
        return identities(self.plan()["packBindings"])

    def search(self, module_id):
        self.page.locator("#directory-search").fill(module_id)
        expect(self.page.locator(f'[data-module-card="{module_id}"]')).to_be_visible()

    def open_module(self, module_id):
        self.search(module_id)
        self.page.locator(f'[data-module-detail="{module_id}"]').click()
        expect(self.page.locator("#drawer")).to_be_visible()

    def pack(self, entry):
        return self.page.locator('#drawer input[data-pack-id="' + entry["pack"]["id"]
                                 + '"][data-pack-version="' + entry["pack"]["version"] + '"]')

    def method_choice(self, entry):
        self.open_module(entry["moduleIds"][0])
        self.page.locator(f'[data-pack-detail="{entry["pack"]["id"]}"]').click()
        return self.page.locator(f'#drawer select[data-method-choice="{entry["pack"]["id"]}"]'
                                 f'[data-pack-version="{entry["pack"]["version"]}"]')

    def navigate(self, target):
        if self.page.viewport_size["width"] <= 640:
            self.page.locator("[data-sidebar-open]").click()
        self.page.locator(f'.sidebar [data-nav="{target}"]').first.click()

    def focus_trapped(self, name):
        for _ in range(16):
            self.page.keyboard.press("Tab")
            self.check(name, self.page.evaluate("document.querySelector('#drawer').contains(document.activeElement)"))

    def glyphs_ready(self, name, selector, minimum):
        nodes = self.page.locator(selector)
        geometry = """nodes=>nodes.map(node=>{
            const box=node.getBBox();
            return {width:box.width,height:box.height,rendered:node.getClientRects().length>0,
                    inline:!!node.querySelector('path,line,polyline,polygon,circle,rect,ellipse'),
                    external:!!node.querySelector('use[href^="/icons.svg"]')};
        })"""
        immediate = nodes.evaluate_all(geometry)
        self.check(name + "_already_inline", len(immediate) >= minimum
                   and all(g["inline"] and not g["external"] for g in immediate), immediate)
        rendered = [g for g in immediate if g["rendered"]]
        self.check(name + "_immediate_geometry", len(rendered) >= minimum
                   and all(g["width"] > 0 and g["height"] > 0 for g in rendered))
        self.page.wait_for_function("""({selector,minimum})=>{
            const nodes=[...document.querySelectorAll(selector)].filter(node=>node.getClientRects().length>0);
            return nodes.length>=minimum&&nodes.every(node=>{
                const box=node.getBBox();return box.width>0&&box.height>0;
            });
        }""", arg={"selector": selector, "minimum": minimum}, timeout=1000)

    def overlay_close(self):
        rect = self.page.locator("#drawer").bounding_box()
        width, height = self.page.viewport_size["width"], self.page.viewport_size["height"]
        points = [(1, 1), (width - 2, 1), (1, height - 2)]
        point = next((p for p in points if not (rect["x"] <= p[0] <= rect["x"] + rect["width"]
                     and rect["y"] <= p[1] <= rect["y"] + rect["height"])), None)
        self.check("overlay_has_outside_target", point is not None)
        self.page.mouse.click(*point)
        expect(self.page.locator("#drawer")).not_to_be_visible()

    def edit(self, label):
        self.navigate("agents")
        job = self.fixture["records"][label]
        self.page.locator(f'[data-edit="{job["id"]}"]').click()
        expect(self.page.locator(".edit-context")).to_contain_text(job["id"])
        return job["selection"]


def verify(args):
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    ui_root = (args.ui_root or workbench.PUBLIC).resolve()
    report = {"mode": "REAL_ISOLATED_WORKBENCH_API_BROWSER", "checks": [], "cases": [],
              "timingsMs": {}, "requests": [], "blockedRequests": [], "pageErrors": [],
              "sources": {"builder": source_info(workbench.PROJECT), "harness": source_info(args.harness)},
              "servedUi": {p.name: sha(p) for p in ui_root.iterdir() if p.is_file()},
              "scope": {"model": "NOT_RUN", "bank": "NOT_RUN", "agentBuild": "NOT_RUN",
                        "fixtures": "SYNTHETIC_OLD_SELECTIONS_AND_PRIVATE_RELEASE_METADATA_ONLY",
                        "draftPersistence": "IN_PAGE_NAVIGATION_AND_RERENDER_ONLY"}}
    servers, holders, startup_errors = [], [], []
    archive_paths = set()
    ready = threading.Event()

    class OwnedJobs(workbench.Jobs):
        def __init__(self, *a, **kw):
            super().__init__(*a, **kw)
            holders.append(self)

    class OwnedServer(ThreadingHTTPServer):
        def __init__(self, address, handler, *a, **kw):
            original_get = handler.do_GET
            original_post = handler.do_POST
            def guarded_get(instance):
                path = urlsplit(instance.path).path
                if path == "/api/bootstrap" or path == "/" or path.endswith((".js", ".css", ".svg", ".ico")) or path in {
                        "/api/v1/catalog", "/api/v1/connections", "/api/v1/assets", "/api/v1/builds"} or path.startswith("/api/v1/builds/"):
                    return original_get(instance)
                report["blockedRequests"].append({"method": "GET", "path": path})
                instance.send_error(405, "UI_VERIFIER_READ_ONLY")
            def reject(instance):
                if instance.command == "POST" and instance.path in archive_paths:
                    report["requests"].append({"method": "POST", "path": instance.path,
                                               "purpose": "AUTHORIZED_SYNTHETIC_ASSET_ARCHIVE"})
                    return original_post(instance)
                report["blockedRequests"].append({"method": instance.command, "path": instance.path})
                instance.send_error(405, "UI_VERIFIER_NO_EXECUTION")
            handler.do_GET = guarded_get
            for method in ("POST", "PUT", "PATCH", "DELETE"):
                setattr(handler, "do_" + method, reject)
            super().__init__(("127.0.0.1", 0), handler, *a, **kw)
            servers.append(self)
            ready.set()

    def launch():
        try:
            workbench.serve(args.harness.resolve(), 0, data_dir=output / "workspace")
        except Exception:
            startup_errors.append(traceback.format_exc())
            ready.set()

    with ExitStack() as stack:
        stack.enter_context(patch.dict(os.environ, model_environment(output), clear=True))
        stack.enter_context(patch.object(workbench, "PUBLIC", ui_root))
        stack.enter_context(patch.object(workbench, "Jobs", OwnedJobs))
        stack.enter_context(patch.object(workbench, "ThreadingHTTPServer", OwnedServer))
        stack.enter_context(patch("finance_solution_builder.workbench.signal.signal"))
        worker = threading.Thread(target=launch, daemon=True)
        worker.start()
        browser = None
        started = time.perf_counter()
        try:
            if not ready.wait(30) or startup_errors:
                raise RuntimeError("isolated server startup failed: " + str(startup_errors))
            base = "http://127.0.0.1:" + str(servers[0].server_port)
            with sync_playwright() as playwright:
                browser = playwright.chromium.launch(executable_path=str(args.chrome), headless=True)
                context = browser.new_context(viewport={"width": 1440, "height": 1000}, service_workers="block")
                def route(request_route):
                    request = request_route.request
                    report["requests"].append({"method": request.method, "url": request.url})
                    if urlsplit(request.url).netloc != urlsplit(base).netloc or request.method != "GET":
                        report["blockedRequests"].append({"method": request.method, "url": request.url})
                        request_route.abort()
                    else:
                        request_route.continue_()
                context.route("**/*", route)
                page = context.new_page()
                page.set_default_timeout(8000)
                page.on("pageerror", lambda error: report["pageErrors"].append(str(error)))
                page.goto(base)
                page.locator('[data-action="build"]').wait_for()
                response = page.request.get(base + "/api/v1/catalog")
                assert response.status == 200, "actual catalog API unavailable"
                catalog = response.json()
                write_json(output / "actual-catalog.json", catalog)
                owner = next(c["value"].split(".")[0] for c in context.cookies() if c["name"] == "fh_local")
                fixture = seed_legacy(holders[0], owner, catalog)
                archive_paths.add("/api/v1/assets/" + fixture["releases"][0]["assetId"] + "/archive")
                regression = Regression(page, base, output, catalog, fixture, report)
                run_cases(regression)
                regression.check("fixture_files_immutable", all(sha(Path(p)) == digest for p, digest in fixture["files"].items()))
                regression.check("no_execution_or_external_requests", not report["blockedRequests"])
                regression.check("no_javascript_errors", not report["pageErrors"])
                regression.check("served_assets_stable_during_run", report["servedUi"] == {
                    p.name: sha(p) for p in ui_root.iterdir() if p.is_file()})
                browser.close()
                browser = None
        except Exception:
            report["fatal"] = traceback.format_exc()
        finally:
            for server in servers:
                server.shutdown()
            worker.join(timeout=20)
            report["serverStopped"] = not worker.is_alive()
            report["elapsedSeconds"] = round(time.perf_counter() - started, 2)
            report["status"] = "PASS" if (not report.get("fatal") and report["cases"]
                and all(c["status"] == "PASS" for c in report["cases"])
                and report["serverStopped"]) else "FAIL"
            write_json(output / "report.json", report)
    print(json.dumps({"status": report["status"], "checks": len(report["checks"]),
                      "cases": [{k: v for k, v in case.items() if k != "traceback"} for case in report["cases"]],
                      "report": str(output / "report.json")}, ensure_ascii=False))
    return 0 if report["status"] == "PASS" else 1


def run_cases(test):
    page = test.page
    directory = test.catalog["unifiedTemplateModuleDirectory"]
    defaults = next(t for t in directory["templates"] if t["id"] == "mobile-banking")
    entries = test.entries

    def catalog_and_pages():
        test.check("actual_catalog_26_unique_modules", len(test.modules) == 26
                   and len({m["id"] for m in test.modules}) == 26)
        test.check("no_empty_ungrouped_module", not any(m["id"] == "agent.ungrouped" for m in test.modules))
        test.check("actual_default_pack_identities", test.bindings() == identities(defaults["defaultPackBindings"]))
        expect(page.locator("#module-count")).to_contain_text("26")
        test.check("catalog_icons_are_metadata_driven_and_specific",
                   len({m["icon"] for m in test.modules}) >= 10
                   and all(m.get("icon") for m in test.modules))
        test.check("page_size_selector_removed", page.locator("#module-page-size").count() == 0)
        template_strip = page.locator(".template-strip")
        test.check("template_scrollbar_hidden", template_strip.evaluate(
            "node=>getComputedStyle(node).scrollbarWidth==='none'"))
        template_before = template_strip.evaluate("node=>node.scrollLeft")
        template_box = template_strip.bounding_box()
        page.mouse.move(template_box["x"] + template_box["width"] * .8,
                        template_box["y"] + template_box["height"] * .5)
        page.mouse.down()
        page.mouse.move(template_box["x"] + template_box["width"] * .35,
                        template_box["y"] + template_box["height"] * .5, steps=8)
        page.mouse.up()
        test.check("template_mouse_drag_scrolls", template_strip.evaluate("node=>node.scrollLeft") > template_before)
        test.check("template_drag_does_not_change_selection",
                   page.locator(f'[data-scenario="{defaults["id"]}"]').get_attribute("aria-checked") == "true")
        cards = page.locator("[data-module-card]")
        heights = cards.evaluate_all("nodes=>nodes.map(n=>n.getBoundingClientRect().height)")
        test.check("desktop_cards_are_115_to_125_px", len(heights) == len(test.modules)
                   and all(115 <= h <= 125 for h in heights), heights)
        test.check("card_has_no_internal_divider", cards.evaluate_all(
            "nodes=>nodes.every(n=>!n.querySelector('.module-card-footer')||getComputedStyle(n.querySelector('.module-card-footer')).borderTopWidth==='0px')"))
        test.check("summary_is_clamped_to_two_lines", cards.evaluate_all(
            "nodes=>nodes.every(n=>getComputedStyle(n.querySelector('.module-card-summary')).webkitLineClamp==='2')"))
        test.search("agent.personal_info")
        pending = page.locator('[data-module-card="agent.personal_info"]')
        test.check("pending_module_is_not_interactive", pending.get_attribute("data-module-detail") is None
                   and pending.get_attribute("tabindex") is None
                   and pending.get_attribute("aria-disabled") == "true")
        page.locator("#directory-search").fill("")
        account = page.locator('[data-module-card="agent.account"]')
        account.click(position={"x": 90, "y": 54})
        expect(page.locator("#drawer")).to_be_visible()
        page.locator(".module-cancel").click()
        expect(account).to_be_focused()
        account.press("Enter")
        expect(page.locator("#drawer")).to_be_visible()
        page.locator(".module-cancel").click()
        account.press(" ")
        expect(page.locator("#drawer")).to_be_visible()
        page.locator(".module-cancel").click()
        test.check("whole_card_pointer_and_keyboard_open_drawer", True)
        dynamic_icons = "#module-results svg.icon, #module-pagination svg.icon"
        test.check("all_filtered_modules_share_one_continuous_track",
                   page.locator("[data-module-card]").count() == len(test.modules))
        start_scroll = page.locator("#module-results").evaluate("node=>node.scrollLeft")
        box = page.locator("#module-results").bounding_box()
        page.mouse.move(box["x"] + box["width"] * .75, box["y"] + box["height"] * .5)
        page.mouse.down()
        page.mouse.move(box["x"] + box["width"] * .35, box["y"] + box["height"] * .5, steps=8)
        page.mouse.up()
        dragged_scroll = page.locator("#module-results").evaluate("node=>node.scrollLeft")
        test.check("mouse_drag_scrolls_module_track_smoothly", dragged_scroll > start_scroll,
                   {"before": start_scroll, "after": dragged_scroll})
        test.check("module_drag_does_not_open_card", not page.locator("#drawer").is_visible())
        box = page.locator("#module-results").bounding_box()
        page.mouse.move(box["x"] + box["width"] * .25, box["y"] + box["height"] * .5)
        page.mouse.down()
        page.mouse.move(box["x"] + box["width"] * .65, box["y"] + box["height"] * .5, steps=8)
        page.mouse.up()
        returned_scroll = page.locator("#module-results").evaluate("node=>node.scrollLeft")
        test.check("mouse_drag_can_return_continuously", returned_scroll < dragged_scroll,
                   {"dragged": dragged_scroll, "returned": returned_scroll})
        page.locator("#module-results").evaluate("node=>node.scrollLeft=0")
        box = page.locator("#module-results").bounding_box()
        page.mouse.move(box["x"] + box["width"] * .5, box["y"] + box["height"] * .5)
        page.mouse.wheel(400, 0)
        page.wait_for_timeout(400)
        wheel_scroll = page.locator("#module-results").evaluate("node=>node.scrollLeft")
        test.check("trackpad_horizontal_wheel_scrolls_track", wheel_scroll > 0, wheel_scroll)
        page.mouse.wheel(-400, 0)
        page.wait_for_timeout(400)
        test.check("trackpad_horizontal_wheel_returns_track",
                   page.locator("#module-results").evaluate("node=>node.scrollLeft") < wheel_scroll)
        before_vertical = page.locator("#module-results").evaluate("node=>node.scrollLeft")
        page.mouse.wheel(0, 100)
        test.check("vertical_wheel_does_not_change_module_track",
                   page.locator("#module-results").evaluate("node=>node.scrollLeft") == before_vertical)
        page.locator('[data-module-scroll="next"]').click()
        page.wait_for_timeout(400)
        test.check("module_arrow_scrolls_smooth_track",
                   page.locator("#module-results").evaluate("node=>node.scrollLeft") > before_vertical)
        page.locator('[data-module-scroll="prev"]').click()
        page.wait_for_timeout(400)
        for iteration, module in enumerate(test.modules[:3]):
            test.search(module["id"])
            test.glyphs_ready(f"search_reinsert_{iteration}", dynamic_icons,
                              page.locator("[data-module-card]").count() + 2)
        page.locator("#directory-search").fill("")
        test.glyphs_ready("clear_search_reinsert", dynamic_icons, 8)
        ids = page.locator("[data-module-card]").evaluate_all("nodes=>nodes.map(n=>n.dataset.moduleCard)")
        test.check("track_equals_real_catalog", set(ids) == {m["id"] for m in test.modules}, ids)
        later = next(e for e in entries if set(e["moduleIds"]).intersection(ids)
                     and identities(e["packBindings"])[0] not in test.bindings())
        target = next(m for m in later["moduleIds"] if m in ids)
        before = test.bindings()
        page.locator(f'[data-module-detail="{target}"]').click()
        test.pack(later).check()
        page.locator("[data-module-confirm]").click()
        selected = test.bindings()
        test.check("later_page_adds_only_selected_pack", selected == sorted(before + identities(later["packBindings"])))
        page.locator('[data-module-scroll="prev"]').click()
        test.check("track_scroll_retains_selection", test.bindings() == selected)
        page.locator("#directory-search").fill("no-such-business-ui-verifier")
        expect(page.locator("[data-module-card]")).to_have_count(0)
        test.check("empty_search_retains_selection", test.bindings() == selected)
        test.search(target)
        test.check("search_retains_selected_card", "is-selected" in page.locator(f'[data-module-card="{target}"]').get_attribute("class"))
        page.locator("#directory-search").fill("")
        test.check("fixed_responsive_paging_retains_selection", test.bindings() == selected)
        test.navigate("connections")
        test.navigate("create")
        test.check("navigation_retains_exact_bindings", test.bindings() == selected)

    def drawer_transaction():
        multi = next(m for m in test.modules if sum(m["id"] in e["moduleIds"] for e in entries) > 1)
        members = [e for e in entries if multi["id"] in e["moduleIds"]]
        before = test.bindings()
        entry = next(e for e in members if identities(e["packBindings"])[0] not in before)
        test.open_module(multi["id"])
        test.check("multi_pack_module_separate_choices", page.locator("#drawer input[data-pack-id]").count() == len(members))
        test.pack(entry).check()
        test.check("drawer_draft_does_not_commit", test.bindings() == before)
        page.locator(f'[data-pack-detail="{entry["pack"]["id"]}"]').click()
        expect(page.locator("#drawer")).to_contain_text(entry["label"])
        page.locator("[data-module-back]").first.click()
        expect(test.pack(entry)).to_be_checked()
        page.locator(".module-cancel").click()
        test.check("drawer_cancel_discards_only_draft", test.bindings() == before)
        test.open_module(multi["id"])
        expect(test.pack(entry)).not_to_be_checked()
        test.pack(entry).check()
        test.focus_trapped("module_drawer_tab_containment")
        page.keyboard.press("Escape")
        expect(page.locator("#drawer")).not_to_be_visible()
        expect(page.locator(f'[data-module-detail="{multi["id"]}"]')).to_be_focused()
        test.check("module_escape_discards_draft", test.bindings() == before)
        test.open_module(multi["id"])
        test.pack(entry).check()
        test.overlay_close()
        test.check("module_overlay_discards_draft", test.bindings() == before)
        test.open_module(multi["id"])
        test.pack(entry).check()
        page.locator("[data-module-confirm]").click()
        test.check("module_confirm_commits_exact_pack", test.bindings() == sorted(before + identities(entry["packBindings"])))
        test.open_module(multi["id"])
        test.pack(entry).uncheck()
        page.locator("[data-module-confirm]").click()
        test.check("partial_module_preserves_other_pack", test.bindings() == before)

    def private_and_legacy():
        for label in ("explicit", "private", "missing-method", "retired-pack", "retired-provider", "implicit", "old-private"):
            selection = test.edit(label)
            expected = identities(selection.get("packBindings", []))
            test.check(label + "_original_bindings_not_replaced", test.bindings() == expected)
            if label in {"private", "old-private"}:
                ref = test.fixture["releases"][0]
                choice = test.method_choice(test.fixture["entry"])
                if label == "old-private":
                    expect(choice).to_have_value("original-method")
                    test.check("legacy_method_option_native_disabled",
                               choice.locator("option:checked").evaluate("el=>el.disabled") is True)
                else:
                    expect(choice).to_have_value(ref["assetId"] + "@" + ref["version"])
                page.keyboard.press("Escape")
                test.check("private_old_release_digest_not_upgraded", test.plan()["methodRef"] == ref)
                test.navigate("connections")
                test.navigate("create")
                test.check("private_reference_survives_navigation", test.plan()["methodRef"] == ref)
                test.open_module(test.fixture["entry"]["moduleIds"][0])
                page.locator(".module-cancel").click()
                test.check("private_reference_survives_drawer_cancel", test.plan()["methodRef"] == ref)
            if label in {"missing-method", "retired-pack", "retired-provider", "implicit", "old-private"}:
                expect(page.locator('[data-action="build"]')).to_be_disabled()
                expect(page.locator(".build-blockers")).to_be_visible()
                if label == "missing-method":
                    test.check("missing_private_ref_retained", test.plan()["methodRef"] == selection["methodRef"])
            job = test.fixture["records"][label]
            response = page.request.get(test.base + "/api/v1/builds/" + job["id"])
            test.check(label + "_real_get_preserves_saved_selection", response.status == 200
                       and response.json()["selection"] == selection)
        other = page.context.browser.new_context()
        try:
            other_page = other.new_page()
            other_page.goto(test.base)
            other_page.locator('[data-action="build"]').wait_for()
            assets = other_page.request.get(test.base + "/api/v1/assets")
            test.check("private_sop_not_shared_between_owners", assets.status == 200 and assets.json()["assets"] == [])
            response = other_page.request.get(test.base + "/api/v1/builds/" + test.fixture["records"]["private"]["id"])
            test.check("old_agent_other_owner_denied", response.status == 403)
        finally:
            other.close()

    def templates():
        test.edit("private")
        before, ref = test.bindings(), test.plan()["methodRef"]
        messages = []
        def dismiss(dialog):
            messages.append(dialog.message)
            dialog.dismiss()
        page.on("dialog", dismiss)
        page.locator('[data-scenario="mobile-banking"]').click()
        test.check("template_switch_no_confirmation", messages == [], messages)
        test.check("template_switch_replaces_draft", test.bindings() != before and test.plan()["methodRef"] != ref)
        test.check("template_switch_effect_explained", page.locator(".selection-help").is_visible())
        test.check("template_apply_uses_only_original_defaults", test.bindings() == identities(defaults["defaultPackBindings"]))
        test.check("template_apply_clears_private_reference", test.plan()["methodRef"] is None)
        expect(page.locator("#selected-method")).to_have_count(0)
        page.locator('[data-scenario="customer-custom"]').click()
        test.check("blank_template_no_confirmation", messages == [], messages)
        test.check("blank_create_no_implicit_packs", test.bindings() == [])
        expect(page.locator('[data-scenario="customer-custom"]')).to_have_attribute("aria-checked", "true")
        expect(page.locator('[data-action="blank-create"], [data-action="more-templates"]')).to_have_count(0)
        page.remove_listener("dialog", dismiss)

    def method_transaction():
        test.edit("private")
        before = test.bindings()
        old, newer = test.fixture["releases"]
        choice = test.method_choice(test.fixture["entry"])
        choice.select_option(newer["assetId"] + "@" + newer["version"])
        test.check("method_draft_keeps_old_reference", test.plan()["methodRef"] == old)
        page.locator("[data-module-back]").first.click()
        page.locator(".module-cancel").click()
        test.check("method_cancel_restores_old_digest", test.plan()["methodRef"] == old)
        choice = test.method_choice(test.fixture["entry"])
        choice.select_option(newer["assetId"] + "@" + newer["version"])
        page.locator("[data-module-back]").first.click()
        page.locator("[data-module-confirm]").click()
        test.check("method_confirm_pins_explicit_new_digest", test.plan()["methodRef"] == newer)
        test.check("method_change_does_not_change_pack_selection", test.bindings() == before)
        choice = test.method_choice(test.fixture["entry"])
        choice.select_option("")
        page.keyboard.press("Escape")
        test.check("method_default_escape_keeps_reference", test.plan()["methodRef"] == newer)
        choice = test.method_choice(test.fixture["entry"])
        choice.select_option("")
        page.locator("[data-module-back]").first.click()
        page.locator("[data-module-confirm]").click()
        test.check("method_default_requires_explicit_commit", test.plan()["methodRef"] is None)

    def previews():
        trigger = page.locator('[data-action="enlarge-preview"]')
        before = test.bindings()
        def current():
            return page.locator('[data-template][aria-checked="true"]').get_attribute("data-template")
        for terminal in ("DESKTOP_WEB", "ATM_WEB", "MOBILE_WEB"):
            original = current()
            trigger.click()
            expect(page.locator("#drawer.device-preview-dialog")).to_be_visible()
            page.locator(f'[data-preview-terminal="{terminal}"]').click()
            expect(page.locator(f'[data-preview-terminal="{terminal}"]')).to_have_attribute("aria-checked", "true")
            test.check(terminal + "_preview_switch_not_applied", current() == original)
            test.glyphs_ready(terminal + "_preview_glyphs", "#drawer svg.icon", 4)
            page.screenshot(path=str(test.output / ("preview-" + terminal + ".png")))
            page.locator("[data-preview-cancel]").click()
            test.check(terminal + "_preview_cancel", current() == original)
            expect(trigger).to_be_focused()
            trigger.click()
            page.locator(f'[data-preview-terminal="{terminal}"]').click()
            page.locator("[data-preview-apply]").click()
            test.check(terminal + "_preview_apply", current() == terminal)
            test.check(terminal + "_preview_keeps_packs", test.bindings() == before)
            test.navigate("connections")
            test.navigate("create")
            test.check(terminal + "_terminal_retained_after_navigation", current() == terminal)
        trigger.click()
        test.focus_trapped("preview_tab_containment")
        page.locator('[data-preview-terminal="ATM_WEB"]').click()
        page.keyboard.press("Escape")
        expect(page.locator("#drawer")).not_to_be_visible()
        expect(trigger).to_be_focused()
        test.check("preview_escape_discards_switch", current() == "MOBILE_WEB")
        trigger.click()
        page.locator('[data-preview-terminal="ATM_WEB"]').click()
        test.overlay_close()
        test.check("preview_overlay_discards_switch", current() == "MOBILE_WEB")

    def models_runtime():
        configured = {m["modelRef"] for m in test.catalog["models"] if m["bindingStatus"] == "CONFIGURED_UNTESTED"}
        test.check("four_real_models_inertly_configured_to_exercise_cap", len(configured) == 4)
        page.locator('[data-catalog="models"]').click()
        choices = page.locator("#drawer [data-pick]:enabled")
        refs = choices.evaluate_all("nodes=>nodes.map(n=>n.dataset.pick)")
        test.check("configured_only_max_three_selectable", 0 < len(refs) <= 3 and set(refs) <= configured, refs)
        test.check("no_unconfigured_model_rows", page.locator("#drawer [data-pick]").count() == len(refs))
        for ref in refs:
            page.locator(f'[data-pick="{ref}"]').check()
        page.locator("#catalog-save").click()
        test.navigate("connections")
        test.navigate("create")
        page.locator('[data-catalog="models"]').click()
        test.check("models_preserved_navigation", page.locator("#drawer [data-pick]:checked").count() == len(refs))
        page.locator("#catalog-cancel").click()
        runtime = page.locator('.framework-option').filter(has=page.locator('[data-runtime="openjiuwen-python-0.1.18"]'))
        expect(runtime.locator("strong")).to_have_text("OpenJiuwen Python")
        test.check("python_readonly_scope_visible", any("只读查询" in text for text in runtime.locator("small").all_text_contents()))
        before = test.bindings()
        page.locator('[data-runtime="openjiuwen-python-0.1.18"]').check()
        test.check("python_switch_does_not_drop_packages", test.bindings() == before)
        test.navigate("connections")
        test.navigate("create")
        expect(page.locator('[data-runtime="openjiuwen-python-0.1.18"]')).to_be_checked()

    def viewport(width):
        page.set_viewport_size({"width": width, "height": 900 if width >= 1024 else 844})
        page.wait_for_timeout(120)
        expected_columns = 1 if width <= 640 else 2 if width <= 1000 else 4
        test.check(f"continuous_track_has_all_cards_{width}",
                   page.locator("[data-module-card]").count() == len(test.modules))
        heights = page.locator("[data-module-card]").evaluate_all("nodes=>nodes.map(n=>n.getBoundingClientRect().height)")
        test.check(f"compact_card_height_{width}", all(112 <= h <= 125 for h in heights), heights)
        geometry = page.locator("#module-results").evaluate("""node=>({
            clientWidth:node.clientWidth,scrollWidth:node.scrollWidth,height:node.getBoundingClientRect().height,
            cardWidth:node.querySelector('[data-module-card]').getBoundingClientRect().width
        })""")
        expected_width = (geometry["clientWidth"] - 12 * (expected_columns - 1)) / expected_columns
        test.check(f"responsive_columns_{width}", abs(geometry["cardWidth"] - expected_width) < 2, geometry)
        test.check(f"two_row_track_{width}", 245 <= geometry["height"] <= 260, geometry)
        test.check(f"hidden_scrollbar_{width}", page.locator("#module-results").evaluate(
            "node=>getComputedStyle(node).scrollbarWidth==='none'"))
        if width == 1440:
            columns = page.locator(".compact-config>.config-column")
            test.check("runtime_configuration_has_three_columns", columns.count() == 3)
            test.check("configuration_columns_scroll_independently", columns.evaluate_all(
                "nodes=>nodes.every(node=>['auto','scroll'].includes(getComputedStyle(node).overflowY))"))
            test.check("configuration_column_scrollbars_hidden", columns.evaluate_all(
                "nodes=>nodes.every(node=>getComputedStyle(node).scrollbarWidth==='none')"))
            test.check("configuration_columns_have_dividers", columns.evaluate_all(
                "nodes=>getComputedStyle(nodes[1]).borderLeftWidth==='1px'&&getComputedStyle(nodes[2]).borderLeftWidth==='1px'"))
            test.check("runtime_section_uses_business_label", page.locator(".compact-config").locator("xpath=preceding-sibling::*[1]//h2").inner_text().strip().endswith("运行框架"))
            heading_colors = page.locator(".compact-config h2,.compact-config h3").evaluate_all(
                "nodes=>nodes.map(node=>getComputedStyle(node).color)")
            test.check("configuration_heading_colors_match", len(set(heading_colors)) == 1, heading_colors)
            terminal_widths = page.locator(".terminal-section .presentation-options button").evaluate_all(
                "nodes=>nodes.map(node=>node.getBoundingClientRect().width)")
            test.check("terminal_buttons_are_equal_width", len(terminal_widths) == 3
                       and max(terminal_widths)-min(terminal_widths) < 1, terminal_widths)
        if width == 390:
            page.locator("#module-results").evaluate("node=>node.scrollLeft=0")
            first = page.locator("#module-results").evaluate("node=>node.scrollLeft")
            box = page.locator("#module-results").bounding_box()
            cdp = page.context.new_cdp_session(page)
            cdp.send("Emulation.setTouchEmulationEnabled", {"enabled": True, "maxTouchPoints": 1})
            start = {"x": box["x"] + box["width"] * .8, "y": box["y"] + box["height"] * .5}
            end = {"x": box["x"] + box["width"] * .25, "y": start["y"]}
            cdp.send("Input.dispatchTouchEvent", {"type": "touchStart", "touchPoints": [start]})
            for step in range(1, 7):
                point = {"x": start["x"] + (end["x"] - start["x"]) * step / 6, "y": start["y"]}
                cdp.send("Input.dispatchTouchEvent", {"type": "touchMove", "touchPoints": [point]})
            cdp.send("Input.dispatchTouchEvent", {"type": "touchEnd", "touchPoints": []})
            test.check("mobile_touch_swipe_scrolls_track",
                       page.locator("#module-results").evaluate("node=>node.scrollLeft") > first)
            test.check("mobile_touch_swipe_does_not_open_card", not page.locator("#drawer").is_visible())
            page.locator('[data-module-scroll="prev"]').click()
            cdp.send("Emulation.setTouchEmulationEnabled", {"enabled": False})
            cdp.detach()
        page.screenshot(path=str(test.output / f"homepage-{width}.png"), full_page=True)
        test.check(f"no_horizontal_overflow_{width}", page.evaluate("document.documentElement.scrollWidth <= innerWidth + 1"))
        test.check(f"no_side_preview_{width}", page.locator(".builder-preview:visible").count() == 0)
        test.check(f"brand_same_real_favicon_{width}", page.evaluate("""() => {
            const image=document.querySelector('.brand img'), icon=document.querySelector('link[rel="icon"]');
            return !!image && !!icon && image.src===icon.href && image.complete && image.naturalWidth>0;
        }"""))
        if width <= 640:
            page.locator("[data-sidebar-open]").click()
        elif page.locator(".app-shell").evaluate("e=>e.classList.contains('sidebar-collapsed')"):
            page.locator("[data-sidebar-toggle]").focus()
            page.keyboard.press("Enter")
        def brand_clear():
            return page.evaluate("""() => {
                const a=document.querySelector('.brand').getBoundingClientRect();
                const b=document.querySelector('[data-sidebar-toggle]').getBoundingClientRect();
                return a.right<=b.left+1 || b.right<=a.left+1 || a.bottom<=b.top+1 || b.bottom<=a.top+1;
            }""")
        test.check(f"brand_toggle_nonoverlap_expanded_{width}", brand_clear())
        page.screenshot(path=str(test.output / f"navigation-{width}.png"))
        if width <= 640:
            page.keyboard.press("Escape")
            expect(page.locator("[data-sidebar-open]")).to_be_focused()
        else:
            page.locator("[data-sidebar-toggle]").click()
            page.mouse.move(width - 12, 100)
            page.wait_for_timeout(180)
            test.check(f"brand_toggle_overlap_collapsed_{width}", not brand_clear())
            test.check(f"collapsed_shows_fh_only_{width}", page.evaluate("""() => {
                const toggle=document.querySelector('[data-sidebar-toggle]');
                const mark=document.querySelector('.sidebar .brand-mark');
                return getComputedStyle(toggle).opacity==='0' && getComputedStyle(mark).opacity==='1';
            }"""))
            brand_box = page.locator(".sidebar .brand").bounding_box()
            page.mouse.move(brand_box["x"] + brand_box["width"] / 2,
                            brand_box["y"] + brand_box["height"] / 2)
            page.wait_for_timeout(180)
            test.check(f"collapsed_hover_shows_toggle_{width}", page.evaluate("""() => {
                const toggle=document.querySelector('[data-sidebar-toggle]');
                const mark=document.querySelector('.sidebar .brand-mark');
                return getComputedStyle(toggle).opacity==='1' && getComputedStyle(toggle).pointerEvents==='auto'
                    && getComputedStyle(mark).opacity==='0';
            }"""))
            page.locator("[data-sidebar-toggle]").focus()
            test.check(f"collapsed_keyboard_focus_shows_toggle_{width}", page.evaluate("""() => {
                const toggle=document.querySelector('[data-sidebar-toggle]');
                return document.activeElement===toggle && getComputedStyle(toggle).opacity==='1';
            }"""))
            page.keyboard.press("Enter")
        page.locator('[data-action="enlarge-preview"]').click()
        test.focus_trapped(f"modal_keyboard_{width}")
        test.check(f"modal_fits_viewport_{width}", page.locator("#drawer").evaluate(
            "e=>{const r=e.getBoundingClientRect();return r.left>=-1&&r.top>=-1&&r.right<=innerWidth+1&&r.bottom<=innerHeight+1}"))
        test.glyphs_ready(f"modal_glyphs_{width}", "#drawer svg.icon", 4)
        page.screenshot(path=str(test.output / f"preview-{width}.png"))
        page.keyboard.press("Escape")
        expect(page.locator('[data-action="enlarge-preview"]')).to_be_focused()

    def archive_selected_method():
        test.edit("private")
        old = test.fixture["releases"][0]
        before = test.bindings()
        expect(page.locator('[data-action="build"]')).to_be_enabled()
        assets = page.request.get(test.base + "/api/v1/assets").json()["assets"]
        asset = next(a for a in assets if a["assetId"] == old["assetId"])
        csrf = page.request.get(test.base + "/api/bootstrap").json()["csrf"]
        response = page.request.post(test.base + "/api/v1/assets/" + old["assetId"] + "/archive",
                                     data={"revision": asset["revision"]}, headers={"X-CSRF-Token": csrf})
        test.check("actual_owner_archive_succeeds", response.status == 200
                   and response.json()["status"] == "ARCHIVED")
        changes = [p for p, digest in test.fixture["files"].items() if sha(Path(p)) != digest]
        test.check("archive_only_mutates_draft_metadata", len(changes) == 1 and Path(changes[0]).name == "draft.json")
        test.report["authorizedFixtureMutation"] = {"operation": "ARCHIVE", "changedFile": "draft.json"}
        test.fixture["files"][changes[0]] = sha(Path(changes[0]))
        with page.expect_response(lambda r: urlsplit(r.url).path == "/api/v1/assets") as refreshed:
            test.navigate("assets")
        test.check("refresh_observes_archived_asset", any(a["assetId"] == old["assetId"]
                   and a["status"] == "ARCHIVED" for a in refreshed.value.json()["assets"]))
        test.navigate("create")
        test.check("archived_method_ref_not_dropped_or_replaced", test.plan()["methodRef"] == old)
        test.check("archived_method_keeps_packs", test.bindings() == before)
        expect(page.locator('[data-action="build"]')).to_be_disabled()
        expect(page.locator(".build-blockers")).to_be_visible()
        test.check("archive_has_method_specific_blocker", "方法" in page.locator(".build-blockers").inner_text())

    for name, action in (("catalog-pages", catalog_and_pages), ("drawer-transaction", drawer_transaction),
                         ("private-legacy", private_and_legacy), ("method-transaction", method_transaction), ("template-impact", templates),
                         ("preview-three-terminals", previews), ("models-runtime", models_runtime)):
        test.case(name, action)
    for width in (320, 390, 1024, 1440):
        test.case(f"viewport-{width}", lambda width=width: viewport(width))
    test.case("archive-selected-method", archive_selected_method)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    for name in ("harness", "chrome", "output"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--ui-root", type=Path, help="Already bundled workbench assets; no UI/source writes")
    raise SystemExit(verify(parser.parse_args()))
