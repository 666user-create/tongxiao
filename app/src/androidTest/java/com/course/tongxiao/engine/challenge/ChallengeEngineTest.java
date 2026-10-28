package com.course.tongxiao.engine.challenge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Step 4.1 G2: challenge engine + config clamping (design.md §5.4, §3.2).
 *
 * <p>The clamps matter beyond this step: a dorm peer's SET_REQUEST payload is
 * untrusted (design.md §3.2 hard bounds), so every value that reaches the stop
 * screen has already gone through {@link ChallengeConfig#clamped()}.
 */
@RunWith(AndroidJUnit4.class)
public class ChallengeEngineTest {

    private ChallengeEngine engine;

    @Before
    public void setUp() {
        Context ctx = ApplicationProvider.getApplicationContext();
        engine = ChallengeEngine.fromAssets(ctx);
    }

    // ---- bank ----

    @Test
    public void bank_loadsAndIsWellFormed() {
        assertTrue("bundled bank must not be empty", engine.bankSize() >= 20);
        // A malformed row would have been dropped, so every index is valid.
        for (int i = 0; i < engine.bankSize(); i++) {
            assertNotNull(engine.buildSession(ChallengeConfig.defaults(), i).get(0));
        }
    }

    // ---- generated arithmetic ----

    @Test
    public void arithmetic_hasFourUniqueOptionsAndOneCorrectAnswer() {
        Random random = new Random(42);
        for (int i = 0; i < 200; i++) {
            Question q = ChallengeEngine.generateArithmetic(random, 1 + (i % 3));
            assertEquals("four options", 4, q.options.size());
            assertEquals("options unique", 4, new HashSet<>(q.options).size());
            assertTrue("correct index in range",
                    q.correctIndex >= 0 && q.correctIndex < q.options.size());
            assertTrue("question text", q.text.contains("?"));
            assertTrue("difficulty clamped", q.difficulty >= 1 && q.difficulty <= 3);
        }
    }

    @Test
    public void arithmetic_isDeterministicForASeed() {
        Question first = ChallengeEngine.generateArithmetic(new Random(7), 2);
        Question second = ChallengeEngine.generateArithmetic(new Random(7), 2);
        assertEquals(first.text, second.text);
        assertEquals(first.options, second.options);
        assertEquals(first.correctIndex, second.correctIndex);
    }

    // ---- session building ----

    @Test
    public void session_hasExactlyQuestionCountQuestions() {
        ChallengeConfig config = new ChallengeConfig(
                ChallengeConfig.Mode.QUIZ, 3,
                EnumSet.of(Question.Type.GENERAL), ChallengeConfig.WrongPolicy.RETRY, 5, 5);
        assertEquals(3, engine.buildSession(config, 1).size());
    }

    @Test
    public void session_respectsClampedUpperBound() {
        ChallengeConfig config = new ChallengeConfig(
                ChallengeConfig.Mode.QUIZ, 99,
                EnumSet.of(Question.Type.GENERAL), ChallengeConfig.WrongPolicy.RETRY, 5, 5);
        assertEquals("more than 5 questions is not allowed",
                ChallengeConfig.MAX_QUESTIONS, engine.buildSession(config, 1).size());
    }

    @Test
    public void session_typesAreRespected() {
        ChallengeConfig config = new ChallengeConfig(
                ChallengeConfig.Mode.QUIZ, 4,
                EnumSet.of(Question.Type.ARITHMETIC), ChallengeConfig.WrongPolicy.RETRY, 0, 5);
        for (Question q : engine.buildSession(config, 3)) {
            assertEquals(Question.Type.ARITHMETIC, q.type);
        }
    }

    @Test
    public void session_survivesEmptyBank() {
        ChallengeEngine bare = new ChallengeEngine(Collections.emptyList());
        ChallengeConfig config = new ChallengeConfig(
                ChallengeConfig.Mode.QUIZ, 2,
                EnumSet.of(Question.Type.LOGIC), ChallengeConfig.WrongPolicy.RETRY, 0, 5);
        // No LOGIC questions in an empty bank -> generated arithmetic fallback.
        assertEquals(2, bare.buildSession(config, 1).size());
    }

    // ---- config clamping (untrusted input) ----

    @Test
    public void clamp_boundsQuestionCountDelayAndShake() {
        ChallengeConfig hostile = new ChallengeConfig(
                ChallengeConfig.Mode.QUIZ, 999,
                EnumSet.noneOf(Question.Type.class),
                ChallengeConfig.WrongPolicy.ADD_SNOOZE, 9999, 999);
        ChallengeConfig safe = hostile.clamped();

        assertEquals(ChallengeConfig.MAX_QUESTIONS, safe.questionCount);
        assertEquals(ChallengeConfig.MAX_DELAY_SECONDS, safe.delaySeconds);
        assertTrue("empty type set falls back to arithmetic",
                safe.types.contains(Question.Type.ARITHMETIC));
        assertTrue("shake count bounded", safe.shakeCount >= 3 && safe.shakeCount <= 10);
    }

    @Test
    public void json_roundTripPreservesSettings() {
        ChallengeConfig original = new ChallengeConfig(
                ChallengeConfig.Mode.SHAKE, 4,
                EnumSet.of(Question.Type.LOGIC, Question.Type.GENERAL),
                ChallengeConfig.WrongPolicy.ESCALATE, 12, 7);
        ChallengeConfig parsed = ChallengeConfig.fromJson(original.toJson());

        assertEquals(original.mode, parsed.mode);
        assertEquals(original.questionCount, parsed.questionCount);
        assertEquals(original.types, parsed.types);
        assertEquals(original.wrongPolicy, parsed.wrongPolicy);
        assertEquals(original.delaySeconds, parsed.delaySeconds);
        assertEquals(original.shakeCount, parsed.shakeCount);
    }

    @Test
    public void json_garbageFallsBackToDefaultsWithoutThrowing() {
        for (String garbage : new String[]{null, "", "not json", "{}", "[]", "{\"mode\":\"NOPE\"}"}) {
            ChallengeConfig config = ChallengeConfig.fromJson(garbage);
            assertNotNull(config);
            assertTrue("fallback is clamped",
                    config.questionCount >= ChallengeConfig.MIN_QUESTIONS
                            && config.questionCount <= ChallengeConfig.MAX_QUESTIONS);
        }
    }

    @Test
    public void json_unknownTypeNamesAreDropped() {
        ChallengeConfig parsed = ChallengeConfig.fromJson(
                "{\"types\":[\"ARITHMETIC\",\"ALIEN\"],\"questionCount\":2}");
        Set<Question.Type> types = parsed.types;
        assertTrue(types.contains(Question.Type.ARITHMETIC));
        assertEquals("unknown type dropped", 1, types.size());
    }

    @Test
    public void defaults_areSaneForFirstRun() {
        ChallengeConfig d = ChallengeConfig.defaults();
        // Challenge is opt-in (product decision): a fresh alarm stops directly.
        assertEquals(ChallengeConfig.Mode.DIRECT, d.mode);
        assertTrue(d.questionCount >= 1 && d.questionCount <= 5);
        assertFalse(d.types.isEmpty());

        ChallengeConfig quiz = ChallengeConfig.quizDefault();
        assertEquals("the edit-screen switch enables QUIZ", ChallengeConfig.Mode.QUIZ, quiz.mode);
    }

    @Test
    public void question_rejectsInvalidShapes() {
        List<String> single = Collections.singletonList("only");
        boolean threw = false;
        try {
            new Question("q", single, 0, Question.Type.GENERAL, 1);
        } catch (IllegalArgumentException expected) {
            threw = true;
        }
        assertTrue("single-option question rejected", threw);

        threw = false;
        try {
            new Question("q", java.util.Arrays.asList("a", "b"), 5,
                    Question.Type.GENERAL, 1);
        } catch (IllegalArgumentException expected) {
            threw = true;
        }
        assertTrue("out-of-range answer rejected", threw);
    }
    // ---- custom (user-authored) questions ----

    @Test
    public void session_usesCustomQuestionsFirst_evenWhenTypesExcludeThem() {
        List<Question> custom = java.util.Arrays.asList(
                new Question("我的自定义题一", java.util.Arrays.asList("A", "B", "C", "D"), 2,
                        Question.Type.GENERAL, 1),
                new Question("我的自定义题二", java.util.Arrays.asList("A", "B", "C", "D"), 0,
                        Question.Type.GENERAL, 1));
        ChallengeEngine withCustom = new ChallengeEngine(
                java.util.Collections.emptyList(), custom);

        // Only arithmetic is enabled, but the user's own questions must still be asked.
        ChallengeConfig config = new ChallengeConfig(
                ChallengeConfig.Mode.QUIZ, 2,
                EnumSet.of(Question.Type.ARITHMETIC), ChallengeConfig.WrongPolicy.RETRY, 0, 5);

        List<Question> session = withCustom.buildSession(config, 7);

        assertEquals(2, session.size());
        assertTrue("both questions come from the custom pool",
                session.get(0).text.startsWith("我的自定义题"));
        assertTrue(session.get(1).text.startsWith("我的自定义题"));
        assertEquals(2, withCustom.customSize());
    }

    @Test
    public void session_mixesCustomWithBankWhenItNeedsMore() {
        List<Question> custom = java.util.Collections.singletonList(
                new Question("只有一道自定义", java.util.Arrays.asList("A", "B", "C", "D"), 1,
                        Question.Type.GENERAL, 1));
        ChallengeEngine mixed = new ChallengeEngine(
                java.util.Collections.singletonList(new Question("内置题",
                        java.util.Arrays.asList("A", "B", "C", "D"), 0, Question.Type.GENERAL, 1)),
                custom);

        ChallengeConfig config = new ChallengeConfig(
                ChallengeConfig.Mode.QUIZ, 2,
                EnumSet.of(Question.Type.GENERAL), ChallengeConfig.WrongPolicy.RETRY, 0, 5);
        List<Question> session = mixed.buildSession(config, 3);

        assertEquals(2, session.size());
        assertEquals("custom first", "只有一道自定义", session.get(0).text);
        assertFalse("the rest comes from the bank", session.get(1).text.isEmpty());
    }
}
