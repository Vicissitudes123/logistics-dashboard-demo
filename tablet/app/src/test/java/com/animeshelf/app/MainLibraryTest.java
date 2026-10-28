package com.animeshelf.app;

import static org.junit.Assert.*;

import android.app.AlertDialog;
import android.graphics.drawable.GradientDrawable;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.SeekBar;
import androidx.media3.common.util.UnstableApi;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowAlertDialog;

/** Actual home-page interactions, with local metadata only and no network requests. */
@UnstableApi
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class MainLibraryTest {
    private ActivityController<MainActivity> controller;
    private MainActivity activity;
    private Store store;
    private Models.Series recent, pinned, other;
    private String savedDownloadKey;

    @Before public void setUp() throws Exception {
        controller = Robolectric.buildActivity(MainActivity.class).setup();
        activity = controller.get();
        store = new Store(activity);
        recent = series("recent-series", "JOJO 奇妙冒险");
        other = series("other-series", "星际旅行");
        pinned = series("pinned-series", "四月的故事");
        store.saveSeries(recent);
        store.saveSeries(other);
        store.saveSeries(pinned);
        store.saveProgress(recent.episodes.get(0), 42000, 300000);
        store.setPinned(recent.id, true);
        store.setPinned(pinned.id, true);
        Models.Quality quality = DownloadCatalogueTest.quality(80, "7", "30280");
        savedDownloadKey = MediaRepository.key(recent.episodes.get(0), quality);
        store.saveDownload(savedDownloadKey, recent.episodes.get(0), quality);
        invoke("renderLibrary");
    }

    @After public void tearDown() throws Exception {
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
        if (controller != null) controller.pause().stop().destroy();
        Field singleton = MediaRepository.class.getDeclaredField("instance"); singleton.setAccessible(true);
        MediaRepository active = (MediaRepository) singleton.get(null);
        if (active != null) active.releaseForTests();
    }

    @Test public void typingFiltersOnlyResultsAndPreservesTheSearchViewFocusAndCursor() throws Exception {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        EditText search = (EditText) findDescription((View) get("root"), "搜索番剧");
        assertNotNull(search);
        assertTrue(search.requestFocus());
        search.setText("jojo");
        search.setSelection(2);
        assertEquals(Arrays.asList(recent.id), cardIds());

        search.getText().insert(2, "j");

        assertSame(search, get("librarySearch"));
        assertSame(search, findDescription((View) get("root"), "搜索番剧"));
        assertTrue(search.hasFocus());
        assertEquals("jojjo", search.getText().toString());
        assertEquals(3, search.getSelectionStart());
        assertTrue(cardIds().isEmpty());

        search.getText().delete(2, 3);
        assertSame(search, get("librarySearch"));
        assertTrue(search.hasFocus());
        assertEquals(2, search.getSelectionStart());
        assertEquals(Arrays.asList(recent.id), cardIds());
        assertEquals("jojo", get("libraryQuery"));
    }

    @Test public void recentIsShownOnceBeforePinsAndPinActionsReorderOnlyTheRemainingCards() throws Exception {
        assertEquals(Arrays.asList(recent.id, pinned.id, other.id), cardIds());

        assertTrue(card(other.id).performLongClick());
        chooseDialogItem(0);
        assertTrue(store.isPinned(other.id));
        assertEquals(Arrays.asList(recent.id, pinned.id, other.id), cardIds());

        assertTrue(card(pinned.id).performLongClick());
        chooseDialogItem(0);
        assertFalse(store.isPinned(pinned.id));
        assertEquals(Arrays.asList(recent.id, other.id, pinned.id), cardIds());
        assertNull(get("player"));
        assertNull(get("currentEpisode"));
    }

    @Test public void hidingRequiresConfirmationAndRestoringKeepsEpisodesDownloadsAndProgress() throws Exception {
        assertTrue(card(recent.id).performLongClick());
        chooseDialogItem(1);
        AlertDialog confirmation = showingDialog();
        assertNotNull(card(recent.id));
        clickDialogButton(confirmation, AlertDialog.BUTTON_NEGATIVE);
        assertNotNull(card(recent.id));

        assertTrue(card(recent.id).performLongClick());
        chooseDialogItem(1);
        clickDialogButton(showingDialog(), AlertDialog.BUTTON_POSITIVE);

        assertFalse(cardIds().contains(recent.id));
        assertEquals(1, store.listHiddenSeries().size());
        assertEquals(recent.id, store.listHiddenSeries().get(0).id);
        assertRetainedData();

        invoke("renderSettings");
        View removedSeries = findDescription((View) get("root"), "已移除番剧");
        assertNotNull(removedSeries);
        assertTrue(removedSeries.performClick());
        chooseDialogItem(0);
        assertTrue((Boolean) get("settingsPage"));
        activity.onBackPressed();
        assertFalse((Boolean) get("settingsPage"));
        assertFalse(activity.isFinishing());
        assertTrue(store.listHiddenSeries().isEmpty());
        assertEquals(Arrays.asList(recent.id, pinned.id, other.id), cardIds());
        assertRetainedData();
    }

    @Test public void themeChoiceUpdatesVisibleButtonsAndResetPreservesTheLibrary() throws Exception {
        assertEquals("blue", store.getThemeId());
        assertAccent("blue");
        invoke("renderSettings");
        View chooseTheme = findDescription((View) get("root"), "主题色设置");
        assertNotNull(chooseTheme);
        assertTrue(chooseTheme.performClick());
        int purpleIndex = -1;
        for (int i = 0; i < ThemePalette.entries().size(); i++) {
            if ("purple".equals(ThemePalette.entries().get(i).id)) purpleIndex = i;
        }
        assertTrue(purpleIndex >= 0);
        chooseDialogItem(purpleIndex);
        assertEquals("purple", new Store(activity).getThemeId());
        assertTrue((Boolean) get("settingsPage"));
        assertAccent("purple");

        invoke("confirmResetSettings");
        clickDialogButton(showingDialog(), AlertDialog.BUTTON_POSITIVE);

        assertEquals("blue", new Store(activity).getThemeId());
        assertAccent("blue");
        assertRetainedData();
        assertTrue(store.isPinned(pinned.id));
        activity.onBackPressed();
        assertFalse((Boolean) get("settingsPage"));
        assertFalse(activity.isFinishing());
        assertEquals(Arrays.asList(recent.id, pinned.id, other.id), cardIds());
    }

    private void assertRetainedData() {
        assertEquals(3, store.listSeries().size());
        assertEquals(42000, store.getProgress(recent.episodes.get(0).id).positionMs);
        assertEquals(recent.episodes.get(0).id, store.getLastEpisode(recent.id));
        assertEquals(1, store.savedDownloads().size());
        assertEquals(savedDownloadKey, store.savedDownloads().get(0).optString("key"));
    }

    private void assertAccent(String themeId) throws Exception {
        View addButton = (View) get("addButton");
        assertTrue(addButton.getBackground() instanceof GradientDrawable);
        GradientDrawable background = (GradientDrawable) addButton.getBackground();
        assertNotNull(background.getColor());
        assertEquals(ThemePalette.get(themeId).accent, background.getColor().getDefaultColor());
        SeekBar timeline = (SeekBar) findDescription((View) get("playerOverlay"), "播放进度");
        assertNotNull(timeline);
        assertNotNull(timeline.getProgressTintList());
        assertEquals("The player timeline follows the same theme", ThemePalette.get(themeId).accent,
            timeline.getProgressTintList().getDefaultColor());
    }

    private List<String> cardIds() throws Exception {
        ArrayList<String> ids = new ArrayList<>();
        collectCardIds((View) get("libraryResults"), ids);
        return ids;
    }

    private static void collectCardIds(View view, List<String> ids) {
        CharSequence description = view.getContentDescription();
        if (description != null && description.toString().startsWith("番剧：")) {
            ids.add(description.toString().substring("番剧：".length()));
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collectCardIds(group.getChildAt(i), ids);
        }
    }

    private View card(String id) throws Exception {
        View result = findDescription((View) get("libraryResults"), "番剧：" + id);
        assertNotNull("Missing series card: " + id, result);
        return result;
    }

    private static View findDescription(View root, String description) {
        if (description.contentEquals(root.getContentDescription() == null ? "" : root.getContentDescription())) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findDescription(group.getChildAt(i), description);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static AlertDialog showingDialog() {
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(dialog);
        assertTrue(dialog.isShowing());
        return dialog;
    }

    private static void chooseDialogItem(int position) {
        AlertDialog dialog = showingDialog();
        assertNotNull(dialog.getListView());
        dialog.getListView().performItemClick(null, position, position);
    }

    private static void clickDialogButton(AlertDialog dialog, int which) {
        assertNotNull(dialog.getButton(which));
        assertTrue(dialog.getButton(which).performClick());
        // AlertController posts button listeners and dismissal to its Handler.
        // PAUSED loopers must deliver those messages before checking their effects.
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private Object get(String name) throws Exception {
        Field field = MainActivity.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(activity);
    }

    private void invoke(String name) throws Exception {
        Method method = MainActivity.class.getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(activity);
    }

    private static Models.Series series(String id, String title) {
        Models.Series series = new Models.Series();
        series.id = id; series.title = title;
        Models.Episode episode = new Models.Episode();
        episode.id = id + "-episode"; episode.seriesId = id; episode.seriesTitle = title;
        episode.title = "第一集"; episode.index = 1;
        series.episodes.add(episode);
        return series;
    }
}
