package com.animeshelf.app;

import static org.junit.Assert.*;
import android.content.SharedPreferences;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class DownloadStoragePolicyTest {
    @Test public void lowSpaceDuringTransferLatchesAcrossRestartUntilExplicitResume() {
        SharedPreferences prefs = RuntimeEnvironment.getApplication().getSharedPreferences("space_test", 0); prefs.edit().clear().commit();
        AtomicLong free = new AtomicLong(500_000_000L), clock = new AtomicLong();
        DownloadStoragePolicy policy = new DownloadStoragePolicy(prefs, free::get, clock::get);
        assertTrue(policy.allowsRead()); free.set(100_000_000L); clock.set(500);
        assertFalse(policy.allowsRead()); assertTrue(policy.isBlocked());
        policy = new DownloadStoragePolicy(prefs, free::get, clock::get); assertFalse(policy.allowsRead());
        free.set(500_000_000L); assertFalse(policy.allowsRead()); policy.resume(); assertTrue(policy.allowsRead());
    }
    @Test public void resumingTooEarlyOrWithUnknownSpaceRetainsPause() {
        SharedPreferences prefs = RuntimeEnvironment.getApplication().getSharedPreferences("space_test_resume", 0); prefs.edit().clear().commit();
        AtomicLong free = new AtomicLong(0); DownloadStoragePolicy policy = new DownloadStoragePolicy(prefs, free::get, () -> 0);
        assertFalse(policy.allowsRead()); free.set(DownloadStoragePolicy.PAUSE_BYTES + 1);
        assertThrows(DownloadStoragePolicy.SpacePausedException.class, policy::resume); assertTrue(policy.isBlocked());
        free.set(-1); assertThrows(DownloadStoragePolicy.SpacePausedException.class, policy::resume);
        free.set(DownloadStoragePolicy.RESUME_BYTES); policy.resume(); assertFalse(policy.isBlocked());
    }
}
