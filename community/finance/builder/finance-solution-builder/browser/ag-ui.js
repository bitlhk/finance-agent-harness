import { HttpAgent } from "@ag-ui/client";
import { requestTiming } from './request-timing.js';

export async function chat({ url, csrf, sessionId, instanceId, query, onEvent }) {
  const timing = requestTiming();
  const agent = new HttpAgent({
    url, threadId: sessionId, headers: { "X-CSRF-Token": csrf },
    initialMessages: [{ id: crypto.randomUUID(), role: "user", content: query }],
    initialState: {},
    fetch: async (url, init) => {
      const response = await fetch(url, init);
      if (!response.ok) {
        const data = await response.json().catch(() => ({}));
        throw Object.assign(new Error(data.error?.code || "AG_UI_REQUEST_REJECTED"), { data });
      }
      return response;
    },
  });
  let evidence, failure, completed = false;
  await agent.runAgent({
    runId: crypto.randomUUID(), tools: [], context: [], forwardedProps: { instanceId },
  }, {
    onEvent: ({ event }) => { timing.event(event); onEvent?.(event); },
    onCustomEvent: ({ event }) => {
      if (event.name === "finance.evidence") evidence = event.value;
      if (event.name === "finance.clearConversation") {
        failure = Object.assign(new Error("会话已清理，请重新连接。"), { data: event.value });
      }
    },
    onRunErrorEvent: ({ event }) => {
      failure = Object.assign(new Error(event.code || event.message), { data: { clearConversation: true } });
    },
    onRunFinishedEvent: () => { completed = true; },
  });
  if (failure) throw failure;
  if (!completed || !evidence) {
    throw Object.assign(new Error("AG_UI_STREAM_INCOMPLETE"), { data: { clearConversation: true } });
  }
  return { ...evidence, browserTiming: timing };
}
