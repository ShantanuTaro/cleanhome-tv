package com.gathin.tvhome;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Now Playing: what Spotify, Amazon Music or any other app with a media session is playing in the background.
 *
 * On Home (with the pills) it is a card under them, in layers from the top: pills, the card's Previous / Play-Pause /
 * Next buttons, the card itself, the dock. Up from the dock lands on the card, Up again on its buttons; Down goes
 * back the same way. OK on the card opens the app. On the idle clock (no pills) the buttons sit under the song and
 * the clock places the card (view(), onChange).
 *
 * Android only hands other apps' sessions to an approved notification listener, so this shows nothing until
 * {@code cmd notification allow_listener com.gathin.tvhome/.GuardRevival} (NOW_PLAYING=1 build-and-install.sh).
 */
final class NowPlaying {
    private static final String TAG = "TvHome.NowPlaying";

    private final Activity activity;
    private final ViewGroup root;
    private final View topButtons;
    private final BooleanSupplier dockFocused;
    private final Runnable backToDock;
    private final float density;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final MediaSessionManager sessions;
    private final ComponentName listener;
    private final LinearLayout card;   // the cover and song, and under them the progress line on the bottom edge
    private final FrameLayout line;
    private final LinearLayout controls;
    private final ImageView art, play;
    private final TextView source, title, subtitle, remaining;
    /** The song's progress as a line on the card's bottom edge, its fill scaled from the left; with the time left
     *  above its right end. Both gone when the app gives no length. */
    private final View track, fill;
    private final Runnable tick = this::showProgress;
    private static final ExecutorService io = Executors.newSingleThreadExecutor();   // shared: the clock makes a NowPlaying each time
    /** The last art link downloaded, and its picture (null while loading or when it failed). */
    private String artUri;
    private Bitmap artLoaded;
    private MediaController current;
    private boolean started;
    private Runnable onChange;

    private final MediaSessionManager.OnActiveSessionsChangedListener changed = this::pick;
    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState s) {
            // Stopped: another app may be playing now. Either way this session stays followed, so its next song
            // (many apps report stopped between two songs) brings the card straight back.
            if (!showable(s == null ? PlaybackState.STATE_NONE : s.getState())) refresh();
            else render();
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            render();
        }

        @Override
        public void onSessionDestroyed() {
            refresh();
        }
    };

    /** The card's fixed width on Home and on the clock: the same size whatever the song is called. */
    private static final int HOME_WIDTH_DP = 500, CLOCK_WIDTH_DP = 400, ART_DP = 136;   // also the card's height: the cover is square

    /**
     * Home: root is its FrameLayout, topButtons the pills, hero the dock app's name (hidden while the card has
     * focus), dockFocused true while a dock tile has focus. The idle clock: root its FrameLayout, the rest null.
     */
    NowPlaying(Activity activity, ViewGroup root, View topButtons, View hero, BooleanSupplier dockFocused,
               Runnable backToDock, float density) {
        this.activity = activity;
        this.root = root;
        this.topButtons = topButtons;
        this.dockFocused = dockFocused;
        this.backToDock = backToDock;
        this.density = density;
        boolean home = topButtons != null;
        sessions = activity.getSystemService(MediaSessionManager.class);
        listener = new ComponentName(activity, GuardRevival.class);

        // The cover fills the card's left end, edge to edge. Right of it the song, under that the buttons and the
        // time left, and under those the progress line on the bottom edge, from the cover's end to the corner.
        card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setBackgroundResource(R.drawable.now_playing_bg);
        card.setForeground(activity.getDrawable(R.drawable.now_playing_ring));   // the ring goes over the cover too
        card.setClipToOutline(true);   // the cover and the line follow the rounded corners
        card.setVisibility(View.GONE);
        if (home) {
            card.setFocusable(true);
            card.setClickable(true);
            card.setOnClickListener(v -> open());
        }

        art = new ImageView(activity);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setBackgroundColor(0xFF26262B);
        card.addView(art, new LinearLayout.LayoutParams(dp(ART_DP), ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout side = new LinearLayout(activity);
        side.setOrientation(LinearLayout.VERTICAL);
        card.addView(side, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        LinearLayout text = new LinearLayout(activity);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(dp(16), dp(10), dp(16), dp(8));
        source = line(11, 0x99FFFFFF);   // the app it plays in, small, top right; the song starts a line lower
        source.setGravity(Gravity.END);
        title = line(19, Color.WHITE);
        subtitle = line(14, 0xB3FFFFFF);
        text.addView(source);
        text.addView(title);
        text.addView(subtitle);
        text.addView(new View(activity), new LinearLayout.LayoutParams(0, 0, 1f));   // the buttons go to the bottom
        side.addView(text, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // The buttons in the middle, the time left at the right end.
        FrameLayout bottom = new FrameLayout(activity);
        text.addView(bottom, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        controls = new LinearLayout(activity);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        button(android.R.drawable.ic_media_previous, () -> current.getTransportControls().skipToPrevious());
        play = button(android.R.drawable.ic_media_play, this::toggle);
        button(android.R.drawable.ic_media_next, () -> current.getTransportControls().skipToNext());
        bottom.addView(controls, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        remaining = new TextView(activity);
        remaining.setTextSize(13);
        remaining.setTextColor(0xB3FFFFFF);
        remaining.setFontFeatureSettings("tnum");   // digits keep their width as the seconds count down
        bottom.addView(remaining, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.CENTER_VERTICAL));

        line = new FrameLayout(activity);
        side.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));
        track = edgeLine(0xFF4A4A4F);
        fill = edgeLine(Color.WHITE);
        fill.setPivotX(0);
        // The focus ring is drawn over the children: lift the line onto its inner edge so it still shows.
        card.setOnFocusChangeListener((v, has) -> line.setTranslationY(has ? -dp(3) : 0));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(home ? HOME_WIDTH_DP : CLOCK_WIDTH_DP),
                dp(ART_DP), Gravity.TOP | Gravity.END);
        if (home) {
            lp.setMarginEnd(dp(56));
            // A margin, not setY: the focus finder ignores translations and would see the card level with the pills.
            topButtons.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> topButtons.post(this::place));
            // The dock app's name says nothing about the song: away while the card or its buttons have focus. Only
            // what this hid is put back (it is gone for good with dock titles off, and Continue Watching hides it too).
            root.getViewTreeObserver().addOnGlobalFocusChangeListener((was, now) -> {
                boolean onCard = now != null && (now == card || isIn(now, card));
                if (onCard && hero.getVisibility() == View.VISIBLE) {
                    hero.setVisibility(View.INVISIBLE);
                    hidHero = true;
                } else if (!onCard && hidHero) {
                    hero.setVisibility(View.VISIBLE);
                    hidHero = false;
                }
            });
        }
        root.addView(card, lp);
    }

    private boolean hidHero;

    private static boolean isIn(View v, View group) {
        for (Object p = v.getParent(); p instanceof View; p = ((View) p).getParent()) {
            if (p == group) return true;
        }
        return false;
    }

    private View edgeLine(int color) {
        View v = new View(activity);
        v.setBackgroundColor(color);
        line.addView(v, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return v;
    }

    private void showLine(boolean on) {
        line.setVisibility(on ? View.VISIBLE : View.GONE);
        remaining.setVisibility(on ? View.VISIBLE : View.INVISIBLE);   // the buttons keep their place
    }

    /** Where the song is now and the time left; again in a second while it plays and the card shows. */
    private void showProgress() {
        main.removeCallbacks(tick);
        if (current == null || !showing()) return;
        MediaMetadata m = current.getMetadata();
        PlaybackState s = current.getPlaybackState();
        long length = m == null ? 0 : m.getLong(MediaMetadata.METADATA_KEY_DURATION);
        if (length <= 0 || s == null || s.getPosition() < 0) {
            showLine(false);
            return;
        }
        long at = s.getPosition();
        boolean playing = s.getState() == PlaybackState.STATE_PLAYING;
        if (playing) at += (long) ((SystemClock.elapsedRealtime() - s.getLastPositionUpdateTime()) * s.getPlaybackSpeed());
        at = Math.max(0, Math.min(length, at));
        fill.setScaleX((float) at / length);
        remaining.setText(timeLeft(length - at));
        showLine(true);
        if (playing) main.postDelayed(tick, 1000);
    }

    /** "-2:05", or "-1:02:05" past an hour. */
    static String timeLeft(long ms) {
        long sec = (ms + 999) / 1000, h = sec / 3600, m = sec / 60 % 60, ss = sec % 60;
        return h > 0 ? String.format(java.util.Locale.ROOT, "-%d:%02d:%02d", h, m, ss)
                : String.format(java.util.Locale.ROOT, "-%d:%02d", m, ss);
    }

    /** The card, for the clock to place. */
    View view() {
        return card;
    }

    /** Runs after every update, shown or hidden (the clock makes room for the card). */
    void setOnChange(Runnable onChange) {
        this.onChange = onChange;
    }

    boolean showing() {
        return card.getVisibility() == View.VISIBLE;
    }

    private ImageView button(int icon, Runnable action) {
        ImageView b = new ImageView(activity);
        b.setImageResource(icon);
        b.setBackgroundResource(R.drawable.now_playing_button);
        b.setPadding(dp(8), dp(8), dp(8), dp(8));
        b.setFocusable(true);
        b.setClickable(true);
        b.setOnClickListener(v -> {
            if (current != null) action.run();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(40), dp(40));
        lp.setMarginStart(dp(5));   // even on both sides, so the three sit in the true middle
        lp.setMarginEnd(dp(5));
        controls.addView(b, lp);
        return b;
    }

    private TextView line(int sp, int color) {
        TextView t = new TextView(activity);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        return t;
    }

    private int dp(int value) {
        return Math.round(value * density);
    }

    /** Home is in front: follow the sessions. Without the listener approval this quietly does nothing. */
    void start() {
        if (started || sessions == null) return;
        try {
            sessions.addOnActiveSessionsChangedListener(changed, listener, main);
            started = true;
            refresh();
        } catch (SecurityException e) {
            Log.i(TAG, "listener not allowed: cmd notification allow_listener " + listener.flattenToShortString());
        }
    }

    /** Home is going away. */
    void stop() {
        if (!started) return;
        started = false;
        sessions.removeOnActiveSessionsChangedListener(changed);
        main.removeCallbacks(tick);
        follow(null);
    }

    private void refresh() {
        try {
            pick(sessions.getActiveSessions(listener));
        } catch (SecurityException e) {
            pick(null);
        }
    }

    /**
     * The first playing session, else the first paused one, else the first at all, which is followed while hidden
     * (between two songs) so its next song shows without waiting for another event. This launcher's own never counts.
     */
    private void pick(List<MediaController> list) {
        MediaController playing = null, paused = null, any = null;
        if (list != null) {
            for (MediaController c : list) {
                if (c.getPackageName().equals(activity.getPackageName())) continue;
                if (any == null) any = c;
                if (c.getMetadata() == null) continue;
                int s = stateOf(c);
                if (s == PlaybackState.STATE_PLAYING || s == PlaybackState.STATE_BUFFERING) {
                    playing = c;
                    break;
                }
                if (paused == null && s == PlaybackState.STATE_PAUSED) paused = c;
            }
        }
        follow(playing != null ? playing : paused != null ? paused : any);
        render();
    }

    /** Shown in every state but these, so skipping and buffering between songs don't make the card blink. */
    private static boolean showable(int state) {
        return state != PlaybackState.STATE_NONE && state != PlaybackState.STATE_STOPPED
                && state != PlaybackState.STATE_ERROR;
    }

    private void follow(MediaController next) {
        if (current != null && (next == null || !current.getSessionToken().equals(next.getSessionToken()))) {
            current.unregisterCallback(callback);
            current = null;
        }
        if (next != null && current == null) {
            current = next;
            current.registerCallback(callback, main);
        }
    }

    private static int stateOf(MediaController c) {
        PlaybackState s = c.getPlaybackState();
        return s == null ? PlaybackState.STATE_NONE : s.getState();
    }

    private void render() {
        MediaMetadata m = current == null ? null : current.getMetadata();
        if (m == null || !showable(stateOf(current)) || activity.isDestroyed()) {
            boolean hadFocus = card.hasFocus();
            card.setVisibility(View.GONE);
            main.removeCallbacks(tick);
            art.setImageDrawable(null);   // the session's bitmap is not ours to keep
            if (hadFocus && backToDock != null) backToDock.run();
            if (onChange != null) onChange.run();
            return;
        }
        String name = first(m, MediaMetadata.METADATA_KEY_DISPLAY_TITLE, MediaMetadata.METADATA_KEY_TITLE);
        String by = first(m, MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM_ARTIST,
                MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, MediaMetadata.METADATA_KEY_ALBUM);
        CharSequence app = appLabel(current.getPackageName());
        title.setText(name != null ? name : app);
        subtitle.setText(by);
        source.setText(app);

        Bitmap picture = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (picture == null) picture = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (picture == null) picture = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        // Amazon Music and others send only a link to the cover: fetch it once per song, the app icon meanwhile.
        String link = picture != null ? null : first(m, MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
                MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI);
        if (link != null && !link.equals(artUri)) load(link);
        if (picture == null && link != null && link.equals(artUri)) picture = artLoaded;
        if (picture != null) art.setImageBitmap(picture);
        else art.setImageDrawable(appIcon(current.getPackageName()));

        boolean playing = stateOf(current) == PlaybackState.STATE_PLAYING;
        play.setImageResource(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);
        if (topButtons != null) place();
        card.setVisibility(View.VISIBLE);
        showProgress();
        if (topButtons == null && !card.hasFocus()) play.requestFocus();   // the clock: the remote is on the buttons
        if (onChange != null) onChange.run();
    }

    /** Just under the pills, wherever the theme put them. */
    private void place() {
        int[] at = new int[2], base = new int[2];
        topButtons.getLocationInWindow(at);
        root.getLocationInWindow(base);
        int top = at[1] - base[1] + topButtons.getHeight() + dp(18);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) card.getLayoutParams();
        if (lp.topMargin == top) return;
        lp.topMargin = top;
        card.setLayoutParams(lp);
    }

    private void load(String link) {
        artUri = link;
        artLoaded = null;
        int size = dp(ART_DP);
        io.execute(() -> {
            Bitmap b = ContinueRow.poster(activity, link, size);
            main.post(() -> {
                if (!link.equals(artUri) || b == null) return;
                artLoaded = b;
                if (card.getVisibility() == View.VISIBLE) render();
            });
        });
    }

    private static String first(MediaMetadata m, String... keys) {
        for (String key : keys) {
            CharSequence v = m.getText(key);
            if (v != null && v.length() > 0) return v.toString();
        }
        return null;
    }

    private CharSequence appLabel(String pkg) {
        PackageManager pm = activity.getPackageManager();
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    private android.graphics.drawable.Drawable appIcon(String pkg) {
        try {
            return activity.getPackageManager().getApplicationIcon(pkg);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private void toggle() {
        if (current == null) return;
        if (stateOf(current) == PlaybackState.STATE_PLAYING) current.getTransportControls().pause();
        else current.getTransportControls().play();
    }

    /** The app's own player screen when the session offers one, else the app. */
    private void open() {
        if (current == null) return;
        PendingIntent screen = current.getSessionActivity();
        try {
            if (screen != null) {
                screen.send();
                return;
            }
        } catch (PendingIntent.CanceledException ignored) {
        }
        PackageManager pm = activity.getPackageManager();
        Intent app = pm.getLeanbackLaunchIntentForPackage(current.getPackageName());
        if (app == null) app = pm.getLaunchIntentForPackage(current.getPackageName());
        if (app != null) activity.startActivity(app.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    /** Focus on the card when it is showing (Up from a Continue Watching row). */
    boolean focusCard() {
        return topButtons != null && showing() && card.requestFocus();
    }

    /**
     * Home's keys for the layers (class comment). Up from a button to the pills is the focus finder's; Left and Right
     * stop at the ends of the buttons and do nothing on the card. True when consumed.
     */
    boolean handleKey(KeyEvent event) {
        if (topButtons == null || !showing()) return false;
        int code = event.getKeyCode();
        boolean up = code == KeyEvent.KEYCODE_DPAD_UP, downKey = code == KeyEvent.KEYCODE_DPAD_DOWN;
        boolean side = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT;
        View target;
        if (downKey && topButtons.hasFocus()) target = play;
        else if (up && dockFocused.getAsBoolean()) target = card;
        else if (card.isFocused()) {
            if (side) return true;
            if (up) target = play;
            else if (downKey) target = null;   // to the dock
            else return false;
        } else if (controls.hasFocus()) {
            if (downKey) target = card;
            else if (side) {
                int at = controls.indexOfChild(controls.findFocus());
                return code == KeyEvent.KEYCODE_DPAD_LEFT ? at == 0 : at == controls.getChildCount() - 1;
            } else return false;
        } else return false;
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (target != null) target.requestFocus();
            else backToDock.run();
        }
        return true;
    }
}
