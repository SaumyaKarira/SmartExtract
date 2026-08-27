package org.example.document;

import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Executes a Gemini API call with:
 *
 * <ul>
 *   <li><b>Hard per-attempt timeout via OkHttp {@code callTimeout}</b> — when the timeout
 *       fires, OkHttp calls {@code call.cancel()} which closes the socket immediately. The
 *       SDK throws an {@link IOException} (or a wrapping RuntimeException). Our retry loop
 *       catches the exception only after the socket is already closed, so the next attempt
 *       always starts on a fresh connection with no overlap.</li>
 *   <li><b>SDK-level retry disabled</b> ({@code HttpRetryOptions.attempts=1}) — our outer
 *       loop owns all retry logic, giving full control over attempt counting, backoff and
 *       logging without double-counting.</li>
 *   <li><b>Up to {@code maxAttempts} total attempts</b> with exponential backoff + full
 *       jitter between attempts.</li>
 *   <li><b>Conservative exception classification</b>: only well-known transient signals
 *       (429, 5xx, timeout, IO) are retried. Unknown exceptions are treated as permanent
 *       to avoid silently retrying logic errors or misconfiguration.</li>
 *   <li><b>Retry-After</b> is honoured only for 429 rate-limit responses, capped at
 *       {@code retryAfterMaxMs}.</li>
 *   <li><b>Safe logging</b>: only attempt number, failure category and timing are logged.
 *       Prompts, document content, API keys and credentials are never logged.</li>
 * </ul>
 */
@Component
public class GeminiCallExecutor {

    private static final Logger log = LoggerFactory.getLogger(GeminiCallExecutor.class);

    private final GeminiResilienceConfig cfg;

    public GeminiCallExecutor(GeminiResilienceConfig cfg) {
        this.cfg = cfg;
    }

    // ── Config ────────────────────────────────────────────────────────────────

    /**
     * Build a {@link GenerateContentConfig} that embeds:
     * <ul>
     *   <li>{@code HttpOptions.timeout} → OkHttp {@code callTimeout} in ms (hard socket cancel)</li>
     *   <li>{@code HttpRetryOptions.attempts=1} → SDK retry disabled; our outer loop retries</li>
     * </ul>
     */
    GenerateContentConfig buildConfig() {
        // Disable SDK-internal retry. Our outer loop owns all retry/backoff logic.
        HttpRetryOptions noSdkRetry = HttpRetryOptions.builder()
                .attempts(1)
                .httpStatusCodes(List.of())
                .build();

        HttpOptions httpOptions = HttpOptions.builder()
                .timeout((int) cfg.getTimeoutMs()) // ms → OkHttp callTimeout
                .retryOptions(noSdkRetry)
                .build();

        return GenerateContentConfig.builder()
                .httpOptions(httpOptions)
                .build();
    }

    // ── Entry point ───────────────────────────────────────────────────────────

    /**
     * Execute {@code call} with per-attempt timeout, retry and backoff.
     *
     * <p>The callable receives the pre-built {@link GenerateContentConfig} so OkHttp
     * enforces the timeout on the actual HTTP socket for every attempt.
     *
     * @param call      idempotent Gemini call; receives the pre-built config
     * @param callLabel short, safe label for log messages — no user data
     * @return the successful response
     * @throws GeminiTransientException if all retries are exhausted due to transient failures
     * @throws GeminiPermanentException if a permanent, non-retryable failure occurs
     */
    public GenerateContentResponse execute(GeminiCall call, String callLabel) {
        GenerateContentConfig config = buildConfig();
        int maxAttempts = Math.max(1, cfg.getMaxAttempts());
        Exception lastTransient = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            log.debug("Gemini [{}]: attempt {}/{} starting", callLabel, attempt, maxAttempts);
            long attemptStart = System.currentTimeMillis();
            try {
                GenerateContentResponse response = call.call(config);
                long durationMs = System.currentTimeMillis() - attemptStart;
                if (attempt == 1) {
                    log.debug("Gemini [{}]: succeeded on first attempt durationMs={}", callLabel, durationMs);
                } else {
                    log.info("Gemini [{}]: succeeded on attempt {}/{} durationMs={}",
                            callLabel, attempt, maxAttempts, durationMs);
                }
                return response;

            } catch (GeminiPermanentException e) {
                long durationMs = System.currentTimeMillis() - attemptStart;
                log.warn("Gemini [{}]: permanent failure on attempt {}/{} durationMs={} category={}",
                        callLabel, attempt, maxAttempts, durationMs, e.getMessage());
                throw e;

            } catch (Exception e) {
                long durationMs = System.currentTimeMillis() - attemptStart;
                RuntimeException classified = classify(e);
                if (classified instanceof GeminiPermanentException p) {
                    log.warn("Gemini [{}]: permanent failure on attempt {}/{} durationMs={} category={}",
                            callLabel, attempt, maxAttempts, durationMs, p.getMessage());
                    throw p;
                }
                log.warn("Gemini [{}]: transient failure on attempt {}/{} durationMs={} category={}",
                        callLabel, attempt, maxAttempts, durationMs, classified.getMessage());
                lastTransient = classified;
            }

            if (attempt < maxAttempts) {
                long sleepMs = computeSleep(lastTransient, attempt);
                log.info("Gemini [{}]: retrying in {}ms (attempt {}/{} failed, {} remaining)",
                        callLabel, sleepMs, attempt, maxAttempts, maxAttempts - attempt);
                sleepSafely(sleepMs);
            }
        }

        log.warn("Gemini [{}]: all {} attempt(s) exhausted — returning retryable failure", callLabel, maxAttempts);
        throw new GeminiTransientException(
                "Gemini [" + callLabel + "] failed after " + maxAttempts +
                " attempt(s) — service appears temporarily unavailable", lastTransient);
    }

    /** Callable that receives the pre-built {@link GenerateContentConfig}. */
    @FunctionalInterface
    public interface GeminiCall {
        GenerateContentResponse call(GenerateContentConfig config) throws Exception;
    }

    // ── Exception classification ──────────────────────────────────────────────

    /**
     * Classify a raw SDK exception as transient or permanent.
     *
     * <p><b>Classification policy</b> (evaluated in order):
     * <ol>
     *   <li>Already typed {@link GeminiPermanentException} / {@link GeminiTransientException}
     *       — returned as-is.</li>
     *   <li>{@link IOException} subtypes — always transient (socket closed, stream reset, etc.).</li>
     *   <li>Permanent 4xx (400, 401, 403, 404) and their well-known descriptions
     *       (bad request, invalid api key, permission denied, unauthenticated, unauthorized).
     *       These indicate misconfiguration or caller errors that retrying will not fix.</li>
     *   <li>Transient 429 — rate-limited. Retry-After parsed from message if present.</li>
     *   <li>Transient 5xx (500, 502, 503, 504) and their descriptions
     *       (internal server error, bad gateway, service unavailable, gateway timeout).</li>
     *   <li>Transient network/timeout — OkHttp {@code "canceled"} (whole-word; the exact
     *       string emitted when callTimeout fires), "timeout", "timed out",
     *       "connection refused", "connection reset", "broken pipe", "no route to host",
     *       "socket closed", "end of stream".</li>
     *   <li><b>Unknown — treated as PERMANENT</b>. Retrying an unknown error (e.g. a
     *       JSON parse failure, an SDK bug, a NullPointerException) wastes quota and
     *       delays the user without any guarantee of success. The document is marked
     *       non-retryable FAILED so the operator can investigate. Add new transient
     *       patterns to branch 5/6 above when discovered.</li>
     * </ol>
     *
     * <p>Never logs the exception message verbatim (may contain response data).
     */
    static RuntimeException classify(Throwable cause) {
        // 1. Already typed
        if (cause instanceof GeminiPermanentException p) return p;
        if (cause instanceof GeminiTransientException t) return t;

        // 2. IOException hierarchy — always transient
        if (cause instanceof IOException) {
            return new GeminiTransientException("transient:io/" + causeType(cause), cause);
        }

        String msg = cause != null && cause.getMessage() != null
                ? cause.getMessage().toLowerCase() : "";

        // 3. Permanent 4xx
        if (containsStatusCode(msg, "400") || containsStatusCode(msg, "401")
                || containsStatusCode(msg, "403") || containsStatusCode(msg, "404")
                || msg.contains("invalid api key") || msg.contains("api key not valid")
                || msg.contains("permission denied") || msg.contains("bad request")
                || msg.contains("unauthenticated") || msg.contains("unauthorized")) {
            return new GeminiPermanentException("permanent:4xx/" + causeType(cause), cause);
        }

        // 4. Transient 429
        if (containsStatusCode(msg, "429") || msg.contains("rate limit")
                || msg.contains("rate_limit") || msg.contains("quota exceeded")
                || msg.contains("resource exhausted")) {
            long retryAfterMs = parseRetryAfterMs(msg);
            return new GeminiTransientException("transient:429/" + causeType(cause), cause, retryAfterMs);
        }

        // 5. Transient 5xx
        if (containsStatusCode(msg, "500") || containsStatusCode(msg, "502")
                || containsStatusCode(msg, "503") || containsStatusCode(msg, "504")
                || msg.contains("internal server error") || msg.contains("service unavailable")
                || msg.contains("bad gateway") || msg.contains("gateway timeout")) {
            return new GeminiTransientException("transient:5xx/" + causeType(cause), cause);
        }

        // 6. Transient network/timeout
        //    "canceled" uses whole-word match — OkHttp emits exactly this word when
        //    callTimeout fires. Substring match would catch unrelated strings.
        if (wholeWord(msg, "canceled") || wholeWord(msg, "cancelled")
                || msg.contains("timeout") || msg.contains("timed out")
                || msg.contains("connection refused") || msg.contains("connection reset")
                || msg.contains("broken pipe") || msg.contains("no route to host")
                || msg.contains("socket closed") || msg.contains("end of stream")
                || msg.contains("network unreachable")) {
            return new GeminiTransientException("transient:network/" + causeType(cause), cause);
        }

        // 7. Unknown — permanent (conservative; see Javadoc above)
        log.warn("Gemini: unrecognised exception type '{}' — classifying as permanent to " +
                "avoid retrying unknown errors; add to transient branches if this is retriable",
                causeType(cause));
        return new GeminiPermanentException("permanent:unknown/" + causeType(cause), cause);
    }

    // ── Backoff ───────────────────────────────────────────────────────────────

    /**
     * Full-jitter exponential backoff:
     * {@code sleep = ThreadLocalRandom(0, min(backoffMax, backoffBase * 2^(attempt-1)))}
     */
    long computeBackoff(int attempt) {
        long cap = Math.min(cfg.getBackoffMaxMs(),
                cfg.getBackoffBaseMs() * (1L << (attempt - 1)));
        return ThreadLocalRandom.current().nextLong(0, Math.max(1, cap));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private long computeSleep(Exception lastException, int attempt) {
        // Retry-After is only honoured for 429 rate-limit responses (retryAfterMs > 0)
        if (lastException instanceof GeminiTransientException t && t.getRetryAfterMs() > 0) {
            long capped = Math.min(t.getRetryAfterMs(), cfg.getRetryAfterMaxMs());
            log.info("Gemini: honouring Retry-After {} ms (capped to {} ms)",
                    t.getRetryAfterMs(), capped);
            return capped;
        }
        return computeBackoff(attempt);
    }

    /**
     * Returns {@code true} if {@code msg} contains {@code code} as an isolated
     * digit token — not preceded or followed by another digit. This prevents
     * "5001" matching "500" or "1400" matching "400".
     */
    static boolean containsStatusCode(String msg, String code) {
        int idx = msg.indexOf(code);
        while (idx >= 0) {
            boolean prevOk = idx == 0 || !Character.isDigit(msg.charAt(idx - 1));
            boolean nextOk = idx + code.length() >= msg.length()
                    || !Character.isDigit(msg.charAt(idx + code.length()));
            if (prevOk && nextOk) return true;
            idx = msg.indexOf(code, idx + 1);
        }
        return false;
    }

    /**
     * Returns {@code true} if {@code word} appears as a whole alphanumeric token
     * in {@code msg} (not immediately preceded or followed by a letter or digit).
     */
    static boolean wholeWord(String msg, String word) {
        int idx = msg.indexOf(word);
        while (idx >= 0) {
            boolean prevOk = idx == 0 || !Character.isLetterOrDigit(msg.charAt(idx - 1));
            boolean nextOk = idx + word.length() >= msg.length()
                    || !Character.isLetterOrDigit(msg.charAt(idx + word.length()));
            if (prevOk && nextOk) return true;
            idx = msg.indexOf(word, idx + 1);
        }
        return false;
    }

    /**
     * Best-effort parse of {@code Retry-After: <seconds>} from an exception message.
     * Only called from the 429 branch, so no risk of false positives.
     * Returns 0 if no value is found.
     */
    static long parseRetryAfterMs(String msg) {
        try {
            int idx = msg.indexOf("retry-after:");
            if (idx >= 0) {
                String tail = msg.substring(idx + "retry-after:".length()).trim();
                String[] parts = tail.split("[^0-9]+", 2);
                if (parts.length > 0 && !parts[0].isEmpty()) {
                    return Long.parseLong(parts[0]) * 1000L;
                }
            }
        } catch (NumberFormatException ignored) {}
        return 0L;
    }

    private static String causeType(Throwable t) {
        return t != null ? t.getClass().getSimpleName() : "null";
    }

    void sleepSafely(long ms) {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

