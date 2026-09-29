# Managed Generation Parameters

`ModelConnection` retains its four-argument constructor and previous defaults.
The optional five-argument constructor accepts `ModelConnection.Generation`:

- `temperature`: finite number in [0, 2].
- `topP`: finite number in (0, 1].
- `maxTokens`: integer in [1, 32768].

The trusted Host supplies all three values together. They are passed to AgentScope
2.0.3 `GenerateOptions`, not appended through arbitrary body overrides. Endpoint,
credentials, retry count, authorization and non-streaming behavior remain unchanged.
This does not grant access to any business capability or enable TASK in an Eval
Launcher that only admits QUERY.

`explicitGenerationParametersReachModelHttpEndpoint` asserts the actual HTTP body
received by a controlled model endpoint. The diagnostic capture remains labeled
`CLIENT_REQUEST_PREPARED`; its existence alone is not network delivery evidence.
The transport projection includes `top_p` and `max_tokens` as well as temperature.

This contract does not claim that every upstream model supports every parameter.
Managed endpoints must be verified separately; a provider rejection must remain
an execution error rather than falling back or silently dropping a parameter.
Equal configuration does not imply deterministic or equivalent answers.

Changing these classes changes runtime qualification identity. Rebuild and qualify
the actual artifact before REQUIRED observation admission. Existing Agents are
not modified or upgraded by this source change.
