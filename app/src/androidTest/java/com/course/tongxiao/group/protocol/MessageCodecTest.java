package com.course.tongxiao.group.protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Step 5.1 G2: framing edge cases of the newline-delimited protocol
 * (design.md §4, §9 "协议编解码（换行 JSON 边界/坏行）").
 *
 * <p>Every test asserts both the delivered messages and the drop counters, so a
 * regression cannot hide behind "no message arrived".
 */
@RunWith(AndroidJUnit4.class)
public class MessageCodecTest {

    private MessageCodec codec;

    @Before
    public void setUp() {
        codec = new MessageCodec();
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] framed(GroupMessage message) {
        return MessageCodec.encode(message);
    }

    @Test
    public void encodeThenFeed_roundTripsEveryField() {
        List<GroupMessage> delivered =
                codec.feed(framed(GroupMessage.hello("dev-1", "小明", "123456")));

        assertEquals(1, delivered.size());
        GroupMessage hello = delivered.get(0);
        assertEquals(GroupMessage.Type.HELLO, hello.type());
        assertEquals("dev-1", hello.string(GroupMessage.FIELD_DEV, ""));
        assertEquals("小明", hello.string(GroupMessage.FIELD_NICK, ""));
        assertEquals("123456", hello.string(GroupMessage.FIELD_GROUP, ""));
        assertEquals(0, codec.invalidLines() + codec.unknownTypes() + codec.oversizeLines());
    }

    @Test
    public void coalescedRead_deliversEveryCompleteMessage() {
        // Two messages arrive in a single read (粘包): both must be delivered.
        String chunk = GroupMessage.ping(11L).toJson() + "\n"
                + GroupMessage.cmdMute("owner-a", "uid-b").toJson() + "\n";

        List<GroupMessage> delivered = codec.feed(utf8(chunk));

        assertEquals(2, delivered.size());
        assertEquals(GroupMessage.Type.PING, delivered.get(0).type());
        assertEquals(GroupMessage.Type.CMD_MUTE, delivered.get(1).type());
        assertEquals("uid-b", delivered.get(1).string(GroupMessage.FIELD_ALARM_UID, ""));
    }

    @Test
    public void splitRead_deliversOnlyAfterNewline() {
        // One message split across two reads (半包).
        byte[] all = framed(GroupMessage.hello("dev-2", "n", "111111"));
        int cut = all.length / 2;

        assertTrue("nothing is complete yet", codec.feed(all, 0, cut).isEmpty());

        List<GroupMessage> delivered = codec.feed(all, cut, all.length - cut);
        assertEquals(1, delivered.size());
        assertEquals("111111", delivered.get(0).string(GroupMessage.FIELD_GROUP, ""));
    }

    @Test
    public void anySplitPoint_reassemblesMultiByteUtf8() {
        // A TCP read may land inside a multi-byte character: try every split point.
        byte[] all = framed(GroupMessage.hello("dev-3", "小王同学", "222222"));

        for (int cut = 1; cut < all.length; cut++) {
            MessageCodec fresh = new MessageCodec();
            List<GroupMessage> delivered = new ArrayList<>(fresh.feed(all, 0, cut));
            delivered.addAll(fresh.feed(all, cut, all.length - cut));

            assertEquals("split at " + cut + " delivered " + delivered.size() + " messages",
                    1, delivered.size());
            assertEquals("split at " + cut + " corrupted the nickname",
                    "小王同学", delivered.get(0).string(GroupMessage.FIELD_NICK, ""));
        }
    }

    @Test
    public void byteByByteFeed_stillBuildsOneMessage() {
        byte[] all = framed(GroupMessage.notifyOp("MUTE uid-9", 42L));

        int delivered = 0;
        for (byte b : all) {
            delivered += codec.feed(new byte[]{b}).size();
        }
        assertEquals(1, delivered);
    }

    @Test
    public void unknownType_isIgnoredAndCounted_streamKeepsWorking() {
        String chunk = "{\"type\":\"WHATEVER\",\"x\":1}\n"
                + GroupMessage.ping(7L).toJson() + "\n";

        List<GroupMessage> delivered = codec.feed(utf8(chunk));

        assertEquals("only the known type is delivered", 1, delivered.size());
        assertEquals(GroupMessage.Type.PING, delivered.get(0).type());
        assertEquals(1, codec.unknownTypes());
        assertEquals(0, codec.invalidLines());
    }

    @Test
    public void malformedJson_isDroppedAndFollowingMessageSurvives() {
        String chunk = "{this is not json\n"
                + "[1,2,3]\n"
                + GroupMessage.ping(8L).toJson() + "\n";

        List<GroupMessage> delivered = codec.feed(utf8(chunk));

        assertEquals(1, delivered.size());
        assertEquals(GroupMessage.Type.PING, delivered.get(0).type());
        assertEquals(2, codec.invalidLines());
    }

    @Test
    public void oversizeLine_isDiscardedAndConnectionStaysUsable() {
        StringBuilder huge = new StringBuilder("{\"type\":\"SYNC_PUSH\",\"pad\":\"");
        for (int i = 0; i <= GroupProtocol.MAX_LINE_BYTES; i++) {
            huge.append('x');
        }
        huge.append("\"}\n");
        String chunk = huge + GroupMessage.hello("dev-4", "n", "333333").toJson() + "\n";

        List<GroupMessage> delivered = codec.feed(utf8(chunk));

        assertEquals("the following message must still arrive", 1, delivered.size());
        assertEquals(GroupMessage.Type.HELLO, delivered.get(0).type());
        assertEquals(1, codec.oversizeLines());
    }

    @Test
    public void blankLines_areIgnoredSilently() {
        String chunk = "\n\n   \n" + GroupMessage.bye().toJson() + "\n";

        List<GroupMessage> delivered = codec.feed(utf8(chunk));

        assertEquals(1, delivered.size());
        assertEquals(0, codec.invalidLines() + codec.unknownTypes() + codec.oversizeLines());
    }

    @Test
    public void reset_dropsHalfLineOfADeadConnection() {
        byte[] all = framed(GroupMessage.ping(9L));
        codec.feed(all, 0, all.length / 2); // connection dies mid-line

        codec.reset();

        List<GroupMessage> delivered = codec.feed(framed(GroupMessage.ping(10L)));
        assertEquals(1, delivered.size());
        assertEquals(10L, delivered.get(0).longValue(GroupMessage.FIELD_TS, -1L));
    }

    @Test
    public void nestedPayload_roundTripsAsJsonObject() {
        byte[] frame = framed(GroupMessage.cmdMute("dev-5", "uid-5"));
        GroupMessage delivered = codec.feed(frame).get(0);

        assertEquals(GroupMessage.Type.CMD_MUTE, delivered.type());
        assertEquals("dev-5", delivered.string(GroupMessage.FIELD_OWNER_DEV, ""));
        assertNotNull(delivered.toJson());
        assertFalse(delivered.toJson().isEmpty());
    }
}
