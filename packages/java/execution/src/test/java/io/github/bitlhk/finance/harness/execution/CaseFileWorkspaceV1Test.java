package io.github.bitlhk.finance.harness.execution;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import static org.junit.jupiter.api.Assertions.*;

class CaseFileWorkspaceV1Test {
    private static CaseFileWorkspaceV1 workspace(String path, String content) throws Exception {
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest((path + "\0" + content + "\0").getBytes(StandardCharsets.UTF_8)));
        return new CaseFileWorkspaceV1(List.of(Map.of("path", path, "content", content)), digest);
    }
    @Test void readsOnlyDeclaredImmutableDataAndRecordsActualReads() throws Exception {
        try (var a = workspace("inputs/a.txt", "synthetic A"); var b = workspace("inputs/a.txt", "synthetic B")) {
            assertEquals(List.of(), a.evidence().get("reads"));
            assertEquals("synthetic A", a.read(Map.of("path", "inputs/a.txt")).get("content"));
            assertEquals("synthetic B", b.read(Map.of("path", "inputs/a.txt")).get("content"));
            assertEquals(1, ((List<?>) a.evidence().get("reads")).size());
            assertThrows(IllegalArgumentException.class, () -> a.read(Map.of("path", "../secret")));
            assertThrows(IllegalArgumentException.class, () -> a.read(Map.of("path", "/etc/passwd")));
            assertThrows(IllegalArgumentException.class, () -> a.read(Map.of("path", "inputs/a.txt", "extra", true)));
        }
    }
    @Test void rejectsUnsafePathsMalformedTextAndDigestDrift() {
        for (String path : List.of("../a", "/a", "a//b", "a\\b", "a:b", "a/./b", "a\0b"))
            assertThrows(IllegalArgumentException.class, () -> workspace(path, "data"));
        assertThrows(IllegalArgumentException.class, () -> workspace("a", "\ud800"));
        assertThrows(IllegalArgumentException.class, () -> new CaseFileWorkspaceV1(List.of(Map.of("path", "a", "content", "data")), "0".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> new CaseFileWorkspaceV1(List.of(Map.of("path", "a", "content", ""), Map.of("path", "a/b", "content", "")), "0".repeat(64)));
    }
    @Test void closedWorkspaceCannotBeReused() throws Exception {
        var workspace = workspace("a", "data"); workspace.close(); workspace.close();
        assertEquals(true, workspace.evidence().get("closed"));
        assertThrows(IllegalStateException.class, () -> workspace.read(Map.of("path", "a")));
        assertThrows(IllegalStateException.class, workspace::manifest);
    }
}
