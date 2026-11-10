package com.gathin.tvhome;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

/**
 * StandBy, shown by Home after home_idle_clock_minutes without input or on Back. It only ever starts from Home, so
 * it can never cut into a programme. Left and right change the clock face; any other key returns to Home. Media and
 * volume keys reach the music playing in the background. While a song shows (NowPlaying, right of the clock) Left,
 * Right and OK work its Previous / Play-Pause / Next, and Up and Down change the face instead.
 */
public class ClockActivity extends Activity {
    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));   // locale
    }

    private StandbyView view;
    private NowPlaying nowPlaying;
    private SharedPreferences prefs;
    private int face;

    @Override
    @SuppressWarnings("deprecation")
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("standby", MODE_PRIVATE);
        face = prefs.getInt("face", 0);
        view = new StandbyView(this);
        view.setFace(face);
        android.widget.FrameLayout root = new android.widget.FrameLayout(this);
        root.addView(view);
        setContentView(root);
        nowPlaying = new NowPlaying(this, root, null, null, null, null, getResources().getDisplayMetrics().density);
        nowPlaying.setOnChange(this::placeSong);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        nowPlaying.start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        nowPlaying.stop();
        finish();   // never left behind in the back stack
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        if (passesThrough(key)) return super.dispatchKeyEvent(event);
        boolean song = nowPlaying.showing();
        if (song && (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT
                || key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER)) {
            return super.dispatchKeyEvent(event);   // along the song's buttons, and OK presses one
        }
        // The key that wakes the clock should not also act on Home, so it is consumed here.
        if (event.getAction() != KeyEvent.ACTION_UP) return true;
        int back = song ? KeyEvent.KEYCODE_DPAD_UP : KeyEvent.KEYCODE_DPAD_LEFT;
        int next = song ? KeyEvent.KEYCODE_DPAD_DOWN : KeyEvent.KEYCODE_DPAD_RIGHT;
        if (key == back || key == next) {
            int step = key == next ? 1 : StandbyView.FACES - 1;
            face = (face + step) % StandbyView.FACES;
            prefs.edit().putInt("face", face).apply();
            view.setFace(face);
            placeSong();
        } else {
            finish();
        }
        return true;
    }

    /** The song right of the clock: under the calendar on the calendar face, else halfway down. */
    private void placeSong() {
        boolean song = nowPlaying.showing();
        view.setMusic(song);
        if (!song) return;
        android.util.DisplayMetrics screen = getResources().getDisplayMetrics();
        android.widget.FrameLayout.LayoutParams lp =
                (android.widget.FrameLayout.LayoutParams) nowPlaying.view().getLayoutParams();
        lp.gravity = android.view.Gravity.START | (face == 0 ? android.view.Gravity.TOP : android.view.Gravity.CENTER_VERTICAL);
        lp.setMarginStart(Math.round(StandbyView.MUSIC_LEFT * screen.widthPixels));
        lp.topMargin = face == 0 ? Math.round(StandbyView.MUSIC_TOP * screen.heightPixels) : 0;
        nowPlaying.view().setLayoutParams(lp);
    }

    /** Volume, mute and the media keys: the music keeps its remote while the clock is up. */
    static boolean passesThrough(int key) {
        switch (key) {
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_MUTE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
            case KeyEvent.KEYCODE_MEDIA_STOP:
                return true;
            default:
                return false;
        }
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        finish();
        return true;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void finish() {
        super.finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }
}
