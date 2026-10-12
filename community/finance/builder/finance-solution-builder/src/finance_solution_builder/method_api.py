"""Owner-scoped method editing. Trial reports originate only in the server runner."""
from __future__ import annotations

import json
import base64
import re
import uuid
from urllib.parse import unquote

from .experience_contracts import ApiError
from .method_assets import write_private, sha, instructions, parse_content, validate_content


def fields(body, required):
    if not isinstance(body, dict) or set(body) != set(required):
        raise ApiError("REQUEST_INVALID", 422)


class MethodApi:
    def __init__(self, jobs):
        self.jobs = jobs
        self.active = {}

    @property
    def store(self):
        return self.jobs.method_assets

    def get(self, path, owner):
        if path == "/api/v1/assets/developer-kit":
            from .developer_kit import developer_kit
            return 200, developer_kit(self.jobs.builder.harness_repo)
        if path == "/api/v1/assets":
            return 200, {"assets": self.store.list(owner), "schemaVersion": "private-method-api.v0.1"}
        parts = path.split("/")
        if len(parts) == 8 and parts[4] == "components" and parts[7] == "contents":
            from .asset_contents import component_contents, development_relations
            from .asset_composition import composition
            return 200, composition(development_relations(component_contents(self.jobs, owner, unquote(parts[5]), unquote(parts[6])), self.jobs.builder.harness_repo))
        if len(parts) == 9 and parts[5] == "packages" and parts[8] == "contents":
            from .asset_contents import release_contents, development_relations
            from .asset_composition import composition
            return 200, composition(development_relations(release_contents(self.jobs, owner,
                {"assetId": unquote(parts[4]), "version": unquote(parts[6]), "releaseDigest": unquote(parts[7])}), self.jobs.builder.harness_repo))
        if len(parts) == 8 and parts[5] == "packages":
            from .business_packages import export_release
            reference = {"assetId": unquote(parts[4]), "version": unquote(parts[6]), "releaseDigest": unquote(parts[7])}
            release, trial = self.store.release_trial(owner, reference)
            return 200, export_release(
                release,
                trial,
                self.jobs.builder.harness_repo,
                self.jobs.business_package_anchor,
            )
        if len(parts) not in {5, 6}: raise ApiError("NOT_FOUND", 404)
        asset = self.store.get(owner, parts[4])
        if len(parts) == 5: return 200, asset
        if parts[5] not in {"trial", "preview"}: raise ApiError("NOT_FOUND", 404)
        file = self.store._folder(owner, parts[4]) / (parts[5] + "-job.json")
        if not file.is_file(): return 200, {"status": "NOT_RUN"}
        with self.jobs.lock:
            value = json.loads(file.read_text(encoding="utf-8"))
            if value["status"] in {"QUEUED", "RUNNING"} and self.active.get((owner, parts[4])) != value["trialJobId"]:
                value["status"] = "INTERRUPTED"
        return 200, value

    def post(self, path, body, owner, workbench):
        if path in {"/api/v1/assets/package-preview", "/api/v1/assets/package-import"}:
            from .business_packages import draft_from_archive
            expected = {"filename", "base64", "name"}
            if path.endswith("package-import") and isinstance(body, dict) and "data" in body: expected.add("data")
            fields(body, expected)
            if not isinstance(body["filename"], str) or not body["filename"].lower().endswith(".zip"):
                raise ApiError("BUSINESS_PACKAGE_IMPORT_TYPE_UNSUPPORTED", 422)
            try: raw = base64.b64decode(body["base64"], validate=True)
            except (ValueError, TypeError): raise ApiError("BUSINESS_PACKAGE_UPLOAD_INVALID", 422) from None
            result = draft_from_archive(
                raw,
                body["name"],
                self.jobs.builder.harness_repo,
                body.get("data"),
                self.jobs.business_package_anchor,
            )
            if path.endswith("preview"):
                return 200, {**result, "validation": validate_content(result["data"])}
            created = self.store.create(owner, result["data"], original=(raw, body["filename"]), source=result["source"])
            return 201, created
        if path in {"/api/v1/assets/customize-import-preview", "/api/v1/assets/customize-import"}:
            from .business_assets import ancestry
            keys = {"componentId", "directoryRevision", "name", "filename", "base64"}
            if not path.endswith("-preview"): keys.add("data")
            fields(body, keys)
            binding, _ = ancestry(self.jobs.project, self.jobs.builder.harness_repo,
                                  body["componentId"], body["directoryRevision"])
            imported = self.store.import_asset(owner, body["filename"], body["base64"], body["name"], preview=True)
            source = {"kind": "BUSINESS_DERIVED", "binding": binding,
                      "importedFrom": imported["source"]}
            data = parse_content(body.get("data", {**imported["data"], "capabilityIds": binding["capabilityIds"]}))
            if sorted(data["capabilityIds"]) != binding["capabilityIds"]:
                raise ApiError("ASSET_BASE_CHANGED", 409)
            if path.endswith("-preview"):
                return 200, {"data": data, "source": source, "validation": validate_content(data)}
            return 201, self.store.create(owner, data, source=source,
                original=(base64.b64decode(body["base64"], validate=True), body["filename"]))
        if path in {"/api/v1/assets/customize-preview", "/api/v1/assets/customize"}:
            from .business_assets import ancestry, development_support_for_binding
            from .development_support import case_provenance
            from .method_assets import canonical
            keys = {"componentId", "directoryRevision", "name"}
            if path.endswith("/customize"): keys.add("data")
            if isinstance(body, dict):
                keys.update(k for k in ("data", "scope") if k in body)
            fields(body, keys)
            binding, text = ancestry(self.jobs.project, self.jobs.builder.harness_repo,
                                     body["componentId"], body["directoryRevision"])
            source = {"kind": "BUSINESS_DERIVED", "binding": binding}
            supplied = body.get("data", {"name": body["name"], "content": text,
                                        "capabilityIds": binding["capabilityIds"]})
            if not isinstance(supplied, dict):
                raise ApiError("ASSET_CONTENT_INVALID", 422)
            warnings = []
            if "cases" in supplied:
                data = parse_content(supplied)
                source["caseProvenance"] = {"schemaVersion": "finance-method-case-provenance.v0.1",
                    "kind": "USER_PROVIDED", "casesDigest": sha(canonical(data["cases"]))}
                if data["cases"]:
                    # The editor submits all cases, not a trusted preview identity.
                    try:
                        support = development_support_for_binding(self.jobs.builder.harness_repo, binding, body.get("scope"))
                    except ApiError as error:
                        if not error.code.startswith("DEVELOPMENT_SUPPORT_"):
                            raise
                        support = None
                    if support and support["sources"] and data["cases"] == support["methodCases"]:
                        source["caseProvenance"] = case_provenance(support)
                        warnings = support["warnings"]
            else:
                support = development_support_for_binding(self.jobs.builder.harness_repo, binding, body.get("scope"))
                data = parse_content({**supplied, "cases": support["methodCases"]})
                source["caseProvenance"] = case_provenance(support)
                warnings = support["warnings"]
            if not data["cases"] and not warnings:
                warnings = [{"code": "ASSET_CASE_REQUIRED", "message": "请补充验证案例"}]
            if sorted(data["capabilityIds"]) != binding["capabilityIds"]:
                raise ApiError("ASSET_BASE_CHANGED", 409)
            if path.endswith("-preview"):
                return 200, {"data": data, "source": source, "validation": validate_content(data), "warnings": warnings}
            return 201, {**self.store.create(owner, data, source=source), "warnings": warnings}
        if path == "/api/v1/assets":
            fields(body, {"data"})
            return 201, self.store.create(owner, body["data"])
        if path in {"/api/v1/assets/import", "/api/v1/assets/import-preview"}:
            preview = path.endswith("-preview")
            keys = {"filename", "base64", "name"}
            if not preview and isinstance(body, dict) and "data" in body: keys.add("data")
            fields(body, keys)
            result = self.store.import_asset(owner, body["filename"], body["base64"],
                    body["name"], data=body.get("data"), preview=preview)
            if preview:
                from .skill_analysis import analyze_skill
                result["analysis"] = analyze_skill(result["data"]["content"], self.jobs.builder.harness_repo)
            return (200 if preview else 201), result
        if path in {"/api/v1/assets/copy-default", "/api/v1/assets/example-preview"}:
            preview = path.endswith("example-preview")
            keys = {"capabilityId", "name"}
            if not preview and isinstance(body, dict) and "data" in body: keys.add("data")
            fields(body, keys)
            mapping = {"wealth.facts.describe": "wealth-facts.md", "account.balance.query": "account-balance.md"}
            capability = body["capabilityId"]
            if not isinstance(capability, str) or capability not in mapping:
                raise ApiError("ASSET_CAPABILITY_UNSUPPORTED", 422)
            text = (
                self.jobs.project
                / "templates/local-readonly-service/methods"
                / mapping[capability]
            ).read_text(encoding="utf-8")
            sources = [{"source": "builder-template://methods/" + mapping[capability], "digest": sha(text.encode())}]
            if capability == "wealth.facts.describe":
                relative = "domain-packs/wealth/shared-facts/skills/wealth-facts-description/SKILL.md"
                method = (self.jobs.builder.harness_repo / relative).read_text(
                    encoding="utf-8"
                )
                sources.append({"source": "repo://" + relative, "digest": sha(method.encode())})
                text += "\n\n" + instructions(method)
            data = parse_content({"name": body["name"], "content": text,
                "capabilityIds": [capability], "cases": [{"name": "只读事实核验",
                "query": "请查询我的账户余额" if capability == "account.balance.query" else "请查询我的资产概览",
                "expectedTerms": []}]} if "data" not in body else body["data"])
            source = {"kind": "COPIED_DEFAULT", "references": sources}
            if preview: return 200, {"data": data, "validation": validate_content(data), "source": source}
            return 201, self.store.create(owner, data, source=source)
        parts = path.split("/")
        if len(parts) != 6 or not re.fullmatch(r"method-[a-f0-9]{16}", parts[4]):
            raise ApiError("NOT_FOUND", 404)
        asset_id, action = parts[4:]
        self.store.get(owner, asset_id)
        if action in {"delete", "archive", "restore"}:
            fields(body, {"revision"})
            with self.jobs.lock:
                if (owner, asset_id) in self.active: raise ApiError("ASSET_TRIAL_ACTIVE", 409)
                referenced = any(job.get("owner") == owner and job.get("methodRef", {}).get("assetId") == asset_id
                                 for job in self.jobs.jobs.values())
                return 200, self.store.lifecycle(owner, asset_id, body["revision"], action, referenced=referenced)
        if action == "save":
            fields(body, {"revision", "data"})
            return 200, self.store.update(owner, asset_id, body["revision"], body["data"])
        if action == "validate":
            fields(body, {"revision"})
            draft = self.store.get(owner, asset_id)
            if type(body["revision"]) is not int or body["revision"] != draft["revision"]:
                raise ApiError("ASSET_REVISION_CONFLICT", 409)
            self.store.check_source(draft)
            return 200, {"revision": draft["revision"], "contentDigest": draft["contentDigest"], **draft["validation"]}
        if action in {"trial", "preview"}:
            fields(body, {"revision"} if action == "trial" else {"revision", "modelRef", "query"})
            draft = self.store.trial_input(owner, asset_id, body["revision"])
            if action == "preview":
                from .model_registry import ModelRegistry
                if (not isinstance(body["modelRef"], str) or not isinstance(body["query"], str)
                        or not 1 <= len(body["query"].strip()) <= 1000): raise ApiError("REQUEST_INVALID", 422)
                if ModelRegistry().status(body["modelRef"]) != "CONFIGURED_UNTESTED":
                    raise ApiError("MODEL_CONNECTION_UNAVAILABLE", 409)
            with self.jobs.lock:
                key = (owner, asset_id)
                # Recheck under the same lock as archive/delete and trial registration.
                draft = self.store.trial_input(owner, asset_id, body["revision"])
                if self.jobs.closing: raise ApiError("WORKBENCH_STOPPING", 409)
                if key in self.active or len(self.active) >= 3: raise ApiError("ASSET_TRIAL_QUEUE_FULL", 409)
                job = {"trialJobId": "method-trial-" + uuid.uuid4().hex[:16], "status": "QUEUED",
                       "revision": draft["revision"], "contentDigest": draft["contentDigest"]}
                file = self.store._folder(owner, asset_id) / (action + "-job.json")
                write_private(file, job)
                self.active[key] = job["trialJobId"]
                try:
                    if action == "trial": self.jobs.queue.submit(self._trial, owner, draft, job, file)
                    else: self.jobs.queue.submit(self._preview, owner, draft, job, file, body["modelRef"], body["query"])
                except Exception:
                    self.active.pop(key, None)
                    job.update(status="FAILED", error="ASSET_TRIAL_QUEUE_UNAVAILABLE")
                    write_private(file, job)
                    raise ApiError("ASSET_TRIAL_QUEUE_UNAVAILABLE", 503) from None
                return 202, job
        if action == "publish":
            expected = {"revision", "version", "trialId"}
            if isinstance(body, dict) and "bindingConfirmations" in body: expected.add("bindingConfirmations")
            fields(body, expected)
            return 201, self.store.publish(owner, asset_id, body["revision"], body["version"], body["trialId"],
                                           body.get("bindingConfirmations"))
        if action == "package-plan":
            fields(body, {"revision", "trialId"})
            draft = self.store.trial_input(owner, asset_id, body["revision"])
            trial = draft.get("trial")
            if not trial or trial.get("trialId") != body["trialId"]:
                raise ApiError("ASSET_TRIAL_NOT_CURRENT", 409)
            from .business_packages import binding_plan
            return 200, binding_plan(
                draft,
                trial,
                self.jobs.builder.harness_repo,
                self.jobs.business_package_anchor,
            )
        if action == "apply":
            fields(body, {"version", "releaseDigest", "selection"})
            reference = {"assetId": asset_id, "version": body["version"], "releaseDigest": body["releaseDigest"]}
            self.store.release(owner, reference)
            if not isinstance(body["selection"], dict): raise ApiError("SELECTION_INVALID", 422)
            selection = {**body["selection"], "schema_version": "0.7", "methodRef": reference}
            return workbench.post("/api/v1/builds", selection, owner)
        raise ApiError("NOT_FOUND", 404)

    def _trial(self, owner, draft, job, file):
        from .method_trials import run_trial
        try:
            job = {**job, "status": "RUNNING"}; write_private(file, job)
            report = run_trial(self.jobs, owner, draft)
            trial = self.store.record_trial(owner, draft["assetId"], draft, report)
            job.update(status="COMPLETED", trial=trial)
        except Exception as error:
            code = error.code if isinstance(error, ApiError) else "ASSET_TRIAL_FAILED"
            job.update(status="FAILED", error=code)
        finally:
            with self.jobs.lock:
                write_private(file, job)
                self.active.pop((owner, draft["assetId"]), None)

    def _preview(self, owner, draft, job, file, model_ref, query):
        from .method_trials import run_preview
        try:
            job = {**job, "status": "RUNNING"}; write_private(file, job)
            result = run_preview(self.jobs, owner, draft, model_ref, query)
            job.update(status="COMPLETED", preview=result)
        except Exception as error:
            code = error.code if isinstance(error, ApiError) else "ASSET_MODEL_PREVIEW_FAILED"
            job.update(status="FAILED", error=code)
        finally:
            with self.jobs.lock:
                write_private(file, job)
                self.active.pop((owner, draft["assetId"]), None)
