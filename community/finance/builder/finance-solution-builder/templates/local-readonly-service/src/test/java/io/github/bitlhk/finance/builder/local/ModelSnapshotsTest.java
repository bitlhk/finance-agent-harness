package io.github.bitlhk.finance.builder.local;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.RejectedExecutionException;
import org.junit.Test;
import static org.junit.Assert.*;

public class ModelSnapshotsTest {
    @Test public void freezesRedactsTruncatesAndMarksQueueFailure() throws Exception {
        Path temp=Files.createTempDirectory("snapshot-test");
        try {
            Path candidate=Files.createDirectory(temp.resolve("candidate"));
            Files.writeString(candidate.resolve("service-lock.json"), "{}");
            var archive=new ExecutionArchive(candidate,"build","instance");
            var request=archive.begin(candidate,"session","subject",0);
            var queued=new ArrayList<Runnable>();
            var snapshots=new ModelSnapshots(request,()->"model-span",List.of("private-key", "customer-secret"),true,queued::add);
            var message=new HashMap<String,Object>();message.put("content","private-key customer-secret <script>text</script>");
            snapshots.capture("call-1","CLIENT_REQUEST_PREPARED","CAPTURED",Map.of("messages",List.of(message),"reasoning_content","HIDDEN"));
            message.put("content","MUTATED_AFTER_CAPTURE");queued.removeFirst().run();
            Path file=request.snapshotDirectory().resolve(request.id+".call-1.request.json");
            String body=Files.readString(file);
            assertFalse(body.contains("private-key"));assertFalse(body.contains("customer-secret"));
            assertFalse(body.contains("MUTATED_AFTER_CAPTURE"));assertFalse(body.contains("HIDDEN"));
            assertTrue(body.contains("REDACTED"));
            assertTrue(Files.readString(request.file).contains("WRITTEN"));
            snapshots.capture("call-2","PARSED_MODEL_RESPONSE","CAPTURED",Map.of("content","x".repeat(60_000)));
            queued.removeFirst().run();
            assertTrue(Files.readString(request.snapshotDirectory().resolve(request.id+".call-2.response.json")).contains("TRUNCATED"));
            var failed=new ModelSnapshots(request,()->"model-span",List.of(),true,r->{throw new RejectedExecutionException();});
            failed.capture("call-3","CLIENT_REQUEST_PREPARED","CAPTURED",Map.of("content","PRIVATE_NOT_ENQUEUED"));
            String metadata=Files.readString(archive.directory.resolve(request.id+".jsonl"));
            assertTrue(metadata.contains("UNAVAILABLE"));assertFalse(metadata.contains("PRIVATE_NOT_ENQUEUED"));
            assertTrue(metadata.contains("BODY_QUEUE_REJECTED"));
            Files.delete(archive.directory.resolve(request.id+".jsonl"));
            snapshots.capture("call-4","CLIENT_REQUEST_PREPARED","CAPTURED",Map.of("content","NO_REFERENCE"));
            assertTrue("No body may be queued without a durable reference",queued.isEmpty());
        } finally { remove(temp); }
    }
    @Test public void unavailableDirectoryAndQuotaDoNotCreateBodyOrThrow() throws Exception {
        Path temp=Files.createTempDirectory("snapshot-failure-test");
        try {
            Path candidate=Files.createDirectory(temp.resolve("candidate"));Files.writeString(candidate.resolve("service-lock.json"),"{}");
            var archive=new ExecutionArchive(candidate,"build","instance");var request=archive.begin(candidate,"session","subject",0);
            Files.writeString(request.snapshotDirectory(),"blocked");
            var snapshots=new ModelSnapshots(request,()->"model-span",List.of(),true,Runnable::run);
            snapshots.capture("call-1","CLIENT_REQUEST_PREPARED","CAPTURED",Map.of("content","synthetic"));
            assertTrue(Files.isRegularFile(request.snapshotDirectory()));
            assertTrue(Files.readString(request.file).contains("BODY_WRITE_FAILED"));
            Files.delete(request.snapshotDirectory());Files.createDirectory(request.snapshotDirectory());
            int quota=SnapshotStorage.Policy.from(System.getenv()).files();
            for(int i=0;i<quota;i++)Files.writeString(request.snapshotDirectory().resolve(i+".json"),"{}");
            snapshots.capture("call-2","CLIENT_REQUEST_PREPARED","CAPTURED",Map.of("content","synthetic"));
            assertFalse(Files.exists(request.snapshotDirectory().resolve(request.id+".call-2.request.json")));
            assertTrue(Files.readString(request.file).contains("BODY_STORAGE_QUOTA"));
        } finally { remove(temp); }
    }
    private static void remove(Path root) throws Exception {
        try(var files=Files.walk(root)){for(Path file:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(file);}
    }
}
