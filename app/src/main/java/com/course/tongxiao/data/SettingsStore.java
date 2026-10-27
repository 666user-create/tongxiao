package com.course.tongxiao.data;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

/**
 * SharedPreferences wrapper for app-wide settings (design.md §3.1 tail).
 *
 * <p>Only app-global values live here; per-alarm settings stay in Room
 * (alarms table), so the challenge override can be null = "inherit this".
 */
public class SettingsStore {

    private static final String FILE = "tongxiao_settings";

    private static final String KEY_DEFAULT_CHALLENGE = "default_challenge_json";
    private static final String KEY_CITY = "weather_city";
    private static final String KEY_GROUP_MASTER_ON = "group_master_on";

    private final SharedPreferences prefs;

    public SettingsStore(Context appContext) {
        this.prefs = appContext.getApplicationContext()
                .getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /**
     * Global default challenge config JSON; null = use the engine built-in
     * defaults (Step 4.1). Per-alarm overrides live in alarms.challengeOverrideJson.
     */
    @Nullable
    public String getDefaultChallengeJson() {
        return prefs.getString(KEY_DEFAULT_CHALLENGE, null);
    }

    public void setDefaultChallengeJson(@Nullable String json) {
        prefs.edit().putString(KEY_DEFAULT_CHALLENGE, json).apply();
    }

    /** City name typed by the user for Open-Meteo; empty = weather feature off. */
    public String getCity() {
        return prefs.getString(KEY_CITY, "");
    }

    public void setCity(String city) {
        prefs.edit().putString(KEY_CITY, city == null ? "" : city.trim()).apply();
    }

    /** Settings-page total switch for dorm permissions (design.md §3.2). */
    public boolean isGroupMasterSwitchOn() {
        return prefs.getBoolean(KEY_GROUP_MASTER_ON, true);
    }

    public void setGroupMasterSwitchOn(boolean on) {
        prefs.edit().putBoolean(KEY_GROUP_MASTER_ON, on).apply();
    }
}
