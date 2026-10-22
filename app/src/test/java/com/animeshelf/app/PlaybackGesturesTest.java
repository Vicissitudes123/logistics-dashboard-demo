package com.animeshelf.app;

import static org.junit.Assert.*;

import org.junit.Test;

/** Gesture intent and cancellation are tested independently of rendering and media decoding. */
public class PlaybackGesturesTest {
    private static final int WIDTH = 400, HEIGHT = 240;
    private static final float EDGE = 12, SLOP = 8;

    private static PlaybackGestures.Snapshot ready() {
        return new PlaybackGestures.Snapshot(60000, 300000, true, .5f, 5, 10);
    }

    private static PlaybackGestures start(float x, float y, PlaybackGestures.Snapshot snapshot) {
        PlaybackGestures gestures = new PlaybackGestures();
        assertTrue(gestures.begin(x, y, WIDTH, HEIGHT, EDGE, SLOP, snapshot));
        return gestures;
    }

    @Test public void smallFingerMovementRemainsATapAndDoesNotSeek() {
        PlaybackGestures gestures = start(160, 100, ready());
        gestures.move(163, 104);
        gestures.move(168, 104);

        assertFalse(gestures.isDragging());
        assertEquals(PlaybackGestures.Mode.PENDING, gestures.getMode());
        assertFalse(gestures.finish(false).commitSeek);
    }

    @Test public void horizontalDragPreviewsThenCommitsExactlyOnce() {
        PlaybackGestures gestures = start(160, 100, ready());
        PlaybackGestures.Update preview = gestures.move(240, 104);

        assertEquals(PlaybackGestures.Mode.SEEK, preview.mode);
        assertTrue(gestures.isDragging());
        assertTrue(preview.positionMs > 60000);
        assertFalse(preview.commitSeek);
        PlaybackGestures.Update commit = gestures.finish(false);
        assertTrue(commit.commitSeek);
        assertEquals(preview.positionMs, commit.positionMs);
        assertFalse(gestures.finish(false).commitSeek);
    }

    @Test public void dragAxisStaysLockedWhenTheFingerChangesDirection() {
        PlaybackGestures horizontal = start(160, 100, ready());
        horizontal.move(200, 101);
        assertEquals(PlaybackGestures.Mode.SEEK, horizontal.move(201, 220).mode);

        PlaybackGestures vertical = start(160, 100, ready());
        vertical.move(161, 70);
        assertEquals(PlaybackGestures.Mode.BRIGHTNESS, vertical.move(380, 69).mode);
    }

    @Test public void leftAndRightVerticalDragsUseTheStartingSide() {
        PlaybackGestures left = start(120, 180, ready());
        PlaybackGestures.Update brightness = left.move(121, 80);
        assertEquals(PlaybackGestures.Mode.BRIGHTNESS, brightness.mode);
        assertTrue(brightness.brightness > .5f);
        assertEquals(5, brightness.volume);
        assertEquals(PlaybackGestures.Mode.BRIGHTNESS, left.move(350, 70).mode);
        assertFalse(left.finish(false).commitSeek);

        PlaybackGestures right = start(280, 180, ready());
        PlaybackGestures.Update volume = right.move(281, 80);
        assertEquals(PlaybackGestures.Mode.VOLUME, volume.mode);
        assertTrue(volume.volume > 5);
        assertEquals(.5f, volume.brightness, .001f);
        assertEquals(PlaybackGestures.Mode.VOLUME, right.move(50, 70).mode);
        assertFalse(right.finish(false).commitSeek);
    }

    @Test public void downwardVerticalDragsReduceBrightnessAndVolume() {
        PlaybackGestures left = start(120, 80, ready());
        assertTrue(left.move(121, 180).brightness < .5f);
        PlaybackGestures right = start(280, 80, ready());
        assertTrue(right.move(281, 180).volume < 5);
    }

    @Test public void seekAndVolumeStayInsideTheirAvailableRanges() {
        PlaybackGestures forward = start(160, 100, ready());
        long end = forward.move(100000, 100).positionMs;
        assertTrue(end >= 60000 && end <= 300000);
        PlaybackGestures backward = start(160, 100, ready());
        assertEquals(0, backward.move(-100000, 100).positionMs);

        PlaybackGestures loud = start(280, 100, ready());
        assertEquals(10, loud.move(280, -100000).volume);
        PlaybackGestures quiet = start(280, 100, ready());
        assertEquals(0, quiet.move(280, 100000).volume);
    }

    @Test public void brightnessMovesContinuouslyAndStaysInItsSupportedRange() {
        PlaybackGestures brighter = start(120, 160, ready());
        float first = brighter.move(120, 145).brightness;
        float next = brighter.move(120, 144).brightness;
        assertTrue(first > .5f);
        assertTrue(next > first);
        assertEquals(1f, brighter.move(120, -100000).brightness, .001f);

        PlaybackGestures dimmer = start(120, 100, ready());
        assertEquals(.05f, dimmer.move(120, 100000).brightness, .001f);
    }

    @Test public void invalidInitialBrightnessHasAFiniteUsableStartingValue() {
        float[] values = {Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -1f, 2f};
        float[] expected = {.5f, .5f, .5f, .05f, 1f};
        for (int i = 0; i < values.length; i++) {
            PlaybackGestures gestures = start(120, 100,
                new PlaybackGestures.Snapshot(60000, 300000, true, values[i], 5, 10));
            assertEquals(expected[i], gestures.move(120, 100).brightness, .001f);
        }
    }

    @Test public void unknownDurationOrNonseekableMediaNeverCommitsASeek() {
        for (PlaybackGestures.Snapshot snapshot : new PlaybackGestures.Snapshot[]{
            new PlaybackGestures.Snapshot(60000, -1, true, .5f, 5, 10),
            new PlaybackGestures.Snapshot(60000, 0, true, .5f, 5, 10),
            new PlaybackGestures.Snapshot(60000, 300000, false, .5f, 5, 10)
        }) {
            PlaybackGestures gestures = start(160, 100, snapshot);
            assertEquals(PlaybackGestures.Mode.BLOCKED_SEEK, gestures.move(240, 100).mode);
            assertFalse(gestures.finish(false).commitSeek);
        }
    }

    @Test public void cancelDiscardsSeekAndTheNextGestureStartsFresh() {
        PlaybackGestures gestures = start(160, 100, ready());
        gestures.move(240, 100);
        assertFalse(gestures.finish(true).commitSeek);
        assertFalse(gestures.finish(false).commitSeek);

        assertTrue(gestures.begin(280, 180, WIDTH, HEIGHT, EDGE, SLOP, ready()));
        assertEquals(PlaybackGestures.Mode.VOLUME, gestures.move(280, 100).mode);
        assertFalse(gestures.finish(false).commitSeek);
    }

    @Test public void screenEdgeTouchesAreReservedForSystemNavigation() {
        for (float[] point : new float[][]{{0, 100}, {EDGE / 2, 100}, {WIDTH - EDGE / 2, 100}, {WIDTH, 100},
            {200, 0}, {200, EDGE / 2}, {200, HEIGHT - EDGE / 2}, {200, HEIGHT}}) {
            PlaybackGestures gestures = new PlaybackGestures();
            assertFalse(gestures.begin(point[0], point[1], WIDTH, HEIGHT, EDGE, SLOP, ready()));
            assertFalse(gestures.move(200, 100).commitSeek);
            assertFalse(gestures.finish(false).commitSeek);
        }
    }

    @Test public void newPointerDownDiscardsAPreviousUncommittedSeek() {
        PlaybackGestures gestures = start(160, 100, ready());
        gestures.move(240, 100);
        assertTrue(gestures.begin(280, 180, WIDTH, HEIGHT, EDGE, SLOP,
            new PlaybackGestures.Snapshot(120000, 300000, true, .5f, 5, 10)));

        PlaybackGestures.Update result = gestures.finish(false);
        assertEquals(PlaybackGestures.Mode.PENDING, result.mode);
        assertEquals(120000, result.positionMs);
        assertFalse(result.commitSeek);
    }
}
