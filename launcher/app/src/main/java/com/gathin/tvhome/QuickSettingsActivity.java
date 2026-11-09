package com.gathin.tvhome;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Control Center: Sleep, Wi-Fi, Bluetooth, Inputs, Settings, Recent Apps, Dark Mode and Launcher settings, opened
 * by Home's Settings button or the remote's Settings button (settings_key_panel). Translucent and in a task of its
 * own, so the app behind keeps running and Back returns to it.
 */
public final class QuickSettingsActivity extends Activity {
    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));   // locale
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_quick_settings);
        findViewById(R.id.scrim).setOnClickListener(v -> finish());
        findViewById(R.id.ccSleep).setOnClickListener(v -> {
            if (!HomeKeyService.sleep()) Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
            finish();
        });
        open(R.id.ccWifi, SystemSettings.intent(this, Settings.ACTION_WIFI_SETTINGS));
        // This TV has no standard Bluetooth settings screen: TCL's Accessories page is where devices are paired.
        open(R.id.ccBluetooth, new Intent().setClassName("com.android.tv.settings",
                "com.tcl.android.tv.settings.accessories.AccessoriesActivity"));
        open(R.id.ccInputs, new Intent(this, MainActivity.class).setAction(MainActivity.ACTION_VIEW_INPUTS));
        open(R.id.ccSettings, SystemSettings.intent(this, Settings.ACTION_SETTINGS));
        // Through Home, so it rebuilds with the new choices when Launcher settings closes.
        open(R.id.ccLauncher, new Intent(this, MainActivity.class).setAction(MainActivity.ACTION_LAUNCHER_SETTINGS));
        open(R.id.ccRecents, new Intent(this, RecentAppsActivity.class));
        open(R.id.ccWallpapers, new Intent(this, WallpapersActivity.class));
        open(R.id.ccRearrange, new Intent(this, MainActivity.class).setAction(MainActivity.ACTION_REARRANGE));
        findViewById(R.id.ccDark).setOnClickListener(v -> {
            DarkMode.toggle(this);
            finish();
        });
        BluetoothAdapter bluetooth = BluetoothAdapter.getDefaultAdapter();
        if (bluetooth != null) {
            ((TextView) findViewById(R.id.ccBluetooth)).setText(getString(R.string.cc_bluetooth)
                    + (bluetooth.isEnabled() ? " · On" : " · Off"));
        }
        findViewById(R.id.ccSettings).requestFocus();
    }

    private void open(int id, Intent target) {
        findViewById(id).setOnClickListener(v -> {
            try {
                startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (ActivityNotFoundException | SecurityException e) {
                Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
            }
            finish();
        });
    }

    /** Settings pressed again while the panel is up. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        finish();
    }

    /** Anything else taking the screen (Home, an app, a screensaver) closes the panel. */
    @Override
    @SuppressWarnings("deprecation")
    protected void onPause() {
        super.onPause();
        finish();
        overridePendingTransition(0, 0);
    }
}
