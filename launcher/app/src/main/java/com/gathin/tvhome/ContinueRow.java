package com.gathin.tvhome;

import android.app.Activity;
import android.content.ContentUris;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Continue Watching for one dock app: after focus rests on the tile for 5 seconds, that app's shows from Android's
 * Watch Next list appear above the dock. Moving to another tile takes them away and lets the posters go.
 */
final class ContinueRow {
    private static final long DWELL_MS = 5000;
    private static final int MAX = 4, CARD_DP = 200, POSTER_DP = 112, ROW_DP = 150;
    private static final Uri WATCH_NEXT = Uri.parse("content://android.media.tv/watch_next_program");

    private static final class Item {
        long id;
        String pkg, title, art;
        float progress;
        Bitmap poster;
    }

    private final Activity activity;
    private final FrameLayout root;
    private final View hero, stats, topButton;
    private final float density;
    private final LinearLayout row;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private String pkg;
    private View anchor;
    private int request;
    private final Runnable dwell = this::load;

    ContinueRow(Activity activity, FrameLayout root, View hero, View stats, View topButton, float density) {
        this.topButton = topButton;
        this.activity = activity;
        this.root = root;
        this.hero = hero;
        this.stats = stats;
        this.density = density;
        row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setVisibility(View.GONE);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(ROW_DP),
                Gravity.TOP | Gravity.START);
        lp.setMarginStart(dp(60));
        root.addView(row, lp);
    }

    private int dp(int value) {
        return Math.round(value * density);
    }

    /** A dock tile has focus (a null package: focus is elsewhere). The same tile again changes nothing. */
    void focused(String focusedPkg, View tile) {
        if (focusedPkg != null && focusedPkg.equals(pkg)) {
            anchor = tile;
            return;
        }
        hide();
        pkg = focusedPkg;
        anchor = tile;
        if (pkg != null) main.postDelayed(dwell, DWELL_MS);
    }

    /** Home is going away. */
    void stop() {
        hide();
        pkg = null;
    }

    private void load() {
        String wanted = pkg;
        int id = ++request;
        io.execute(() -> {
            List<Item> items = query(wanted);
            for (Item item : items) item.poster = poster(item.art);
            main.post(() -> {
                if (id == request && wanted.equals(pkg) && !activity.isDestroyed()) show(items);
            });
        });
    }

    private List<Item> query(String wanted) {
        List<Item> out = new ArrayList<>();
        String[] columns = {"_id", "title", "episode_title", "poster_art_uri", "thumbnail_uri",
                "last_playback_position_millis", "duration_millis", "package_name"};
        // The provider refuses a selection from an ordinary app: read the list and pick this app's shows here.
        try (Cursor c = activity.getContentResolver().query(WATCH_NEXT, columns, null, null,
                "last_engagement_time_utc_millis DESC")) {
            while (c != null && c.moveToNext() && out.size() < MAX) {
                if (!wanted.equals(c.getString(7))) continue;
                Item item = new Item();
                item.id = c.getLong(0);
                item.pkg = wanted;
                item.title = c.getString(1) == null ? "" : c.getString(1);
                item.art = c.getString(4) != null && !c.getString(4).isEmpty() ? c.getString(4) : c.getString(3);
                long position = c.getLong(5), duration = c.getLong(6);
                item.progress = duration > 0 ? Math.min(1f, (float) position / duration) : 0f;
                out.add(item);
            }
        } catch (RuntimeException e) {
            Log.w("TvHome.Continue", "Watch Next: " + e);
        }
        return out;
    }

    /** The poster at card size, or null: a card without one still shows its title. */
    private Bitmap poster(String art) {
        if (art == null || art.isEmpty()) return null;
        try {
            Uri uri = Uri.parse(art);
            InputStream in;
            HttpURLConnection http = null;
            if ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) {
                http = (HttpURLConnection) new URL(art).openConnection();
                http.setConnectTimeout(4000);
                http.setReadTimeout(4000);
                in = http.getInputStream();
            } else {
                in = activity.getContentResolver().openInputStream(uri);
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream stream = in) {
                byte[] buffer = new byte[16384];
                int n;
                while ((n = stream.read(buffer)) > 0 && bytes.size() < 8 * 1024 * 1024) bytes.write(buffer, 0, n);
            } finally {
                if (http != null) http.disconnect();
            }
            byte[] data = bytes.toByteArray();
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            o.inSampleSize = Math.max(1, o.outWidth / dp(CARD_DP));
            o.inJustDecodeBounds = false;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o);
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    private void show(List<Item> items) {
        if (items.isEmpty()) return;
        row.removeAllViews();
        for (Item item : items) row.addView(card(item));
        row.setY(stats.getY() - dp(ROW_DP) - dp(6));
        row.setVisibility(View.VISIBLE);
        hero.setVisibility(View.INVISIBLE);
    }

    private View card(Item item) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.continue_card);
        card.setPadding(dp(5), dp(5), dp(5), dp(5));
        card.setFocusable(true);
        card.setClickable(true);
        FrameLayout poster = new FrameLayout(activity);
        poster.setBackgroundColor(0xFF26262B);
        ImageView image = new ImageView(activity);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setImageBitmap(item.poster);
        poster.addView(image, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (item.progress > 0f) {
            View bar = new View(activity);
            bar.setBackgroundColor(Color.WHITE);
            poster.addView(bar, new FrameLayout.LayoutParams(Math.round(dp(CARD_DP) * item.progress), dp(3), Gravity.BOTTOM));
        }
        card.addView(poster, new LinearLayout.LayoutParams(dp(CARD_DP), dp(POSTER_DP)));
        TextView title = new TextView(activity);
        title.setText(item.title);
        title.setTextColor(Color.WHITE);
        title.setTextSize(14);
        title.setSingleLine(true);
        title.setShadowLayer(4, 0, 1, Color.BLACK);
        title.setMaxWidth(dp(CARD_DP));
        title.setPadding(0, dp(4), 0, 0);
        card.addView(title);
        card.setOnClickListener(v -> open(item));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(10));
        card.setLayoutParams(lp);
        return card;
    }

    /** Opens the show, only ever in the app that listed it; failing that, the app itself. */
    private void open(Item item) {
        try (Cursor c = activity.getContentResolver().query(ContentUris.withAppendedId(WATCH_NEXT, item.id),
                new String[]{"intent_uri"}, null, null, null)) {
            if (c != null && c.moveToFirst() && c.getString(0) != null) {
                Intent i = Intent.parseUri(c.getString(0), Intent.URI_INTENT_SCHEME);
                i.setSelector(null);
                i.setPackage(item.pkg);
                if (i.getComponent() != null && !i.getComponent().getPackageName().equals(item.pkg)) i.setComponent(null);
                activity.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            }
        } catch (Exception e) {
            Log.w("TvHome.Continue", "open: " + e);
        }
        Intent app = activity.getPackageManager().getLeanbackLaunchIntentForPackage(item.pkg);
        if (app != null) activity.startActivity(app.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        else Toast.makeText(activity, R.string.not_available, Toast.LENGTH_SHORT).show();
    }

    private void hide() {
        main.removeCallbacks(dwell);
        request++;
        if (row.getVisibility() != View.VISIBLE) return;
        boolean hadFocus = row.hasFocus();
        row.setVisibility(View.GONE);
        row.removeAllViews();   // nothing holds the posters now: they are released with the views
        hero.setVisibility(View.VISIBLE);
        if (hadFocus && anchor != null) anchor.requestFocus();
    }

    /** Up from the dock goes into the row; Down or Back from the row returns to the dock. True when consumed. */
    boolean handleKey(KeyEvent event) {
        if (row.getVisibility() != View.VISIBLE) return false;
        int code = event.getKeyCode();
        boolean inRow = row.hasFocus(), down = event.getAction() == KeyEvent.ACTION_DOWN;
        if (!inRow && code == KeyEvent.KEYCODE_DPAD_UP && anchor != null && anchor.hasFocus()) {
            if (down) row.getChildAt(0).requestFocus();
            return true;
        }
        if (inRow && code == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (down && anchor != null) anchor.requestFocus();
            return true;
        }
        if (inRow && code == KeyEvent.KEYCODE_DPAD_UP) {
            if (down) topButton.requestFocus();   // focus search will not leave this row by itself
            return true;
        }
        if (inRow && code == KeyEvent.KEYCODE_BACK) {
            if (!down) hide();
            return true;
        }
        return false;
    }
}
