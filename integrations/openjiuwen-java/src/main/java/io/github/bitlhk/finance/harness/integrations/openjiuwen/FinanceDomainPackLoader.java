package io.github.bitlhk.finance.harness.integrations.openjiuwen;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Loads a domain pack and its referenced Skill instructions without following paths outside the pack. */
public final class FinanceDomainPackLoader {
    private final ObjectMapper mapper = new ObjectMapper(new YAMLFactory())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public LoadedDomainPack load(Path packFile) throws IOException {
        Path normalizedPack = packFile.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalizedPack)) {
            throw new IllegalArgumentException("Domain Pack does not exist: " + packFile);
        }
        FinanceDomainPackV1 pack = mapper.readValue(normalizedPack.toFile(), FinanceDomainPackV1.class);
        Path root = normalizedPack.getParent();
        List<LoadedSkill> skills = new ArrayList<>();
        for (FinanceDomainPackV1.Skill skill : pack.skills()) {
            Path entry = root.resolve(skill.entry()).normalize();
            if (!entry.startsWith(root) || !Files.isRegularFile(entry)) {
                throw new IllegalArgumentException("Domain Pack skill entry is invalid: " + skill.entry());
            }
            String raw = Files.readString(entry, StandardCharsets.UTF_8);
            skills.add(new LoadedSkill(
                    skill.directory(),
                    skill.entry(),
                    stripFrontMatter(raw),
                    sha256(raw.getBytes(StandardCharsets.UTF_8))));
        }
        return new LoadedDomainPack(pack, normalizedPack, List.copyOf(skills));
    }

    private static String stripFrontMatter(String content) {
        String normalized = content.replace("\r\n", "\n");
        if (!normalized.startsWith("---\n")) return normalized.trim();
        int closing = normalized.indexOf("\n---\n", 4);
        return closing < 0 ? normalized.trim() : normalized.substring(closing + 5).trim();
    }

    private static String sha256(byte[] value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record LoadedDomainPack(
            FinanceDomainPackV1 pack,
            Path packFile,
            List<LoadedSkill> skills) {
        public LoadedDomainPack {
            skills = List.copyOf(skills);
        }
    }

    public record LoadedSkill(
            String directory,
            String entry,
            String instructions,
            String digest) {
    }
}
