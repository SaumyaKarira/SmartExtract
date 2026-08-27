package org.example.document;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Externally configurable resilience settings for all Gemini API calls.
 * All values can be overridden via environment variables or application.properties.
 *
 * <pre>
 * app.gemini.resilience.timeout-ms        (env: GEMINI_TIMEOUT_MS)        default: 10000
 * app.gemini.resilience.max-attempts      (env: GEMINI_MAX_ATTEMPTS)       default: 3
 * app.gemini.resilience.backoff-base-ms   (env: GEMINI_BACKOFF_BASE_MS)    default: 1000
 * app.gemini.resilience.backoff-max-ms    (env: GEMINI_BACKOFF_MAX_MS)     default: 8000
 * app.gemini.resilience.retry-after-max-ms(env: GEMINI_RETRY_AFTER_MAX_MS) default: 15000
 * </pre>
 */
@Component
@ConfigurationProperties(prefix = "app.gemini.resilience")
public class GeminiResilienceConfig {

    /** Per-attempt OkHttp callTimeout in ms. The socket is hard-cancelled when this fires. */
    private long timeoutMs = 10_000;

    /** Total number of attempts (1 = no retries). */
    private int maxAttempts = 3;

    /** Base backoff delay in ms for exponential backoff. */
    private long backoffBaseMs = 1_000;

    /** Hard cap on per-attempt backoff delay in ms. */
    private long backoffMaxMs = 8_000;

    /** Maximum ms we will honour from a Retry-After header on 429 responses. */
    private long retryAfterMaxMs = 15_000;

    public long getTimeoutMs()             { return timeoutMs; }
    public void setTimeoutMs(long v)       { this.timeoutMs = v; }

    public int  getMaxAttempts()           { return maxAttempts; }
    public void setMaxAttempts(int v)      { this.maxAttempts = v; }

    public long getBackoffBaseMs()         { return backoffBaseMs; }
    public void setBackoffBaseMs(long v)   { this.backoffBaseMs = v; }

    public long getBackoffMaxMs()          { return backoffMaxMs; }
    public void setBackoffMaxMs(long v)    { this.backoffMaxMs = v; }

    public long getRetryAfterMaxMs()       { return retryAfterMaxMs; }
    public void setRetryAfterMaxMs(long v) { this.retryAfterMaxMs = v; }
}

