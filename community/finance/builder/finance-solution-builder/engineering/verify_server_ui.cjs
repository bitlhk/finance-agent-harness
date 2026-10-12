const { chromium } = require("@playwright/test");
const path = require("path");

(async () => {
  const [base, output] = process.argv.slice(2);
  const browser = await chromium.launch({ headless: true });
  try {
    const context = await browser.newContext({
      ignoreHTTPSErrors: true,
      httpCredentials: { username: "alice", password: process.env.FH_TEST_BASIC_PASSWORD },
    });
    const page = await context.newPage();
    const errors = [];
    page.on("pageerror", (error) => errors.push(error.message));
    for (const [label, width, height] of [["desktop", 1440, 960], ["mobile", 390, 844]]) {
      await page.setViewportSize({ width, height });
      await page.goto(base, { waitUntil: "networkidle" });
      await page.getByRole("button", { name: "开始构建", exact: true }).waitFor();
      if (!(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth))) {
        throw new Error("PAGE_OVERFLOW_" + label);
      }
      await page.screenshot({ path: path.join(output, "server-" + label + ".png"), fullPage: true });
    }
    if (errors.length) throw new Error("BROWSER_SCRIPT_ERROR");
    await context.close();
  } finally {
    await browser.close();
  }
})().catch((error) => { console.error(error.message); process.exitCode = 1; });
