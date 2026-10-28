package com.course.tongxiao.engine.challenge;

import android.content.Context;
import android.content.res.AssetManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Builds a challenge session (design.md §5.4).
 *
 * <p>Two sources of questions:
 * <ul>
 *   <li><b>arithmetic</b> — generated on the fly, so this part always works
 *       offline and never runs out;</li>
 *   <li><b>bank</b> — {@code assets/quiz-bank.json} (general knowledge / logic /
 *       arithmetic word problems).</li>
 * </ul>
 * Generation is seeded so tests can assert exact output; production passes a
 * fresh random seed.
 */
public class ChallengeEngine {

    private static final String BANK_ASSET = "quiz-bank.json";

    private final List<Question> bank;
    /** User-authored questions (design.md §5.4): always eligible, see buildSession. */
    private final List<Question> custom;

    public ChallengeEngine(@NonNull List<Question> bank) {
        this(bank, java.util.Collections.emptyList());
    }

    public ChallengeEngine(@NonNull List<Question> bank, @NonNull List<Question> custom) {
        this.bank = new ArrayList<>(bank);
        this.custom = new ArrayList<>(custom);
    }

    public static ChallengeEngine fromAssets(Context appContext) {
        return new ChallengeEngine(loadBank(appContext.getAssets()));
    }

    /**
     * Engine with the bundled bank plus the user's own questions.
     *
     * <p>Custom questions are kept in a separate pool because the type filter must not
     * hide them: a user who typed their own question expects to see it, even when only
     * "算术" is enabled in the challenge settings.
     */
    public static ChallengeEngine fromAssets(Context appContext,
                                             @NonNull List<Question> customQuestions) {
        return new ChallengeEngine(loadBank(appContext.getAssets()), customQuestions);
    }

    /** @return the number of bank questions loaded (0 = only generated arithmetic). */
    public int bankSize() {
        return bank.size();
    }

    /** @return how many user-authored questions this engine will use. */
    public int customSize() {
        return custom.size();
    }

    /**
     * Builds exactly {@code config.questionCount} questions.
     *
     * <p>The pool follows {@link ChallengeConfig#questionSource}: with {@code MY_OWN}
     * the user's own questions are used exclusively (falling back to the bundled bank
     * only when they have not written any — a challenge must never become
     * unanswerable), and with {@code BUILT_IN} they are ignored entirely.
     *
     * @param seed deterministic source (tests pass a fixed value)
     */
    @NonNull
    public List<Question> buildSession(@NonNull ChallengeConfig config, long seed) {
        ChallengeConfig safe = config.clamped();
        Random random = new Random(seed);

        // Own questions are shuffled but lead the session, so a user who wrote their
        // own questions actually meets them at 6 a.m.
        List<Question> customPool = new ArrayList<>();
        if (safe.questionSource == ChallengeConfig.QuestionSource.MY_OWN) {
            customPool.addAll(custom);
            Collections.shuffle(customPool, random);
        }

        List<Question> pool = new ArrayList<>();
        for (Question q : bank) {
            if (safe.types.contains(q.type)) {
                pool.add(q);
            }
        }
        Collections.shuffle(pool, random);

        List<Question> session = new ArrayList<>();
        int customUsed = 0;
        for (int i = 0; i < safe.questionCount; i++) {
            if (customUsed < customPool.size()) {
                session.add(customPool.get(customUsed++));
                continue;
            }
            boolean useGenerated = safe.types.contains(Question.Type.ARITHMETIC)
                    && (pool.isEmpty() || (i % 2 == 0 && safe.types.size() == 1));
            if (!useGenerated && i < pool.size()) {
                session.add(pool.get(i));
            } else if (safe.types.contains(Question.Type.ARITHMETIC)) {
                session.add(generateArithmetic(random, 1 + random.nextInt(3)));
            } else if (!pool.isEmpty()) {
                session.add(pool.get(i % pool.size()));
            } else {
                // Only non-arithmetic types requested but bank is empty: fall back.
                session.add(generateArithmetic(random, 1));
            }
        }
        return session;
    }

    /** Offline multiple-choice arithmetic: a op b, four unique options. */
    @NonNull
    public static Question generateArithmetic(@NonNull Random random, int difficulty) {
        int level = Math.max(1, Math.min(3, difficulty));
        int bound = level == 1 ? 9 : level == 2 ? 20 : 50;
        int a = 1 + random.nextInt(bound);
        int b = 1 + random.nextInt(bound);
        int operator = random.nextInt(level == 1 ? 2 : 3); // + - × (× only from level 2)

        int answer;
        String text;
        switch (operator) {
            case 1:
                if (b > a) {
                    int tmp = a;
                    a = b;
                    b = tmp;
                }
                answer = a - b;
                text = a + " − " + b + " = ?";
                break;
            case 2:
                b = 1 + random.nextInt(9); // keep the product reasonable to read at 6 a.m.
                answer = a * b;
                text = a + " × " + b + " = ?";
                break;
            default:
                answer = a + b;
                text = a + " + " + b + " = ?";
                break;
        }

        List<String> options = new ArrayList<>();
        options.add(String.valueOf(answer));
        int guard = 0;
        while (options.size() < 4 && guard++ < 100) {
            int delta = 1 + random.nextInt(6);
            int distractor = random.nextBoolean() ? answer + delta : answer - delta;
            if (distractor < 0 || distractor == answer) {
                continue;
            }
            String candidate = String.valueOf(distractor);
            if (!options.contains(candidate)) {
                options.add(candidate);
            }
        }
        while (options.size() < 4) { // pathological fallback
            options.add(String.valueOf(answer + options.size() * 10));
        }
        Collections.shuffle(options, random);
        return new Question(text, options, options.indexOf(String.valueOf(answer)),
                Question.Type.ARITHMETIC, level);
    }

    // ---- bank loading ----

    private static List<Question> loadBank(AssetManager assets) {
        List<Question> result = new ArrayList<>();
        String json;
        try (InputStream in = assets.open(BANK_ASSET)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            json = new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException missing) {
            return result; // generated arithmetic still works
        }
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                Question parsed = parse(array.getJSONObject(i));
                if (parsed != null) {
                    result.add(parsed);
                }
            }
        } catch (Exception broken) {
            // "宁响勿哑": a broken bank must not break the alarm path.
            return new ArrayList<>();
        }
        return result;
    }

    @Nullable
    private static Question parse(JSONObject item) {
        try {
            String text = item.getString("q");
            JSONArray optionArray = item.getJSONArray("options");
            List<String> options = new ArrayList<>();
            for (int i = 0; i < optionArray.length(); i++) {
                options.add(optionArray.getString(i));
            }
            int answer = item.getInt("answer");
            String typeName = item.optString("type", "GENERAL");
            Question.Type type;
            try {
                type = Question.Type.valueOf(typeName);
            } catch (IllegalArgumentException unknown) {
                type = Question.Type.GENERAL;
            }
            int difficulty = item.optInt("difficulty", 1);
            return new Question(text, options, answer, type, difficulty);
        } catch (Exception malformed) {
            return null;
        }
    }
}
