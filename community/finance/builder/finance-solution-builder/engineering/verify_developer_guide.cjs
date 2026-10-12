const fs = require("node:fs");
const path = require("node:path");
const assert = require("node:assert/strict");
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || "@playwright/test");

(async () => {
  const [accessFile, output, previewGuide] = process.argv.slice(2);
  const access = JSON.parse(fs.readFileSync(accessFile, "utf8"));
  assert.equal(fs.statSync(accessFile).mode & 0o077, 0, "PRIVATE_ACCESS_FILE_REQUIRED");
  const base = new URL(access.url).origin;
  const project = path.resolve(__dirname, "..");
  const browser = await chromium.launch({ headless: true });
  const evidence = { mode: previewGuide ? "STATIC_PATCH_PREVIEW" : "DEPLOYED_READ_ONLY_UI_CHECK", checks: [], mutations: [], errors: [] };
  const check = (name, passed) => { assert.ok(passed, name); evidence.checks.push(name); };
  fs.mkdirSync(output, { recursive: true });
  try {
    const context = await browser.newContext({ httpCredentials: { username: access.username, password: access.password } });
    const page = await context.newPage();
    page.on("pageerror", error => evidence.errors.push(error.message));
    context.on("request", request => { if (request.method() !== "GET" && request.method() !== "HEAD") evidence.mutations.push({ method: request.method(), path: new URL(request.url()).pathname }); });
    if (previewGuide) {
      for (const name of ["app.js", "live.css"]) {
        await context.route(`${base}/${name}`, route => route.fulfill({ status: 200,
          contentType: name.endsWith("js") ? "application/javascript" : "text/css",
          body: fs.readFileSync(path.join(project, "examples/local-workbench", name)) }));
      }
      await context.route(`${base}/developer-guide/`, route => route.fulfill({ status: 200,
        contentType: "text/html; charset=utf-8", body: fs.readFileSync(previewGuide) }));
    }
    for (const [name, width, height] of [["desktop", 1440, 1000], ["tablet", 820, 1000], ["mobile", 390, 844], ["narrow", 320, 740]]) {
      await page.setViewportSize({ width, height });
      await page.goto(base, { waitUntil: "networkidle" });
      const link = page.getByRole("link", { name: "开发指导" });
      await link.waitFor();
      check(`${name}:link_visible`, await link.isVisible());
      check(`${name}:new_tab_preserves_workspace`, await link.getAttribute("target") === "_blank" && (await link.getAttribute("rel")).includes("noopener"));
      check(`${name}:page_fits`, await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
      check(`${name}:icon_available`, await page.evaluate(() => {
        const use = document.querySelector(".developer-guide-link use");
        return use && use.getAttribute("href") === "/icons.svg#file-check-2" && document.querySelector(".developer-guide-link svg").getBoundingClientRect().width > 0;
      }));
      if (name === "desktop") check("desktop:bottom_left", await link.evaluate(el => {
        const rect = el.getBoundingClientRect(); return rect.left < 220 && rect.top > innerHeight * .7;
      }));
      await page.screenshot({ path: path.join(output, `${name}.png`), fullPage: false });
      const popupPromise = context.waitForEvent("page");
      await link.click();
      const guide = await popupPromise;
      guide.on("pageerror", error => evidence.errors.push(error.message));
      await guide.locator("#article h1").waitFor();
      check(`${name}:guide_loaded`, (await guide.locator("#article h1").innerText()).includes("Finance Harness"));
      check(`${name}:opener_isolated`, await guide.evaluate(() => window.opener === null));
      if (name === "desktop") await guide.screenshot({ path: path.join(output, "guide.png"), fullPage: false });
      await guide.close();
    }
    check("no_build_model_or_bank_mutations", evidence.mutations.length === 0);
    check("no_browser_errors", evidence.errors.length === 0);
    await context.close();
    fs.writeFileSync(path.join(output, "verification.json"), JSON.stringify(evidence, null, 2));
    console.log(JSON.stringify({ mode: evidence.mode, checks: evidence.checks.length, mutations: evidence.mutations.length, errors: evidence.errors }, null, 2));
  } finally { await browser.close(); }
})().catch(error => { console.error(error.message); process.exitCode = 1; });
