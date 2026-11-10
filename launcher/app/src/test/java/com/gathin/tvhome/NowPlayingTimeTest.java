package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class NowPlayingTimeTest {
    @Test
    public void timeLeftRoundsUpToTheSecond() {
        assertEquals("-0:00", NowPlaying.timeLeft(0));
        assertEquals("-0:01", NowPlaying.timeLeft(1));
        assertEquals("-2:05", NowPlaying.timeLeft(125_000));
        assertEquals("-1:02:05", NowPlaying.timeLeft(3_725_000));
    }
}
