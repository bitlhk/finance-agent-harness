package io.github.bitlhk.finance.builder.local;

import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class SnapshotStorageTest {
    private static String name(int n){return "00000000-0000-0000-0000-000000000000.call-"+n+".request.json";}
    @Test public void expiryUsesInjectedTimeAndPreservesMetadata() throws Exception {
        Path root=Files.createTempDirectory("snapshot-retention");
        try {
            Path bodies=root.resolve("model-snapshots");Files.createDirectories(bodies);
            Path index=root.resolve("request.jsonl");Files.writeString(index,"keep");
            Path other=bodies.resolve("unrelated.json");Files.writeString(other,"keep");
            var policy=new SnapshotStorage.Policy(4,65536,60,1000);
            var before=Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"),ZoneOffset.UTC);
            new SnapshotStorage(bodies,policy,before).put(name(1),"{}".getBytes());
            Files.setLastModifiedTime(bodies.resolve(name(1)),FileTime.from(before.instant()));
            new SnapshotStorage(bodies,policy,Clock.offset(before,Duration.ofSeconds(59))).put(name(2),"{}".getBytes());
            assertTrue(Files.exists(bodies.resolve(name(1))));
            new SnapshotStorage(bodies,policy,Clock.offset(before,Duration.ofSeconds(60))).put(name(3),"{}".getBytes());
            assertFalse(Files.exists(bodies.resolve(name(1))));
            assertTrue(Files.readString(bodies.resolve(name(1)+".expired")).contains("EXPIRED"));
            assertEquals("keep",Files.readString(index));assertEquals("keep",Files.readString(other));
        } finally {remove(root);}
    }
    @Test public void quotasNoDeletionByDefaultAndConflictFailClosed() throws Exception {
        var defaults=SnapshotStorage.Policy.from(Map.of());assertEquals(0,defaults.retentionSeconds());
        assertThrows(IllegalArgumentException.class,()->SnapshotStorage.Policy.from(Map.of("FH_SNAPSHOT_MAX_BYTES","0")));
        assertThrows(IllegalArgumentException.class,()->SnapshotStorage.validateConfiguration(Map.of(
            "FH_SNAPSHOTS_ENABLED","true","FH_OTHER_MODEL_BODY_COLLECTOR","external")));
        SnapshotStorage.validateConfiguration(Map.of("FH_SNAPSHOTS_ENABLED","false","FH_OTHER_MODEL_BODY_COLLECTOR","external"));
        Path root=Files.createTempDirectory("snapshot-quota");
        try {
            var storage=new SnapshotStorage(root,new SnapshotStorage.Policy(1,65536,0,1000),Clock.systemUTC());
            storage.put(name(1),"{}".getBytes());
            assertThrows(IllegalStateException.class,()->storage.put(name(2),"{}".getBytes()));
            assertTrue(Files.exists(root.resolve(name(1))));
            assertThrows(IllegalArgumentException.class,()->storage.put("../outside.json","{}".getBytes()));
        } finally {remove(root);}
    }
    @Test public void flushDrainsOrExplicitlyTimesOut() throws Exception {
        var completed=Executors.newSingleThreadExecutor();var flag=new java.util.concurrent.atomic.AtomicBoolean();
        completed.submit(()->flag.set(true));assertTrue(ModelSnapshots.drain(completed,1000));assertTrue(flag.get());
        var blocked=Executors.newSingleThreadExecutor();var latch=new CountDownLatch(1);
        blocked.submit(()->{try{latch.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});
        try{assertFalse(ModelSnapshots.drain(blocked,1));}finally{latch.countDown();assertTrue(blocked.awaitTermination(1,TimeUnit.SECONDS));}
    }
    private static void remove(Path root)throws Exception {
        try(var paths=Files.walk(root)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}
    }
}
