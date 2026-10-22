package com.animeshelf.app;

import static org.junit.Assert.*;
import static com.animeshelf.app.DownloadCatalogueTest.*;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/** Deterministic resolver callbacks exercise cancellation and queueing without network or sleeps. */
public class BatchDownloadSessionTest {
    @Test public void episodesResolveSeriallyInNumericOrderAndRepeatedStartDoesNothing() {
        Fixture f = new Fixture(10, 2, 1);
        f.job.start();
        f.job.start();
        f.pump();
        assertEquals(Collections.singletonList("ep1"), f.gateway.requested);
        f.succeed("ep1");
        assertEquals(Collections.singletonList("ep1"), f.gateway.requested);
        f.pump();
        assertEquals(Arrays.asList("ep1", "ep2"), f.gateway.requested);
        f.succeed("ep2"); f.pump();
        f.succeed("ep10"); f.pump();

        assertEquals(Arrays.asList("ep1", "ep2", "ep10"), f.gateway.enqueued);
        assertEquals(3, f.job.added());
        assertEquals(3, f.job.processed());
        assertTrue(f.job.isFinished());
        assertEquals(1, f.finished);
    }

    @Test public void cancellationKeepsAlreadyQueuedEpisodesAndDiscardsLateResolverCallbacks() {
        Fixture f = new Fixture(1, 2, 3);
        f.job.start(); f.pump();
        f.succeed("ep1"); f.pump();
        BatchDownloadSession.Result late = f.gateway.pending.get("ep2");

        f.job.cancel();
        late.success(Collections.singletonList(f.profile));
        late.failure(new IOException("late error"));
        f.pump();
        f.job.cancel();

        assertTrue(f.job.isCancelled());
        assertTrue(f.job.isFinished());
        assertEquals(Collections.singletonList("ep1"), f.gateway.enqueued);
        assertEquals(Arrays.asList("ep1", "ep2"), f.gateway.requested);
        assertEquals(1, f.job.added());
        assertEquals(1, f.finished);
        assertNull(f.job.current());
    }

    @Test public void repeatedOrOlderCallbacksCannotAddAnEpisodeTwiceOrCompleteTheNextOne() {
        Fixture f = new Fixture(1, 2);
        f.job.start(); f.pump();
        BatchDownloadSession.Result first = f.gateway.pending.get("ep1");
        first.success(Collections.singletonList(f.profile));
        first.success(Collections.singletonList(f.profile));
        first.failure(new IOException("duplicate"));
        f.pump();
        first.success(Collections.singletonList(f.profile));
        assertEquals(1, f.job.processed());
        assertEquals("ep2", f.job.current().id);
        f.succeed("ep2"); f.pump();

        assertEquals(Arrays.asList("ep1", "ep2"), f.gateway.enqueued);
        assertEquals(2, f.job.added());
        assertEquals(0, f.job.failed());
        assertEquals(1, f.finished);
    }

    @Test public void missingVideoProfileIsReportedWithoutDownloadingALowerQualityOrDifferentCodec() {
        Fixture f = new Fixture(1, 2);
        f.job.start(); f.pump();
        f.gateway.pending.get("ep1").success(Arrays.asList(quality(80, "7", "30280"), quality(112, "12", "30280")));
        f.pump();
        Models.Quality actual = quality(112, "7", "30216");
        f.gateway.pending.get("ep2").success(Collections.singletonList(actual));
        f.pump();

        assertEquals(Collections.singletonList("ep2"), f.gateway.enqueued);
        assertSame(actual, f.gateway.enqueuedQualities.get(0));
        assertEquals(1, f.job.unavailable());
        assertEquals(1, f.job.added());
        assertEquals(0, f.job.failed());
        assertEquals(2, f.job.processed());
    }

    @Test public void completeAndPendingVersionsAreSkippedWhileFailedVersionsAreResolvedAgain() {
        Fixture f = new Fixture(1, 2, 3);
        MediaRepository.OfflineItem complete = f.existing("ep1"); complete.complete = true;
        f.existing("ep2"); // Metadata exists before Media3 has created either track.
        MediaRepository.OfflineItem failed = f.existing("ep3"); failed.failed = true;
        f.job.start(); f.pump();
        assertEquals(Collections.singletonList("ep3"), f.gateway.requested);
        f.succeed("ep3"); f.pump();

        assertEquals(Collections.singletonList("ep3"), f.gateway.enqueued);
        assertEquals(2, f.job.skipped());
        assertEquals(1, f.job.added());
        assertEquals(0, f.job.failed());
        assertEquals(3, f.job.processed());
    }

    @Test public void anEpisodeQueuedElsewhereDuringResolutionIsNotAddedAgain() {
        Fixture f = new Fixture(1);
        f.job.start(); f.pump();
        f.existing("ep1");
        f.succeed("ep1"); f.pump();

        assertTrue(f.gateway.enqueued.isEmpty());
        assertEquals(1, f.job.skipped());
        assertEquals(1, f.job.processed());
        assertEquals(1, f.finished);
    }

    @Test public void aFailedTaskDeletedOrReplacedDuringResolutionIsNotResurrected() {
        for (boolean replaced : new boolean[]{false, true}) {
            Fixture f = new Fixture(1);
            MediaRepository.OfflineItem failed = f.existing("ep1");
            failed.failed = true;
            f.job.start(); f.pump();
            f.gateway.saved.remove("ep1");
            if (replaced) {
                MediaRepository.OfflineItem newer = f.existing("ep1");
                newer.key = "newer-request"; newer.failed = true;
            }
            f.succeed("ep1"); f.pump();

            assertTrue(f.gateway.enqueued.isEmpty());
            assertEquals(0, f.job.added());
            assertEquals(1, f.job.skipped());
            assertTrue(f.job.isFinished());
            assertEquals(1, f.finished);
        }
    }

    @Test public void resolutionAndEnqueueErrorsDoNotAbortLaterEpisodesOrExposeRemoteMessages() {
        Fixture f = new Fixture(1, 2, 3);
        f.gateway.failEnqueue = "ep2";
        f.job.start(); f.pump();
        f.gateway.pending.get("ep1").failure(new IOException("https://private.example/video?SESSDATA=secret_cookie"));
        f.pump();
        f.succeed("ep2"); f.pump();
        f.succeed("ep3"); f.pump();

        assertEquals(Collections.singletonList("ep3"), f.gateway.enqueued);
        assertEquals(2, f.job.failed());
        assertEquals(1, f.job.added());
        assertEquals(3, f.job.processed());
        assertTrue(f.job.isFinished());
        assertEquals(1, f.finished);
        String report = f.job.summary() + f.job.details();
        assertFalse(report.contains("secret_cookie"));
        assertFalse(report.contains("private.example"));
        assertFalse(report.contains("SESSDATA"));
    }

    @Test public void cancellingBeforeTheScheduledFirstStepStartsNoResolution() {
        Fixture f = new Fixture(1, 2);
        f.job.start();
        f.job.cancel();
        f.pump();
        f.job.start();

        assertTrue(f.gateway.requested.isEmpty());
        assertTrue(f.gateway.enqueued.isEmpty());
        assertEquals(0, f.job.processed());
        assertEquals(1, f.finished);
    }

    private static final class Fixture {
        final ArrayDeque<Runnable> scheduled = new ArrayDeque<>();
        final FakeGateway gateway = new FakeGateway();
        final Models.Quality profile = quality(112, "7", "30280");
        final BatchDownloadSession job;
        int finished;
        Fixture(int... indices) {
            List<Models.Episode> episodes = new ArrayList<>();
            for (int index : indices) episodes.add(episode("ss1", "番剧", "ep" + index, index));
            job = new BatchDownloadSession(episodes, profile, gateway, scheduled::addLast, new BatchDownloadSession.Listener() {
                @Override public void changed(BatchDownloadSession value) { }
                @Override public void finished(BatchDownloadSession value) { finished++; }
            });
        }
        void pump() {
            int steps = 0;
            while (!scheduled.isEmpty()) {
                assertTrue("Scheduler must make progress", ++steps < 100);
                scheduled.removeFirst().run();
            }
        }
        void succeed(String id) { gateway.pending.get(id).success(Collections.singletonList(profile)); }
        MediaRepository.OfflineItem existing(String id) {
            MediaRepository.OfflineItem item = item("saved-" + id, "ss1", "番剧", id, 1, profile);
            gateway.saved.put(id, item);
            return item;
        }
    }

    private static final class FakeGateway implements BatchDownloadSession.Gateway {
        final Map<String, MediaRepository.OfflineItem> saved = new HashMap<>();
        final Map<String, BatchDownloadSession.Result> pending = new HashMap<>();
        final List<String> requested = new ArrayList<>(), enqueued = new ArrayList<>();
        final List<Models.Quality> enqueuedQualities = new ArrayList<>();
        String failEnqueue;
        @Override public MediaRepository.OfflineItem existing(Models.Episode episode, Models.Quality quality) {
            return saved.get(episode.id);
        }
        @Override public void resolve(Models.Episode episode, BatchDownloadSession.Result callback) {
            requested.add(episode.id); pending.put(episode.id, callback);
        }
        @Override public void enqueue(Models.Episode episode, Models.Quality quality) throws Exception {
            if (episode.id.equals(failEnqueue)) throw new IOException("private.example?SESSDATA=secret_cookie");
            enqueued.add(episode.id); enqueuedQualities.add(quality);
            saved.put(episode.id, item("queued-" + episode.id, episode.seriesId, episode.seriesTitle,
                episode.id, episode.index, quality));
        }
    }
}
