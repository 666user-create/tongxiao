package com.course.tongxiao.engine.challenge;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * User-authored challenge questions (design.md §5.4 "自定义题目").
 *
 * <p>Stored in SharedPreferences rather than Room on purpose: this is a short,
 * replaceable list (≤ {@link #MAX_QUESTIONS} entries) that never needs joins or
 * queries, and adding a table would force a schema migration for data the user can
 * re-enter in a minute.
 *
 * <p>Validation is strict on write and lenient on read: a malformed row written by an
 * older/broken build is skipped instead of taking the whole list (and the wake-up)
 * down with it.
 */
public final class CustomQuestionStore {

    private static final String PREFS = "tongxiao_custom_questions";
    private static final String KEY_ITEMS = "items";

    public static final int MAX_QUESTIONS = 50;
    public static final int MAX_TEXT_LENGTH = 120;
    public static final int MAX_OPTION_LENGTH = 24;
    public static final int OPTION_COUNT = 4;

    private final SharedPreferences prefs;

    public CustomQuestionStore(@NonNull Context appContext) {
        this(appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE));
    }

    public CustomQuestionStore(@NonNull SharedPreferences prefs) {
        this.prefs = prefs;
    }

    /** @return the stored questions, oldest first; never null, never throws. */
    @NonNull
    public List<Question> load() {
        List<Question> result = new ArrayList<>();
        String raw = prefs.getString(KEY_ITEMS, "");
        if (TextUtils.isEmpty(raw)) {
            return result;
        }
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length() && result.size() < MAX_QUESTIONS; i++) {
                Question parsed = parse(array.optJSONObject(i));
                if (parsed != null) {
                    result.add(parsed);
                }
            }
        } catch (Exception broken) {
            // A corrupt list must not break the stop-challenge path.
            return new ArrayList<>();
        }
        return result;
    }

    /**
     * Appends one question.
     *
     * @return false when the input is unusable or the list is full (nothing is saved)
     */
    public boolean add(@NonNull String text, @NonNull List<String> options, int correctIndex) {
        List<Question> current = load();
        if (current.size() >= MAX_QUESTIONS) {
            return false;
        }
        JSONObject row = normalize(text, options, correctIndex);
        if (row == null) {
            return false;
        }
        try {
            JSONArray array = new JSONArray();
            for (Question question : current) {
                array.put(toJson(question));
            }
            array.put(row);
            prefs.edit().putString(KEY_ITEMS, array.toString()).apply();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Deletes by position; out-of-range indexes are a no-op. */
    public void removeAt(int index) {
        List<Question> current = load();
        if (index < 0 || index >= current.size()) {
            return;
        }
        current.remove(index);
        JSONArray array = new JSONArray();
        for (Question question : current) {
            array.put(toJson(question));
        }
        prefs.edit().putString(KEY_ITEMS, array.toString()).apply();
    }

    public void clear() {
        prefs.edit().remove(KEY_ITEMS).apply();
    }

    public int size() {
        return load().size();
    }

    // ---- validation / (de)serialization ----

    /**
     * @return a normalized JSON row, or null when the input cannot be a question
     *         (blank text, blank option, answer outside 0..3)
     */
    @Nullable
    private static JSONObject normalize(@NonNull String text, @NonNull List<String> options,
                                        int correctIndex) {
        if (options.size() != OPTION_COUNT) {
            return null;
        }
        String question = text.trim();
        if (question.isEmpty() || question.length() > MAX_TEXT_LENGTH) {
            return null;
        }
        if (correctIndex < 0 || correctIndex >= OPTION_COUNT) {
            return null;
        }
        JSONArray optionArray = new JSONArray();
        for (String option : options) {
            String value = option == null ? "" : option.trim();
            if (value.isEmpty() || value.length() > MAX_OPTION_LENGTH) {
                return null;
            }
            optionArray.put(value);
        }
        try {
            JSONObject row = new JSONObject();
            row.put("q", question);
            row.put("options", optionArray);
            row.put("answer", correctIndex);
            return row;
        } catch (Exception e) {
            return null;
        }
    }

    @Nullable
    private static Question parse(@Nullable JSONObject row) {
        if (row == null) {
            return null;
        }
        try {
            String text = row.getString("q").trim();
            JSONArray optionArray = row.getJSONArray("options");
            if (text.isEmpty() || optionArray.length() != OPTION_COUNT) {
                return null;
            }
            List<String> options = new ArrayList<>(OPTION_COUNT);
            for (int i = 0; i < optionArray.length(); i++) {
                String option = optionArray.getString(i).trim();
                if (option.isEmpty()) {
                    return null;
                }
                options.add(option);
            }
            int answer = row.getInt("answer");
            return new Question(text, options, answer, Question.Type.GENERAL, 1);
        } catch (Exception malformed) {
            return null;
        }
    }

    @NonNull
    private static JSONObject toJson(@NonNull Question question) {
        JSONObject row = new JSONObject();
        try {
            row.put("q", question.text);
            JSONArray optionArray = new JSONArray();
            for (String option : question.options) {
                optionArray.put(option);
            }
            row.put("options", optionArray);
            row.put("answer", question.correctIndex);
        } catch (Exception ignored) {
            // Values come from a validated Question; this cannot realistically fail.
        }
        return row;
    }
}
