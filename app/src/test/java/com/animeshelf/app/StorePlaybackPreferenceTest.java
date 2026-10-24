package com.animeshelf.app;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class StorePlaybackPreferenceTest {
    private Context context;
    private SharedPreferences prefs;
    private Store store;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE);
        prefs.edit().clear().commit(); store = new Store(context);
    }

    @Test public void eachSeriesRemembersItsOwnModeAndProfileAcrossStoreInstances() {
        assertNull(store.getSeriesPlaybackPreference("ss1"));
        store.setSeriesPlaybackPreference("ss1", false, quality(125, "12"));
        store.setSeriesPlaybackPreference("ss2", true, quality(80, "7"));
        Store reopened = new Store(context);
        assertFalse(reopened.getSeriesPlaybackPreference("ss1").offline);
        assertEquals("125:12:0", reopened.getSeriesPlaybackPreference("ss1").quality.id);
        assertTrue(reopened.getSeriesPlaybackPreference("ss2").offline);
        assertEquals("80:7:0", reopened.getSeriesPlaybackPreference("ss2").quality.id);
        store.setRememberQuality(false);
        assertEquals("125:12:0", reopened.getSeriesPlaybackPreference("ss1").quality.id);
    }

    @Test public void storedChoiceContainsNoSourceSecretsAndDoesNotAliasMutableQualities() {
        Models.Quality quality = quality(112, "012");
        quality.label = "PRIVATE_LABEL";
        quality.videoUrl = "https://private.example/video?secret=SECRET_VIDEO";
        quality.audioUrl = "https://private.example/audio?secret=SECRET_AUDIO";
        quality.headers.put("Cookie", "SECRET_COOKIE");
        store.setSeriesPlaybackPreference("ss1", true, quality);
        String serialized = prefs.getString("playback:ss1", "");
        for (String secret : Arrays.asList("PRIVATE_LABEL", "SECRET_VIDEO", "SECRET_AUDIO", "SECRET_COOKIE", "headers", "Url"))
            assertFalse(serialized.contains(secret));
        quality.id = "bad"; quality.qn = 1;
        Store.SeriesPlaybackPreference saved = store.getSeriesPlaybackPreference("ss1");
        assertEquals("112:12:0", saved.quality.id);
        assertTrue(saved.quality.videoCandidates().isEmpty()); assertTrue(saved.quality.headers.isEmpty());
        saved.quality.id = "changed-after-read";
        assertEquals("112:12:0", store.getSeriesPlaybackPreference("ss1").quality.id);
    }

    @Test public void invalidInputsDoNotEraseAnExistingSuccessfulPlaybackChoice() {
        store.setSeriesPlaybackPreference("ss1", true, quality(80, "7"));
        Map<String, ?> before = new HashMap<>(prefs.getAll());
        store.setSeriesPlaybackPreference("ss1", false, null);
        Models.Quality invalid = quality(112, "12"); invalid.id = "80:12:30280";
        store.setSeriesPlaybackPreference("ss1", false, invalid);
        store.setSeriesPlaybackPreference(null, false, quality(112, "12"));
        store.setSeriesPlaybackPreference(" ", false, quality(112, "12"));
        assertEquals(before, prefs.getAll());
        assertTrue(store.getSeriesPlaybackPreference("ss1").offline);
        assertNull(store.getSeriesPlaybackPreference(null));
    }

    @Test public void corruptStoredPreferencesCannotSelectAnUnintendedModeOrSource() {
        for (String invalid : Arrays.asList("broken", "{}", "{\"offline\":\"true\",\"profile\":{\"qn\":80,\"codec\":\"7\"}}",
            "{\"offline\":true,\"profile\":{\"qn\":80,\"codec\":\"7\",\"videoUrl\":\"https://private\"}}")) {
            prefs.edit().putString("playback:ss1", invalid).commit();
            assertNull(store.getSeriesPlaybackPreference("ss1"));
        }
        prefs.edit().putBoolean("playback:ss1", true).commit();
        assertNull(store.getSeriesPlaybackPreference("ss1"));
    }

    @Test public void resetRetainsSeriesChoicesAndExplicitClearAffectsOnlyTheSelectedSeries() {
        store.setSeriesPlaybackPreference("ss1", true, quality(80, "7"));
        store.setSeriesPlaybackPreference("ss2", false, quality(125, "12"));
        store.setPreferredQuality(quality(112, "12")); store.setThemeId("pink");
        prefs.edit().putString("progress:ep1", "history").putString("downloads", "[]").commit();
        store.resetAppearanceAndPlaybackSettings();
        assertEquals("blue", store.getThemeId()); assertNull(store.getPreferredQuality());
        assertTrue(store.getSeriesPlaybackPreference("ss1").offline);
        store.clearSeriesPlaybackPreference("ss1");
        assertNull(store.getSeriesPlaybackPreference("ss1"));
        assertEquals("125:12:0", store.getSeriesPlaybackPreference("ss2").quality.id);
        assertEquals("history", prefs.getString("progress:ep1", ""));
        assertEquals("[]", prefs.getString("downloads", ""));
    }

    private static Models.Quality quality(int qn, String codec) {
        Models.Quality result = new Models.Quality(); result.qn = qn; result.id = qn + ":" + codec + ":30280";
        return result;
    }
}
