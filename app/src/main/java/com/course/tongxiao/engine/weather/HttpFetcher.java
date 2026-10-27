package com.course.tongxiao.engine.weather;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;

import java.io.IOException;

/**
 * Transport seam for {@link WeatherClient} (plan.md Step 6.2).
 *
 * <p>Kept as a one-method interface on purpose: the parser, the cache policy and
 * the advance arithmetic must be testable without touching the network, so tests
 * inject a fake that returns canned Open-Meteo JSON (or throws). Production uses
 * {@link WeatherClient}'s built-in {@link java.net.HttpURLConnection} fetcher.
 *
 * <p>Implementations must be <b>synchronous</b> and are always called from a
 * worker thread (never the main thread: NetworkOnMainThreadException).
 */
public interface HttpFetcher {

    /**
     * @param url absolute HTTP(S) URL
     * @return the response body, encoded as the server sent it (UTF-8 for
     *         Open-Meteo)
     * @throws IOException on any transport or non-2xx response; the caller
     *                     degrades to "no advance" and never rethrows
     */
    @WorkerThread
    @NonNull
    String get(@NonNull String url) throws IOException;
}
