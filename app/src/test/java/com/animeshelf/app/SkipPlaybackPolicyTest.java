package com.animeshelf.app;

import static org.junit.Assert.*;

import org.junit.Test;

/** Playback intent and elapsed-time behavior, independent of ExoPlayer or Android UI. */
public class SkipPlaybackPolicyTest {
    private static final long EPISODE = 1440000;

    private static SkipPlaybackPolicy policy(boolean enabled, int intro, int outro, long resume) {
        SkipPlaybackPolicy policy = new SkipPlaybackPolicy();
        policy.startSession(enabled, intro, outro, resume);
        return policy;
    }

    private static SkipPlaybackPolicy.Decision tick(SkipPlaybackPolicy policy, long position, long now) {
        return policy.update(position, EPISODE, true, true, true, now);
    }

    @Test public void defaultsAndDisabledSettingsDoNothing() {
        SkipPlaybackPolicy unset = new SkipPlaybackPolicy();
        assertEquals(SkipPlaybackPolicy.Action.NONE, tick(unset, 0, 0).action);
        SkipPlaybackPolicy disabled = policy(false, 90, 90, 0);
        assertEquals(SkipPlaybackPolicy.Action.NONE, tick(disabled, 0, 0).action);
        assertEquals(SkipPlaybackPolicy.Action.NONE, tick(disabled, EPISODE - 60000, 10000).action);
        SkipPlaybackPolicy zero = policy(true, 0, 0, 0);
        assertEquals(SkipPlaybackPolicy.Action.NONE, tick(zero, 0, 0).action);
        assertEquals(SkipPlaybackPolicy.Action.NONE, tick(zero, EPISODE - 1000, 10000).action);
    }

    @Test public void introSkipsOnceOnlyWhenTheSessionStartedAtTheBeginning() {
        SkipPlaybackPolicy policy = policy(true, 90, 90, 0);
        SkipPlaybackPolicy.Decision intro = tick(policy, 500, 0);
        assertEquals(SkipPlaybackPolicy.Action.SKIP_INTRO, intro.action);
        assertEquals(90000, intro.targetMs);
        assertEquals(0, intro.countdownSeconds);
        assertEquals(SkipPlaybackPolicy.Action.NONE, tick(policy, 0, 1000).action);
        assertEquals(SkipPlaybackPolicy.Action.NONE, tick(policy, 60000, 2000).action);

        for (long resume : new long[]{1001, 15000, 90000, -1}) {
            SkipPlaybackPolicy resumed = policy(true, 90, 90, resume);
            assertEquals("Resume must not be overwritten: " + resume,
                SkipPlaybackPolicy.Action.NONE, tick(resumed, Math.max(0, resume), 0).action);
        }
        SkipPlaybackPolicy nearStart = policy(true, 90, 90, 1000);
        assertEquals(SkipPlaybackPolicy.Action.SKIP_INTRO, tick(nearStart, 1000, 0).action);
    }

    @Test public void aLateFirstPollPastTheIntroDoesNotSeekBackwards() {
        SkipPlaybackPolicy policy = policy(true, 90, 90, 0);
        assertEquals(SkipPlaybackPolicy.Action.NONE, tick(policy, 90000, 0).action);
        assertEquals(SkipPlaybackPolicy.Action.NONE, tick(policy, 1000, 1000).action);
    }

    @Test public void introWaitsForKnownReadySeekablePlayingMedia() {
        SkipPlaybackPolicy policy = policy(true, 90, 90, 0);
        assertEquals(SkipPlaybackPolicy.Action.NONE, policy.update(0, EPISODE, false, true, true, 0).action);
        assertEquals(SkipPlaybackPolicy.Action.NONE, policy.update(0, EPISODE, true, false, true, 1).action);
        assertEquals(SkipPlaybackPolicy.Action.NONE, policy.update(0, EPISODE, true, true, false, 2).action);
        assertEquals(SkipPlaybackPolicy.Action.NONE, policy.update(0, -1, true, true, true, 3).action);
        assertEquals(SkipPlaybackPolicy.Action.NONE, policy.update(-1, EPISODE, true, true, true, 4).action);
        assertEquals(SkipPlaybackPolicy.Action.SKIP_INTRO, tick(policy, 500, 5).action);
    }

    @Test public void shortOrOverlappingSettingsCannotSkipMostOfTheVideo() {
        for (long duration : new long[]{1, 10000, 120000, 209999}) {
            SkipPlaybackPolicy policy = policy(true, 90, 90, 0);
            assertEquals(SkipPlaybackPolicy.Action.NONE, policy.update(0, duration, true, true, true, 0).action);
            assertEquals(SkipPlaybackPolicy.Action.NONE, policy.update(duration - 1, duration, true, true, true, 10000).action);
        }
        SkipPlaybackPolicy enoughContent = policy(true, 90, 90, 0);
        assertEquals(SkipPlaybackPolicy.Action.SKIP_INTRO,
            enoughContent.update(0, 210000, true, true, true, 0).action);
    }

    @Test public void corruptSecondsAreBoundedWithoutOverflow() {
        SkipPlaybackPolicy oversized = policy(true, Integer.MAX_VALUE, Integer.MAX_VALUE, 0);
        assertEquals(600000, tick(oversized, 0, 0).targetMs);
        SkipPlaybackPolicy negative = policy(true, Integer.MIN_VALUE, Integer.MIN_VALUE, 0);
        assertEquals(SkipPlaybackPolicy.Action.NONE, tick(negative, 0, 0).action);
    }

    @Test public void outroWaitsFiveSecondsThenSeeksToTheEndExactlyOnce() {
        SkipPlaybackPolicy policy = policy(true, 0, 90, 0);
        assertEquals(SkipPlaybackPolicy.Action.NONE, tick(policy, EPISODE - 90001, 0).action);
        SkipPlaybackPolicy.Decision start = tick(policy, EPISODE - 90000, 10000);
        assertEquals(SkipPlaybackPolicy.Action.COUNTDOWN_OUTRO, start.action);
        assertEquals(EPISODE, start.targetMs);
        assertEquals(5, start.countdownSeconds);
        assertEquals(5000, start.remainingMs);
        assertEquals(4, tick(policy, EPISODE - 89000, 11000).countdownSeconds);
        assertEquals(1, tick(policy, EPISODE - 85001, 14999).countdownSeconds);
        SkipPlaybackPolicy.Decision skip = tick(policy, EPISODE - 85000, 15000);
        assertEquals(SkipPlaybackPolicy.Action.SKIP_OUTRO, skip.action);
        assertEquals(EPISODE, skip.targetMs);
        assertEquals(0, skip.countdownSeconds);
        assertEquals(SkipPlaybackPolicy.Action.NONE, tick(policy, EPISODE - 85000, 16000).action);
        assertEquals(SkipPlaybackPolicy.Action.NONE, tick(policy, EPISODE, 17000).action);
    }

    @Test public void veryShortOutroOrAlreadyEndedPlaybackDoesNotShowAPointlessCountdown() {
        for (int outro : new int[]{0, 1, 5}) {
            assertEquals(SkipPlaybackPolicy.Action.NONE,
                tick(policy(true, 0, outro, 0), EPISODE - 2000, 0).action);
        }
        SkipPlaybackPolicy policy = policy(true, 0, 90, 0);
        assertEquals(SkipPlaybackPolicy.Action.NONE, tick(policy, EPISODE - 5000, 0).action);
        assertEquals(SkipPlaybackPolicy.Action.NONE, tick(policy, EPISODE, 1000).action);
        assertEquals(SkipPlaybackPolicy.Action.NONE, tick(policy, EPISODE + 1000, 2000).action);
    }

    @Test public void sessionDisableAndManualSeekingCancelTheNoticeAndFutureSkips() {
        for (boolean manual : new boolean[]{false, true}) {
            SkipPlaybackPolicy policy = policy(true, 90, 90, 0);
            assertEquals(SkipPlaybackPolicy.Action.COUNTDOWN_OUTRO, tick(policy, EPISODE - 60000, 1000).action);
            if (manual) policy.onManualSeek(); else policy.disableForSession();
            assertTrue(policy.isDisabledForSession());
            assertEquals(SkipPlaybackPolicy.Action.NONE, tick(policy, EPISODE - 55000, 10000).action);
            assertEquals(SkipPlaybackPolicy.Action.NONE, tick(policy, 0, 11000).action);
        }
        SkipPlaybackPolicy beforeIntro = policy(true, 90, 90, 0);
        beforeIntro.onManualSeek();
        assertEquals(SkipPlaybackPolicy.Action.NONE, tick(beforeIntro, 0, 0).action);
    }

    @Test public void pauseBufferingAndLostSeekabilityRestartTheFullCancellationWindow() {
        for (int interruption = 0; interruption < 4; interruption++) {
            SkipPlaybackPolicy policy = policy(true, 0, 90, 0);
            tick(policy, EPISODE - 90000, 1000);
            assertEquals(2, tick(policy, EPISODE - 87000, 4000).countdownSeconds);
            SkipPlaybackPolicy.Decision suspended = policy.update(EPISODE - 87000,
                interruption == 3 ? -1 : EPISODE, interruption != 0, interruption != 1, interruption != 2, 5000);
            assertEquals(SkipPlaybackPolicy.Action.NONE, suspended.action);
            assertEquals(5, tick(policy, EPISODE - 87000, 30000).countdownSeconds);
            assertEquals(SkipPlaybackPolicy.Action.COUNTDOWN_OUTRO, tick(policy, EPISODE - 83000, 34999).action);
            assertEquals(SkipPlaybackPolicy.Action.SKIP_OUTRO, tick(policy, EPISODE - 82000, 35000).action);
        }
    }

    @Test public void leavingAnAnnouncedOutroDoesNotStartAnotherAttemptWhenReturning() {
        SkipPlaybackPolicy policy = policy(true, 0, 90, 0);
        tick(policy, EPISODE - 90000, 1000);
        assertEquals(SkipPlaybackPolicy.Action.NONE, tick(policy, EPISODE - 100000, 2000).action);
        assertEquals(SkipPlaybackPolicy.Action.NONE, tick(policy, EPISODE - 90000, 3000).action);
        assertEquals(SkipPlaybackPolicy.Action.NONE, tick(policy, EPISODE - 80000, 20000).action);
    }

    @Test public void changedDurationAndClockRollbackNeverFastForwardAnExistingCountdown() {
        SkipPlaybackPolicy policy = policy(true, 0, 90, 0);
        tick(policy, EPISODE - 60000, 10000);
        assertEquals(5, policy.update(EPISODE - 58000, EPISODE + 1000, true, true, true, 14000).countdownSeconds);
        assertEquals(5, policy.update(EPISODE - 57000, EPISODE + 1000, true, true, true, 12000).countdownSeconds);
        assertEquals(SkipPlaybackPolicy.Action.COUNTDOWN_OUTRO,
            policy.update(EPISODE - 55000, EPISODE + 1000, true, true, true, 16999).action);
        assertEquals(SkipPlaybackPolicy.Action.SKIP_OUTRO,
            policy.update(EPISODE - 54000, EPISODE + 1000, true, true, true, 17000).action);
    }

    @Test public void aNewSessionResetsConsumedDecisionsAndTemporaryDisable() {
        SkipPlaybackPolicy policy = policy(true, 90, 90, 0);
        assertEquals(SkipPlaybackPolicy.Action.SKIP_INTRO, tick(policy, 0, 0).action);
        policy.disableForSession();
        policy.startSession(true, 90, 90, 0);
        assertFalse(policy.isDisabledForSession());
        assertEquals(SkipPlaybackPolicy.Action.SKIP_INTRO, tick(policy, 0, 1).action);
        policy.reset();
        assertTrue(policy.isDisabledForSession());
        assertEquals(SkipPlaybackPolicy.Action.NONE, tick(policy, EPISODE - 60000, 20000).action);
        policy.startSession(true, 0, 90, 0);
        assertEquals(5, tick(policy, EPISODE - 60000, 30000).countdownSeconds);
    }

    @Test public void invalidTimeCannotTriggerASkipAndLargeElapsedTimesDoNotOverflow() {
        SkipPlaybackPolicy policy = policy(true, 0, 90, 0);
        assertEquals(SkipPlaybackPolicy.Action.NONE, tick(policy, EPISODE - 60000, -1).action);
        assertEquals(5, tick(policy, EPISODE - 60000, Long.MAX_VALUE - 10000).countdownSeconds);
        assertEquals(SkipPlaybackPolicy.Action.SKIP_OUTRO, tick(policy, EPISODE - 55000, Long.MAX_VALUE - 5000).action);
    }
}
