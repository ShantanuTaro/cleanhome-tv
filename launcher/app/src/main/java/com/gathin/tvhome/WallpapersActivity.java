package com.gathin.tvhome;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Every wallpaper on the TV: OK turns one in or out of the power-on rotation. At least one always stays on. */
public final class WallpapersActivity extends Activity {
    private SharedPreferences prefs;
    private Set<String> off;
    private int total;

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
        prefs = getSharedPreferences("wallpaper_rotation", MODE_PRIVATE);
        off = new HashSet<>(prefs.getStringSet("off", new HashSet<>()));
        List<File> files = Config.load(this).wallpapers;
        total = files.size();

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        column.setPadding(0, dp(30), 0, dp(30));
        TextView title = new TextView(this);
        title.setText(R.string.cc_wallpapers);
        title.setTextColor(Color.WHITE);
        title.setTextSize(24);
        column.addView(title);
        TextView hint = new TextView(this);
        hint.setText(R.string.cc_wallpapers_hint);
        hint.setTextColor(0xB3FFFFFF);
        hint.setTextSize(14);
        hint.setPadding(0, dp(4), 0, dp(14));
        column.addView(hint);
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(3);
        for (File file : files) grid.addView(tile(file));
        column.addView(grid);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(column, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xF2000000);
        root.addView(scroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        setContentView(root);
        if (grid.getChildCount() > 0) grid.getChildAt(0).requestFocus();
    }

    private FrameLayout tile(File file) {
        FrameLayout tile = new FrameLayout(this);
        tile.setBackgroundResource(R.drawable.cc_tile);
        tile.setPadding(dp(5), dp(5), dp(5), dp(5));
        tile.setFocusable(true);
        tile.setClickable(true);
        ImageView thumb = new ImageView(this);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        tile.addView(thumb, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        TextView mark = new TextView(this);
        mark.setText("✓");
        mark.setTextColor(Color.WHITE);
        mark.setTextSize(18);
        mark.setGravity(Gravity.CENTER);
        mark.setBackgroundResource(R.drawable.wallpaper_on);
        FrameLayout.LayoutParams markLp = new FrameLayout.LayoutParams(dp(34), dp(34), Gravity.TOP | Gravity.END);
        markLp.setMargins(0, dp(8), dp(8), 0);
        tile.addView(mark, markLp);
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = dp(270);
        lp.height = dp(152);
        lp.setMargins(dp(8), dp(8), dp(8), dp(8));
        tile.setLayoutParams(lp);
        String name = file.getName();
        paint(thumb, mark, name);
        tile.setOnClickListener(v -> {
            if (!off.contains(name) && off.size() >= total - 1) {
                Toast.makeText(this, R.string.cc_wallpapers_last, Toast.LENGTH_SHORT).show();
                return;
            }
            if (!off.remove(name)) off.add(name);
            prefs.edit().putStringSet("off", new HashSet<>(off)).apply();
            paint(thumb, mark, name);
        });
        new Thread(() -> {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 4;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap small = BitmapFactory.decodeFile(file.getPath(), o);
            runOnUiThread(() -> thumb.setImageBitmap(small));
        }).start();
        return tile;
    }

    private void paint(ImageView thumb, TextView mark, String name) {
        boolean on = !off.contains(name);
        thumb.setAlpha(on ? 1f : 0.3f);
        mark.setVisibility(on ? TextView.VISIBLE : TextView.GONE);
    }
}
