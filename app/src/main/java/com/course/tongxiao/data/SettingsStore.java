package com.course.tongxiao.data;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

/**
 * SharedPreferences wrapper for app-wide settings (design.md §3.1 tail, §3.2).
 *
 * <p>Contract (口径, kept in sync with design.md):
 * <ul>
 *   <li>default challenge JSON: {@code null} = inherit ChallengeEngine built-in
 *       defaults; per-alarm overrides stay in {@code alarms.challengeOverrideJson};</li>
 *   <li>city: empty string = weather feature off (no Open-Meteo call, no advance);</li>
 *   <li>group master switch: default ON. Turning it OFF is a <b>protocol-layer
 *       gate</b> (PeerLink refuses CMD_MUTE / SET_REQUEST), it does NOT clear
 *       grantedMask — revoking per-member permissions stays a separate explicit
 *       action, so flipping the switch back does not lose authorizations.</li>
 * </ul>
 *
 * <p>Instantiate once from {@code Application} and reuse: the first
 * {@code getSharedPreferences} call parses XML on the calling thread, which must
 * not happen in an Activity {@code onCreate}.
 */
public class SettingsStore {

    private static final String FILE = "tongxiao_settings";

    private static final String KEY_DEFAULT_CHALLENGE = "default_challenge_json";
    private static final String KEY_CITY = "weather_city";
    private static final String KEY_GROUP_MASTER_ON = "group_master_on";

    private final SharedPreferences prefs;

    public SettingsStore(Context appContext) {
        this(appContext.getApplicationContext()
                .getSharedPreferences(FILE, Context.MODE_PRIVATE));
    }

    /** Test seam: lets tests use an isolated prefs file instead of the real one. */
    @VisibleForTesting
    public SettingsStore(SharedPreferences prefs) {
        this.prefs = prefs;
    }

    @Nullable
    public String getDefaultChallengeJson() {
        return prefs.getString(KEY_DEFAULT_CHALLENGE, null);
    }

    public void setDefaultChallengeJson(@Nullable String json) {
        prefs.edit().putString(KEY_DEFAULT_CHALLENGE, json).apply();
    }

    /** City name typed by the user for Open-Meteo; empty = weather feature off. */
    public String getCity() {
        String city = prefs.getString(KEY_CITY, "");
        return city == null ? "" : city;
    }

    public void setCity(String city) {
        prefs.edit().putString(KEY_CITY, city == null ? "" : city.trim()).apply();
    }

    /**
     * Settings-page master switch for dorm co-operation (design.md §3.2).
     * OFF gates remote commands at the protocol layer; it never mutates
     * grantedMask. Written with commit(): this is a privacy switch, and a
     * fail-open window after an immediate kill is not acceptable.
     */
    public boolean isGroupMasterSwitchOn() {
        return prefs.getBoolean(KEY_GROUP_MASTER_ON, true);
    }

    public void setGroupMasterSwitchOn(boolean on) {
        prefs.edit().putBoolean(KEY_GROUP_MASTER_ON, on).commit();
    }
}
