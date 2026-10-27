package com.course.tongxiao.group.protocol;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Newline-delimited JSON framer (design.md §4): a TCP read has no message
 * boundaries, so the codec accumulates bytes and emits whole lines.
 *
 * <p><b>Why bytes and not a BufferedReader:</b> a read can split a UTF-8
 * character in half, and 0x0A can never appear inside a multi-byte UTF-8
 * sequence. Splitting on the raw byte first and decoding whole lines afterwards
 * is lossless; reading chars would corrupt split characters.
 *
 * <p><b>Error handling (design.md §4 "错误处理"):</b> malformed JSON is dropped,
 * unknown {@code type}s are ignored, an over-long line is discarded, and in
 * every case the stream resynchronises on the next newline — the peer is never
 * disconnected because of one bad line. Counters are exposed so the caller can
 * log what was dropped without the codec depending on Android logging.
 */
public final class MessageCodec {

    private final int maxLineBytes;
    private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

    /** Inside an over-long line: throw bytes away until the next newline. */
    private boolean discarding;

    private int invalidLines;
    private int oversizeLines;
    private int unknownTypes;

    public MessageCodec() {
        this(GroupProtocol.MAX_LINE_BYTES);
    }

    /** Test seam: lets a test use a tiny cap instead of 8 KB. */
    MessageCodec(int maxLineBytes) {
        if (maxLineBytes <= 0) {
            throw new IllegalArgumentException("maxLineBytes must be positive");
        }
        this.maxLineBytes = maxLineBytes;
    }

    /** Frames one message for the socket writer (JSON + newline). */
    @NonNull
    public static byte[] encode(@NonNull GroupMessage message) {
        return (message.toJson() + GroupProtocol.LINE_SEPARATOR)
                .getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Feeds bytes just read off the socket.
     *
     * @return every message completed by this chunk, in order — zero, one, or
     *         several (coalesced packets), or one completed from a line whose
     *         first half arrived in an earlier call (split packet).
     */
    @NonNull
    public List<GroupMessage> feed(@NonNull byte[] data, int offset, int length) {
        List<GroupMessage> messages = new ArrayList<>(2);
        int end = offset + length;
        for (int i = offset; i < end; i++) {
            byte b = data[i];
            if (b == GroupProtocol.LINE_SEPARATOR) {
                finishLine(messages);
                continue;
            }
            if (discarding) {
                continue;
            }
            if (pending.size() + 1 > maxLineBytes) {
                // Too long to be one of ours: drop what we buffered, skip to the
                // next newline, keep the connection.
                discarding = true;
                oversizeLines++;
                pending.reset();
                continue;
            }
            pending.write(b);
        }
        return messages;
    }

    /** Convenience overload for tests and callers that own a whole array. */
    @NonNull
    public List<GroupMessage> feed(@NonNull byte[] data) {
        return feed(data, 0, data.length);
    }

    /**
     * Drops a partially buffered line. Called when a connection is replaced: the
     * remainder of a half-read message from the dead socket would otherwise be
     * glued onto the first message of the new one.
     */
    public void reset() {
        pending.reset();
        discarding = false;
    }

    /** Malformed JSON lines dropped so far (design.md §4: discard, keep the link). */
    public int invalidLines() {
        return invalidLines;
    }

    /** Over-long lines discarded so far. */
    public int oversizeLines() {
        return oversizeLines;
    }

    /** Lines with an unknown {@code type}, ignored so far. */
    public int unknownTypes() {
        return unknownTypes;
    }

    private void finishLine(List<GroupMessage> messages) {
        if (discarding) {
            discarding = false; // resynchronised on this newline
            pending.reset();
            return;
        }
        if (pending.size() == 0) {
            return; // blank line: harmless keep-alive noise, not an error
        }
        String line = new String(pending.toByteArray(), StandardCharsets.UTF_8);
        pending.reset();
        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        JSONObject root;
        try {
            root = new JSONObject(trimmed);
        } catch (JSONException malformed) {
            invalidLines++;
            return;
        } catch (RuntimeException broken) {
            // org.json can still throw non-JSONException types for pathological
            // input; one bad line must never kill the reader thread.
            invalidLines++;
            return;
        }
        GroupMessage message = GroupMessage.fromJson(root);
        if (message == null) {
            unknownTypes++; // known JSON, unknown type: ignore (design.md §4)
            return;
        }
        messages.add(message);
    }
}
