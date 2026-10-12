/* Browser checks against the real local API. Optional controlled mode performs a real build. */
const { chromium } = require("@playwright/test");
const fs = require("node:fs");
const path = require("node:path");
const assert = require("node:assert/strict");

(async () => {
  const [base, output, mode = "metadata"] = process.argv.slice(2);
  fs.mkdirSync(output, { recursive: true });
  const browser = await chromium.launch({ headless: true });
  const context = await browser.newContext({
    viewport: { width: 1440, height: 1000 },
  });
  const page = await context.newPage();
  // Observe the actual fetch body; Chromium may evict streamed bodies from CDP.
  await page.addInitScript(() => {
    window.__studioStreams = [];
    const fetch = window.fetch.bind(window);
    window.fetch = async (...args) => {
      const response = await fetch(...args);
      if (response.url.endsWith('/ag-ui')) response.clone().text().then(body => window.__studioStreams.push({status:response.status,body}));
      return response;
    };
  });
  const errors = [],
    checks = [],
    apiObservations = [];
  page.on("pageerror", (e) => errors.push(e.message));
  page.on("response", async (response) => {
    const pathname = new URL(response.url()).pathname;
    if (!pathname.startsWith("/api/")) return;
    try {
      if (response.headers()["content-type"]?.includes("text/event-stream")) {
        const { BaseEventSchema } = require("@ag-ui/core");
        const events = (await response.text()).split("\n")
          .filter((line) => line.startsWith("data: "))
          .map((line) => JSON.parse(line.slice(6)));
        for (const event of events) BaseEventSchema.parse(event);
        apiObservations.push({path: pathname, status: response.status(),
          eventTypes: events.map((event) => event.type)});
        return;
      }
      const data = await response.json();
      apiObservations.push({
        path: pathname,
        status: response.status(),
        code: data.error?.code,
        clearConversation: data.clearConversation,
      });
    } catch {
      /* Ignore responses interrupted by navigation or shutdown. */
    }
  });
  async function check(name, value) {
    assert.ok(value, name);
    checks.push(name);
  }
  async function screenshot(name) {
    await page.screenshot({
      path: path.join(output, name + ".png"),
      fullPage: !(await page.locator("dialog").isVisible()),
      animations: "disabled",
    });
  }
  try {
    await page.goto(base);
    await page.locator('[data-scenario="customer-wealth-readonly"]').waitFor();
    await check("initial_no_implicit_selection", await page.locator('[data-action="build"]').isDisabled());
    await page.locator('[data-scenario="customer-wealth-readonly"]').click();
    await check(
      "initial_selected_capabilities",
      (await page.locator(".selected-cap").count()) === 2,
    );
    await screenshot("01-create-desktop");
    await page.locator('[data-cap="account.balance.query"]').click();
    await check(
      "inline_capability_checkbox_changes_preview",
      (await page.locator(".preview-app-body .suggestion").count()) === 1,
    );
    await page.locator('[data-catalog="caps"]').first().click();
    await page.locator('[data-pick="account.balance.query"]').check();
    await page.getByRole("button", {name:"保存选择",exact:true}).click();
    await page
      .getByRole("button", { name: "添加金融能力", exact: true })
      .click();
    await check(
      "plus_opens_registered_capability_modal",
      (await page.locator("[data-pick]").count()) === 2,
    );
    await page.keyboard.press("Escape");
    await page.locator('[data-action="bank-picker"]').click();
    await check(
      "bank_connection_selected_in_place",
      (await page.locator(".page-title-row h1").innerText()).includes("创建金融 Agent") &&
        (await page.locator('dialog input[name="bank"]:checked').count()) === 1,
    );
    await page.getByRole("button", { name: "使用此连接", exact: true }).click();
    await check(
      "bank_modal_preserves_selection",
      (await page.locator("[data-cap]:checked").count()) === 2 &&
        !(await page.locator("dialog").isVisible()),
    );
    await page.locator('[data-catalog="caps"]').first().click();
    await check(
      "only_registered_capabilities",
      (await page.locator("[data-pick]").count()) === 2,
    );
    await page.locator('[data-pick="account.balance.query"]').uncheck();
    await page.getByRole("button", { name: "取消", exact: true }).click();
    await check(
      "cancel_capability_draft",
      (await page.locator(".selected-cap").count()) === 2,
    );
    await page.locator('[data-catalog="caps"]').first().click();
    await page.locator('dialog').getByRole("textbox", { name: "搜索目录" }).fill("余额");
    await check(
      "capability_search",
      (await page.locator("[data-pick]").count()) === 1,
    );
    await page.keyboard.press("Escape");
    await check(
      "escape_closes_modal",
      !(await page.locator("dialog").isVisible()),
    );
    await page.locator('[data-catalog="models"]').click();
    const count = await page.locator("[data-pick]").count();
    await check(
      "actual_model_catalog",
      count === (mode === "controlled" ? 3 : 12),
    );
    const refs = await page
      .locator("[data-pick]")
      .evaluateAll((items) => items.map((x) => x.dataset.pick));
    await page.locator(`[data-pick="${refs[0]}"]`).check();
    await page.locator(`[data-pick="${refs[1]}"]`).check();
    await page
      .locator(`input[name="default-model"][value="${refs[1]}"]`)
      .check();
    await page.getByRole("button", { name: "保存选择", exact: true }).click();
    await check(
      "multiple_selected_on_home",
      (await page.locator('[data-catalog="models"]').innerText()).includes(
        "2 个",
      ),
    );
    await page.locator('[data-catalog="models"]').click();
    for (const ref of refs.slice(0, 2))
      await page.locator(`[data-pick="${ref}"]`).uncheck();
    await check(
      "empty_models_cannot_save",
      await page
        .getByRole("button", { name: "保存选择", exact: true })
        .isDisabled(),
    );
    await page.getByRole("button", { name: "取消", exact: true }).click();
    await check(
      "cancel_models_preserves_configuration",
      (await page.locator('[data-catalog="models"]').innerText()).includes(
        "2 个",
      ),
    );
    await page.locator('[data-catalog="models"]').click();
    await page.locator("#only-selected").check();
    await check(
      "only_selected_filter",
      (await page.locator("[data-pick]").count()) === 2,
    );
    await page.locator(`[data-pick="${refs[1]}"]`).click();
    await check(
      "unselected_row_removed_from_filter",
      (await page.locator(`[data-pick="${refs[1]}"]`).count()) === 0,
    );
    await check(
      "removed_default_reassigned",
      await page
        .locator(`input[name="default-model"][value="${refs[0]}"]`)
        .isChecked(),
    );
    await page.getByRole("button", { name: "取消", exact: true }).click();
    for (const width of [390, 320]) {
      await page.setViewportSize({ width, height: 850 });
      await check(
        "home_no_horizontal_overflow_" + width,
        await page.evaluate(
          () => document.documentElement.scrollWidth <= innerWidth,
        ),
      );
      await page.locator('[data-catalog="models"]').click();
      await screenshot("02-models-" + width);
      await check(
        "modal_no_horizontal_overflow_" + width,
        await page.evaluate(() => {
          const d = document.querySelector("dialog");
          return (
            d.scrollWidth <= innerWidth &&
            d.getBoundingClientRect().left >= 0 &&
            Math.abs(d.getBoundingClientRect().top) < 1 &&
            d.getBoundingClientRect().bottom <= innerHeight + 1
          );
        }),
      );
      await page.keyboard.press("Escape");
    }
    await page.setViewportSize({ width: 1440, height: 1000 });
    if (mode === "controlled") {
      await page.getByRole("radio", { name: "桌面金融助手 桌面 Web" }).click();
      await page.getByRole("button", { name: /开始构建/ }).click();
      await page.locator(".building-emblem").waitFor();
      await page.evaluate(() => {
        window.originalSidebar = document.querySelector(".sidebar");
        window.originalAssembly = document.querySelector(".assembly-flow");
      });
      await screenshot("03-real-build");
      for (const width of [390, 320]) {
        await page.setViewportSize({ width, height: 1000 });
        await check(
          "build_no_horizontal_overflow_" + width,
          await page.evaluate(
            () => document.documentElement.scrollWidth <= innerWidth,
          ),
        );
        await screenshot("03-build-" + width);
      }
      await page.setViewportSize({ width: 1440, height: 1000 });
      await page
        .getByRole("button", { name: "启动 Agent", exact: true })
        .waitFor();
      await page.waitForFunction(
        () => !document.querySelector('[data-action="launch"]')?.disabled,
        {},
        { timeout: 1200000 },
      );
      await check(
        "real_backend_build_completed",
        (await page.locator(".hero").innerText()).includes("构建完成"),
      );
      await check(
        "polling_preserves_sidebar_and_animation_nodes",
        await page.evaluate(
          () =>
            window.originalSidebar === document.querySelector(".sidebar") &&
            window.originalAssembly ===
              document.querySelector(".assembly-flow"),
        ),
      );
      await check(
        "technical_stages_track_actual_completion",
        (await page
          .locator("#build-progress progress")
          .getAttribute("value")) === "3" &&
          (await page.locator(".stage-content").count()) === 3,
      );
      await page
        .getByRole("button", { name: "启动 Agent", exact: true })
        .click();
      await page
        .getByRole("textbox", { name: "消息", exact: true })
        .waitFor({ timeout: 60000 });
      await check(
        "default_model_b",
        (await page.locator("#session-model").inputValue()) === refs[1],
      );
      await page
        .getByRole("textbox", { name: "消息", exact: true })
        .fill("browser-private-first-marker");
      await page.getByRole("button", { name: "发送", exact: true }).click();
      await page
        .locator(".assistant-content")
        .first()
        .waitFor({ timeout: 60000 });
      await check(
        "real_native_response",
        (await page.locator(".assistant-content").first().innerText()).includes(
          "CONTROLLED_TEST_COMPLETION",
        ),
      );
      await check(
        "form_received_standard_agui_stream",
        await (async () => {
          await page.waitForFunction(() => window.__studioStreams.length > 0);
          const response = await page.evaluate(() => window.__studioStreams[0]);
          const events = response.body.split('\n').filter(l => l.startsWith('data: ')).map(l => JSON.parse(l.slice(6)));
          for (const event of events) require('@ag-ui/core').BaseEventSchema.parse(event);
          apiObservations.push({path:'/ag-ui',status:response.status,eventTypes:events.map(e=>e.type)});
          return response.status === 200 && events[0].type === 'RUN_STARTED'
            && events.at(-1).type === 'RUN_FINISHED' && events.some(e => e.type === 'TEXT_MESSAGE_CONTENT');
        })(),
      );
      await screenshot("04-agent-desktop");
      await page.locator("#session-model").selectOption(refs[0]);
      await page.waitForFunction(
        () => !document.querySelector("#session-model")?.disabled,
        {},
        { timeout: 60000 },
      );
      await check(
        "old_conversation_available_as_history",
        (await page.locator(".session-history").count()) === 1,
      );
      await page
        .getByRole("textbox", { name: "消息", exact: true })
        .fill("browser-new-model");
      await page.getByRole("button", { name: "发送", exact: true }).click();
      await page.waitForFunction(
        () =>
          document.querySelectorAll("#chat-messages > .message.assistant")
            .length === 1,
        {},
        { timeout: 60000 },
      );
      for (const width of [390, 320]) {
        await page.setViewportSize({ width, height: 900 });
        await screenshot("05-agent-" + width);
        await check(
          "agent_no_overflow_" + width,
          await page.evaluate(
            () => document.documentElement.scrollWidth <= innerWidth,
          ),
        );
      }
      await page
        .getByRole("button", { name: "来源与执行证据", exact: true })
        .click();
      await check(
        "actual_model_evidence",
        (await page.locator("dialog pre").innerText()).includes(
          "modelConfigVersion",
        ),
      );
      await page.keyboard.press("Escape");
      await page
        .getByRole("button", { name: "撤销本次授权", exact: true })
        .click();
      await check(
        "revocation_clears_history",
        (await page.locator(".session-history").count()) === 0,
      );
      await check(
        "revocation_clears_messages",
        (await page.locator(".user-bubble").count()) === 0,
      );
      await check(
        "revocation_disables_chat",
        await page
          .getByRole("textbox", { name: "消息", exact: true })
          .isDisabled(),
      );
      await page.getByRole("button", { name: "停止", exact: true }).click();
      await page.locator(".building-emblem").waitFor({ timeout: 30000 });
      const before = await page.evaluate(
        async () => (await (await fetch("/api/v1/builds")).json()).builds,
      );
      const oldBuild = before[0];
      await page.setViewportSize({ width: 1440, height: 1000 });
      await page.getByRole("button", { name: "返回列表", exact: true }).click();
      await page.locator(`[data-edit="${oldBuild.buildId}"]`).click();
      await page.locator('[data-catalog="models"]').click();
      await page.locator(`[data-pick="${refs[0]}"]`).uncheck();
      await page.getByRole("button", { name: "保存选择", exact: true }).click();
      await page.locator('[data-catalog="caps"]').first().click();
      await page.locator('[data-pick="account.balance.query"]').uncheck();
      await page.getByRole("button", { name: "保存选择", exact: true }).click();
      await page.getByRole("button", { name: /重新构建/ }).click();
      await page.locator(".building-emblem").waitFor();
      await page.waitForFunction(
        () =>
          !!document.querySelector('[data-action="launch"]') &&
          !document.querySelector('[data-action="launch"]').disabled,
        {},
        { timeout: 1200000 },
      );
      const after = await page.evaluate(
        async () => (await (await fetch("/api/v1/builds")).json()).builds,
      );
      const newBuild = after.find(
        (x) => !before.some((y) => x.buildId === y.buildId),
      );
      await check(
        "new_build_narrows_model_and_capability",
        newBuild &&
          JSON.stringify(newBuild.modelSelection.selectedModelRefs) ===
            JSON.stringify([refs[1]]) &&
          JSON.stringify(newBuild.selection.capabilityIds) ===
            JSON.stringify(["wealth.facts.describe"]),
      );
      await check(
        "old_build_allowlist_immutable",
        JSON.stringify(
          after.find((x) => x.buildId === oldBuild.buildId).modelSelection,
        ) === JSON.stringify(oldBuild.modelSelection),
      );
      await page
        .getByRole("button", { name: "启动 Agent", exact: true })
        .click();
      await page
        .getByRole("textbox", { name: "消息", exact: true })
        .waitFor({ timeout: 60000 });
      await check(
        "new_instance_model_selector_narrowed",
        (await page.locator("#session-model option").count()) === 1 &&
          (await page.locator("#session-model").inputValue()) === refs[1],
      );
      await screenshot("06-narrowed-agent");
      const attempt = await page.evaluate(
        async ({ buildId, ref }) => {
          const boot = await (await fetch("/api/bootstrap")).json();
          const build = await (await fetch("/api/v1/builds/" + buildId)).json();
          const response = await fetch(
            "/api/v1/builds/" + buildId + "/switch-model",
            {
              method: "POST",
              headers: {
                "Content-Type": "application/json",
                "X-CSRF-Token": boot.csrf,
              },
              body: JSON.stringify({
                instanceId: build.instance.instanceId,
                sessionId: build.instance.sessionId,
                modelRef: ref,
              }),
            },
          );
          return { status: response.status, data: await response.json() };
        },
        { buildId: newBuild.buildId, ref: refs[0] },
      );
      await check(
        "forged_removed_model_rejected",
        attempt.status === 403 &&
          attempt.data.error.code === "MODEL_NOT_SELECTED",
      );
      await page
        .getByRole("button", { name: "来源与执行证据", exact: true })
        .click();
      await check(
        "removed_balance_not_installed",
        !(await page.locator("dialog pre").innerText()).includes(
          "account.balance.query",
        ),
      );
      await page.keyboard.press("Escape");
      await page.getByRole("button", { name: "停止", exact: true }).click();
      await page.locator(".building-emblem").waitFor({ timeout: 30000 });
      await page.getByRole("button", { name: "返回列表", exact: true }).click();
      await page.locator(`[data-delete="${newBuild.buildId}"]`).click();
      await screenshot("07-delete-confirmation");
      await page.getByRole("button", { name: "取消", exact: true }).click();
      await check(
        "cancel_delete_preserves_agent",
        (await page.locator(`[data-open="${newBuild.buildId}"]`).count()) === 1,
      );
      await page.locator(`[data-delete="${newBuild.buildId}"]`).click();
      await page.getByRole("button", { name: "确认删除", exact: true }).click();
      await page
        .locator(`[data-open="${newBuild.buildId}"]`)
        .waitFor({ state: "detached" });
      await page.reload();
      await page.locator('[data-nav="agents"]').click();
      await check(
        "deleted_agent_absent_after_reload",
        (await page.locator(`[data-open="${newBuild.buildId}"]`).count()) === 0,
      );
      await check(
        "deletion_keeps_other_build",
        (await page.locator(`[data-open="${oldBuild.buildId}"]`).count()) === 1,
      );
      await check(
        "delete_went_to_real_api",
        apiObservations.some(
          (x) =>
            x.path === "/api/v1/builds/" + newBuild.buildId + "/delete" &&
            x.status === 200,
        ),
      );
    }
    await check("no_browser_exceptions", errors.length === 0);
  } finally {
    await screenshot("99-final-state");
    fs.writeFileSync(
      path.join(output, "report.json"),
      JSON.stringify(
        {
          mode,
          checks,
          errors,
          apiObservations,
          realModel: "NOT_RUN",
          humanOnboarding: "NOT_RUN",
        },
        null,
        2,
      ),
    );
    await browser.close();
  }
  console.log(JSON.stringify({ checks: checks.length, mode }));
})().catch((e) => {
  console.error(e);
  process.exitCode = 1;
});
