package com.animeshelf.app;

import static org.junit.Assert.*;

import android.app.AlertDialog;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.cache.SimpleCache;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowAlertDialog;

/** Real list interactions and persistence; no remote playback or downloads are started. */
@UnstableApi
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class MainSelectionTest {
    private ActivityController<MainActivity> controller;
    private MainActivity activity;
    private Store store;
    private MediaRepository repository;
    private Models.Series series;
    private Models.Episode first, second, tenth;
    private Models.Quality standard, high;

    @Before public void setUp() throws Exception {
        controller = Robolectric.buildActivity(MainActivity.class).setup();
        activity = controller.get();
        store = new Store(activity);
        repository = (MediaRepository) get("media");
        series = new Models.Series();
        series.id = "selection-series";
        series.title = "多选测试番剧";
        first = episode(series, "selection-episode-1", 1);
        second = episode(series, "selection-episode-2", 2);
        tenth = episode(series, "selection-episode-10", 10);
        series.episodes.addAll(Arrays.asList(tenth, first, second));
        store.saveSeries(series);
        standard = quality(80, "1080P 高清 · AVC");
        high = quality(112, "1080P 高码率 · AVC");
    }

    @After public void tearDown() throws Exception {
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
        if (controller != null) controller.pause().stop().destroy();
        Field singleton = MediaRepository.class.getDeclaredField("instance");
        singleton.setAccessible(true);
        MediaRepository active = (MediaRepository) singleton.get(null);
        if (active != null) {
            active.manager.release();
            Field cache = MediaRepository.class.getDeclaredField("cache");
            cache.setAccessible(true);
            ((SimpleCache) cache.get(active)).release();
            singleton.set(null, null);
        }
    }

    @Test public void episodeLongPressSelectsItAndSubsequentTapsOnlyToggleSelection() throws Exception {
        openSeries(series);
        int tasksBefore = (Integer) get("taskStatusToken");

        assertTrue(episodeCard(first).performLongClick());

        assertTrue((Boolean) get("episodeSelectionMode"));
        assertEquals(series.id, get("selectionSeriesId"));
        assertEquals(ids(first.id), selected("selectedEpisodeIds"));
        assertTrue(episodeCard(first).isSelected());
        assertFalse(episodeCard(second).isSelected());
        assertTrue(button("下载 1 集").isEnabled());
        assertNoDialog();

        assertTrue(episodeCard(second).performClick());
        assertEquals(ids(first.id, second.id), selected("selectedEpisodeIds"));
        assertTrue(button("下载 2 集").isEnabled());
        assertTrue(episodeCard(first).performClick());
        assertEquals(ids(second.id), selected("selectedEpisodeIds"));
        assertFalse(episodeCard(first).isSelected());
        assertTrue(episodeCard(second).isSelected());
        assertNull(get("player"));
        assertNull(get("currentEpisode"));
        assertEquals(tasksBefore, ((Integer) get("taskStatusToken")).intValue());
        assertNoDialog();
    }

    @Test public void episodeSelectAllAndCancelStayInsideTheEpisodeList() throws Exception {
        openSeries(series);
        assertTrue(episodeCard(first).performLongClick());

        click("全选");

        assertEquals(ids(first.id, second.id, tenth.id), selected("selectedEpisodeIds"));
        assertTrue(button("下载 3 集").isEnabled());
        click("取消");
        assertFalse((Boolean) get("episodeSelectionMode"));
        assertTrue(selected("selectedEpisodeIds").isEmpty());
        assertTrue((Boolean) get("seriesPage"));
        assertSame(series, get("currentSeries"));
        assertNotNull(episodeCard(tenth));
        assertNoDialog();
    }

    @Test public void emptyEpisodeSelectionCannotStartABatch() throws Exception {
        openSeries(series);
        assertTrue(episodeCard(first).performLongClick());
        assertTrue(episodeCard(first).performClick());

        assertTrue((Boolean) get("episodeSelectionMode"));
        assertTrue(selected("selectedEpisodeIds").isEmpty());
        assertFalse(button("下载 0 集").isEnabled());
        assertNull(get("batchSession"));
        assertNoDialog();
    }

    @Test public void backExitsEpisodeSelectionBeforeLeavingTheSeries() throws Exception {
        openSeries(series);
        assertTrue(episodeCard(first).performLongClick());

        activity.onBackPressed();

        assertFalse((Boolean) get("episodeSelectionMode"));
        assertTrue(selected("selectedEpisodeIds").isEmpty());
        assertTrue((Boolean) get("seriesPage"));
        assertSame(series, get("currentSeries"));
        assertFalse(activity.isFinishing());
        activity.onBackPressed();
        assertFalse((Boolean) get("seriesPage"));
        assertFalse((Boolean) get("downloadsPage"));
        assertFalse(activity.isFinishing());
    }

    @Test public void openingAnotherSeriesDoesNotCarrySelectionsAcrossSeries() throws Exception {
        openSeries(series);
        assertTrue(episodeCard(first).performLongClick());
        Models.Series other = otherSeries();

        openSeries(other);

        assertFalse((Boolean) get("episodeSelectionMode"));
        assertFalse((Boolean) get("downloadSelectionMode"));
        assertTrue(selected("selectedEpisodeIds").isEmpty());
        assertTrue(selected("selectedDownloadKeys").isEmpty());
        assertSame(other, get("currentSeries"));
    }

    @Test public void offlineLongPressAndTapsSelectIndividualQualityVersionsWithoutPlayback() throws Exception {
        save(first, standard); save(first, high); save(second, standard);
        openOfflineGroup(series.id);

        assertTrue(offlineCard(first, standard).performLongClick());

        assertTrue((Boolean) get("downloadSelectionMode"));
        assertEquals(ids(key(first, standard)), selected("selectedDownloadKeys"));
        assertTrue(offlineCard(first, standard).isSelected());
        assertFalse(offlineCard(first, high).isSelected());
        assertTrue(button("删除 1 份").isEnabled());
        assertTrue(offlineCard(first, high).performClick());
        assertEquals(ids(key(first, standard), key(first, high)), selected("selectedDownloadKeys"));
        assertTrue(button("删除 2 份").isEnabled());
        assertTrue(offlineCard(first, standard).performClick());
        assertEquals(ids(key(first, high)), selected("selectedDownloadKeys"));
        assertNull(get("player"));
        assertNull(get("currentEpisode"));
        assertNoDialog();
    }

    @Test public void downloadRefreshKeepsSelectedKeysWithoutSelectingNewVersions() throws Exception {
        save(first, standard); save(second, standard);
        openOfflineGroup(series.id);
        assertTrue(offlineCard(first, standard).performLongClick());
        save(first, high);

        invoke("renderDownloads", new Class<?>[]{});

        assertEquals(series.id, get("downloadGroupId"));
        assertTrue((Boolean) get("downloadSelectionMode"));
        assertEquals(ids(key(first, standard)), selected("selectedDownloadKeys"));
        assertTrue(button("删除 1 份").isEnabled());
        assertTrue(offlineCard(first, high).performClick());
        assertEquals(ids(key(first, standard), key(first, high)), selected("selectedDownloadKeys"));
    }

    @Test public void offlineSelectAllIsScopedToTheCurrentSeriesAndCancelKeepsFiles() throws Exception {
        save(first, standard); save(first, high); save(second, standard);
        Models.Series other = otherSeries();
        save(other.episodes.get(0), standard);
        openOfflineGroup(series.id);

        assertTrue(offlineCard(first, standard).performLongClick());
        click("全选");

        assertEquals(ids(key(first, standard), key(first, high), key(second, standard)), selected("selectedDownloadKeys"));
        assertTrue(button("删除 3 份").isEnabled());
        click("取消");
        assertFalse((Boolean) get("downloadSelectionMode"));
        assertTrue(selected("selectedDownloadKeys").isEmpty());
        assertEquals(series.id, get("downloadGroupId"));
        assertEquals(4, repository.downloads().size());
        assertNoDialog();
    }

    @Test public void backExitsOfflineSelectionBeforeLeavingItsGroup() throws Exception {
        save(first, standard);
        openOfflineGroup(series.id);
        assertTrue(offlineCard(first, standard).performLongClick());

        activity.onBackPressed();

        assertFalse((Boolean) get("downloadSelectionMode"));
        assertTrue(selected("selectedDownloadKeys").isEmpty());
        assertEquals(series.id, get("downloadGroupId"));
        assertTrue((Boolean) get("downloadsPage"));
        assertFalse(activity.isFinishing());
        activity.onBackPressed();
        assertNull(get("downloadGroupId"));
        assertTrue((Boolean) get("downloadsPage"));
    }

    @Test public void groupLongPressAllowsCrossSeriesSelectionWithoutOpeningEitherGroup() throws Exception {
        save(first, standard); save(first, high); save(second, standard);
        Models.Series other = otherSeries();
        Models.Episode otherEpisode = other.episodes.get(0);
        save(otherEpisode, standard);
        invoke("openDownloads", new Class<?>[]{});

        assertTrue(groupCard(series).performLongClick());

        assertNull(get("downloadGroupId"));
        assertTrue((Boolean) get("downloadSelectionMode"));
        assertEquals(ids(key(first, standard), key(first, high), key(second, standard)), selected("selectedDownloadKeys"));
        assertTrue(groupCard(other).performClick());
        assertEquals(4, selected("selectedDownloadKeys").size());
        assertTrue(button("删除 4 份").isEnabled());
        assertNull(get("downloadGroupId"));
        assertTrue(groupCard(series).performClick());
        assertEquals(ids(key(otherEpisode, standard)), selected("selectedDownloadKeys"));
        assertNoDialog();
    }

    @Test public void inlineDeleteRequiresConfirmationAndUsesAVersionSnapshotWithoutLosingProgress() throws Exception {
        save(first, standard); save(first, high); save(second, standard);
        store.saveProgress(first, 98000, 300000);
        openOfflineGroup(series.id);
        assertTrue(offlineCard(first, standard).performLongClick());

        click("删除 1 份");

        AlertDialog cancelled = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(cancelled);
        assertTrue(cancelled.isShowing());
        assertEquals(3, repository.downloads().size());
        cancelled.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        idleMain();
        assertEquals(3, repository.downloads().size());
        assertEquals(98000, store.getProgress(first.id).positionMs);

        // Cancellation must not silently delete a version; select it again if
        // the confirmation flow chose to leave selection mode.
        if (!(Boolean) get("downloadSelectionMode")) assertTrue(offlineCard(first, standard).performLongClick());
        if (!selected("selectedDownloadKeys").contains(key(first, standard))) assertTrue(offlineCard(first, standard).performClick());
        click("删除 1 份");
        AlertDialog confirmed = ShadowAlertDialog.getLatestAlertDialog();
        Models.Quality addedLater = quality(64, "720P 高清 · AVC");
        save(first, addedLater);
        confirmed.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        idleMain();

        assertEquals(ids(key(first, high), key(second, standard), key(first, addedLater)), savedKeys());
        assertEquals(98000, store.getProgress(first.id).positionMs);
        assertEquals(first.id, store.getLastEpisode(series.id));
        assertTrue(store.listSeries().stream().anyMatch(item -> series.id.equals(item.id)));
    }

    private void openSeries(Models.Series chosen) throws Exception {
        invoke("renderSeries", new Class<?>[]{Models.Series.class}, chosen);
    }
    private void openOfflineGroup(String seriesId) throws Exception {
        invoke("openDownloads", new Class<?>[]{});
        for (DownloadCatalogue.Group group : DownloadCatalogue.groups(repository.downloads())) {
            if (seriesId.equals(group.id)) {
                invoke("openDownloadGroup", new Class<?>[]{DownloadCatalogue.Group.class}, group);
                return;
            }
        }
        fail("Missing offline series: " + seriesId);
    }
    private Models.Series otherSeries() {
        Models.Series other = new Models.Series();
        other.id = "selection-other-series";
        other.title = "另一部多选番剧";
        other.episodes.add(episode(other, "selection-other-episode", 1));
        store.saveSeries(other);
        return other;
    }
    private static Models.Episode episode(Models.Series series, String id, int index) {
        Models.Episode episode = new Models.Episode();
        episode.id = id; episode.index = index; episode.title = "第 " + index + " 集";
        episode.seriesId = series.id; episode.seriesTitle = series.title;
        return episode;
    }
    private static Models.Quality quality(int qn, String label) {
        Models.Quality quality = new Models.Quality();
        quality.qn = qn; quality.id = qn + ":7:30280"; quality.label = label;
        quality.videoUrl = "https://media.example/selection-" + qn + ".m4s";
        quality.audioUrl = "https://media.example/selection-audio.m4s";
        return quality;
    }
    private void save(Models.Episode episode, Models.Quality quality) {
        store.saveDownload(key(episode, quality), episode, quality);
    }
    private static String key(Models.Episode episode, Models.Quality quality) { return MediaRepository.key(episode, quality); }
    private static Set<String> ids(String... values) { return new HashSet<>(Arrays.asList(values)); }
    @SuppressWarnings("unchecked") private Set<String> selected(String field) throws Exception {
        return new HashSet<>((Set<String>) get(field));
    }
    private Set<String> savedKeys() {
        Set<String> keys = new HashSet<>();
        for (MediaRepository.OfflineItem item : repository.downloads()) keys.add(item.key);
        return keys;
    }
    private View episodeCard(Models.Episode episode) { return described("第 " + episode.index + " 集，长按选择"); }
    private View offlineCard(Models.Episode episode, Models.Quality quality) {
        return described("离线第 " + episode.index + " 集，" + quality.label + "，长按选择");
    }
    private View groupCard(Models.Series series) { return described("离线番剧 " + series.title + "，长按选择"); }
    private View described(String prefix) {
        View found = findDescription(activity.getWindow().getDecorView(), prefix);
        assertNotNull("Missing list card: " + prefix, found);
        return found;
    }
    private Button button(String label) {
        Button found = findButton(activity.getWindow().getDecorView(), label);
        assertNotNull("Missing inline action: " + label, found);
        return found;
    }
    private void click(String label) { assertTrue(button(label).performClick()); }
    private static Button findButton(View root, String label) {
        if (root instanceof Button && label.contentEquals(((Button) root).getText())) return (Button) root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                Button found = findButton(group.getChildAt(i), label);
                if (found != null) return found;
            }
        }
        return null;
    }
    private static View findDescription(View root, String prefix) {
        if (root.getContentDescription() != null && root.getContentDescription().toString().startsWith(prefix)) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findDescription(group.getChildAt(i), prefix);
                if (found != null) return found;
            }
        }
        return null;
    }
    private static void assertNoDialog() {
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertTrue("Selection should happen directly in the list", dialog == null || !dialog.isShowing());
    }
    private static void idleMain() { org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle(); }
    private Object get(String name) throws Exception {
        Field field = MainActivity.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(activity);
    }
    private void invoke(String name, Class<?>[] parameters, Object... arguments) throws Exception {
        Method method = MainActivity.class.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        method.invoke(activity, arguments);
    }
}
