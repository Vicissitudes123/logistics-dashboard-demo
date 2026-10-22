package com.animeshelf.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
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

/** Video-only controls. Interactive child views retain their own touch streams. */
public final class PlayerOverlay extends FrameLayout {
    public interface Host {
        boolean hasPlayer();
        boolean isPlaying();
        boolean canSeek();
        long positionMs();
        long durationMs();
        float speed();
        void togglePlayback();
        void seekTo(long positionMs);
        void setSpeed(float speed);
        void chooseSpeed();
        void chooseEpisode();
        void chooseDisplay();
        void back();
        void home();
        void toggleFullScreen();
    }

    private final Host host;
    private final AudioManager audio;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final PlaybackGestures gestures = new PlaybackGestures();
    private final GestureDetector taps;
    private final LinearLayout topBar, bottomBar;
    private final TextView title, subtitle, position, duration, feedback;
    private final ImageButton playButton, fullButton;
    private final Button speedButton;
    private final SeekBar seekBar;
    private boolean active, controlsVisible = true, fullScreen, scrubbing, scrubCanceled, touchAccepted, multiTouch, wasPlaying;
    private long previewMs = -1;
    private int appliedVolume = -1;
    private float appliedSpeed = Float.NaN;
    private float edgeInsetPx;

    private final Runnable hide = new Runnable() {
        @Override public void run() { if (active && host.isPlaying() && !scrubbing && !gestures.isDragging()) hideControls(); }
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
        setContentDescription("视频画面，单击显示控制，双击播放或暂停；横滑进度，左侧上下倍速，右侧上下音量");

        topBar = new LinearLayout(context);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(4), dp(2), dp(4), dp(2));
        topBar.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{0xD9000000, 0x77000000, 0x00000000}));
        topBar.addView(icon("返回", Glyph.BACK, false, host::back), new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout titles = new LinearLayout(context);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(6), 0, dp(8), 0);
        title = text("", 16); title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setSingleLine(true); title.setEllipsize(TextUtils.TruncateAt.END);
        subtitle = text("", 12); subtitle.setTextColor(0xFFD3D8E2); subtitle.setSingleLine(true); subtitle.setEllipsize(TextUtils.TruncateAt.END);
        subtitle.setPadding(0, dp(2), 0, 0);
        titles.addView(title); titles.addView(subtitle);
        topBar.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        topBar.addView(icon("回到首页", Glyph.HOME, false, host::home), new LinearLayout.LayoutParams(dp(48), dp(48)));
        addView(topBar, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        bottomBar = new LinearLayout(context);
        bottomBar.setOrientation(LinearLayout.VERTICAL);
        bottomBar.setPadding(dp(8), 0, dp(8), 0);
        bottomBar.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
            new int[]{0xE6000000, 0x88000000, 0x00000000}));
        LinearLayout timeline = new LinearLayout(context); timeline.setGravity(Gravity.CENTER_VERTICAL);
        position = text("00:00", 12); duration = text("--:--", 12);
        position.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        duration.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        position.setGravity(Gravity.CENTER); duration.setGravity(Gravity.CENTER);
        position.setSingleLine(true); duration.setSingleLine(true);
        timeline.addView(position, new LinearLayout.LayoutParams(dp(54), dp(32)));
        seekBar = new SeekBar(context); seekBar.setMax(1000); seekBar.setContentDescription("播放进度");
        seekBar.setProgressTintList(ColorStateList.valueOf(0xFFBEA6FF));
        seekBar.setProgressBackgroundTintList(ColorStateList.valueOf(0x66FFFFFF));
        seekBar.setThumbTintList(ColorStateList.valueOf(Color.WHITE));
        seekBar.setPadding(dp(8), 0, dp(8), 0);
        timeline.addView(seekBar, new LinearLayout.LayoutParams(0, dp(48), 1));
        timeline.addView(duration, new LinearLayout.LayoutParams(dp(54), dp(32)));
        bottomBar.addView(timeline);
        LinearLayout actions = new LinearLayout(context); actions.setGravity(Gravity.CENTER_VERTICAL);
        playButton = icon("播放", Glyph.PLAY, true, () -> { host.togglePlayback(); refresh(); });
        actions.addView(playButton, new LinearLayout.LayoutParams(dp(48), dp(48)));
        speedButton = label("1.0×", "播放速度", host::chooseSpeed);
        actions.addView(speedButton, new LinearLayout.LayoutParams(dp(72), dp(48)));
        actions.addView(label("选集", "选集", host::chooseEpisode), new LinearLayout.LayoutParams(dp(52), dp(48)));
        actions.addView(label("画面", "显示方式", host::chooseDisplay), new LinearLayout.LayoutParams(dp(52), dp(48)));
        actions.addView(new View(context), new LinearLayout.LayoutParams(0, 1, 1));
        fullButton = icon("全屏", Glyph.FULL, true, host::toggleFullScreen);
        actions.addView(fullButton, new LinearLayout.LayoutParams(dp(48), dp(48)));
        bottomBar.addView(actions);
        addView(bottomBar, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        feedback = text("", 17);
        feedback.setGravity(Gravity.CENTER); feedback.setPadding(dp(18), dp(10), dp(18), dp(10));
        GradientDrawable bubble = new GradientDrawable(); bubble.setColor(0xDD171B27); bubble.setCornerRadius(dp(12));
        feedback.setBackground(bubble); feedback.setVisibility(GONE);
        feedback.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(feedback, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        seekBar.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_CANCEL || event.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN) scrubCanceled = true;
            return false;
        });
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar bar) {
                scrubbing = true; scrubCanceled = false; previewMs = host.positionMs();
                handler.removeCallbacks(hide); showControls();
            }
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                if (!fromUser || !host.canSeek() || host.durationMs() <= 0) return;
                previewMs = Math.max(0, Math.min(host.durationMs(), Math.round(host.durationMs() * (value / 1000d))));
                position.setText(time(previewMs)); showFeedback("进度 " + time(previewMs) + " / " + time(host.durationMs()));
            }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                if (!scrubCanceled && host.canSeek() && previewMs >= 0) host.seekTo(previewMs);
                scrubbing = false; previewMs = -1;
                refresh(); dismissFeedbackSoon(); scheduleHide();
            }
        });

        taps = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent event) { return true; }
            @Override public boolean onSingleTapConfirmed(MotionEvent event) {
                if (!active || multiTouch) return false;
                performClick(); return true;
            }
            @Override public boolean onDoubleTap(MotionEvent event) {
                if (!active || multiTouch || !host.hasPlayer()) return false;
                host.togglePlayback(); refresh();
                showFeedback(host.isPlaying() ? "继续播放" : "已暂停"); dismissFeedbackSoon();
                if (!host.isPlaying()) showControls(); else scheduleHide();
                return true;
            }
        });
        taps.setIsLongpressEnabled(false);
        refresh();
    }

    public void setActive(boolean value) {
        active = value;
        cancelInteraction();
        handler.removeCallbacks(update);
        if (value) { showControls(); handler.post(update); }
        else { handler.removeCallbacks(hide); handler.removeCallbacks(hideFeedback); feedback.setVisibility(GONE); }
    }

    public void setTitle(String name, String detail) { title.setText(name); subtitle.setText(detail); }
    public void setFullScreen(boolean value) {
        fullScreen = value;
        fullButton.setImageDrawable(new ControlIcon(value ? Glyph.EXIT_FULL : Glyph.FULL, dp(32)));
        fullButton.setContentDescription(value ? "退出全屏" : "全屏");
        cancelInteraction(); showControls();
    }

    /** Insets supplied by the window; no system gesture exclusion region is requested. */
    public void setSystemGestureInset(int left, int top, int right, int bottom) {
        edgeInsetPx = Math.max(dp(20), Math.max(Math.max(left, right), Math.max(top, bottom)));
    }

    public void refresh() {
        long length = host.durationMs();
        boolean seekable = host.hasPlayer() && host.canSeek() && length > 0;
        seekBar.setEnabled(seekable);
        seekBar.setAlpha(seekable ? 1f : .45f);
        playButton.setEnabled(host.hasPlayer());
        playButton.setImageDrawable(new ControlIcon(host.isPlaying() ? Glyph.PAUSE : Glyph.PLAY, dp(32)));
        playButton.setContentDescription(host.isPlaying() ? "暂停" : "播放");
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
        controlsVisible = true; topBar.setVisibility(VISIBLE); bottomBar.setVisibility(VISIBLE);
        scheduleHide();
    }
    public void hideControls() {
        controlsVisible = false; topBar.setVisibility(GONE); bottomBar.setVisibility(GONE);
        handler.removeCallbacks(hide);
    }
    public boolean areControlsVisible() { return controlsVisible; }

    public void cancelInteraction() {
        gestures.finish(true); touchAccepted = false; multiTouch = true;
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
        scrubbing = false; scrubCanceled = true; previewMs = -1;
        if (taps != null) {
            long now = android.os.SystemClock.uptimeMillis();
            MotionEvent cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0, 0, 0);
            taps.onTouchEvent(cancel); cancel.recycle();
        }
        handler.removeCallbacks(hideFeedback);
        feedback.setVisibility(GONE);
    }
    public void dispose() { active = false; cancelInteraction(); handler.removeCallbacksAndMessages(null); }

    @Override public boolean performClick() {
        super.performClick();
        if (controlsVisible) hideControls(); else showControls();
        return true;
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!active || !host.hasPlayer()) return false;
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            multiTouch = false;
            int maxVolume = audio == null || audio.isVolumeFixed() ? 0 : audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int volume = audio == null ? 0 : audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            PlaybackGestures.Snapshot snapshot = new PlaybackGestures.Snapshot(host.positionMs(), host.durationMs(), host.canSeek(), host.speed(), volume, maxVolume);
            touchAccepted = gestures.begin(event.getX(), event.getY(), getWidth(), getHeight(), edgeInsetPx,
                ViewConfiguration.get(getContext()).getScaledTouchSlop(), snapshot);
            if (!touchAccepted) return false;
            appliedVolume = volume; appliedSpeed = snapshot.speed;
            handler.removeCallbacks(hide);
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
        }
        if (!touchAccepted) return false;
        if (action == MotionEvent.ACTION_CANCEL) {
            cancelInteraction(); refresh(); scheduleHide(); return true;
        }
        if (action == MotionEvent.ACTION_POINTER_DOWN || event.getPointerCount() > 1) {
            multiTouch = true; gestures.finish(true); previewMs = -1;
            MotionEvent cancel = MotionEvent.obtain(event); cancel.setAction(MotionEvent.ACTION_CANCEL); taps.onTouchEvent(cancel); cancel.recycle();
            feedback.setVisibility(GONE);
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
            return true;
        }
        if (!multiTouch) taps.onTouchEvent(event);
        if (action == MotionEvent.ACTION_MOVE && !multiTouch) {
            PlaybackGestures.Update change = gestures.move(event.getX(), event.getY());
            if (gestures.isDragging()) { hideControls(); applyPreview(change); }
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            PlaybackGestures.Update result = gestures.finish(action == MotionEvent.ACTION_CANCEL || multiTouch);
            if (result.commitSeek && host.canSeek()) host.seekTo(result.positionMs);
            previewMs = -1; touchAccepted = false;
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
            dismissFeedbackSoon(); refresh(); scheduleHide();
        }
        return true;
    }

    private void applyPreview(PlaybackGestures.Update change) {
        switch (change.mode) {
            case SEEK:
                previewMs = change.positionMs;
                showFeedback("进度 " + time(change.positionMs) + " / " + time(host.durationMs()) + "\n松手跳转");
                position.setText(time(change.positionMs)); break;
            case BLOCKED_SEEK: showFeedback("当前视频暂不能拖动进度"); break;
            case SPEED:
                if (Float.compare(appliedSpeed, change.speed) != 0) { host.setSpeed(change.speed); appliedSpeed = change.speed; }
                showFeedback("倍速 " + speedText(change.speed)); break;
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

    private void scheduleHide() {
        handler.removeCallbacks(hide);
        if (active && controlsVisible && host.isPlaying() && !scrubbing && !gestures.isDragging()) handler.postDelayed(hide, 3500);
    }
    private void showFeedback(String value) { handler.removeCallbacks(hideFeedback); feedback.setText(value); feedback.setVisibility(VISIBLE); }
    private void dismissFeedbackSoon() { handler.removeCallbacks(hideFeedback); handler.postDelayed(hideFeedback, 1000); }
    private ImageButton icon(String description, Glyph glyph, boolean emphasized, Runnable action) {
        ImageButton button = new ImageButton(getContext());
        // ImageButton's default CENTER uses intrinsic pixels: 24px was only 8dp
        // on xxhdpi phones. Both the intrinsic size and the content box use dp.
        button.setImageDrawable(new ControlIcon(glyph, dp(32)));
        button.setScaleType(ImageView.ScaleType.FIT_CENTER);
        button.setContentDescription(description); button.setBackground(controlBackground(emphasized));
        // Background insets replace View padding, so set the icon padding last.
        button.setPadding(dp(8), dp(8), dp(8), dp(8));
        button.setOnClickListener(v -> { action.run(); if (active) showControls(); }); return button;
    }
    private Button label(String value, String description, Runnable action) {
        Button button = new Button(getContext()); button.setText(value); button.setTextColor(Color.WHITE); button.setTextSize(14);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); button.setIncludeFontPadding(false);
        button.setSingleLine(true);
        button.setAllCaps(false); button.setMinWidth(0); button.setMinimumWidth(0); button.setMinHeight(0); button.setMinimumHeight(0);
        button.setPadding(0, 0, 0, 0); button.setContentDescription(description); button.setBackground(controlBackground(false));
        button.setOnClickListener(v -> { action.run(); if (active) showControls(); }); return button;
    }
    private Drawable controlBackground(boolean emphasized) {
        GradientDrawable plate = new GradientDrawable();
        plate.setColor(emphasized ? 0x50A890ED : 0x24101420);
        plate.setCornerRadius(dp(10));
        plate.setStroke(dp(1), emphasized ? 0x60D7C9FF : 0x2AFFFFFF);
        GradientDrawable mask = new GradientDrawable(); mask.setColor(Color.WHITE); mask.setCornerRadius(dp(10));
        return new InsetDrawable(new RippleDrawable(ColorStateList.valueOf(0x55FFFFFF), plate, mask), dp(3), dp(7), dp(3), dp(7));
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

    private enum Glyph { PLAY, PAUSE, BACK, HOME, FULL, EXIT_FULL }
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
                case BACK: path.moveTo(16, 3); path.lineTo(7, 12); path.lineTo(16, 21); break;
                case HOME: path.moveTo(3, 11); path.lineTo(12, 3); path.lineTo(21, 11); path.moveTo(6, 10); path.lineTo(6, 21); path.lineTo(10, 21); path.lineTo(10, 15); path.lineTo(14, 15); path.lineTo(14, 21); path.lineTo(18, 21); path.lineTo(18, 10); break;
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
