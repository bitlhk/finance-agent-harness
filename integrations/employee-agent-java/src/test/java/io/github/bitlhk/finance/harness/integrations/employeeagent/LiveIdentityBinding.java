package io.github.bitlhk.finance.harness.integrations.employeeagent;

import io.github.bitlhk.finance.harness.core.HarnessJson;
import io.github.bitlhk.finance.harness.providers.employeeagent.EmployeeAgentIdentityBinding;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

/** Manual smoke only: an operator supplies an owner mapping exported by the trusted host. */
final class LiveIdentityBinding {
    private LiveIdentityBinding() { }
    static EmployeeAgentIdentityBinding read(String variable) {
        String file = System.getenv(variable);
        if (file == null || file.isBlank()) throw new IllegalStateException(variable + " requires a verified host export");
        try {
            return HarnessJson.mapper().readValue(Path.of(file).toFile(), EmployeeAgentIdentityBinding.class);
        } catch (IOException failure) { throw new UncheckedIOException("Verified host binding could not be read", failure); }
    }
}
