# Controlled model HTTP/1.1 egress

This loopback-only process is a compatibility boundary for the customer-manager
report model connection. It is not a general HTTP proxy.

It accepts only `POST /v1/chat/completions`, one configured model, streaming
requests, a bounded body, and a private caller token. It sends requests only to
one HTTPS origin whose exact value must also be present in
`MODEL_EGRESS_ALLOWED_ORIGIN`. TLS verification uses the operating-system trust
store. Incoming authorization is never forwarded; the upstream key is supplied
to the separate process through its environment. Logs contain protocol/status,
event counts and digested upstream request IDs, never request bodies or headers.

Required environment variables:

- `MODEL_EGRESS_UPSTREAM_BASE`
- `MODEL_EGRESS_ALLOWED_ORIGIN`
- `MODEL_EGRESS_UPSTREAM_KEY`
- `MODEL_EGRESS_CALLER_TOKEN`
- `MODEL_EGRESS_MODEL`

The Agent model connection uses `http://127.0.0.1:<port>/v1` and the caller token.
The process fails closed when the upstream status or content type is unexpected.
It must be supervised separately from the Agent and stopped independently.
