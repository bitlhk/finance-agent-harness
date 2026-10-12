package com.openjiuwen.intelligence.finance.harness;

import io.github.bitlhk.finance.harness.core.FinanceContextV1;
import io.github.bitlhk.finance.harness.integrations.openjiuwen.OpenJiuwenFinanceBridge;
import io.github.bitlhk.finance.harness.providers.nonaibanking.NonAiBankingProvider;
import org.springframework.core.env.Environment;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;

/** Creates the customer-self-service Provider selected by the Solution manifest. */
public final class FinanceHarnessProfileSupport {
    private FinanceHarnessProfileSupport() {}

    public static Optional<FinanceHarnessCapabilityAdapter> maybeAdapter(Environment environment) {
        if (!"customer-self-service".equals(environment.getProperty(
                "three-engine.finance-harness.profile", ""))) return Optional.empty();
        String baseUrl = property(environment, "three-engine.finance-harness.base-url");
        if (baseUrl.isBlank()) return Optional.empty();
        var provider = new NonAiBankingProvider(new NonAiBankingProvider.Config(
                URI.create(baseUrl),
                property(environment, "three-engine.finance-harness.login-name"),
                property(environment, "three-engine.finance-harness.login-secret"),
                environment.getProperty("three-engine.finance-harness.device-id", "finance-builder"),
                environment.getProperty("three-engine.finance-harness.upstream-channel", "M"),
                environment.getProperty("three-engine.finance-harness.app-version", "finance-harness-v1.1"),
                Duration.ofMillis(environment.getProperty(
                        "three-engine.finance-harness.timeout-ms", Integer.class, 5000))));
        var defaults = new OpenJiuwenFinanceBridge.InvocationDefaults(
                environment.getProperty("three-engine.finance-harness.principal-id", "mobile-customer"),
                environment.getProperty("three-engine.finance-harness.subject-ref", "mobile-customer"),
                FinanceContextV1.PrincipalType.CUSTOMER,
                "mobile-banking",
                "CUSTOMER_SERVICE",
                FinanceContextV1.AuthenticationLevel.BASIC,
                Set.of("wealth:read"));
        return Optional.of(new FinanceHarnessCapabilityAdapter(new OpenJiuwenFinanceBridge(
                provider,
                NonAiBankingProvider.PROVIDER_ID,
                defaults,
                Set.of(NonAiBankingProvider.WEALTH_CUSTOMER_CONTEXT_QUERY),
                false)));
    }

    private static String property(Environment environment, String name) {
        return environment.getProperty(name, "").trim();
    }
}
