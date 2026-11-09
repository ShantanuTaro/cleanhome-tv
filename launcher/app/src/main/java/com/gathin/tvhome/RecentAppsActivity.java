package com.gathin.tvhome;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Recent Apps: the TV apps used most recently, with a live RAM meter. OK opens one. Back closes. */
public final class RecentAppsActivity extends Activity {
    private static final int MAX = 8;
    private static final long WEEK_MS = 7L * 24 * 60 * 60 * 1000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private List<String> shown;
    private TextView ramText;
    private FrameLayout ramTrack;
    private View ramBar;
    private final Runnable meter = new Runnable() {
        @Override
        public void run() {
            ActivityManager.MemoryInfo m = new ActivityManager.MemoryInfo();
            getSystemService(ActivityManager.class).getMemoryInfo(m);
            long used = m.totalMem - m.availMem;
            ramText.setText(String.format(Locale.US, "RAM %.1f GB used of %.0f GB", used / 1073741824.0, m.totalMem / 1073741824.0));
            ramBar.getLayoutParams().width = Math.round(dp(120) * (float) used / m.totalMem);
            ramBar.requestLayout();
            handler.postDelayed(this, 3000);
        }
    };

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));   // locale
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xE6000000);

        TextView title = new TextView(this);
        title.setText(R.string.cc_recents);
        title.setTextColor(Color.WHITE);
        title.setTextSize(24);
        FrameLayout.LayoutParams titleLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.setMargins(dp(60), dp(40), 0, 0);
        root.addView(title, titleLp);

        LinearLayout ram = new LinearLayout(this);
        ram.setGravity(Gravity.CENTER_VERTICAL);
        ramText = new TextView(this);
        ramText.setTextColor(Color.WHITE);
        ramText.setTextSize(15);
        ram.addView(ramText);
        ramTrack = new FrameLayout(this);
        ramTrack.setBackgroundColor(0x40FFFFFF);
        LinearLayout.LayoutParams trackLp = new LinearLayout.LayoutParams(dp(120), dp(5));
        trackLp.setMarginStart(dp(12));
        ram.addView(ramTrack, trackLp);
        ramBar = new View(this);
        ramBar.setBackgroundColor(Color.WHITE);
        ramTrack.addView(ramBar, new FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT));
        FrameLayout.LayoutParams ramLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        ramLp.setMargins(0, dp(48), dp(60), 0);
        root.addView(ram, ramLp);

        LinearLayout cards = new LinearLayout(this);
        cards.setPadding(dp(60), dp(10), dp(60), dp(10));
        HorizontalScrollView scroller = new HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.addView(cards);
        root.addView(scroller, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        setContentView(root);

        PackageManager pm = getPackageManager();
        shown = recent(pm);
        for (String pkg : shown) {
            View card = card(pm, pkg);
            if (card != null) cards.addView(card);
        }
        TextView none = new TextView(this);
        none.setText(R.string.cc_no_recents);
        none.setTextColor(Color.WHITE);
        none.setTextSize(18);
        root.addView(none, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        if (cards.getChildCount() == 0) return;
        none.setVisibility(View.GONE);
        cards.getChildAt(0).requestFocus();

        TextView close = new TextView(this);
        close.setText(R.string.cc_close_all);
        close.setTextColor(getColorStateList(R.color.cc_text));
        close.setTextSize(16);
        close.setBackgroundResource(R.drawable.cc_tile);
        close.setPadding(dp(32), dp(12), dp(32), dp(12));
        close.setFocusable(true);
        close.setOnClickListener(v -> {
            close.setEnabled(false);
            close.setText(R.string.cc_closing);
            closeAll(() -> {
                close.setVisibility(View.GONE);
                cards.removeAllViews();
                none.setVisibility(View.VISIBLE);
            }, () -> {
                close.setEnabled(true);
                close.setText(R.string.cc_close_all);
                Toast.makeText(this, R.string.cc_close_failed, Toast.LENGTH_LONG).show();
            });
        });
        FrameLayout.LayoutParams closeLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        closeLp.bottomMargin = dp(60);
        root.addView(close, closeLp);
    }

    /** Closes everything idle in the background, and starts the list afresh. Something playing is left alone. */
    private void closeAll(Runnable closed, Runnable failed) {
        // kill-all takes what Android already counts as cached; the listed apps cover the one used last, which is not yet.
        StringBuilder command = new StringBuilder("am kill-all");
        for (String pkg : shown) command.append("; am kill ").append(pkg);
        new Thread(() -> {
            try {
                LocalShell.run(command.toString());
                getSharedPreferences("recents", MODE_PRIVATE).edit().putLong("closed_at", System.currentTimeMillis()).apply();
                handler.post(closed);
            } catch (IOException | GeneralSecurityException e) {
                Log.w("TvHome.Recents", "Close All failed", e);
                handler.post(failed);
            }
        }).start();
    }

    /** Most recent first; only apps that open on a TV, so system parts and this launcher never show. */
    private List<String> recent(PackageManager pm) {
        long now = System.currentTimeMillis();
        long closedAt = getSharedPreferences("recents", MODE_PRIVATE).getLong("closed_at", 0);
        List<UsageStats> stats = getSystemService(UsageStatsManager.class)
                .queryUsageStats(UsageStatsManager.INTERVAL_BEST, now - WEEK_MS, now);
        List<UsageStats> used = new ArrayList<>();
        if (stats != null) for (UsageStats s : stats) if (s.getLastTimeUsed() > closedAt) used.add(s);
        used.sort((a, b) -> Long.compare(b.getLastTimeUsed(), a.getLastTimeUsed()));
        List<String> out = new ArrayList<>();
        for (UsageStats s : used) {
            String pkg = s.getPackageName();
            if (out.size() < MAX && !out.contains(pkg) && !pkg.equals(getPackageName())
                    && pm.getLeanbackLaunchIntentForPackage(pkg) != null) out.add(pkg);
        }
        return out;
    }

    private View card(PackageManager pm, String pkg) {
        CharSequence label;
        Drawable banner, icon;
        try {
            label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
            banner = pm.getApplicationBanner(pkg);
            icon = pm.getApplicationIcon(pkg);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(this);
        name.setText(label);
        name.setTextColor(Color.WHITE);
        name.setTextSize(15);
        name.setPadding(dp(4), 0, 0, dp(6));
        column.addView(name);
        FrameLayout tile = new FrameLayout(this);
        tile.setBackgroundResource(R.drawable.cc_tile);
        tile.setFocusable(true);
        tile.setClickable(true);
        tile.setClipToOutline(true);
        ImageView art = new ImageView(this);
        art.setImageDrawable(banner != null ? banner : icon);
        art.setScaleType(banner != null ? ImageView.ScaleType.FIT_CENTER : ImageView.ScaleType.CENTER_INSIDE);
        FrameLayout.LayoutParams artLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        artLp.setMargins(dp(12), dp(12), dp(12), dp(12));
        tile.addView(art, artLp);
        tile.setOnClickListener(v -> {
            try {
                startActivity(pm.getLeanbackLaunchIntentForPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (ActivityNotFoundException | SecurityException e) {
                Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
            }
            finish();
        });
        column.addView(tile, new LinearLayout.LayoutParams(dp(250), dp(141)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(16));
        column.setLayoutParams(lp);
        return column;
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(meter);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(meter);
        super.onPause();
    }
}
