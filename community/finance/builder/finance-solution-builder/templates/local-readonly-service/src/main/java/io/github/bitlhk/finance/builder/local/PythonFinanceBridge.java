package io.github.bitlhk.finance.builder.local;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.bitlhk.finance.harness.core.*;
import io.github.bitlhk.finance.harness.core.spi.*;
import io.github.bitlhk.finance.harness.core.observation.*;
import io.github.bitlhk.finance.harness.integrations.openjiuwen.*;
import io.github.bitlhk.finance.harness.providers.nonaibanking.NonAiBankingProvider;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static io.github.bitlhk.finance.harness.core.observation.ObservationEventV01.Outcome;

/** Owned pipe-only bridge. No model client, arbitrary route, task, or identity setter. */
public final class PythonFinanceBridge {
    private final String generation = BankAccess.env("FH_BRIDGE_GENERATION");
    private final Path root;
    private final BankAccess bank = new BankAccess();
    private final Map<String, State> sessions = new HashMap<>();
    private final ExecutionArchive archive;
    private final Set<String> selected;
    private final JsonNode manifest;

    private final class State {
        final BankAccess.Login login;
        final OpenJiuwenSolutionAssembler.ReadOnlyInstallation execution;
        final List<Map<String, String>> hostMethods;
        final Object installationLock;
        final Object displayLabels;
        final Map<String, ObservationRun.Span> spans = new HashMap<>();
        final Map<String, String> snapshotCalls = new HashMap<>();
        final Set<String> snapshotParts = new HashSet<>();
        State(BankAccess.Login login) throws Exception {
            this.login = login;
            var providers = ReadOnlyProviders.create(root, bank, login,
                    () -> login.observation == null ? null : login.observation.run, () -> {},
                    () -> bank.current(login), ignored -> {});
            var grant=bank.current(login);
            var defaults=new OpenJiuwenFinanceBridge.InvocationDefaults(grant.principalId(), login.customer,
                    grant.principalType(), "local-preview", BankAccess.PURPOSE,
                    FinanceContextV1.AuthenticationLevel.BASIC, grant.scopes(), () -> bank.current(login).scopes());
            var prepared = ObservationAdmission.prepare(root,
                    root.resolve("bridge-solution.json"), providers);
            execution = prepared.openQueries(defaults, true, login.hostSession);
            Object labels;
            try { labels = prepared.displayMetadata(); } catch (RuntimeException unavailable) { labels = null; }
            displayLabels = labels;
            if (!execution.capabilityIds().equals(selected)) throw new SecurityException("INSTALLATION_PLAN_MISMATCH");
            installationLock = ReadOnlyProviders.plan(prepared.snapshot());
            if (!ObservationAdmission.stablePlan(HarnessJson.mapper().valueToTree(installationLock)).equals(
                    ObservationAdmission.stablePlan(HarnessJson.mapper().readTree(root.resolve("installation-plan.json").toFile()))))
                throw new SecurityException("BUILD_RUNTIME_PLAN_MISMATCH");
            hostMethods = HostMethods.load(root, selected);
        }
        void authorize() {
            var required = new HashSet<>(execution.requiredScopes());
            required.addAll(ReadOnlyProviders.sourceScopes(root));
            if (!bank.current(login).scopes().containsAll(required))
                throw new SecurityException("AUTHORIZATION_REJECTED");
        }
        void close() {
            bank.revoke(login);
            if (login.observation != null) login.observation.close();
        }
    }

    PythonFinanceBridge(Path root) throws Exception {
        this.root = root;
        if (ModelSnapshots.allowed() && !System.getenv().getOrDefault("FH_OTHER_MODEL_BODY_COLLECTOR", "").isBlank())
            throw new IllegalArgumentException("SNAPSHOT_COLLECTOR_CONFLICT");
        manifest = HarnessJson.mapper().readTree(root.resolve("service-lock.json").toFile());
        var ids = new HashSet<String>();
        for (var id : manifest.path("capabilities"))
            if (!id.isTextual() || !ids.add(id.asText())) throw new IllegalArgumentException("CAPABILITY_SELECTION_INVALID");
        if (ids.isEmpty()) throw new IllegalArgumentException("EMPTY_CAPABILITY_SELECTION");
        selected = Set.copyOf(ids);
        var fields = manifest.path("files").fields();
        if (!fields.hasNext()) throw new IllegalArgumentException("EMPTY_LOCK");
        while (fields.hasNext()) {
            var item = fields.next(); Path path = root.resolve(item.getKey()).normalize();
            if (!path.startsWith(root) || Files.isSymbolicLink(path) || !Files.isRegularFile(path)
                    || !path.toRealPath().startsWith(root.toRealPath())
                    || !ExecutionArchive.hash(Files.readAllBytes(path)).equals(item.getValue().asText()))
                throw new SecurityException("ARTIFACT_MISMATCH");
        }
        archive = new ExecutionArchive(root, manifest.path("buildId").asText(), generation);
    }

    private Object dispatch(JsonNode request) throws Exception {
        var keys = new HashSet<String>(); request.fieldNames().forEachRemaining(keys::add);
        if (!Set.of("schemaVersion", "generation", "requestId", "operation", "session", "parameters").containsAll(keys)
                || !"finance-local-pipe.v0.1".equals(request.path("schemaVersion").asText())
                || !generation.equals(request.path("generation").asText())
                || !request.path("requestId").asText().matches("[a-f0-9-]{36}"))
            throw new SecurityException("PIPE_BINDING_REJECTED");
        String operation = request.path("operation").asText();
        JsonNode params = request.path("parameters");
        if (!params.isObject()) throw new IllegalArgumentException("INVALID_PARAMETERS");
        if ("health".equals(operation)) {
            if (!params.isEmpty()) throw new IllegalArgumentException();
            return Map.of("generation", generation, "buildId", manifest.path("buildId").asText(),
                    "capabilities", selected, "pid", ProcessHandle.current().pid(),
                    "snapshotCapture", ModelSnapshots.allowed() ? "ENABLED" : "DISABLED");
        }
        if ("open".equals(operation)) {
            if (!params.isEmpty() || sessions.size() >= 16) throw new IllegalArgumentException("SESSION_LIMIT_OR_INPUT");
            String handle = UUID.randomUUID().toString(); var login=bank.open(handle);
            try {
                var state = new State(login); state.authorize(); sessions.put(handle,state);
                return Map.of("session", handle,"javaBridgeInstallation",state.installationLock, "hostMethods",state.hostMethods);
            } catch (Exception denied) { bank.revoke(login); throw denied; }
        }
        State state = sessions.get(request.path("session").asText());
        if (state == null) throw new SecurityException("SESSION_UNAVAILABLE");
        if ("close".equals(operation)) {
            if (!params.isEmpty()) throw new IllegalArgumentException();
            sessions.remove(state.login.hostSession); state.close(); return Map.of("closed", true);
        }
        if ("finish".equals(operation)) {
            if (params.size()!=1 || !Set.of("COMPLETED","DENIED","ERROR","CANCELLED","INCOMPLETE")
                    .contains(params.path("outcome").asText())) throw new IllegalArgumentException();
            if (state.login.observation == null) throw new IllegalStateException();
            var observation=state.login.observation;
            observation.finish(Outcome.valueOf(params.path("outcome").asText()));
            state.login.observation=null; state.spans.clear(); state.snapshotCalls.clear();
            state.snapshotParts.clear(); return observation.receipt();
        }
        if ("snapshot".equals(operation)) {
            var fields = new HashSet<String>(); params.fieldNames().forEachRemaining(fields::add);
            String call=params.path("callId").asText(), span=params.path("spanId").asText();
            String boundary=params.path("boundary").asText(), status=params.path("state").asText();
            if (!fields.equals(Set.of("callId","spanId","boundary","state","value","pythonProjectionNanos"))
                    || !call.matches("[a-f0-9-]{36}") || !state.spans.containsKey(span)
                    || state.login.observation==null || !params.path("value").isObject()
                    || !Set.of("CLIENT_REQUEST_PREPARED","PARSED_MODEL_RESPONSE").contains(boundary)
                    || !Set.of("CAPTURED","FILTERED","TRUNCATED","CLIENT_ERROR","NOT_CAPTURED").contains(status)
                    || !params.path("pythonProjectionNanos").canConvertToLong()
                    || params.path("pythonProjectionNanos").asLong()<0)
                throw new IllegalArgumentException("SNAPSHOT_BINDING_INVALID");
            if (!ModelSnapshots.allowed()) return Map.of("capture","DISABLED");
            if (boundary.equals("CLIENT_REQUEST_PREPARED")) {
                if (state.snapshotCalls.putIfAbsent(call,span)!=null) throw new IllegalArgumentException();
            } else if (!span.equals(state.snapshotCalls.get(call))) throw new IllegalArgumentException();
            if (!state.snapshotParts.add(call+boundary)) throw new IllegalArgumentException();
            // Capture permission is not a new business grant. No business API is called here.
            var capture=new ModelSnapshots(state.login.observation,()->span,List.of(state.login.bankSession,
                    state.login.hostSession,state.login.customer,System.getenv().getOrDefault("FH_BANK_SECRET","")));
            capture.capturePython(call,boundary,status,HarnessJson.mapper().convertValue(params.path("value"),Map.class),
                    params.path("pythonProjectionNanos").asLong());
            return Map.of("capture","REFERENCE_RECORDED_BODY_NOT_VERIFIED");
        }
        state.authorize();
        switch (operation) {
            case "authorize":
                if (!params.isEmpty()) throw new IllegalArgumentException();
                return Map.of("authorized", true);
            case "begin":
                if (state.login.observation != null || (!params.isEmpty() &&
                        (params.size()!=2 || !params.path("model").isObject() || params.path("model").size()!=3
                         || !params.path("model").path("ref").isTextual()
                         || !params.path("model").path("name").isTextual()
                         || !params.path("model").path("version").isTextual()
                         || !params.path("clientRunId").isTextual()))) throw new IllegalArgumentException();
                state.login.observation=archive.begin(root,state.login.hostSession,state.login.customer,0,"CONVERSATION_TURN");
                state.login.observation.labels(state.displayLabels);
                if (!params.isEmpty()) {
                    var model=params.path("model");
                    state.login.observation.modelContext(new ModelConnections.Connection(model.path("ref").asText(),
                            model.path("version").asText(),"OpenAI",model.path("name").asText(),"",""),
                            params.path("clientRunId").asText());
                }
                state.login.observation.installation(Map.of("pythonRuntime",manifest.path("runtime"),
                        "javaBridgeInstallation",state.installationLock,
                        "clockScope","JAVA_RECEIVE_WINDOWS_INCLUDE_PIPE_AND_CHECKS_NOT_PYTHON_CLOCK"));
                return state.login.observation.receipt();
            case "stage-start": {
                if (state.login.observation==null || params.size()!=1
                        || !Set.of("model","runtime-tool").contains(params.path("component").asText()))
                    throw new IllegalArgumentException();
                var span=state.login.observation.run.start(state.login.observation.root.id(),
                        params.path("component").asText(), "python-sdk-attempt", Map.of());
                state.spans.put(span.id(),span); return Map.of("spanId",span.id());
            }
            case "stage-end": {
                if (params.size()!=2 || !Set.of("RETURNED","ERROR","DENIED").contains(params.path("outcome").asText()))
                    throw new IllegalArgumentException();
                var span=state.spans.remove(params.path("spanId").asText());
                if (span==null) throw new IllegalArgumentException();
                span.finish(Outcome.valueOf(params.path("outcome").asText()),Map.of()); return Map.of("recorded",true);
            }
            case "query": {
                if (params.size()!=2 || !params.path("capabilityId").isTextual() || !params.path("arguments").isObject())
                    throw new IllegalArgumentException("QUERY_INPUT_INVALID");
                String id=state.login.observation==null?request.path("requestId").asText():state.login.observation.id;
                Map<String,Object> arguments=HarnessJson.mapper().convertValue(params.path("arguments"),Map.class);
                var result=state.execution.query(params.path("capabilityId").asText(), arguments, id);
                state.authorize();
                return Map.of("execution", result, "cards", DisplayProjection.cards(List.of(result), selected));
            }
            default: throw new IllegalArgumentException("OPERATION_UNSUPPORTED");
        }
    }

    public static void main(String[] args) throws Exception {
        PrintStream pipe=System.out; System.setOut(System.err);
        var bridge=new PythonFinanceBridge(Path.of(args[0]).toAbsolutePath().normalize());
        try (var input=new BufferedInputStream(System.in)) {
            while (true) {
                var frame=new ByteArrayOutputStream(); int b;
                while ((b=input.read())!=-1 && b!='\n') {
                    if (frame.size()>=65536) throw new IOException("PIPE_FRAME_TOO_LARGE"); frame.write(b);
                }
                if (b==-1) break;
                JsonNode request=HarnessJson.mapper().readTree(frame.toByteArray());
                var response=new LinkedHashMap<String,Object>();
                response.put("requestId",request.path("requestId").asText()); response.put("generation",bridge.generation);
                try { response.put("result",bridge.dispatch(request)); response.put("ok",true); }
                catch (SecurityException denied) { response.put("ok",false); response.put("error","AUTHORIZATION_REJECTED"); }
                catch (Exception failure) { response.put("ok",false); response.put("error","BRIDGE_OPERATION_FAILED"); }
                byte[] bytes=HarnessJson.mapper().writeValueAsBytes(response);
                if(bytes.length>262144) throw new IOException("PIPE_RESPONSE_TOO_LARGE");
                pipe.write(bytes); pipe.write('\n'); pipe.flush();
            }
        } finally { bridge.sessions.values().forEach(State::close); ModelSnapshots.flush(); }
    }
}
