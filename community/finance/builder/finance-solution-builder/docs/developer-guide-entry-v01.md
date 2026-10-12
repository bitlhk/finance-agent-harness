# Hosted Developer Guide Entry V0.1

The authenticated Studio sidebar links to `/developer-guide/` in a new tab. The
current workspace, build selection and Agent conversations are not replaced.
On desktop the entry sits at the bottom left; tablet keeps the existing icon-only
navigation with a tooltip, and mobile keeps a visible link below the main navigation.
Local-only mode does not advertise an edge route that has not been installed.

## Publication Boundary

The complete 2026-09-08 offline guide contains internal source references, Builder
appendices and historical deployment details. It is **not a public-safe export**.
The approved publication scope is **public redacted guide; authenticated workbench
and internal archive**. `deploy/server/developer-guide.locations.conf` disables Basic
auth only on the exact public document routes, not on the virtual host or proxy.

| Route | Content | Access |
| --- | --- | --- |
| `/developer-guide/` or `/developer-guide/index.html` | Reviewed ten-chapter guide, no source appendix | Anonymous |
| `/developer-guide/handbook.pdf` | PDF of the same public guide | Anonymous |
| `/developer-guide/internal/` | Full offline reader with internal references | Existing Studio login |
| `/developer-guide/internal/handbook.pdf` | Full internal handbook | Existing Studio login |
| `/`, API and instance operations | Workbench, configuration and Agent operations | Existing Studio login |

The public guide is authored separately in Harness `docs/public/developer-guide.md`
and exported by `engineering/offline-docs/build-public.mjs`. It is not a partially
hidden full archive: the HTML contains only that single reviewed document. Automated
checks are a backstop, not a substitute for publication review.

The Studio login also determines build ownership. Disabling Basic auth and injecting
a shared operator name is not anonymous-user isolation: it would expose shared build
records and allow quota consumption or destructive instance management. Public guide
access, limited Agent experience and authenticated management require separate
access decisions. A public guide should omit private source/history/deployment
appendices. None of these can grant bank access or bypass the per-turn Harness checks.

## Install Static Assets

On the TLS edge, put only the reviewed `index.html` and renamed `handbook.pdf` in a
root-owned, read-only version directory under `/srv/finance-studio/developer-guide/`.
Keep separate root-owned links: `public-current` selects the reviewed public release;
`current` selects the full authenticated release. Include the fragment inside the
existing Studio HTTPS virtual host. Never point `public-current` at the full export.
Do not expose `.git`, the Markdown/source export directory, manifest, credentials or
another application directory. Only exact HTML/PDF routes are served; unknown paths
return 404 and write methods are denied. Trailing-slash paths internally rewrite to
exact file routes, avoiding Nginx's directory-index handling on file aliases.

Back up this virtual host, validate with `nginx -t`, then reload Nginx. Keep existing
authentication, proxy headers, model configuration, ownership keys and business ports
unchanged. UI assets are loaded from disk on each request, so deploying `app.js` and
`live.css` does not require restarting Builder or generated Agents. Back up and verify
source/runtime asset digests before replacement, and retain the previous pair for rollback.

Source UI is `browser/workbench.js`; rebuild its tracked bundle with `npm ci` and
`npm run build:ui`. Do not patch the minified bundle by hand. The documentation snapshot
remains a separately versioned reading asset, not a new Harness/Agent runtime release.

## Read-Only Verification

```sh
node engineering/verify_developer_guide.cjs \
  /private/path/access.json build/guide-ui-check
python3 engineering/verify_public_guide_access.py \
  /private/path/access.json /path/to/public-export /path/to/internal-export \
  build/guide-access-check.json
```

The private 0600 file has `url`, `username`, `password`; never put those values into
logs, Git or browser URLs. Set `PLAYWRIGHT_MODULE` to a installed Playwright CommonJS
entry if `@playwright/test` is not available. An optional third argument previews a
local HTML export while intercepting the two UI assets before deployment.

The check reads metadata and opens documents only; it does not build, start, delete,
send chat messages, call the model or query bank business data. It checks desktop,
tablet, mobile and narrow layouts, new-tab isolation and page errors. The access check
compares public/internal file digests, anonymous denials and exact routes. It also sends
a rejected unauthenticated build POST and denied document writes; it never creates
a build or issues authenticated business mutations. Harness's `verify-public.mjs`
checks anonymous reading, chapter navigation and absence of external requests. This
is not human independent onboarding or a new financial business acceptance result.
