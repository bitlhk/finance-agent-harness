package io.github.bitlhk.finance.harness.assembly;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** Loads a capability pack without coupling the pack format to a specific application. */
public final class FinanceCapabilityPackLoader {
    private final ObjectMapper mapper;

    public FinanceCapabilityPackLoader() {
        mapper = new ObjectMapper(new YAMLFactory())
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    public FinanceCapabilityPackV1 load(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        try (InputStream stream = Files.newInputStream(path)) {
            return load(stream);
        }
    }

    public FinanceCapabilityPackV1 load(InputStream stream) throws IOException {
        return mapper.readValue(Objects.requireNonNull(stream, "stream"), FinanceCapabilityPackV1.class);
    }
}
