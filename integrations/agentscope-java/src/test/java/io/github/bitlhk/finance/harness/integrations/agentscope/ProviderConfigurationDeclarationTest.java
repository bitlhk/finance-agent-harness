package io.github.bitlhk.finance.harness.integrations.agentscope;

import io.github.bitlhk.finance.harness.core.HarnessJson;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProviderConfigurationDeclarationTest {
    private static final String FILE = """
        {"id":"source","pathEnvironment":"FH_SOURCE_FILE","digestEnvironment":"FH_SOURCE_SHA256","maxBytes":262144}
        """;

    @Test void optionalExtensionKeepsLegacyDeclarationsStrict() throws Exception {
        var mapper = HarnessJson.mapper();
        var legacy = mapper.readValue("{}", AgentScopeQueryAssembler.ProviderDeclaration.class);
        assertTrue(legacy.configurationFiles().isEmpty());
        var configured = mapper.readValue("{\"configurationFiles\":[" + FILE + "]}", AgentScopeQueryAssembler.ProviderDeclaration.class);
        assertEquals("source", configured.configurationFiles().getFirst().id());
        assertThrows(Exception.class, () -> mapper.readValue("{\"command\":\"never-execute\"}", AgentScopeQueryAssembler.ProviderDeclaration.class));
        assertThrows(Exception.class, () -> mapper.readValue(FILE.replace("262144", "262145"), AgentScopeQueryAssembler.ConfigurationFile.class));
        assertThrows(Exception.class, () -> mapper.readValue(FILE.replace("FH_SOURCE_SHA256", "FH_SOURCE_FILE"), AgentScopeQueryAssembler.ConfigurationFile.class));
        assertThrows(Exception.class, () -> mapper.readValue("{\"configurationFiles\":[" + FILE + "," + FILE + "]}", AgentScopeQueryAssembler.ProviderDeclaration.class));
    }
}
