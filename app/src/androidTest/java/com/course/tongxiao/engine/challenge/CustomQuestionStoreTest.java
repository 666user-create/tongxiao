package com.course.tongxiao.engine.challenge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * User-authored questions (design.md §5.4): the store is the single validator, so a
 * malformed row can never reach the challenge screen and break the wake-up.
 */
@RunWith(AndroidJUnit4.class)
public class CustomQuestionStoreTest {

    private SharedPreferences prefs;
    private CustomQuestionStore store;

    @Before
    public void setUp() {
        Context ctx = ApplicationProvider.getApplicationContext();
        prefs = ctx.getSharedPreferences("custom_question_store_test", Context.MODE_PRIVATE);
        prefs.edit().clear().commit();
        store = new CustomQuestionStore(prefs);
    }

    private static List<String> options(String a, String b, String c, String d) {
        return Arrays.asList(a, b, c, d);
    }

    @Test
    public void addThenLoad_roundTripsTheQuestion() {
        assertTrue(store.add("1+1=?", options("1", "2", "3", "4"), 1));

        List<Question> loaded = store.load();
        assertEquals(1, loaded.size());
        Question question = loaded.get(0);
        assertEquals("1+1=?", question.text);
        assertEquals(4, question.options.size());
        assertEquals("2", question.options.get(1));
        assertEquals(1, question.correctIndex);
        assertEquals(Question.Type.GENERAL, question.type);
    }

    @Test
    public void blankTextOrBlankOption_isRejected() {
        assertFalse("blank question", store.add("   ", options("a", "b", "c", "d"), 0));
        assertFalse("one blank option", store.add("q?", options("a", "", "c", "d"), 0));
        assertEquals("nothing was stored", 0, store.size());
    }

    @Test
    public void answerOutsideZeroToThree_isRejected() {
        assertFalse(store.add("q?", options("a", "b", "c", "d"), -1));
        assertFalse(store.add("q?", options("a", "b", "c", "d"), 4));
        assertEquals(0, store.size());
    }

    @Test
    public void wrongOptionCount_isRejected() {
        assertFalse("three options", store.add("q?", Arrays.asList("a", "b", "c"), 0));
        assertFalse("one option", store.add("q?", Arrays.asList("a"), 0));
        assertEquals(0, store.size());
    }

    @Test
    public void overlongTextOrOption_isRejected() {
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < CustomQuestionStore.MAX_TEXT_LENGTH + 1; i++) {
            longText.append('题');
        }
        assertFalse(store.add(longText.toString(), options("a", "b", "c", "d"), 0));

        StringBuilder longOption = new StringBuilder();
        for (int i = 0; i < CustomQuestionStore.MAX_OPTION_LENGTH + 1; i++) {
            longOption.append('答');
        }
        assertFalse(store.add("q?", options("a", longOption.toString(), "c", "d"), 0));
    }

    @Test
    public void valuesAreTrimmedOnWrite() {
        assertTrue(store.add("  题干  ", options(" A ", " B ", " C ", " D "), 2));
        Question question = store.load().get(0);
        assertEquals("题干", question.text);
        assertEquals("A", question.options.get(0));
    }

    @Test
    public void listIsCappedAtFifty() {
        for (int i = 0; i < CustomQuestionStore.MAX_QUESTIONS; i++) {
            assertTrue("question " + i, store.add("第" + i + "题", options("a", "b", "c", "d"), 0));
        }
        assertEquals(CustomQuestionStore.MAX_QUESTIONS, store.size());
        assertFalse("51st rejected", store.add("再来一题", options("a", "b", "c", "d"), 0));
        assertEquals(CustomQuestionStore.MAX_QUESTIONS, store.size());
    }

    @Test
    public void removeAt_deletesOnlyThatRow() {
        store.add("第一题", options("a", "b", "c", "d"), 0);
        store.add("第二题", options("a", "b", "c", "d"), 1);
        store.add("第三题", options("a", "b", "c", "d"), 2);

        store.removeAt(1);

        List<String> remaining = new ArrayList<>();
        for (Question question : store.load()) {
            remaining.add(question.text);
        }
        assertEquals(Arrays.asList("第一题", "第三题"), remaining);
    }

    @Test
    public void removeAt_outOfRange_isANoOp() {
        store.add("唯一一题", options("a", "b", "c", "d"), 0);
        store.removeAt(-1);
        store.removeAt(5);
        assertEquals(1, store.size());
    }

    @Test
    public void corruptPayload_degradesToAnEmptyList() {
        for (String garbage : new String[]{"{", "[]x", "null", "{\"items\":[]}"}) {
            prefs.edit().putString("items", garbage).commit();
            assertTrue("must not throw for: " + garbage, store.load().isEmpty());
        }
    }

    @Test
    public void malformedRow_isSkippedButGoodRowsSurvive() {
        // One valid row, one row with only three options, one with a missing answer.
        prefs.edit().putString("items", "[{\"q\":\"好题\",\"options\":[\"a\",\"b\",\"c\",\"d\"],"
                + "\"answer\":0},{\"q\":\"坏题\",\"options\":[\"a\",\"b\",\"c\"],\"answer\":0},"
                + "{\"q\":\"缺答案\",\"options\":[\"a\",\"b\",\"c\",\"d\"]}]").commit();

        List<Question> loaded = store.load();
        assertEquals("only the valid row survives", 1, loaded.size());
        assertEquals("好题", loaded.get(0).text);
    }

    @Test
    public void clear_removesEverything() {
        store.add("题", options("a", "b", "c", "d"), 0);
        store.clear();
        assertEquals(0, store.size());
    }
}
