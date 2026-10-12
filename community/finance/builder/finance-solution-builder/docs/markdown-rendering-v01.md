# Markdown Rendering V0.1

AG-UI carries messages and lifecycle events; it does not format Markdown. Previously
`messages()` escaped assistant text and displayed it with `white-space: pre-wrap`.
That preserved safety but left headings, emphasis, lists and table syntax visible.

This change belongs only to the Finance Studio frontend. The EA reference uses
ReactMarkdown, remark-gfm and styled renderers. Studio is vanilla JavaScript, so it
uses pinned Marked and DOMPurify, not a copy of EA's React component or business code.
No financial contracts, prompts, authorization, AG-UI payloads or Agent JARs change.

## Implementation

- Edit `browser/workbench.js` (the former unbundled `examples/local-workbench/app.js`).
- `browser/markdown.js` parses GFM and sanitizes a limited HTML vocabulary. Raw HTML
  is literal text. Model images do not automatically fetch remote or local URLs.
  Unsupported link protocols are disabled; supported links use no-referrer and
  noopener/noreferrer. Task-list checkboxes are disabled.
- Headings are 16-18px, paragraphs and nested lists have dedicated spacing. Table
  viewports and fenced code scroll horizontally without widening the phone page.
- User messages remain escaped plain text. Original assistant text and evidence
  remain unchanged; only presentation is transformed. Revocation clears rendered
  content through the existing session path.
- `npm ci && npm run build:ui` produces checked-in `examples/local-workbench/app.js`.
  The existing static URL is preserved, so an idle build workspace can publish
  this frontend update without restarting the server or running user Agents.
  `npm run build:transport` remains separate and unchanged.

Marked explicitly does not sanitize HTML. This implementation follows its
[security guidance](https://marked.js.org/#usage) by using
[DOMPurify](https://github.com/cure53/DOMPurify) before inserting parsed content.
This is not HTML application execution, chart rendering or mathematical typesetting.
Model-generated source links are not automatically verified citations.

## Verification

```bash
NODE_PATH=<playwright-node-modules> node engineering/verify_markdown.cjs build/md-check
.venv/bin/python -m pytest tests -q
```

The browser test drives the actual UI and official AG-UI client using controlled
API/SSE responses. It covers formatting, nested lists, zero/amount preservation,
long code, read-only task lists, source links, HTML/XSS cases, no automatic image
requests, plain text, evidence preservation, and revoke cleanup. Screenshots and
horizontal-scroll checks use 320px, 390px and 1440px viewports.

The `--baseline --baseline-ref <available-plain-text-revision>` option runs a previous
plain-text application present in the checkout and must fail `ui_headings`. The
upstream squash does not include the private development history. This is a rendering
regression test, not a new model eval.
The original draft of the test used an insecure test origin, which prevented the
AG-UI client's browser UUID API. The corrected test uses HTTPS and retains those
failed probe records separately.

Optional deployed-static verification:

```bash
NODE_PATH=<playwright-node-modules> node engineering/verify_markdown.cjs build/md-public \
  --public-access-file <private-access-json>
```

This fetches the live HTML/JS/CSS but intercepts every API request with synthetic
responses. It does not open, revoke, chat in, or stop a user's server session, and
must not be reported as another live-model or banking validation.

## 2026-09-07 Result

Implementation deployed: `a970c87b075ee6259bcacbe278a14159cdb130db` to Shanghai's
existing Builder release. No Python backend, AG-UI transport or Java artifact changed.
The workbench, active user Agent and EA kept their original PIDs. No active build
was present during publication; no user's session was opened, cleared or revoked.

- Builder Python: 193 passed.
- Local browser: 34 checks passed (`build/markdown-final/report.json`).
- Live static assets with controlled API: 34 checks passed
  (`build/markdown-public/report.json`), no JS errors or unexpected requests.
- Previous committed UI: fails `ui_headings` with the same controlled sample
  (`build/markdown-before-02/report.json`).
- Local/server app.js SHA256:
  `5776635b33422d541c4d46bf38c20b970f14782f834f97709687363949ac65e8`.
- Local/server live.css SHA256:
  `d2caa4b87fdd13c6bb1580d4bd2a0857c606c5c9e50635471c1c7ef2e2bd1c4b`.
- Dependency audit: no known vulnerabilities reported for the pinned dependency set.

Screenshots of headings and scrolled tables were inspected on desktop and mobile.
Full dependency licenses are included in the generated JS banner. This UI-only
rollout does not certify new live-model output quality or change the earlier
deployment manifest's historical artifact identity. Builder upstream acceptance
remains separate; this commit is in the local development and deployed Git copies.
