"""Test-only observation of the same browser Fetch response, without a second call."""
import hashlib


CAPTURE_AGUI = r"""(() => {
  const original = window.fetch.bind(window);
  const records = Object.create(null);
  window.__fhAguiCaptures = records;
  window.fetch = async (...args) => {
    let id, url;
    try {
      const [input, options] = args;
      url = new URL(typeof input === 'string' ? input : input.url, location.href);
      const endpoint = /^\/experience\/[^/]+\/api\/ag-ui$|^\/api\/v1\/builds\/[^/]+\/ag-ui$/;
      if (url.origin === location.origin && endpoint.test(url.pathname) &&
          options?.method?.toUpperCase() === 'POST' && typeof options.body === 'string') {
        id = JSON.parse(options.body).runId;
        if (typeof id !== 'string' || !id) id = undefined;
      }
    } catch (_) { id = undefined; }
    const response = await original(...args);
    if (id) {
      if (Object.hasOwn(records, id)) {
        records[id] = {done: true, error: 'DUPLICATE_CAPTURE_RUN'};
      } else {
        const record = {done: false, runId: id, url: url.href, status: response.status,
          contentType: response.headers.get('content-type')};
        records[id] = record;
        let clone;
        try { clone = response.clone(); }
        catch (_) { record.error = 'CAPTURE_BODY_UNAVAILABLE'; record.done = true; return response; }
        (async () => {
          const chunks = [];
          let size = 0;
          try {
            if (!clone.body) throw new Error('CAPTURE_BODY_UNAVAILABLE');
            const reader = clone.body.getReader();
            while (true) {
              const {done, value} = await reader.read();
              if (done) break;
              size += value.byteLength;
              if (size > 2 * 1024 * 1024) {
                reader.cancel().catch(() => {});
                throw new Error('CAPTURE_SIZE_LIMIT');
              }
              chunks.push(value);
            }
            const bytes = new Uint8Array(size);
            let offset = 0;
            for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
            record.text = new TextDecoder('utf-8', {fatal: true}).decode(bytes);
            record.byteLength = size;
          } catch (_) { record.error = 'CAPTURE_BODY_UNAVAILABLE'; }
          finally { record.done = true; }
        })();
      }
    }
    return response;
  };
})();"""


def read_agui_capture(page, run_id, status, url):
    page.wait_for_function("id => window.__fhAguiCaptures?.[id]?.done === true",
                           arg=run_id, timeout=240000)
    capture = page.evaluate("id => window.__fhAguiCaptures[id]", run_id)
    if capture.get("error"):
        raise AssertionError(capture["error"])
    if (capture.get("runId"), capture.get("status"), capture.get("url")) != (run_id, status, url):
        raise AssertionError("CAPTURE_RESPONSE_IDENTITY")
    raw = capture["text"].encode("utf-8")
    if len(raw) != capture.get("byteLength"):
        raise AssertionError("CAPTURE_UTF8_LENGTH_MISMATCH")
    return capture["text"], {key: capture[key] for key in
        ("runId", "url", "status", "contentType", "byteLength")} | {
        "sha256": hashlib.sha256(raw).hexdigest(), "boundary": "BROWSER_FETCH_RESPONSE_CLONE"}
