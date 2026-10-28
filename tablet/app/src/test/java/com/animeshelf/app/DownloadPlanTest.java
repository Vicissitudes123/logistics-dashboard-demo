package com.animeshelf.app;

import static org.junit.Assert.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class) @Config(sdk = 28)
public class DownloadPlanTest {
    private Models.Episode ep(int i) { Models.Episode e = new Models.Episode(); e.id = "ep"+i; e.index = i; e.seriesId = "s"; return e; }
    private static class Gateway implements DownloadPlan.Gateway {
        Map<String,List<Models.Quality>> choices = new HashMap<>(); Map<String,MediaRepository.OfflineItem> old = new HashMap<>();
        int requests, checked;
        public List<Models.Quality> resolve(Models.Episode e) { requests++; return choices.getOrDefault(e.id, Collections.emptyList()); }
        public MediaRepository.OfflineItem existing(Models.Episode e, Models.Quality q) { return old.get(e.id); }
        public void checked(int done, int total) { checked = done; }
    }
    @Test public void onlyMatchingVersionsContributeToTotalsAndExistingTasksAreSkipped() {
        Models.Episode a=ep(1), b=ep(2), c=ep(3); Models.Quality q=DownloadEstimateTest.quality(); Gateway g=new Gateway();
        g.choices.put(a.id, Collections.singletonList(q));
        MediaRepository.OfflineItem complete=new MediaRepository.OfflineItem();complete.complete=true;g.old.put(b.id,complete);
        DownloadPlan plan=DownloadPlan.prepare(Arrays.asList(c,b,a),q,g,()->false);
        assertEquals(1,plan.items.size());assertEquals(a.id,plan.items.get(0).episode.id);
        assertEquals(123840000,plan.knownBytes);assertFalse(plan.allExact);assertEquals(2,plan.skipped.size());assertEquals(2,g.requests);
    }
    @Test public void failedMatchingResourceSubtractsCachedBytesButDifferentResourcesDoNot() {
        Models.Episode a=ep(1);Models.Quality q=DownloadEstimateTest.quality();q.videoBytes=1000;q.audioBytes=100;
        Gateway g=new Gateway();g.choices.put(a.id,Collections.singletonList(q));
        MediaRepository.OfflineItem failed=new MediaRepository.OfflineItem();failed.failed=true;failed.bytesDownloaded=300;failed.key=MediaRepository.key(a,q);g.old.put(a.id,failed);
        assertEquals(800,DownloadPlan.prepare(Collections.singletonList(a),q,g,()->false).knownBytes);
        failed.key="different-resource";assertEquals(1100,DownloadPlan.prepare(Collections.singletonList(a),q,g,()->false).knownBytes);
    }
    @Test public void missingSizingMetadataIsCountedSeparatelyFromKnownTotal() {
        Models.Episode a=ep(1),b=ep(2);Models.Quality q=DownloadEstimateTest.quality(), unknown=DownloadEstimateTest.quality();unknown.durationMs=0;
        Gateway g=new Gateway();g.choices.put(a.id,Collections.singletonList(q));g.choices.put(b.id,Collections.singletonList(unknown));
        DownloadPlan plan=DownloadPlan.prepare(Arrays.asList(a,b),q,g,()->false);
        assertEquals(2,plan.items.size());assertEquals(1,plan.unknownCount);assertEquals(123840000,plan.knownBytes);
    }
    @Test public void cancelBeforeOrDuringResolutionNeverProducesAReadyPlan() {
        Models.Quality q=DownloadEstimateTest.quality();AtomicBoolean cancelled=new AtomicBoolean(true);Gateway g=new Gateway();
        try { DownloadPlan.prepare(Collections.singletonList(ep(1)),q,g,cancelled::get);fail(); } catch(CancellationException expected) { }
        assertEquals(0,g.requests);cancelled.set(false);
        Gateway late=new Gateway(){public List<Models.Quality> resolve(Models.Episode e){cancelled.set(true);return Collections.singletonList(q);}};
        try { DownloadPlan.prepare(Collections.singletonList(ep(1)),q,late,cancelled::get);fail(); } catch(CancellationException expected) { }
    }
}
