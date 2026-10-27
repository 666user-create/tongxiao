package com.course.tongxiao.provider;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.course.tongxiao.data.TongxiaoDb;

/**
 * Step 6.5 read-only ContentProvider (design.md §2.1 "ContentProvider 对外共享
 * 闹钟数据（加分项）", §7 ⑤ statistics/sharing corner).
 *
 * <p>{@code content://com.course.tongxiao.alarms/alarms} exposes the {@code alarms}
 * table with its database column names, e.g. for the defence demo:
 * <pre>adb shell content query --uri content://com.course.tongxiao.alarms/alarms</pre>
 *
 * <p><b>Read-only by construction.</b> {@link #query} and {@link #getType} work;
 * {@link #insert}, {@link #delete} and {@link #update} throw
 * {@link UnsupportedOperationException} instead of silently doing nothing, so a
 * client cannot mistake "accepted" for "applied". No alarm data leaves the
 * device — the provider is a local IPC surface, not a sync endpoint.
 *
 * <p>Threading: the framework calls these methods on a binder thread, and Room
 * asserts it is not on the main thread, which matches.
 */
public class AlarmProvider extends ContentProvider {

    /** Must match the {@code <provider android:authorities>} in AndroidManifest.xml. */
    public static final String AUTHORITY = "com.course.tongxiao.alarms";

    public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/alarms");

    public static final String MIME_TYPE_ALARMS =
            "vnd.android.cursor.dir/vnd.com.course.tongxiao.alarm";

    private static final String TABLE_ALARMS = "alarms";
    private static final String PATH_ALARMS = "alarms";

    @Override
    public boolean onCreate() {
        // Do not open the database here: the provider is exported, and the system
        // creates it on app start even when nobody queries it.
        return true;
    }

    @Nullable
    @Override
    public Cursor query(@NonNull Uri uri, @Nullable String[] projection,
                        @Nullable String selection, @Nullable String[] selectionArgs,
                        @Nullable String sortOrder) {
        requireAlarmsUri(uri);
        Context context = getContext();
        if (context == null) {
            throw new IllegalStateException("AlarmProvider is not attached to a context");
        }

        StringBuilder sql = new StringBuilder("SELECT ");
        sql.append(projection == null || projection.length == 0 ? "*" : TextUtils.join(", ", projection));
        sql.append(" FROM ").append(TABLE_ALARMS);
        if (!TextUtils.isEmpty(selection)) {
            sql.append(" WHERE ").append(selection);
        }
        if (!TextUtils.isEmpty(sortOrder)) {
            sql.append(" ORDER BY ").append(sortOrder);
        }
        // selectionArgs are bound, not concatenated: caller-supplied values never
        // become SQL text. projection/selection/sortOrder are raw SQL fragments by
        // ContentProvider API design (same contract Android's own providers use).
        return TongxiaoDb.get(context).query(sql.toString(), selectionArgs);
    }

    @Nullable
    @Override
    public String getType(@NonNull Uri uri) {
        requireAlarmsUri(uri);
        return MIME_TYPE_ALARMS;
    }

    @Nullable
    @Override
    public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
        throw new UnsupportedOperationException(
                "AlarmProvider is read-only: alarms are managed by the Tongxiao app itself");
    }

    @Override
    public int delete(@NonNull Uri uri, @Nullable String selection,
                      @Nullable String[] selectionArgs) {
        throw new UnsupportedOperationException(
                "AlarmProvider is read-only: alarms are managed by the Tongxiao app itself");
    }

    @Override
    public int update(@NonNull Uri uri, @Nullable ContentValues values,
                      @Nullable String selection, @Nullable String[] selectionArgs) {
        throw new UnsupportedOperationException(
                "AlarmProvider is read-only: alarms are managed by the Tongxiao app itself");
    }

    /** Only the alarms collection exists; anything else is a client bug, not a silent null. */
    private static void requireAlarmsUri(Uri uri) {
        if (uri == null
                || !AUTHORITY.equals(uri.getAuthority())
                || uri.getPathSegments().size() != 1
                || !PATH_ALARMS.equals(uri.getPathSegments().get(0))) {
            throw new IllegalArgumentException("unsupported URI: " + uri
                    + " (expected " + CONTENT_URI + ")");
        }
    }
}
