package com.course.tongxiao.engine.challenge;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Challenge settings for one alarm or as the app-wide default (design.md §3.1/§5.4).
 *
 * <p>Two ways to build it: engine defaults ({@link #defaults()}) and JSON
 * (stored on {@code alarms.challengeOverrideJson} or in SettingsStore). JSON is
 * parsed defensively and always {@link #clamped()}: a remote peer may send any
 * garbage, and the limits are sender-independent (design.md §3.2 hard bounds).
 */
public class ChallengeConfig {

    public enum Mode {
        /** Stop immediately, no challenge. */
        DIRECT,
        /** Shake the phone N times. */
        SHAKE,
        /** Answer multiple-choice questions. */
        QUIZ
    }

    public enum WrongPolicy {
        /** Answer again on the same question. */
        RETRY,
        /** Add snooze seconds and switch question. */
        ADD_SNOOZE,
        /** Raise difficulty on the next question. */
        ESCALATE
    }

    /** Where the questions come from (per-alarm choice, design.md §5.4). */
    public enum QuestionSource {
        /** Bundled bank + generated arithmetic. */
        BUILT_IN,
        /** The questions the user wrote themselves (settings → 自定义题目). */
        MY_OWN
    }

    public static final int MIN_QUESTIONS = 1;
    public static final int MAX_QUESTIONS = 5;
    public static final int MAX_DELAY_SECONDS = 30;

    public final Mode mode;
    public final int questionCount;
    public final Set<Question.Type> types;
    public final WrongPolicy wrongPolicy;
    public final int delaySeconds;
    /** Shake count for SHAKE mode (fixed bound, not user visible in v1). */
    public final int shakeCount;
    /** Which question pool this alarm draws from; defaults to the bundled bank. */
    public final QuestionSource questionSource;

    public ChallengeConfig(@NonNull Mode mode, int questionCount,
                           @NonNull Set<Question.Type> types,
                           @NonNull WrongPolicy wrongPolicy, int delaySeconds,
                           int shakeCount) {
        this(mode, questionCount, types, wrongPolicy, delaySeconds, shakeCount,
                QuestionSource.BUILT_IN);
    }

    public ChallengeConfig(@NonNull Mode mode, int questionCount,
                           @NonNull Set<Question.Type> types,
                           @NonNull WrongPolicy wrongPolicy, int delaySeconds,
                           int shakeCount, @NonNull QuestionSource questionSource) {
        this.mode = mode;
        this.questionCount = questionCount;
        this.types = EnumSet.copyOf(types.isEmpty()
                ? EnumSet.of(Question.Type.ARITHMETIC) : types);
        this.wrongPolicy = wrongPolicy;
        this.delaySeconds = delaySeconds;
        this.shakeCount = shakeCount;
        this.questionSource = questionSource;
    }

    /** @return a copy with a different question source (mode/limits preserved). */
    @NonNull
    public ChallengeConfig withQuestionSource(@NonNull QuestionSource source) {
        return new ChallengeConfig(mode, questionCount, types, wrongPolicy, delaySeconds,
                shakeCount, source);
    }

    /** @return a copy with a different wrong-answer penalty (everything else kept). */
    @NonNull
    public ChallengeConfig withWrongPolicy(@NonNull WrongPolicy policy) {
        return new ChallengeConfig(mode, questionCount, types, policy, delaySeconds,
                shakeCount, questionSource);
    }

    /** Config produced by the edit-screen 摇一摇 option (5 shakes). */
    public static ChallengeConfig shakeDefault() {
        return new ChallengeConfig(Mode.SHAKE, 2,
                EnumSet.of(Question.Type.ARITHMETIC), WrongPolicy.RETRY, 0, 5);
    }

    /**
     * Engine defaults: <b>challenge OFF</b> (mode DIRECT).
     *
     * <p>The challenge is an opt-in feature (explicit product decision): a fresh
     * alarm must ring and stop normally, and the user turns the puzzle on per
     * alarm or globally in settings. Enabling it is a choice, never a surprise.
     * When enabled from the UI the count/types come from the edit screen.
     */
    public static ChallengeConfig defaults() {
        return new ChallengeConfig(Mode.DIRECT, 2,
                EnumSet.of(Question.Type.ARITHMETIC, Question.Type.GENERAL),
                WrongPolicy.RETRY, 5, 5);
    }

    /** Config produced by the edit-screen 选择题 option (2 questions). */
    public static ChallengeConfig quizDefault() {
        return new ChallengeConfig(Mode.QUIZ, 2,
                EnumSet.of(Question.Type.ARITHMETIC, Question.Type.GENERAL),
                WrongPolicy.RETRY, 5, 5);
    }

    /**
     * Enforces the hard bounds from design.md §3.2 — used on anything that did not
     * originate on this device (imported JSON, peer SET_REQUEST payloads).
     */
    @NonNull
    public ChallengeConfig clamped() {
        int count = Math.max(MIN_QUESTIONS, Math.min(MAX_QUESTIONS, questionCount));
        int delay = Math.max(0, Math.min(MAX_DELAY_SECONDS, delaySeconds));
        int shakes = Math.max(3, Math.min(10, shakeCount));
        Set<Question.Type> safeTypes = types.isEmpty()
                ? EnumSet.of(Question.Type.ARITHMETIC) : EnumSet.copyOf(types);
        return new ChallengeConfig(mode, count, safeTypes, wrongPolicy, delay, shakes,
                questionSource);
    }

    // ---- JSON ----

    @NonNull
    public String toJson() {
        try {
            JSONObject root = new JSONObject();
            root.put("mode", mode.name());
            root.put("questionCount", questionCount);
            JSONArray typeArray = new JSONArray();
            for (Question.Type t : types) {
                typeArray.put(t.name());
            }
            root.put("types", typeArray);
            root.put("wrongPolicy", wrongPolicy.name());
            root.put("delaySeconds", delaySeconds);
            root.put("shakeCount", shakeCount);
            root.put("questionSource", questionSource.name());
            return root.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    /** Parses JSON, falling back field-by-field to defaults; never throws. */
    @NonNull
    public static ChallengeConfig fromJson(@Nullable String json) {
        ChallengeConfig fallback = defaults();
        if (json == null || json.trim().isEmpty()) {
            return fallback;
        }
        try {
            JSONObject root = new JSONObject(json);
            Mode mode = parseEnum(Mode.class, root.optString("mode"), fallback.mode);
            WrongPolicy policy = parseEnum(WrongPolicy.class,
                    root.optString("wrongPolicy"), fallback.wrongPolicy);
            int count = root.optInt("questionCount", fallback.questionCount);
            int delay = root.optInt("delaySeconds", fallback.delaySeconds);
            int shakes = root.optInt("shakeCount", fallback.shakeCount);
            QuestionSource source = parseEnum(QuestionSource.class,
                    root.optString("questionSource"), fallback.questionSource);

            Set<Question.Type> types = EnumSet.noneOf(Question.Type.class);
            JSONArray array = root.optJSONArray("types");
            if (array != null) {
                for (int i = 0; i < array.length(); i++) {
                    Question.Type parsed = parseEnum(Question.Type.class,
                            String.valueOf(array.opt(i)), null);
                    if (parsed != null) {
                        types.add(parsed);
                    }
                }
            }
            return new ChallengeConfig(mode, count, types, policy, delay, shakes, source)
                    .clamped();
        } catch (Exception e) {
            return fallback;
        }
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw, E fallback) {
        if (raw != null) {
            for (E candidate : type.getEnumConstants()) {
                if (candidate.name().equalsIgnoreCase(raw.trim())) {
                    return candidate;
                }
            }
        }
        return fallback;
    }

    /** Convenience for the list of enabled question types. */
    @NonNull
    public List<Question.Type> typeList() {
        return new ArrayList<>(types);
    }
}
