package io.github.bitlhk.finance.harness.integrations.employeeagent;

import io.github.bitlhk.finance.harness.core.*;
import io.github.bitlhk.finance.harness.core.spi.*;
import io.github.bitlhk.finance.harness.providers.employeeagent.*;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** Trusted Host client. The browser cannot supply Owner, employee identity, grants, or broker credentials. */
public final class EmployeeAgentPreparationBroker implements AutoCloseable {
    private static final String CAPABILITY="wealth.previsit-context.query";
    private final URI endpoint;
    private final String owner,session;
    private final Supplier<String> credential;
    private final Duration timeout;
    private final HttpClient http;
    private final AtomicReference<Snapshot> snapshot=new AtomicReference<>();
    private volatile boolean closed;
    private long refreshGeneration;
    private record Snapshot(EmployeeAgentWealthSession.Authority authority,Map<String,String> customers,String token) {
        @Override public String toString() { return "Snapshot[redacted]"; }
    }
    public EmployeeAgentPreparationBroker(URI endpoint,String owner,String session,Supplier<String> credential,Duration timeout) {
        this.endpoint=Objects.requireNonNull(endpoint);this.owner=required(owner);this.session=required(session);
        this.credential=Objects.requireNonNull(credential);this.timeout=Objects.requireNonNull(timeout);
        if(timeout.isZero() || timeout.isNegative() || endpoint.getUserInfo()!=null || endpoint.getQuery()!=null || endpoint.getFragment()!=null
                || !(endpoint.getPath().isEmpty() || endpoint.getPath().equals("/"))
                || !("https".equals(endpoint.getScheme()) || "http".equals(endpoint.getScheme())
                     && Set.of("127.0.0.1","localhost").contains(Objects.toString(endpoint.getHost(),""))))
            throw new IllegalArgumentException("TRUSTED_BROKER_ENDPOINT_REQUIRED");
        http=HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    /** Network operation: call outside Session/Task locks. Failure invalidates the previous snapshot. */
    public void refresh() throws Exception {
        final long generation;
        synchronized(this) { if(closed) throw new SecurityException("BROKER_CLOSED");generation=++refreshGeneration; }
        try {
            var request=HttpRequest.newBuilder(endpoint.resolve("/api/internal/harness/service-preparation/authority"))
                .timeout(timeout).header("Content-Type","application/json")
                .header("Authorization","Bearer "+required(credential.get()))
                .POST(HttpRequest.BodyPublishers.ofString(HarnessJson.mapper().writeValueAsString(Map.of("ownerRef",owner,"sessionRef",session)))).build();
            var response=http.send(request,HttpResponse.BodyHandlers.ofInputStream());
            byte[] bytes;try(var body=response.body()) { bytes=body.readNBytes(65537); }
            if(response.statusCode()!=200 || bytes.length>65536) throw new SecurityException("BROKER_AUTHORITY_REJECTED");
            var data=HarnessJson.mapper().readTree(bytes);
            if(!owner.equals(data.path("ownerRef").asText()) || !session.equals(data.path("sessionRef").asText())
                    || !data.path("capabilityIds").equals(HarnessJson.mapper().valueToTree(List.of(CAPABILITY)))
                    || !data.path("scopes").equals(HarnessJson.mapper().valueToTree(List.of("wealth:previsit:read")))
                    || !"WEALTH_PREVISIT".equals(data.path("purpose").asText())) throw new SecurityException("BROKER_SCOPE_REJECTED");
            String principal=required(data.path("principalRef").asText()),ref=required(data.path("authorityRef").asText());
            Instant expires=Instant.parse(data.path("expiresAt").asText());
            if(!expires.isAfter(Instant.now()) || expires.isAfter(Instant.now().plusSeconds(310))) throw new SecurityException("BROKER_LEASE_REJECTED");
            var r=data.path("runtime");
            var runtime=new EmployeeAgentRuntimeIdentity(required(r.path("runtimeId").asText()),required(r.path("agentId").asText()),required(r.path("adoptId").asText()));
            var customers=new LinkedHashMap<String,String>();var subjects=new HashSet<String>();
            if(!data.path("customers").isArray() || data.path("customers").isEmpty() || data.path("customers").size()>100)
                throw new SecurityException("BROKER_CUSTOMERS_REJECTED");
            for(var row:data.path("customers")) {
                String customer=required(row.path("customerRef").asText()),label=required(row.path("displayName").asText());
                if(customer.length()>128 || label.length()>200 || !subjects.add(customer)) throw new SecurityException("BROKER_CUSTOMERS_REJECTED");
                customers.put(label+" ("+customer+")",customer);
            }
            var binding=new EmployeeAgentIdentityBinding(principal,runtime,ref,expires);
            var grant=new BoundFinanceProvider.Grant(principal,FinanceContextV1.PrincipalType.EMPLOYEE,subjects,Set.of("WEALTH_PREVISIT"),
                Set.of("wealth:previsit:read"),session,ref,expires);
            String token=required(data.path("accessToken").asText());
            if(!token.matches("fhp\\.[A-Za-z0-9_-]{43}")) throw new SecurityException("BROKER_TOKEN_REJECTED");
            synchronized(this) {
                if(closed || generation!=refreshGeneration) throw new SecurityException("BROKER_REFRESH_SUPERSEDED");
                snapshot.set(new Snapshot(new EmployeeAgentWealthSession.Authority(owner,binding,grant,FinanceContextV1.AuthenticationLevel.BASIC),
                    Map.copyOf(customers),token));
            }
        } catch(Exception failure) { synchronized(this) { if(generation==refreshGeneration) snapshot.set(null); } throw failure; }
    }
    private Snapshot current() {
        var value=snapshot.get();
        if(closed || value==null || !value.authority().grant().expiresAt().isAfter(Instant.now())) throw new SecurityException("BROKER_AUTHORITY_UNAVAILABLE");
        return value;
    }
    public EmployeeAgentWealthSession.Authority authority() { return current().authority(); }
    public Map<String,String> customers() { return current().customers(); }
    public EmployeeAgentWealthSession openSession() {
        var runtime=authority().binding().runtime();
        return new EmployeeAgentWealthSession(owner,session,runtime,this::authority,context->{
            var value=current();
            if(!context.sessionRef().equals(session) || !context.principalId().equals(value.authority().grant().principalId())
                    || !value.authority().grant().subjects().contains(context.subjectRef())) throw new SecurityException("BROKER_CONTEXT_REJECTED");
            return value.token();
        },new ManagedProviderFactoryV01.Connection(EmployeeAgentWealthFactoryV01.CONNECTION_KIND,endpoint,timeout,()->null,()->null,c->{}),Set.of(CAPABILITY));
    }
    private static String required(String text) { if(text==null || text.isBlank()) throw new SecurityException("BROKER_FIELD_MISSING");return text; }
    @Override public synchronized void close() { closed=true;refreshGeneration++;snapshot.set(null);http.shutdownNow(); }
}
