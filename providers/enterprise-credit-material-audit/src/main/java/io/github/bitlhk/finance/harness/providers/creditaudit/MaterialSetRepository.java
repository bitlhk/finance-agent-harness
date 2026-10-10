package io.github.bitlhk.finance.harness.providers.creditaudit;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bitlhk.finance.harness.core.FinanceContextV1;
import io.github.bitlhk.finance.harness.core.HarnessJson;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.regex.Pattern;

/** Reads host-created immutable material sets; model supplied paths are never accepted. */
public final class MaterialSetRepository {
    private static final Pattern REF = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9._-]{2,95}$");
    private final Path root;
    private final ObjectMapper json = HarnessJson.mapper();

    public MaterialSetRepository(Path root) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
    }

    public MaterialSet load(String reference, FinanceContextV1 context) throws IOException {
        if (reference == null || !REF.matcher(reference).matches()) {
            throw new IllegalArgumentException("MATERIAL_SET_REF_INVALID");
        }
        Path file = root.resolve(reference + ".json").normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file) || Files.isSymbolicLink(file)) {
            throw new IllegalArgumentException("MATERIAL_SET_NOT_FOUND");
        }
        MaterialSet set = json.readValue(file.toFile(), MaterialSet.class);
        if (!"finance-material-set.v0.1".equals(set.schemaVersion()) || !reference.equals(set.materialSetId())) {
            throw new IllegalArgumentException("MATERIAL_SET_CONTRACT_INVALID");
        }
        if (!Objects.equals(set.ownerRef(), context.subjectRef())
                || !Objects.equals(set.principalId(), context.principalId())
                || !Objects.equals(set.sessionRef(), context.sessionRef())) {
            throw new SecurityException("MATERIAL_SET_SCOPE_MISMATCH");
        }
        return set;
    }
}
