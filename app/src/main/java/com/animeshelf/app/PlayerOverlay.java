package com.animeshelf.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.Locale;
import java.util.function.Consumer;

/** Video-only controls. Interactive child views retain their own touch streams. */
public final class PlayerOverlay extends FrameLayout {
    public interface Host {
        boolean hasPlayer();
        boolean isPlaying();
        boolean canSeek();
        long positionMs();
        long durationMs();
        float speed();
        default float holdSpeed() { return 2f; }
        default int controlsTimeoutMs() { return 3500; }
        default int accentColor() { return 0xFF8DB7FF; }
        float brightness();
        void setBrightness(float brightness);
        void setTemporarySpeed(float speed);
        void togglePlayback();
        void seekTo(long positionMs);
        void setSpeed(float speed);
        void chooseSpeed();
        void chooseEpisode();
        void chooseDisplay();
        void back();
        void home();
        void toggleFullScreen();
        default void previousEpisode() { }
        default void nextEpisode() { }
        default boolean canPrevious() { return false; }
        default boolean canNext() { return false; }
        default String qualityLabel() { return "清晰度"; }
        default void chooseQuality() { }
        default void requestSeekPreview(long positionMs, Consumer<Bitmap> callback) { callback.accept(null); }
        default void cancelSeekPreview() { }
    }

    private final Host host;
    private final AudioManager audio;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final PlaybackGestures gestures = new PlaybackGestures();
    private final GestureDetector taps;
    private final LinearLayout topBar, bottomBar, timeline, actions, extraActions, seekPreview;
    private final TextView title, subtitle, position, duration, feedback;
    private final TextView seekPreviewTime;
    private final ImageView seekPreviewImage;
    private final ImageButton playButton, fullButton, lockButton, unlockButton, previousButton, nextButton;
    private final Button speedButton, episodeButton, displayButton, qualityButton;
    private final SeekBar seekBar;
    private boolean active, controlsVisible = true, fullScreen, scrubbing, scrubCanceled, touchAccepted, multiTouch, wasPlaying;
    private long previewMs = -1;
    private int appliedVolume = -1;
    private float appliedBrightness = Float.NaN;
    private float edgeInsetPx, speedBeforeHold = 1f;
    private boolean temporaryBoost, longPressConsumed, doubleTapConsumed, locked;
    private boolean compactActions;
    private int previewRequest;
    private long requestedPreviewPosition = -1;

    private final Runnable hide = new Runnable() {
        @Override public void run() {
            if (!locked && controlsTimeoutMs() != 0 && active && host.isPlaying() && !scrubbing && !gestures.isDragging()) hideControls();
        }
    };
    private final Runnable hideFeedback = new Runnable() {
        @Override public void run() { feedback.setVisibility(GONE); }
    };
    private final Runnable update = new Runnable() {
        @Override public void run() {
            if (!active) return;
            refresh();
            handler.postDelayed(this, 500);
        }
    };

    public PlayerOverlay(Context context, Host host) {
        super(context);
        this.host = host;
        audio = (AudioManager) context.getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
        edgeInsetPx = dp(20);
        setFocusable(true);
        setClickable(true);
        setClipChildren(true);
        setContentDescription("视频画面，单击显示控制，双击播放或暂停；横滑进度，左侧上下亮度，右侧上下音量；长按临时加速，松手恢复");

        topBar = new LinearLayout(context);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(4), dp(2), dp(4), dp(2));
        topBar.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{0xD9000000, 0x77000000, 0x00000000}));
        topBar.addView(icon("返回", Glyph.BACK, host::back), new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout titles = new LinearLayout(context);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(6), 0, dp(8), 0);
        title = text("", 16); title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setSingleLine(true); title.setEllipsize(TextUtils.TruncateAt.END);
        subtitle = text("", 12); subtitle.setTextColor(0xFFD3D8E2); subtitle.setSingleLine(true); subtitle.setEllipsize(TextUtils.TruncateAt.END);
        subtitle.setPadding(0, dp(2), 0, 0);
        titles.addView(title); titles.addView(subtitle);
        topBar.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        lockButton = icon("锁定播放器", Glyph.LOCK, this::lock);
        lockButton.setTooltipText("锁定播放器"); lockButton.setVisibility(GONE);
        topBar.addView(icon("回到首页", Glyph.HOME, host::home), new LinearLayout.LayoutParams(dp(48), dp(48)));
        addView(topBar, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        bottomBar = new LinearLayout(context);
        bottomBar.setOrientation(LinearLayout.VERTICAL);
        bottomBar.setPadding(dp(8), 0, dp(8), 0);
        bottomBar.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
            new int[]{0xE6000000, 0x88000000, 0x00000000}));
        timeline = new LinearLayout(context); timeline.setGravity(Gravity.CENTER_VERTICAL);
        position = text("00:00", 12); duration = text("--:--", 12);
        position.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        duration.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        position.setGravity(Gravity.CENTER); duration.setGravity(Gravity.CENTER);
        position.setSingleLine(true); duration.setSingleLine(true);
        timeline.addView(position, new LinearLayout.LayoutParams(dp(54), dp(32)));
        seekBar = new SeekBar(context); seekBar.setMax(1000); seekBar.setContentDescription("播放进度");
        seekBar.setProgressTintList(ColorStateList.valueOf(host.accentColor()));
        seekBar.setProgressBackgroundTintList(ColorStateList.valueOf(0x66FFFFFF));
        seekBar.setThumbTintList(ColorStateList.valueOf(Color.WHITE));
        seekBar.setPadding(dp(8), 0, dp(8), 0);
        timeline.addView(seekBar, new LinearLayout.LayoutParams(0, dp(48), 1));
        timeline.addView(duration, new LinearLayout.LayoutParams(dp(54), dp(32)));
        bottomBar.addView(timeline);
        actions = new LinearLayout(context); actions.setGravity(Gravity.CENTER_VERTICAL);
        extraActions = new LinearLayout(context); extraActions.setGravity(Gravity.CENTER);
        previousButton = icon("上一集", Glyph.PREVIOUS, () -> { if (host.canPrevious()) host.previousEpisode(); });
        playButton = icon("播放", Glyph.PLAY, () -> { host.togglePlayback(); refresh(); });
        nextButton = icon("下一集", Glyph.NEXT, () -> { if (host.canNext()) host.nextEpisode(); });
        speedButton = label("1.0×", "播放速度", host::chooseSpeed);
        episodeButton = label("选集", "选集", host::chooseEpisode);
        displayButton = label("画面", "显示方式", host::chooseDisplay);
        qualityButton = label("清晰度", "清晰度", host::chooseQuality);
        fullButton = icon("全屏", Glyph.FULL, host::toggleFullScreen);
        bottomBar.addView(extraActions);
        bottomBar.addView(actions);
        arrangeActions(true);
        addView(bottomBar, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        feedback = text("", 17);
        feedback.setGravity(Gravity.CENTER); feedback.setPadding(dp(18), dp(10), dp(18), dp(10));
        GradientDrawable bubble = new GradientDrawable(); bubble.setColor(0xDD171B27); bubble.setCornerRadius(dp(12));
        feedback.setBackground(bubble); feedback.setVisibility(GONE);
        feedback.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(feedback, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        seekPreview = new LinearLayout(context); seekPreview.setOrientation(LinearLayout.VERTICAL);
        seekPreview.setGravity(Gravity.CENTER); seekPreview.setVisibility(GONE);
        seekPreview.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        seekPreviewImage = new ImageView(context); seekPreviewImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        GradientDrawable previewFrame = new GradientDrawable(); previewFrame.setColor(0xCC000000);
        previewFrame.setCornerRadius(dp(8)); previewFrame.setStroke(dp(1), Color.WHITE);
        seekPreviewImage.setBackground(previewFrame); seekPreviewImage.setPadding(dp(1), dp(1), dp(1), dp(1));
        seekPreviewImage.setClipToOutline(true); seekPreviewImage.setVisibility(GONE);
        seekPreview.addView(seekPreviewImage, new LinearLayout.LayoutParams(dp(176), dp(99)));
        seekPreviewTime = text("", 15); seekPreviewTime.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        seekPreviewTime.setGravity(Gravity.CENTER); seekPreviewTime.setPadding(dp(8), dp(8), dp(8), dp(4));
        seekPreview.addView(seekPreviewTime);
        addView(seekPreview, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        // This is independent of both auto-hiding bars, so locking always leaves
        // a visible, reachable way out without blocking the system Back action.
        unlockButton = icon("解锁播放器", Glyph.UNLOCK, this::unlock);
        unlockButton.setTooltipText("解锁播放器"); unlockButton.setVisibility(GONE);
        FrameLayout.LayoutParams unlockPosition = new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.START | Gravity.CENTER_VERTICAL);
        unlockPosition.setMarginStart(dp(16));
        FrameLayout.LayoutParams lockPosition = new FrameLayout.LayoutParams(unlockPosition);
        addView(lockButton, lockPosition);
        addView(unlockButton, unlockPosition);

        seekBar.setOnTouchListener((view, event) -> {
            if (locked) return true;
            if (event.getActionMasked() == MotionEvent.ACTION_CANCEL || event.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN) scrubCanceled = true;
            return false;
        });
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar bar) {
                if (locked) { scrubCanceled = true; return; }
                scrubbing = true; scrubCanceled = false; previewMs = host.positionMs();
                handler.removeCallbacks(hide); showControls();
            }
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                if (locked || !fromUser || !host.canSeek() || host.durationMs() <= 0) return;
                previewMs = Math.max(0, Math.min(host.durationMs(), Math.round(host.durationMs() * (value / 1000d))));
                position.setText(time(previewMs)); showSeekPreview(previewMs);
            }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                if (!locked && !scrubCanceled && host.canSeek() && previewMs >= 0) host.seekTo(previewMs);
                scrubbing = false; previewMs = -1; hideSeekPreview();
                refresh(); dismissFeedbackSoon(); scheduleHide();
            }
        });

        taps = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent event) { return true; }
            @Override public boolean onSingleTapConfirmed(MotionEvent event) {
                if (locked || !active || multiTouch) return false;
                performClick(); return true;
            }
            @Override public void onLongPress(MotionEvent event) {
                if (locked || !active || !touchAccepted || multiTouch || scrubbing || longPressConsumed || doubleTapConsumed
                    || gestures.getMode() != PlaybackGestures.Mode.PENDING || !host.hasPlayer() || !host.isPlaying()) return;
                float speed = host.speed();
                speedBeforeHold = Float.isNaN(speed) || Float.isInfinite(speed) || speed <= 0 ? 1f : speed;
                float configured = host.holdSpeed();
                if (!Float.isFinite(configured) || configured < 1.25f || configured > 3f) configured = 2f;
                float heldSpeed = Math.max(configured, speedBeforeHold);
                longPressConsumed = true; temporaryBoost = true;
                host.setTemporarySpeed(heldSpeed);
                hideControls();
                showFeedback(speedText(heldSpeed) + " 加速中\n松手恢复 " + speedText(speedBeforeHold));
            }
            @Override public boolean onDoubleTap(MotionEvent event) {
                if (locked || !active || multiTouch || !host.hasPlayer()) return false;
                doubleTapConsumed = true;
                host.togglePlayback(); refresh();
                showFeedback(host.isPlaying() ? "继续播放" : "已暂停"); dismissFeedbackSoon();
                if (!host.isPlaying()) showControls(); else scheduleHide();
                return true;
            }
        });
        taps.setIsLongpressEnabled(true);
        refresh();
    }

    /** Keep the same buttons and touch targets when moving between portrait and landscape. */
    private void arrangeActions(boolean compact) {
        compactActions = compact;
        actions.removeAllViews(); extraActions.removeAllViews(); bottomBar.removeAllViews();
        action(actions, previousButton, 48); action(actions, playButton, 48); action(actions, nextButton, 48);
        actions.addView(new View(getContext()), new LinearLayout.LayoutParams(0, 1, 1));
        if (compact) {
            action(extraActions, speedButton, 72); action(extraActions, episodeButton, 64); action(extraActions, displayButton, 64);
        } else {
            action(actions, episodeButton, 56); action(actions, speedButton, 64); action(actions, displayButton, 56);
        }
        action(actions, qualityButton, 80); action(actions, fullButton, 48);
        extraActions.setVisibility(compact ? VISIBLE : GONE);
        if (compact) {
            bottomBar.addView(extraActions); bottomBar.addView(actions); bottomBar.addView(timeline);
        } else {
            bottomBar.addView(timeline); bottomBar.addView(extraActions); bottomBar.addView(actions);
        }
    }

    private void action(LinearLayout row, View view, int width) {
        row.addView(view, new LinearLayout.LayoutParams(dp(width), dp(48)));
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        boolean compact = MeasureSpec.getSize(widthMeasureSpec) < dp(500);
        if (compact != compactActions) arrangeActions(compact);
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    public void setActive(boolean value) {
        active = value;
        if (!value) locked = false;
        cancelInteraction(); updateLockControls();
        handler.removeCallbacks(update);
        if (value) { refresh(); showControls(); handler.post(update); }
        else { handler.removeCallbacks(hide); handler.removeCallbacks(hideFeedback); feedback.setVisibility(GONE); }
    }

    public void setTitle(String name, String detail) { title.setText(name); subtitle.setText(detail); }
    public void setFullScreen(boolean value) {
        fullScreen = value;
        if (!value) locked = false;
        fullButton.setImageDrawable(new ControlIcon(value ? Glyph.EXIT_FULL : Glyph.FULL, dp(32)));
        fullButton.setContentDescription(value ? "退出全屏" : "全屏");
        cancelInteraction(); updateLockControls(); refresh(); showControls();
    }

    public boolean isLocked() { return locked; }

    private void lock() {
        if (!active || !fullScreen || locked || !host.hasPlayer()) return;
        locked = true;
        cancelInteraction();
        hideControls(); refresh();
        unlockButton.announceForAccessibility("播放器已锁定，点击解锁播放器，或按返回键解锁");
    }

    public void unlock() {
        if (!locked) return;
        locked = false;
        cancelInteraction(); updateLockControls(); refresh(); showControls();
    }

    private void updateLockControls() {
        lockButton.setVisibility(fullScreen && active && !locked && controlsVisible && requestedPreviewPosition < 0 ? VISIBLE : GONE);
        unlockButton.setVisibility(fullScreen && active && locked ? VISIBLE : GONE);
    }

    /** Insets supplied by the window; no system gesture exclusion region is requested. */
    public void setSystemGestureInset(int left, int top, int right, int bottom) {
        edgeInsetPx = Math.max(dp(20), Math.max(Math.max(left, right), Math.max(top, bottom)));
    }

    /** Theme updates only repaint the existing control; visibility timers keep their deadline. */
    public void refreshTheme() {
        seekBar.setProgressTintList(ColorStateList.valueOf(host.accentColor()));
    }

    public void refresh() {
        if (temporaryBoost && (!active || !host.hasPlayer() || !host.isPlaying())) {
            stopTemporarySpeed(); feedback.setVisibility(GONE);
        }
        long length = host.durationMs();
        boolean seekable = !locked && host.hasPlayer() && host.canSeek() && length > 0;
        seekBar.setEnabled(seekable);
        seekBar.setAlpha(seekable ? 1f : .45f);
        playButton.setEnabled(!locked && host.hasPlayer());
        playButton.setImageDrawable(new ControlIcon(host.isPlaying() ? Glyph.PAUSE : Glyph.PLAY, dp(32)));
        playButton.setContentDescription(host.isPlaying() ? "暂停" : "播放");
        previousButton.setEnabled(!locked && host.hasPlayer() && host.canPrevious());
        nextButton.setEnabled(!locked && host.hasPlayer() && host.canNext());
        previousButton.setAlpha(previousButton.isEnabled() ? 1f : .35f);
        nextButton.setAlpha(nextButton.isEnabled() ? 1f : .35f);
        qualityButton.setEnabled(!locked && host.hasPlayer());
        String quality = host.qualityLabel();
        qualityButton.setText(quality == null || quality.trim().isEmpty() ? "清晰度" : quality);
        speedButton.setText(speedText(host.speed()));
        duration.setText(length > 0 ? time(length) : "--:--");
        if (!scrubbing && !gestures.isDragging()) {
            long current = Math.max(0, host.positionMs());
            position.setText(time(current));
            seekBar.setProgress(length > 0 ? (int) Math.max(0, Math.min(1000, Math.round(current * 1000d / length))) : 0);
        }
        boolean playing = host.isPlaying();
        if (active && wasPlaying && !playing && !gestures.isDragging()) showControls();
        wasPlaying = playing;
    }

    public void showControls() {
        if (locked) { hideControls(); return; }
        controlsVisible = true; topBar.setVisibility(VISIBLE); bottomBar.setVisibility(VISIBLE);
        actions.setVisibility(VISIBLE); extraActions.setVisibility(compactActions ? VISIBLE : GONE);
        updateLockControls();
        scheduleHide();
    }
    public void hideControls() {
        controlsVisible = false; topBar.setVisibility(GONE); bottomBar.setVisibility(GONE);
        if (!gestures.isDragging() && !scrubbing) hideSeekPreview();
        updateLockControls(); handler.removeCallbacks(hide);
    }
    public boolean areControlsVisible() { return controlsVisible; }

    public void cancelInteraction() {
        stopTemporarySpeed(); longPressConsumed = false; doubleTapConsumed = false;
        gestures.finish(true); touchAccepted = false; multiTouch = true;
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
        scrubbing = false; scrubCanceled = true; previewMs = -1;
        hideSeekPreview();
        if (taps != null) {
            long now = android.os.SystemClock.uptimeMillis();
            MotionEvent cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0, 0, 0);
            taps.onTouchEvent(cancel); cancel.recycle();
        }
        handler.removeCallbacks(hideFeedback);
        feedback.setVisibility(GONE);
    }
    public void dispose() {
        active = false; locked = false; cancelInteraction(); updateLockControls(); handler.removeCallbacksAndMessages(null);
    }

    @Override public boolean performClick() {
        if (locked) return true;
        super.performClick();
        if (controlsVisible) hideControls(); else showControls();
        return true;
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (locked) return true;
        if (!active || !host.hasPlayer()) { cancelInteraction(); return false; }
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            stopTemporarySpeed(); longPressConsumed = false; doubleTapConsumed = false; multiTouch = false;
            int maxVolume = audio == null || audio.isVolumeFixed() ? 0 : audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int volume = audio == null ? 0 : audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            PlaybackGestures.Snapshot snapshot = new PlaybackGestures.Snapshot(host.positionMs(), host.durationMs(), host.canSeek(), host.brightness(), volume, maxVolume);
            touchAccepted = gestures.begin(event.getX(), event.getY(), getWidth(), getHeight(), edgeInsetPx,
                ViewConfiguration.get(getContext()).getScaledTouchSlop(), snapshot);
            if (!touchAccepted) return false;
            appliedVolume = volume; appliedBrightness = snapshot.brightness;
            handler.removeCallbacks(hide);
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
        }
        if (!touchAccepted) return false;
        if (action == MotionEvent.ACTION_CANCEL) {
            cancelInteraction(); refresh(); scheduleHide(); return true;
        }
        if (action == MotionEvent.ACTION_POINTER_DOWN || event.getPointerCount() > 1) {
            stopTemporarySpeed();
            multiTouch = true; gestures.finish(true); previewMs = -1;
            hideSeekPreview();
            MotionEvent cancel = MotionEvent.obtain(event); cancel.setAction(MotionEvent.ACTION_CANCEL); taps.onTouchEvent(cancel); cancel.recycle();
            feedback.setVisibility(GONE);
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
            return true;
        }
        if (!multiTouch) taps.onTouchEvent(event);
        if (action == MotionEvent.ACTION_MOVE && !multiTouch) {
            PlaybackGestures.Update change = gestures.move(event.getX(), event.getY());
            if (gestures.isDragging()) {
                hideControls();
                if (longPressConsumed) {
                    if (stopTemporarySpeed()) {
                        showFeedback("已恢复 " + speedText(speedBeforeHold)); dismissFeedbackSoon();
                    }
                } else applyPreview(change);
            }
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (stopTemporarySpeed()) showFeedback("已恢复 " + speedText(speedBeforeHold));
            PlaybackGestures.Update result = gestures.finish(action == MotionEvent.ACTION_CANCEL || multiTouch || longPressConsumed);
            if (result.commitSeek && host.canSeek()) host.seekTo(result.positionMs);
            hideSeekPreview();
            previewMs = -1; touchAccepted = false; longPressConsumed = false; doubleTapConsumed = false;
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
            dismissFeedbackSoon(); refresh(); scheduleHide();
        }
        return true;
    }

    private void applyPreview(PlaybackGestures.Update change) {
        switch (change.mode) {
            case SEEK:
                previewMs = change.positionMs;
                showSeekPreview(change.positionMs);
                position.setText(time(change.positionMs)); break;
            case BLOCKED_SEEK: showFeedback("当前视频暂不能拖动进度"); break;
            case BRIGHTNESS:
                if (Float.compare(appliedBrightness, change.brightness) != 0) {
                    host.setBrightness(change.brightness); appliedBrightness = change.brightness;
                }
                showFeedback("亮度 " + Math.round(change.brightness * 100) + "%"); break;
            case VOLUME:
                if (audio == null || audio.isVolumeFixed() || audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) <= 0) {
                    showFeedback("系统音量由设备控制"); break;
                }
                try {
                    if (change.volume != appliedVolume) { audio.setStreamVolume(AudioManager.STREAM_MUSIC, change.volume, 0); appliedVolume = change.volume; }
                    int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                    showFeedback("音量 " + Math.round(100f * audio.getStreamVolume(AudioManager.STREAM_MUSIC) / max) + "%");
                } catch (SecurityException exception) { showFeedback("系统未允许调整音量"); }
                break;
            default: break;
        }
    }

    /** The host deliberately applies this without changing the saved speed preference. */
    private boolean stopTemporarySpeed() {
        if (!temporaryBoost) return false;
        temporaryBoost = false;
        host.setTemporarySpeed(speedBeforeHold);
        return true;
    }

    private int controlsTimeoutMs() {
        int timeout = host.controlsTimeoutMs();
        return timeout == 0 || timeout == 3500 || timeout == 5000 || timeout == 8000 ? timeout : 3500;
    }

    private void scheduleHide() {
        handler.removeCallbacks(hide);
        int timeout = controlsTimeoutMs();
        if (!locked && timeout > 0 && active && controlsVisible && host.isPlaying() && !scrubbing && !gestures.isDragging())
            handler.postDelayed(hide, timeout);
    }
    private void showSeekPreview(long positionMs) {
        if (!active || locked || !host.canSeek() || host.durationMs() <= 0) return;
        long target = Math.max(0, Math.min(host.durationMs(), positionMs));
        feedback.setVisibility(GONE); handler.removeCallbacks(hideFeedback);
        seekPreviewTime.setText(time(target) + " / " + time(host.durationMs()));
        seekPreview.setVisibility(VISIBLE);
        topBar.setVisibility(INVISIBLE); actions.setVisibility(INVISIBLE);
        extraActions.setVisibility(compactActions ? INVISIBLE : GONE);
        bottomBar.setVisibility(VISIBLE); lockButton.setVisibility(GONE);
        seekBar.setProgress((int) Math.max(0, Math.min(1000, Math.round(target * 1000d / host.durationMs()))));
        if (target == requestedPreviewPosition) return;
        requestedPreviewPosition = target;
        seekPreviewImage.setImageDrawable(null); seekPreviewImage.setVisibility(GONE);
        final int request = ++previewRequest;
        host.requestSeekPreview(target, bitmap -> {
            Runnable render = () -> {
                if (!active || locked || request != previewRequest || target != requestedPreviewPosition
                        || seekPreview.getVisibility() != VISIBLE) return;
                if (bitmap == null || bitmap.isRecycled()) {
                    seekPreviewImage.setImageDrawable(null); seekPreviewImage.setVisibility(GONE);
                } else {
                    seekPreviewImage.setImageBitmap(bitmap); seekPreviewImage.setVisibility(VISIBLE);
                }
            };
            if (Looper.myLooper() == Looper.getMainLooper()) render.run(); else handler.post(render);
        });
    }

    private void hideSeekPreview() {
        boolean showing = requestedPreviewPosition >= 0;
        ++previewRequest; requestedPreviewPosition = -1;
        if (showing) host.cancelSeekPreview();
        if (seekPreview == null) return;
        seekPreview.setVisibility(GONE); seekPreviewImage.setImageDrawable(null); seekPreviewImage.setVisibility(GONE);
        topBar.setVisibility(controlsVisible && !locked ? VISIBLE : GONE);
        bottomBar.setVisibility(controlsVisible && !locked ? VISIBLE : GONE);
        actions.setVisibility(VISIBLE); extraActions.setVisibility(compactActions ? VISIBLE : GONE);
        updateLockControls();
    }

    private void showFeedback(String value) {
        hideSeekPreview(); handler.removeCallbacks(hideFeedback); feedback.setText(value); feedback.setVisibility(VISIBLE);
    }
    private void dismissFeedbackSoon() { handler.removeCallbacks(hideFeedback); handler.postDelayed(hideFeedback, 1000); }
    private ImageButton icon(String description, Glyph glyph, Runnable action) {
        ImageButton button = new ImageButton(getContext());
        // ImageButton's default CENTER uses intrinsic pixels: 24px was only 8dp
        // on xxhdpi phones. Both the intrinsic size and the content box use dp.
        button.setImageDrawable(new ControlIcon(glyph, dp(32)));
        button.setScaleType(ImageView.ScaleType.FIT_CENTER);
        button.setContentDescription(description); button.setBackground(controlBackground());
        // Background insets replace View padding, so set the icon padding last.
        button.setPadding(dp(8), dp(8), dp(8), dp(8));
        button.setOnClickListener(v -> {
            if (locked && v != unlockButton) return;
            action.run(); if (active) showControls();
        }); return button;
    }
    private Button label(String value, String description, Runnable action) {
        Button button = new Button(getContext()); button.setText(value); button.setTextColor(Color.WHITE); button.setTextSize(14);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); button.setIncludeFontPadding(false);
        button.setSingleLine(true);
        // These fixed-width labels never need TextView's unbounded single-line
        // scrolling layout; keep centering and ellipsis inside the touch target.
        button.setHorizontallyScrolling(false); button.setEllipsize(TextUtils.TruncateAt.END);
        button.setAllCaps(false); button.setMinWidth(0); button.setMinimumWidth(0); button.setMinHeight(0); button.setMinimumHeight(0);
        button.setContentDescription(description); button.setBackground(controlBackground());
        button.setPadding(dp(3), dp(7), dp(3), dp(7));
        button.setOnClickListener(v -> {
            if (locked) return;
            action.run(); if (active) showControls();
        }); return button;
    }
    private Drawable controlBackground() {
        // A mask limits the press ripple; no content layer is drawn while idle.
        GradientDrawable mask = new GradientDrawable(); mask.setColor(Color.WHITE); mask.setCornerRadius(dp(10));
        return new RippleDrawable(ColorStateList.valueOf(0x55FFFFFF), null, mask);
    }
    private TextView text(String value, float size) {
        TextView view = new TextView(getContext()); view.setText(value); view.setTextSize(size); view.setTextColor(Color.WHITE);
        view.setIncludeFontPadding(false); view.setShadowLayer(dp(1), 0, dp(1), 0xB3000000); return view;
    }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private static String speedText(float speed) { return String.format(Locale.ROOT, "%s×", speed); }
    private static String time(long ms) {
        long seconds = Math.max(0, ms) / 1000;
        return seconds >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
            : String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
    }

    private enum Glyph { PLAY, PAUSE, PREVIOUS, NEXT, BACK, HOME, FULL, EXIT_FULL, LOCK, UNLOCK }
    private static final class ControlIcon extends Drawable {
        private final Glyph glyph;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int intrinsicSize;
        ControlIcon(Glyph glyph, int intrinsicSize) {
            this.glyph = glyph; this.intrinsicSize = intrinsicSize;
            paint.setColor(Color.WHITE); paint.setStrokeWidth(2.2f);
            paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
        }
        @Override public void draw(Canvas canvas) {
            int checkpoint = canvas.save();
            canvas.translate(getBounds().left, getBounds().top);
            canvas.scale(getBounds().width() / 24f, getBounds().height() / 24f);
            paint.setStyle(Paint.Style.STROKE);
            Path path = new Path();
            switch (glyph) {
                case PLAY: paint.setStyle(Paint.Style.FILL); path.moveTo(6, 2); path.lineTo(22, 12); path.lineTo(6, 22); path.close(); break;
                case PAUSE: paint.setStyle(Paint.Style.FILL); canvas.drawRoundRect(5, 2, 10, 22, 1, 1, paint); canvas.drawRoundRect(14, 2, 19, 22, 1, 1, paint); break;
                case PREVIOUS:
                    paint.setStyle(Paint.Style.FILL); canvas.drawRoundRect(3, 4, 6, 20, 1, 1, paint);
                    path.moveTo(19, 4); path.lineTo(7, 12); path.lineTo(19, 20); path.close(); break;
                case NEXT:
                    paint.setStyle(Paint.Style.FILL); canvas.drawRoundRect(18, 4, 21, 20, 1, 1, paint);
                    path.moveTo(5, 4); path.lineTo(17, 12); path.lineTo(5, 20); path.close(); break;
                case BACK: path.moveTo(16, 3); path.lineTo(7, 12); path.lineTo(16, 21); break;
                case HOME: path.moveTo(3, 11); path.lineTo(12, 3); path.lineTo(21, 11); path.moveTo(6, 10); path.lineTo(6, 21); path.lineTo(10, 21); path.lineTo(10, 15); path.lineTo(14, 15); path.lineTo(14, 21); path.lineTo(18, 21); path.lineTo(18, 10); break;
                case LOCK:
                case UNLOCK:
                    canvas.drawRoundRect(5, 10, 19, 22, 2, 2, paint);
                    path.moveTo(8, 10); path.lineTo(8, 7);
                    if (glyph == Glyph.LOCK) { path.cubicTo(8, 1, 16, 1, 16, 7); path.lineTo(16, 10); }
                    else path.cubicTo(8, 1, 18, 1, 18, 7);
                    path.moveTo(12, 15); path.lineTo(12, 18); break;
                case FULL:
                    path.moveTo(9, 3); path.lineTo(3, 3); path.lineTo(3, 9); path.moveTo(15, 3); path.lineTo(21, 3); path.lineTo(21, 9);
                    path.moveTo(3, 15); path.lineTo(3, 21); path.lineTo(9, 21); path.moveTo(15, 21); path.lineTo(21, 21); path.lineTo(21, 15); break;
                case EXIT_FULL:
                    path.moveTo(3, 9); path.lineTo(9, 9); path.lineTo(9, 3); path.moveTo(15, 3); path.lineTo(15, 9); path.lineTo(21, 9);
                    path.moveTo(3, 15); path.lineTo(9, 15); path.lineTo(9, 21); path.moveTo(15, 21); path.lineTo(15, 15); path.lineTo(21, 15); break;
            }
            canvas.drawPath(path, paint); canvas.restoreToCount(checkpoint);
        }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        @Override public int getIntrinsicWidth() { return intrinsicSize; }
        @Override public int getIntrinsicHeight() { return intrinsicSize; }
    }
}
