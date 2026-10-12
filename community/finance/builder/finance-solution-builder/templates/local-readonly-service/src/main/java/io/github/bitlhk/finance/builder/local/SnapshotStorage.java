package io.github.bitlhk.finance.builder.local;

import io.github.bitlhk.finance.harness.core.HarnessJson;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.time.Clock;
import java.util.*;

/** Owns only snapshot bodies; request evidence and installation locks are never removed. */
final class SnapshotStorage {
    record Policy(int files, long bytes, long retentionSeconds, int flushMillis) {
        static Policy from(Map<String,String> env) {
            return new Policy((int)number(env,"FH_SNAPSHOT_MAX_FILES",128,1,4096),
                number(env,"FH_SNAPSHOT_MAX_BYTES",16_777_216,65_536,1_073_741_824),
                number(env,"FH_SNAPSHOT_RETENTION_SECONDS",0,0,31_536_000),
                (int)number(env,"FH_SNAPSHOT_FLUSH_MILLIS",2000,1,30_000));
        }
        private static long number(Map<String,String> env,String key,long fallback,long min,long max) {
            try { long value=Long.parseLong(env.getOrDefault(key,Long.toString(fallback)));
                if(value<min || value>max)throw new IllegalArgumentException();return value;
            } catch(RuntimeException invalid) { throw new IllegalArgumentException("INVALID_SNAPSHOT_CONFIGURATION:"+key); }
        }
    }
    static void validateConfiguration(Map<String,String> env) {
        if(!"true".equals(env.get("FH_SNAPSHOTS_ENABLED")))return;
        Policy.from(env);
        if(!"none".equals(env.getOrDefault("FH_OTHER_MODEL_BODY_COLLECTOR","none")))
            throw new IllegalArgumentException("MODEL_BODY_COLLECTOR_CONFLICT");
    }
    /** Startup probe only: does not expire bodies or promise capacity for a future capture. */
    static boolean available(Path directory, Map<String,String> env) {
        Path temporary = null;
        try {
            validateConfiguration(env);
            var policy = Policy.from(env);
            if (Files.isSymbolicLink(directory)) return false;
            Files.createDirectories(directory);
            Files.setPosixFilePermissions(directory, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
            long total = 0;
            int count = 0;
            try (var files = Files.list(directory)) {
                for (var path : files.toList()) {
                    if (Files.isSymbolicLink(path)) return false;
                    if (Files.isRegularFile(path) && path.toString().endsWith(".json")) {
                        count++;
                        total = Math.addExact(total, Files.size(path));
                    }
                }
            }
            if (count >= policy.files() || total >= policy.bytes()) return false;
            temporary = Files.createTempFile(directory, ".snapshot-admission-", ".tmp");
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                channel.write(java.nio.ByteBuffer.wrap(new byte[]{0}));
                channel.force(true);
            }
            return true;
        } catch (Exception unavailable) {
            return false;
        } finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (Exception ignored) { }
        }
    }
    private static final java.util.regex.Pattern BODY = java.util.regex.Pattern.compile(
        "[a-f0-9-]{36}\\.[A-Za-z0-9-]{1,64}\\.(request|response)\\.json");
    private final Path directory;
    private final Policy policy;
    private final Clock clock;
    SnapshotStorage(Path directory,Policy policy,Clock clock) {this.directory=directory;this.policy=policy;this.clock=clock;}
    void put(String filename,byte[] bytes) throws Exception {
        if(!BODY.matcher(filename).matches())throw new IllegalArgumentException("INVALID_SNAPSHOT_NAME");
        Files.createDirectories(directory);
        if(Files.isSymbolicLink(directory))throw new IllegalStateException("SNAPSHOT_DIRECTORY_SYMLINK");
        Files.setPosixFilePermissions(directory,java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        try(var channel=FileChannel.open(directory.resolve(".quota.lock"),StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);var lock=channel.lock()) {
            expire();
            long total=0;int count=0;
            try(var files=Files.list(directory)) {
                for(Path p:files.toList()) {
                    if(Files.isSymbolicLink(p))throw new IllegalStateException("SNAPSHOT_FILE_SYMLINK");
                    if(Files.isRegularFile(p) && p.toString().endsWith(".json")){count++;total+=Files.size(p);}
                }
            }
            if(count>=policy.files || total+bytes.length>policy.bytes)throw new IllegalStateException("SNAPSHOT_QUOTA");
            durable(directory.resolve(filename),bytes);
        }
    }
    private void expire() throws Exception {
        if(policy.retentionSeconds==0)return;
        try(var files=Files.list(directory)) {
            for(Path p:files.toList()) {
                if(!BODY.matcher(p.getFileName().toString()).matches() || !Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS))continue;
                if(Files.getLastModifiedTime(p,LinkOption.NOFOLLOW_LINKS).toInstant()
                        .plusSeconds(policy.retentionSeconds).isAfter(clock.instant()))continue;
                Path marker=p.resolveSibling(p.getFileName()+".expired");
                String digest=ExecutionArchive.hash(Files.readAllBytes(p));
                if(!Files.exists(marker,LinkOption.NOFOLLOW_LINKS))durable(marker,HarnessJson.mapper().writeValueAsBytes(
                        Map.of("state","EXPIRED","digest",digest,"expiredAt",clock.instant().toString())));
                else if(!digest.equals(HarnessJson.mapper().readTree(Files.readAllBytes(marker)).path("digest").asText()))
                    throw new IllegalStateException("EXPIRY_MARKER_CONFLICT");
                Files.delete(p);
            }
        }
    }
    private void durable(Path path,byte[] data) throws Exception {
        Files.createFile(path);
        Files.setPosixFilePermissions(path,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        try(var channel=FileChannel.open(path,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
            var buffer=java.nio.ByteBuffer.wrap(data);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);
        }
    }
}
