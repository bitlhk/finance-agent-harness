package io.github.bitlhk.finance.harness.examples.creditaudit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import static org.junit.jupiter.api.Assertions.*;

class AuditTaskAuthorityTest {
    @TempDir Path root;
    @Test void checksCurrentTaskGenerationAndOwnerRatherThanCachedAllowance() throws Exception {
        Path file=root.resolve("task.json");
        Files.writeString(file,"""
                {"ownerRef":"owner","principalId":"principal","sessionRef":"session","materialSetRef":"materials","generation":2,"status":"READY"}
                """);
        Runnable guard=EnterpriseCreditAuditApplication.taskAuthority(file,2,"owner","principal","session","materials");
        guard.run();
        Files.writeString(file,Files.readString(file).replace("\"generation\":2","\"generation\":3"));
        assertThrows(SecurityException.class,guard::run);
    }
    @Test void rejectsMissingFileWrongOwnerAndCanceledTask() throws Exception {
        Path file=root.resolve("task.json");
        Runnable guard=EnterpriseCreditAuditApplication.taskAuthority(file,2,"owner","principal","session","materials");
        assertThrows(SecurityException.class,guard::run);
        Files.writeString(file,"""
                {"ownerRef":"other","principalId":"principal","sessionRef":"session","materialSetRef":"materials","generation":2,"status":"READY"}
                """);
        assertThrows(SecurityException.class,guard::run);
        Files.writeString(file,Files.readString(file).replace("other","owner").replace("READY","CANCELED"));
        assertThrows(SecurityException.class,guard::run);
    }
}
