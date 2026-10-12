# Device Preview Release: 2026-09-10

## Delivery and Compatibility

The protected Finance Studio demonstration now serves Builder
`283e6d52c813394d99a5e3806287ace9af798629` from branch
`feat/studio-terminal-ui-v01`. The branch was pushed to the existing Builder
repository. This is not a claim that its upstream merge request was merged.

The release contains phone/laptop/ATM Builder previews, enlargement, compact
module cards and shared balance-card presentation. Decorative device shells are
not included in the generated Agent interface. No Core, Provider, Runtime,
authorization or bank capability was changed in this UI release. The 26-entry
catalog still exposes only the two previously connected read-only modules.

| Participating component | Actual version |
| --- | --- |
| Deployed Builder | `283e6d52c813394d99a5e3806287ace9af798629`, clean checkout |
| Previous Builder, retained for rollback | `a8064117996cd7ebb591550959d018ce9607a933` |
| Harness | `b55d0722cac2905388f57dc4a17b85d346a0e470`, unchanged |
| Runtime | OpenJiuwen Java `0.1.13`, unchanged |
| Model in the live smoke | Server-configured `deepseek-v4-flash` |
| Data in the live smoke | Existing full non-ai-banking simulator, synthetic data |
| Speech in the live smoke | Existing XFYUN IAT connection |

The final evidence commit after `283e6d52` only updates documentation and the
preview verifier's SVG-render wait. It is not the deployed checkout. The served
JS/CSS/icon hashes were compared against the clean deployed source, independently
of the preview verifier's local-file hash fields.

## Verification Results

| Verification | Observed result | Boundary |
| --- | --- | --- |
| Fresh server Python suite | 389 passed, 0 failed/skipped; 10.11 s | Builder suite, not human acceptance |
| Harness Maven build during generation | 172 tests: 165 passed, 7 explicit real-environment smoke skips | Existing Harness revision, not production banking validation |
| Generated Candidate Java tests | 9 passed per Candidate | Five clean-source builds |
| Portable service/browser checks | 118 passed: mobile 26, desktop 25, ATM 25, alternate theme 25, wealth-only 17 | Controlled model and controlled bank HTTP |
| Artifact integrity | All locked files checked: 144 per Candidate, wealth-only 143 | Build lock, service lock, observed artifact lock and stage logs checked |
| Authenticated live preview | 304 passed; 1440/1024/736/390/320 widths; all three terminals; no page errors | Preview does not call a model, bank or build endpoint |
| HTTP-served UI assets | JS, CSS and icons match deployed source hashes | Authenticated HTTPS bytes, not just local source |
| New page-built Agent | 19 checks passed with actual model and ASR | Full synthetic bank simulator; not a professional-quality evaluation |
| Old Agent preservation | Eight prior records and seven prior locks unchanged | New total: nine records, eight locks |
| Anonymous access | Workbench 401; public developer guide 200 | Existing protection retained |
| Independent human use / real bank authorization | NOT_RUN | Separate acceptance required |

The five accepted builds used a clean, frozen `283e6d52` Git checkout. An earlier
desktop attempt was rejected because the source changed while generation was in
progress; its successful stage logs did not override the dirty-source gate. That
attempt is retained as rejected evidence, not included among accepted Candidates.

Initial authenticated screenshots were captured before external SVG references
had painted. The final verifier waits for nonzero rendered glyph bounds before
each capture; all 45 glyph checks passed in the 304-check run. No CSP, origin or
authentication protection was disabled. The final preview report retains
`deployment: NOT_RUN` because that checker does not perform deployment; it is not
the deployment status of this release. Its target was the authenticated live site.

## Live Agent

The browser created `agent-66c10fcd29794cc2`, named **金融助手（设备新版）**. The observed
build took 29.92 seconds in the existing cached server environment; no comparable
baseline exists, so this is not an efficiency improvement claim.

The actual configured model queried balances, followed with wealth facts in the
same session, and answered an explicitly sent question after a speech edit. The
checks compared the deterministic balance display, decimal strings, zero USD,
source and scope; they did not certify every model-generated sentence. In
particular, free-text risk labels still require separate quality evaluation and
do not replace the source's unspecified rating scheme or suitability decision.

XFYUN returned HTTP 200 using an official reference WAV delivered through a
browser fake microphone. Recognition remained editable and was not automatically
sent. This verifies the real ASR endpoint and UI wiring, not a person's physical
microphone. The test session was explicitly ended and cleared; the new instance
was left running for demonstration.

Existing Agents keep their original immutable assets. To see the new shared
cards, open the new demonstration Agent or build a new version. A service restart
can stop an old running process without deleting its record or lock; an old Agent
can be restarted through Studio.

## Evidence and Reproduction

- [Five artifact checks](device-polish-evidence/release/artifact-verification.json)
- [Authenticated served assets](device-polish-evidence/release/live-assets.json)
- [New live lock and preservation checks](device-polish-evidence/release/live-lock-verification.json)
- [Final live preview checks](device-polish-evidence/release/preview-verification.json)
- [Live model/ASR check summary](device-polish-evidence/release/live-summary.json)

Representative settled screenshots: [desktop](device-polish-evidence/release/1440-desktop_web-result-preview.png),
[ATM](device-polish-evidence/release/736-atm_web-result-preview.png),
[320-pixel phone viewport](device-polish-evidence/release/320-mobile_web-result-preview.png).

Run the commands in [the implementation guide](device-polish-v1.md) from this
Builder module. For a protected site, supply `STUDIO_TEST_USER` and
`STUDIO_TEST_PASSWORD` through a private process environment, not CLI arguments
or checked-in configuration:

```bash
python engineering/verify_device_polish.py https://finance-harness.demo.linggan.top \
  /path/to/new-preview-evidence --chromium /path/to/chrome \
  --widths 1440 1024 736 390 320
```

The full local evidence is retained under
`/home/ubuntu/finance-device-polish-evidence-20260910/release-stage/`, including
`verified-generated`, `verified-controlled`, `live` and `live-preview-settled`.
These are maintainer-local paths, not public download endpoints. The release
archive is 67 MB on the server; generated Candidates have additional dependency
costs and should be retained/cleaned by explicit build ID, not by deleting old
locks indiscriminately.

## Operations and Rollback

The new release directory is
`/opt/finance-studio/releases/20260910-device-283e6d52/builder`, selected by the
existing Builder symlink and `finance-studio.service`. Server model, bank and
speech configuration remained outside the source and generated artifacts.

Before switching, the prior symlink and service drop-in were recorded. A guarded
rollback script is retained on the deployment host:

```bash
bash /opt/finance-studio/rollback/20260910-device-polish/rollback.sh
```

Rollback was prepared but not executed. It restores the preceding service code;
it does not migrate or erase newly generated Agent records. The previous release
and the before/after lock inventories are retained in that rollback directory.
No EA deployment, upstream merge, real transfer or customer-data migration was
performed.
