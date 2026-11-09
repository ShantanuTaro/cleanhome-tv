package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** An open folder: its apps on a dim layer. OK opens an app, holding OK puts it back on Home, Back closes. */
public final class FolderActivity extends Activity {
    private static final int COLUMNS = 4;

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
        String name = getIntent().getStringExtra("name");
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView title = new TextView(this);
        title.setText(name);
        title.setTextColor(Color.WHITE);
        title.setTextSize(24);
        column.addView(title);
        TextView caption = new TextView(this);
        caption.setTextColor(Color.WHITE);
        caption.setTextSize(15);
        caption.setPadding(0, dp(4), 0, dp(10));
        column.addView(caption);
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(COLUMNS);
        column.addView(grid);
        FrameLayout scrim = new FrameLayout(this);
        scrim.setBackgroundColor(0xD9000000);
        scrim.addView(column, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        setContentView(scrim);

        PackageManager pm = getPackageManager();
        List<View> tiles = new ArrayList<>();
        List<String> apps = Folders.load(this).get(name);
        if (apps != null) for (String pkg : apps) {
            View tile = tile(pm, pkg, caption);
            if (tile != null) {
                grid.addView(tile);
                tiles.add(tile);
            }
        }
        if (tiles.isEmpty()) finish();
        else tiles.get(0).requestFocus();
    }

    /** One app's tile; null when the app is no longer installed. */
    private View tile(PackageManager pm, String pkg, TextView caption) {
        CharSequence label;
        ImageView icon = new ImageView(this);
        android.graphics.drawable.Drawable banner;
        try {
            label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
            banner = pm.getApplicationBanner(pkg);
            icon.setImageDrawable(banner != null ? banner : pm.getApplicationIcon(pkg));
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
        FrameLayout tile = new FrameLayout(this);
        tile.setBackgroundResource(R.drawable.cc_tile);
        tile.setFocusable(true);
        tile.setClickable(true);
        // A banner fills the tile, leaving only the focus ring around it; an app without one gets a large icon.
        FrameLayout.LayoutParams art = banner != null
                ? new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                : new FrameLayout.LayoutParams(dp(80), dp(80), Gravity.CENTER);
        if (banner != null) art.setMargins(dp(4), dp(4), dp(4), dp(4));
        icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        tile.addView(icon, art);
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = dp(156);
        lp.height = dp(92);
        lp.setMargins(dp(8), dp(8), dp(8), dp(8));
        tile.setLayoutParams(lp);
        tile.setOnFocusChangeListener((v, focused) -> {
            if (focused) caption.setText(label);
        });
        tile.setOnClickListener(v -> {
            Intent open = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (open == null) open = pm.getLaunchIntentForPackage(pkg);
            try {
                if (open != null) startActivity(open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (ActivityNotFoundException | SecurityException e) {
                Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
            }
            finish();
        });
        tile.setOnLongClickListener(v -> {
            Folders.move(this, pkg, null);
            Toast.makeText(this, label + " is back on Home", Toast.LENGTH_SHORT).show();
            finish();
            return true;
        });
        return tile;
    }
}
