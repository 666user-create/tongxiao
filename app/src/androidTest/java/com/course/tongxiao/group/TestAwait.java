package com.course.tongxiao.group;

/**
 * Deadline-based waiting helper for the asynchronous group tests.
 *
 * <p>Sockets, reader threads and heartbeats never finish at a deterministic
 * moment, so tests poll a condition with a generous deadline instead of sleeping
 * a fixed amount (fixed sleeps are either flaky or slow).
 */
public final class TestAwait {

    public interface Condition {
        boolean met() throws Exception;
    }

    private TestAwait() {
    }

    public static void until(String what, long timeoutMs, Condition condition) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        Throwable last = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (condition.met()) {
                    return;
                }
                last = null;
            } catch (Exception e) {
                last = e;
            }
            sleep(20L);
        }
        throw new AssertionError("timed out after " + timeoutMs + "ms waiting for " + what, last);
    }

    /** Short settle window for "must NOT happen" assertions. */
    public static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
