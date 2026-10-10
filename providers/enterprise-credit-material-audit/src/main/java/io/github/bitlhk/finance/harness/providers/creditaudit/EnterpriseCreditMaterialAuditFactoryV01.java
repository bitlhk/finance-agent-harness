package io.github.bitlhk.finance.harness.providers.creditaudit;

import io.github.bitlhk.finance.harness.core.spi.BoundFinanceProvider;
import io.github.bitlhk.finance.harness.core.spi.DescribedFinanceProvider;
import io.github.bitlhk.finance.harness.core.spi.ManagedProviderFactoryV01;
import io.github.bitlhk.finance.harness.core.CapabilityRequestV1;
import io.github.bitlhk.finance.harness.core.CapabilityResultV1;
import io.github.bitlhk.finance.harness.core.ProviderDescriptorV1;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public final class EnterpriseCreditMaterialAuditFactoryV01 implements ManagedProviderFactoryV01 {
    private final Map<String, String> environment;

    public EnterpriseCreditMaterialAuditFactoryV01() { this(System.getenv()); }

    EnterpriseCreditMaterialAuditFactoryV01(Map<String, String> environment) {
        var selected = new HashMap<String, String>();
        for (String name : Set.of("FH_CREDIT_AUDIT_MATERIAL_ROOT", "FH_CREDIT_AUDIT_RULE_FILE")) {
            if (environment.containsKey(name)) selected.put(name, environment.get(name));
        }
        this.environment = Map.copyOf(selected);
    }

    @Override public String providerId() { return EnterpriseCreditMaterialAuditProvider.ID; }
    @Override public String connectionKind() { return "local-credit-materials.v1"; }
    @Override public Map<String, Set<String>> requirements() {
        return Map.of(
                EnterpriseCreditMaterialAuditProvider.FINANCIAL, Set.of("credit-audit:financial-statement:read"),
                EnterpriseCreditMaterialAuditProvider.CROSS_MATERIAL, Set.of("credit-audit:application-contract:read"));
    }

    @Override public DescribedFinanceProvider create(Connection connection,
                                                      Map<String, BoundFinanceProvider> dependencies) {
        if (!dependencies.isEmpty()) throw new IllegalArgumentException("CREDIT_AUDIT_DEPENDENCIES_UNSUPPORTED");
        return new DescribedFinanceProvider() {
            private volatile EnterpriseCreditMaterialAuditProvider delegate;

            @Override public ProviderDescriptorV1 descriptor() {
                return EnterpriseCreditMaterialAuditProvider.descriptorValue();
            }

            @Override public CapabilityResultV1 query(CapabilityRequestV1 request) {
                return delegate().query(request);
            }

            @Override public CapabilityResultV1 executeTask(CapabilityRequestV1 request) {
                return delegate().executeTask(request);
            }

            private EnterpriseCreditMaterialAuditProvider delegate() {
                var current = delegate;
                if (current != null) return current;
                synchronized (this) {
                    if (delegate == null) delegate = configuredProvider();
                    return delegate;
                }
            }
        };
    }

    private EnterpriseCreditMaterialAuditProvider configuredProvider() {
        try {
            String root = required("FH_CREDIT_AUDIT_MATERIAL_ROOT");
            String rule = required("FH_CREDIT_AUDIT_RULE_FILE");
            return new EnterpriseCreditMaterialAuditProvider(
                    new MaterialSetRepository(Path.of(root)), DemoRuleSet.load(Path.of(rule)));
        } catch (java.io.IOException exception) {
            throw new IllegalArgumentException("CREDIT_AUDIT_RULE_LOAD_FAILED", exception);
        }
    }

    private String required(String name) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + "_REQUIRED");
        return value;
    }
}
