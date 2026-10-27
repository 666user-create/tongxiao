package com.course.tongxiao.group.protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Step 5.1 G2: wire vocabulary — group-code/port validation and message factories. */
@RunWith(AndroidJUnit4.class)
public class GroupProtocolTest {

    @Test
    public void groupCode_mustBeExactlySixDigits() {
        assertTrue(GroupProtocol.isValidGroupCode("123456"));
        assertTrue(GroupProtocol.isValidGroupCode("000000"));

        assertFalse("5 digits", GroupProtocol.isValidGroupCode("12345"));
        assertFalse("7 digits", GroupProtocol.isValidGroupCode("1234567"));
        assertFalse("letters", GroupProtocol.isValidGroupCode("12345a"));
        assertFalse("spaces", GroupProtocol.isValidGroupCode(" 12345"));
        assertFalse("empty", GroupProtocol.isValidGroupCode(""));
        assertFalse("null", GroupProtocol.isValidGroupCode(null));
    }

    @Test
    public void port_mustBeInTcpRange() {
        assertTrue(GroupProtocol.isValidPort(1));
        assertTrue(GroupProtocol.isValidPort(65535));
        assertFalse(GroupProtocol.isValidPort(0));
        assertFalse(GroupProtocol.isValidPort(-1));
        assertFalse(GroupProtocol.isValidPort(65536));
    }

    @Test
    public void deviceId_mustBeNonEmptyAndBounded() {
        assertTrue(GroupProtocol.isValidDeviceId("550e8400-e29b-41d4-a716-446655440000"));
        assertFalse(GroupProtocol.isValidDeviceId(""));
        assertFalse(GroupProtocol.isValidDeviceId("   "));
        assertFalse(GroupProtocol.isValidDeviceId(null));

        StringBuilder tooLong = new StringBuilder();
        for (int i = 0; i <= GroupProtocol.MAX_DEVICE_ID_LENGTH; i++) {
            tooLong.append('a');
        }
        assertFalse(GroupProtocol.isValidDeviceId(tooLong.toString()));
    }

    @Test
    public void cmdAck_carriesOkAndReason() throws JSONException {
        GroupMessage denied = GroupMessage.cmdAck(false, GroupProtocol.REASON_PERMISSION_DENIED);
        JSONObject json = new JSONObject(denied.toJson());

        assertEquals("CMD_ACK", json.getString(GroupMessage.FIELD_TYPE));
        assertFalse(json.getBoolean(GroupMessage.FIELD_OK));
        assertEquals(GroupProtocol.REASON_PERMISSION_DENIED,
                json.getString(GroupMessage.FIELD_REASON));

        GroupMessage accepted = GroupMessage.cmdAck(true, null);
        assertTrue(new JSONObject(accepted.toJson()).getBoolean(GroupMessage.FIELD_OK));
    }

    @Test
    public void setRequest_carriesNestedPayloadAndKind() throws JSONException {
        JSONObject payload = new JSONObject().put("hour", 6).put("minute", 40);
        GroupMessage request = GroupMessage.setRequest(
                "req-1", "ADD_ALARM", payload);

        JSONObject json = new JSONObject(request.toJson());
        assertEquals("req-1", json.getString(GroupMessage.FIELD_REQ_ID));
        assertEquals("ADD_ALARM", json.getString(GroupMessage.FIELD_KIND));
        assertEquals(6, json.getJSONObject(GroupMessage.FIELD_PAYLOAD).getInt("hour"));
        assertNotNull(GroupMessage.fromJson(json));
    }

    @Test
    public void syncPush_carriesAlarmArray() {
        JSONArray alarms = new JSONArray()
                .put(parse("{\"uid\":\"u1\"}"))
                .put(parse("{\"uid\":\"u2\"}"));

        GroupMessage push = GroupMessage.syncPush(alarms);
        GroupMessage reparsed = GroupMessage.fromJson(parse(push.toJson()));

        assertNotNull(reparsed);
        assertEquals(GroupMessage.Type.SYNC_PUSH, reparsed.type());
        assertEquals(2, reparsed.array(GroupMessage.FIELD_ALARMS).length());
    }

    @Test
    public void fromJson_unknownOrMissingType_isNull() {
        assertNull(GroupMessage.fromJson(parse("{}")));
        assertNull(GroupMessage.fromJson(parse("{\"type\":\"NOPE\"}")));

        GroupMessage bye = GroupMessage.fromJson(parse("{\"type\":\"BYE\"}"));
        assertNotNull(bye);
        assertEquals(GroupMessage.Type.BYE, bye.type());
    }

    private static JSONObject parse(String json) {
        try {
            return new JSONObject(json);
        } catch (JSONException e) {
            throw new AssertionError(e);
        }
    }
}
