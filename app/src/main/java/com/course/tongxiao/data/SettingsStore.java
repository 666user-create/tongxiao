package com.course.tongxiao.data;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.WorkerThread;

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
 * <p>Construct once from {@code Application} and reuse. The first
 * {@code getSharedPreferences} call starts an asynchronous load; the first read
 * blocks until it finishes, so this must not happen lazily inside an Activity
 * {@code onCreate} (StrictMode disk-read report on the main thread).
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

    public boolean isGroupMasterSwitchOn() {
        return prefs.getBoolean(KEY_GROUP_MASTER_ON, true);
    }

    /**
     * Settings-page master switch for dorm co-operation (design.md §3.2).
     * OFF gates remote commands at the protocol layer; it never mutates
     * grantedMask.
     *
     * <p>Uses {@code commit()} on purpose: this is a privacy switch, and an
     * {@code apply()} write still queued when the process dies would come back
     * ON (fail-open). That costs a synchronous disk write, so the method is
     * {@link WorkerThread} and returns whether the write actually succeeded.
     *
     * @return true when the new value was persisted.
     */
    @SuppressLint("ApplySharedPref") // deliberate: durability beats async apply here
    @WorkerThread
    public boolean setGroupMasterSwitchOn(boolean on) {
        return prefs.edit().putBoolean(KEY_GROUP_MASTER_ON, on).commit();
    }
}
