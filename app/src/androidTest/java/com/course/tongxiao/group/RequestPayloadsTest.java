package com.course.tongxiao.group;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.course.tongxiao.data.entity.PendingRequest;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Step 5.3 G2 (table-driven): receiver-side clamping of untrusted SET_REQUEST
 * payloads (design.md §3.2 hard bounds, §5.4 "接收端强制钳制").
 */
@RunWith(AndroidJUnit4.class)
public class RequestPayloadsTest {

    private static JSONObject json(Object... keyValues) {
        try {
            JSONObject object = new JSONObject();
            for (int i = 0; i + 1 < keyValues.length; i += 2) {
                object.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
            }
            return object;
        } catch (JSONException e) {
            throw new AssertionError(e);
        }
    }

    private static JSONObject challenge(int questions, int delay, int shakes) {
        return json("mode", "QUIZ", "questionCount", questions, "delaySeconds", delay,
                "shakeCount", shakes, "wrongPolicy", "RETRY",
                "types", new org.json.JSONArray().put("ARITHMETIC"));
    }

    @Test
    public void challenge_boundsAreClampedToTheEditorLimits() throws Exception {
        // untrusted in → clamped out
        Object[][] table = {
                {99, 999, 1, 5, 30, 3},
                {0, -5, 99, 1, 0, 10},
                {3, 5, 5, 3, 5, 5},
        };
        for (Object[] row : table) {
            int inQuestions = (Integer) row[0];
            int inDelay = (Integer) row[1];
            int inShakes = (Integer) row[2];
            int expectedQuestions = (Integer) row[3];
            int expectedDelay = (Integer) row[4];
            int expectedShakes = (Integer) row[5];

            JSONObject normalized = RequestPayloads.normalize(PendingRequest.Kind.SET_CHALLENGE,
                    json("alarmUid", "", "challenge", challenge(inQuestions, inDelay, inShakes)));

            assertNotNull("input " + inQuestions + "/" + inDelay + "/" + inShakes, normalized);
            JSONObject clamped = normalized.getJSONObject("challenge");
            assertEquals("question count", expectedQuestions, clamped.getInt("questionCount"));
            assertEquals("delay", expectedDelay, clamped.getInt("delaySeconds"));
            assertEquals("shake count", expectedShakes, clamped.getInt("shakeCount"));
        }
    }

    @Test
    public void challenge_unknownModeFallsBackToTheSafeDefault() throws Exception {
        JSONObject normalized = RequestPayloads.normalize(PendingRequest.Kind.SET_CHALLENGE,
                json("alarmUid", "uid-1", "challenge", json("mode", "GODMODE", "questionCount", 2)));

        assertNotNull(normalized);
        assertEquals("an unknown mode must not enable a surprise challenge",
                "DIRECT", normalized.getJSONObject("challenge").getString("mode"));
        assertEquals("uid-1", normalized.getString("alarmUid"));
    }

    @Test
    public void challenge_withoutChallengeObject_isRejected() {
        assertNull(RequestPayloads.normalize(PendingRequest.Kind.SET_CHALLENGE,
                json("alarmUid", "uid-1")));
    }

    @Test
    public void addAlarm_clampsSnoozeAndTruncatesLabel() throws Exception {
        StringBuilder longLabel = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            longLabel.append('x');
        }

        JSONObject normalized = RequestPayloads.normalize(PendingRequest.Kind.ADD_ALARM,
                json("hour", 6, "minute", 40, "repeatMode", "DAILY",
                        "snoozeMin", 999, "label", longLabel.toString()));

        assertNotNull(normalized);
        assertEquals(6, normalized.getInt("hour"));
        assertEquals(40, normalized.getInt("minute"));
        assertEquals(30, normalized.getInt("snoozeMin"));
        assertEquals(RequestPayloads.MAX_LABEL_LENGTH, normalized.getString("label").length());
        assertEquals("DAILY", normalized.getString("repeatMode"));
    }

    @Test
    public void addAlarm_rejectsAnImpossibleTimeOrUnknownRepeatMode() {
        assertNull("hour 25 cannot be guessed into something sane",
                RequestPayloads.normalize(PendingRequest.Kind.ADD_ALARM,
                        json("hour", 25, "minute", 0, "repeatMode", "DAILY")));
        assertNull("minute -1",
                RequestPayloads.normalize(PendingRequest.Kind.ADD_ALARM,
                        json("hour", 6, "minute", -1, "repeatMode", "DAILY")));
        assertNull("unknown repeat rule would silently change firing days",
                RequestPayloads.normalize(PendingRequest.Kind.ADD_ALARM,
                        json("hour", 6, "minute", 0, "repeatMode", "WEEKLY")));
    }

    @Test
    public void removeAlarm_requiresATargetUid() {
        assertNull(RequestPayloads.normalize(PendingRequest.Kind.REMOVE_ALARM, json()));
        assertNull(RequestPayloads.normalize(PendingRequest.Kind.REMOVE_ALARM,
                json("alarmUid", "   ")));

        JSONObject normalized = RequestPayloads.normalize(PendingRequest.Kind.REMOVE_ALARM,
                json("alarmUid", " uid-9 "));
        assertNotNull(normalized);
        assertEquals("uid-9", normalized.optString("alarmUid"));
    }

    @Test
    public void kindOf_mapsWireValuesAndRejectsUnknownOnes() {
        assertEquals(PendingRequest.Kind.SET_CHALLENGE, RequestPayloads.kindOf("SET_CHALLENGE"));
        assertEquals(PendingRequest.Kind.ADD_ALARM, RequestPayloads.kindOf(" ADD_ALARM "));
        assertEquals(PendingRequest.Kind.REMOVE_ALARM, RequestPayloads.kindOf("REMOVE_ALARM"));

        assertNull(RequestPayloads.kindOf("DELETE_ALL"));
        assertNull(RequestPayloads.kindOf(null));
        assertNull(RequestPayloads.kindOf(""));
    }
}
