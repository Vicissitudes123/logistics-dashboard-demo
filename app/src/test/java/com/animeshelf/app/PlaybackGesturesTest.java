package com.animeshelf.app;

import static org.junit.Assert.*;

import org.junit.Test;

/** Gesture intent and cancellation are tested independently of rendering and media decoding. */
public class PlaybackGesturesTest {
    private static final int WIDTH = 400, HEIGHT = 240;
    private static final float EDGE = 12, SLOP = 8;

    private static PlaybackGestures.Snapshot ready() {
        return new PlaybackGestures.Snapshot(60000, 300000, true, 1f, 5, 10);
    }

    private static PlaybackGestures start(float x, float y, PlaybackGestures.Snapshot snapshot) {
        PlaybackGestures gestures = new PlaybackGestures();
        assertTrue(gestures.begin(x, y, WIDTH, HEIGHT, EDGE, SLOP, snapshot));
        return gestures;
    }

    @Test public void smallFingerMovementRemainsATapAndDoesNotSeek() {
        PlaybackGestures gestures = start(160, 100, ready());
        gestures.move(163, 104);

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
        assertEquals(PlaybackGestures.Mode.SPEED, vertical.move(380, 69).mode);
    }

    @Test public void leftAndRightVerticalDragsUseTheStartingSide() {
        PlaybackGestures left = start(120, 180, ready());
        PlaybackGestures.Update speed = left.move(121, 80);
        assertEquals(PlaybackGestures.Mode.SPEED, speed.mode);
        assertTrue(speed.speed > 1f);
        assertEquals(5, speed.volume);
        assertEquals(PlaybackGestures.Mode.SPEED, left.move(350, 70).mode);
        assertFalse(left.finish(false).commitSeek);

        PlaybackGestures right = start(280, 180, ready());
        PlaybackGestures.Update volume = right.move(281, 80);
        assertEquals(PlaybackGestures.Mode.VOLUME, volume.mode);
        assertTrue(volume.volume > 5);
        assertEquals(1f, volume.speed, .001f);
        assertEquals(PlaybackGestures.Mode.VOLUME, right.move(50, 70).mode);
        assertFalse(right.finish(false).commitSeek);
    }

    @Test public void downwardVerticalDragsReduceSpeedAndVolume() {
        PlaybackGestures left = start(120, 80, ready());
        assertTrue(left.move(121, 180).speed < 1f);
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

    @Test public void playbackSpeedUsesQuarterStepsAndStaysInItsSupportedRange() {
        PlaybackGestures faster = start(120, 160, ready());
        float step = faster.move(120, 140).speed;
        assertTrue(step > 1f);
        assertEquals(Math.round(step * 4f), step * 4f, .001f);
        assertEquals(3f, faster.move(120, -100000).speed, .001f);

        PlaybackGestures slower = start(120, 100, ready());
        assertEquals(.25f, slower.move(120, 100000).speed, .001f);
    }

    @Test public void unknownDurationOrNonseekableMediaNeverCommitsASeek() {
        for (PlaybackGestures.Snapshot snapshot : new PlaybackGestures.Snapshot[]{
            new PlaybackGestures.Snapshot(60000, -1, true, 1f, 5, 10),
            new PlaybackGestures.Snapshot(60000, 0, true, 1f, 5, 10),
            new PlaybackGestures.Snapshot(60000, 300000, false, 1f, 5, 10)
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
        for (float x : new float[]{0, EDGE / 2, WIDTH - EDGE / 2, WIDTH}) {
            PlaybackGestures gestures = new PlaybackGestures();
            assertFalse(gestures.begin(x, 100, WIDTH, HEIGHT, EDGE, SLOP, ready()));
            assertFalse(gestures.move(200, 100).commitSeek);
            assertFalse(gestures.finish(false).commitSeek);
        }
    }
}
