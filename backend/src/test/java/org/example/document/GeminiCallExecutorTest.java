package org.example.document;

import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Focused unit tests for {@link GeminiCallExecutor}.
 *
 * Inter-attempt delays are suppressed (sleepSafely no-op) so the suite runs in
 * milliseconds. Sleep values are captured via {@link AtomicInteger} where exact
 * assertions are needed.
 */
class GeminiCallExecutorTest {

    private GeminiResilienceConfig cfg;
    private GeminiCallExecutor executor; // uses no-op sleepSafely

    @BeforeEach
    void setUp() {
        cfg = new GeminiResilienceConfig();
        cfg.setMaxAttempts(3);
        cfg.setTimeoutMs(10_000);
        cfg.setBackoffBaseMs(0);   // avoid real sleeping in most tests
        cfg.setBackoffMaxMs(0);
        cfg.setRetryAfterMaxMs(30_000);
        executor = noSleep(cfg);
    }

    // ── Factories ─────────────────────────────────────────────────────────────

    private static GeminiCallExecutor noSleep(GeminiResilienceConfig c) {
        return new GeminiCallExecutor(c) {
            @Override void sleepSafely(long ms) { /* suppress */ }
        };
    }

    private static GeminiCallExecutor captureSleep(GeminiResilienceConfig c, AtomicInteger total) {
        return new GeminiCallExecutor(c) {
            @Override void sleepSafely(long ms) { total.addAndGet((int) ms); }
        };
    }

    // =========================================================================
    // buildConfig — OkHttp timeout + SDK retry disabled
    // =========================================================================

    @Test
    void buildConfig_setsOkHttpCallTimeoutInMs() {
        cfg.setTimeoutMs(10_000);
        GenerateContentConfig config = executor.buildConfig();

        assertThat(config.httpOptions()).isPresent();
        assertThat(config.httpOptions().get().timeout()).hasValue(10_000);
    }

    @Test
    void buildConfig_disablesSdkRetry() {
        // attempts=1 means the SDK makes exactly one try; our loop owns all retries
        GenerateContentConfig config = executor.buildConfig();

        assertThat(config.httpOptions().get().retryOptions()).isPresent();
        assertThat(config.httpOptions().get().retryOptions().get().attempts()).hasValue(1);
    }

    // =========================================================================
    // Success on first attempt — no retry
    // =========================================================================

    @Test
    void execute_successOnFirstAttempt_noRetry() {
        AtomicInteger calls = new AtomicInteger();
        GenerateContentResponse mockResp = mock(GenerateContentResponse.class);

        GenerateContentResponse result = executor.execute(config -> {
            calls.incrementAndGet();
            return mockResp;
        }, "test");

        assertThat(result).isSameAs(mockResp);
        assertThat(calls.get()).isEqualTo(1);
    }

    // =========================================================================
    // 429 → retry → success
    // =========================================================================

    @Test
    void execute_429typed_retriesThenSucceeds() {
        AtomicInteger calls = new AtomicInteger();
        GenerateContentResponse mockResp = mock(GenerateContentResponse.class);

        GenerateContentResponse result = executor.execute(config -> {
            int n = calls.incrementAndGet();
            if (n == 1) throw new GeminiTransientException("429", new RuntimeException("429"));
            return mockResp;
        }, "test-429");

        assertThat(result).isSameAs(mockResp);
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void execute_429rawException_classifiedAndRetried() {
        AtomicInteger calls = new AtomicInteger();
        GenerateContentResponse mockResp = mock(GenerateContentResponse.class);

        GenerateContentResponse result = executor.execute(config -> {
            int n = calls.incrementAndGet();
            if (n == 1) throw new RuntimeException("HTTP 429 quota exceeded");
            return mockResp;
        }, "test-429-raw");

        assertThat(result).isSameAs(mockResp);
        assertThat(calls.get()).isEqualTo(2);
    }

    // =========================================================================
    // 5xx → retry → success
    // =========================================================================

    @Test
    void execute_503_retriesThenSucceeds() {
        AtomicInteger calls = new AtomicInteger();
        GenerateContentResponse mockResp = mock(GenerateContentResponse.class);

        GenerateContentResponse result = executor.execute(config -> {
            int n = calls.incrementAndGet();
            if (n < 3) throw new RuntimeException("503 service unavailable");
            return mockResp;
        }, "test-503");

        assertThat(result).isSameAs(mockResp);
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    void execute_500_retriesThenSucceeds() {
        AtomicInteger calls = new AtomicInteger();
        GenerateContentResponse mockResp = mock(GenerateContentResponse.class);

        GenerateContentResponse result = executor.execute(config -> {
            int n = calls.incrementAndGet();
            if (n == 1) throw new RuntimeException("500 internal server error");
            return mockResp;
        }, "test-500");

        assertThat(result).isSameAs(mockResp);
        assertThat(calls.get()).isEqualTo(2);
    }

    // =========================================================================
    // Timeout → retry → success
    // =========================================================================

    @Test
    void execute_okHttpCanceled_retriesThenSucceeds() {
        // OkHttp emits "canceled" (exact word) when callTimeout fires
        AtomicInteger calls = new AtomicInteger();
        GenerateContentResponse mockResp = mock(GenerateContentResponse.class);

        GenerateContentResponse result = executor.execute(config -> {
            int n = calls.incrementAndGet();
            if (n == 1) throw new RuntimeException("canceled");
            return mockResp;
        }, "test-timeout");

        assertThat(result).isSameAs(mockResp);
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void execute_timedOutMessage_retriesThenSucceeds() {
        AtomicInteger calls = new AtomicInteger();
        GenerateContentResponse mockResp = mock(GenerateContentResponse.class);

        GenerateContentResponse result = executor.execute(config -> {
            int n = calls.incrementAndGet();
            if (n == 1) throw new RuntimeException("call timed out after 10000ms");
            return mockResp;
        }, "test-timed-out");

        assertThat(result).isSameAs(mockResp);
        assertThat(calls.get()).isEqualTo(2);
    }

    // =========================================================================
    // Network / IO failure → retry → success
    // =========================================================================

    @Test
    void execute_ioException_retriesThenSucceeds() {
        AtomicInteger calls = new AtomicInteger();
        GenerateContentResponse mockResp = mock(GenerateContentResponse.class);

        GenerateContentResponse result = executor.execute(config -> {
            int n = calls.incrementAndGet();
            if (n == 1) throw new IOException("connection reset by peer");
            return mockResp;
        }, "test-io");

        assertThat(result).isSameAs(mockResp);
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void execute_connectionRefused_retriesThenSucceeds() {
        AtomicInteger calls = new AtomicInteger();
        GenerateContentResponse mockResp = mock(GenerateContentResponse.class);

        GenerateContentResponse result = executor.execute(config -> {
            int n = calls.incrementAndGet();
            if (n == 1) throw new RuntimeException("connection refused");
            return mockResp;
        }, "test-conn-refused");

        assertThat(result).isSameAs(mockResp);
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void execute_connectionReset_retriesThenSucceeds() {
        AtomicInteger calls = new AtomicInteger();
        GenerateContentResponse mockResp = mock(GenerateContentResponse.class);

        GenerateContentResponse result = executor.execute(config -> {
            int n = calls.incrementAndGet();
            if (n == 1) throw new RuntimeException("connection reset");
            return mockResp;
        }, "test-conn-reset");

        assertThat(result).isSameAs(mockResp);
        assertThat(calls.get()).isEqualTo(2);
    }

    // =========================================================================
    // Permanent 4xx — no retry
    // =========================================================================

    @Test
    void execute_permanentExceptionDirect_noRetry() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> executor.execute(config -> {
            calls.incrementAndGet();
            throw new GeminiPermanentException("401", new RuntimeException());
        }, "test-perm"))
                .isInstanceOf(GeminiPermanentException.class);

        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void execute_400_noRetry() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> executor.execute(config -> {
            calls.incrementAndGet();
            throw new RuntimeException("400 bad request");
        }, "test-400"))
                .isInstanceOf(GeminiPermanentException.class);

        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void execute_401_noRetry() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> executor.execute(config -> {
            calls.incrementAndGet();
            throw new RuntimeException("401 unauthenticated invalid api key");
        }, "test-401"))
                .isInstanceOf(GeminiPermanentException.class);

        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void execute_403_noRetry() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> executor.execute(config -> {
            calls.incrementAndGet();
            throw new RuntimeException("403 permission denied");
        }, "test-403"))
                .isInstanceOf(GeminiPermanentException.class);

        assertThat(calls.get()).isEqualTo(1);
    }

    // =========================================================================
    // Unknown exception — treated as permanent, never retried
    // =========================================================================

    @Test
    void execute_nullPointerException_permanent_noRetry() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> executor.execute(config -> {
            calls.incrementAndGet();
            throw new NullPointerException("unexpected null");
        }, "test-npe"))
                .isInstanceOf(GeminiPermanentException.class)
                .hasMessageContaining("unknown");

        assertThat(calls.get())
                .as("Unknown exceptions must not be retried")
                .isEqualTo(1);
    }

    @Test
    void execute_illegalStateException_permanent_noRetry() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> executor.execute(config -> {
            calls.incrementAndGet();
            throw new IllegalStateException("SDK bug");
        }, "test-ise"))
                .isInstanceOf(GeminiPermanentException.class);

        assertThat(calls.get()).isEqualTo(1);
    }

    // =========================================================================
    // Retries exhausted — retryable failure returned to caller
    // =========================================================================

    @Test
    void execute_retriesExhausted_throwsTransientWithAttemptCount() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> executor.execute(config -> {
            calls.incrementAndGet();
            throw new RuntimeException("503 service unavailable");
        }, "test-exhausted"))
                .isInstanceOf(GeminiTransientException.class)
                .hasMessageContaining("3 attempt");

        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    void execute_retriesExhausted_causeIsLastTransientException() {
        assertThatThrownBy(() -> executor.execute(config -> {
            throw new RuntimeException("503 service unavailable");
        }, "test-cause"))
                .isInstanceOf(GeminiTransientException.class)
                .cause()
                .isInstanceOf(GeminiTransientException.class);
    }

    // =========================================================================
    // Actual timeout/cancellation — no overlapping Gemini requests
    // =========================================================================

    @Test
    void execute_onTimeout_nextAttemptStartsOnlyAfterPreviousCallReturns() {
        // Verifies that when attempt N fails (simulating OkHttp socket cancel), the
        // executor does NOT start attempt N+1 before attempt N's call has returned.
        // If there were overlap, 'inFlight' would still be true when the next attempt begins.
        AtomicBoolean inFlight = new AtomicBoolean(false);
        AtomicBoolean overlap  = new AtomicBoolean(false);
        AtomicInteger calls    = new AtomicInteger();
        GenerateContentResponse mockResp = mock(GenerateContentResponse.class);

        executor.execute(config -> {
            int n = calls.incrementAndGet();
            if (inFlight.get()) overlap.set(true); // previous call still running?
            inFlight.set(true);
            try {
                if (n == 1) throw new RuntimeException("canceled"); // simulate OkHttp cancel
                return mockResp;
            } finally {
                inFlight.set(false); // call has fully returned
            }
        }, "test-no-overlap");

        assertThat(overlap.get())
                .as("Previous call must fully return before next attempt starts")
                .isFalse();
        assertThat(calls.get()).isEqualTo(2);
    }

    // =========================================================================
    // Exponential backoff + full jitter + maximum delay
    // =========================================================================

    @Test
    void computeBackoff_exponentialGrowthWithCap() {
        cfg.setBackoffBaseMs(1_000);
        cfg.setBackoffMaxMs(8_000);

        // attempt 1: cap = min(8000, 1000 * 2^0) = 1000
        for (int i = 0; i < 100; i++)
            assertThat(executor.computeBackoff(1)).isBetween(0L, 1_000L);

        // attempt 2: cap = min(8000, 1000 * 2^1) = 2000
        for (int i = 0; i < 100; i++)
            assertThat(executor.computeBackoff(2)).isBetween(0L, 2_000L);

        // attempt 3: cap = min(8000, 1000 * 2^2) = 4000
        for (int i = 0; i < 100; i++)
            assertThat(executor.computeBackoff(3)).isBetween(0L, 4_000L);

        // attempt 10: cap hits backoffMaxMs = 8000
        for (int i = 0; i < 100; i++)
            assertThat(executor.computeBackoff(10)).isBetween(0L, 8_000L);
    }

    @Test
    void computeBackoff_fullJitter_valuesVary() {
        cfg.setBackoffBaseMs(1_000);
        cfg.setBackoffMaxMs(8_000);
        long first = executor.computeBackoff(3);
        boolean varied = false;
        for (int i = 0; i < 200; i++) {
            if (executor.computeBackoff(3) != first) { varied = true; break; }
        }
        assertThat(varied).as("Full jitter must produce varying values").isTrue();
    }

    @Test
    void execute_backoffAppliedBetweenAttempts_notAfterLast() {
        // 3 attempts → 2 inter-attempt sleeps (not after attempt 3)
        cfg.setBackoffBaseMs(500);
        cfg.setBackoffMaxMs(2_000);
        AtomicInteger sleptMs = new AtomicInteger();
        GeminiCallExecutor exe = captureSleep(cfg, sleptMs);

        assertThatThrownBy(() -> exe.execute(config -> {
            throw new RuntimeException("503 service unavailable");
        }, "test-backoff-count"))
                .isInstanceOf(GeminiTransientException.class);

        // 2 sleeps, each ∈ [0, 500] for attempt 1, [0, 1000] for attempt 2
        assertThat(sleptMs.get()).isBetween(0, 2 * 2_000);
    }

    // =========================================================================
    // Retry-After — honoured for 429, capped, not used for non-429
    // =========================================================================

    @Test
    void execute_retryAfter_429_honouredExactly() {
        AtomicInteger sleptMs = new AtomicInteger();
        GeminiCallExecutor exe = captureSleep(cfg, sleptMs);
        GenerateContentResponse mockResp = mock(GenerateContentResponse.class);

        AtomicInteger calls = new AtomicInteger();
        exe.execute(config -> {
            int n = calls.incrementAndGet();
            if (n == 1) throw new GeminiTransientException("429", new RuntimeException("429"), 2_000L);
            return mockResp;
        }, "test-retry-after");

        assertThat(sleptMs.get())
                .as("Retry-After of 2000 ms must be honoured exactly")
                .isEqualTo(2_000);
    }

    @Test
    void execute_retryAfter_cappedAtRetryAfterMaxMs() {
        cfg.setRetryAfterMaxMs(5_000);
        AtomicInteger sleptMs = new AtomicInteger();
        GeminiCallExecutor exe = captureSleep(cfg, sleptMs);

        // Server claims 60 s but cap is 5 s; 3 attempts → 2 sleeps each capped at 5000
        assertThatThrownBy(() -> exe.execute(config -> {
            throw new GeminiTransientException("429", new RuntimeException("429"), 60_000L);
        }, "test-retry-after-cap"))
                .isInstanceOf(GeminiTransientException.class);

        assertThat(sleptMs.get())
                .as("Retry-After must be capped at retryAfterMaxMs per sleep")
                .isLessThanOrEqualTo(5_000 * 2); // 2 inter-attempt sleeps
    }

    @Test
    void execute_retryAfter_notUsedForNon429() {
        // Non-429 transient must use backoff, not Retry-After
        cfg.setBackoffBaseMs(100);
        cfg.setBackoffMaxMs(200);
        cfg.setRetryAfterMaxMs(30_000);
        AtomicInteger sleptMs = new AtomicInteger();
        GeminiCallExecutor exe = captureSleep(cfg, sleptMs);
        GenerateContentResponse mockResp = mock(GenerateContentResponse.class);

        AtomicInteger calls = new AtomicInteger();
        exe.execute(config -> {
            int n = calls.incrementAndGet();
            // retryAfterMs=0 signals no Retry-After (not a 429)
            if (n == 1) throw new GeminiTransientException("503", new RuntimeException("503"), 0L);
            return mockResp;
        }, "test-no-retry-after");

        // Must use backoff (≤ 200 ms), not retryAfterMaxMs (30 000 ms)
        assertThat(sleptMs.get())
                .as("Non-429 must use backoff, not Retry-After")
                .isLessThanOrEqualTo(200);
    }

    // =========================================================================
    // classify() — precise keyword matching
    // =========================================================================

    @Test
    void classify_ioException_isTransient() {
        assertThat(GeminiCallExecutor.classify(new IOException("stream closed")))
                .isInstanceOf(GeminiTransientException.class);
    }

    @Test
    void classify_okHttpCanceled_exactWord_isTransient() {
        assertThat(GeminiCallExecutor.classify(new RuntimeException("canceled")))
                .isInstanceOf(GeminiTransientException.class);
    }

    @Test
    void classify_canceledAsSubstring_noFalsePositive() {
        // "precanceled" must NOT match the whole-word "canceled" check → unknown → permanent
        assertThat(GeminiCallExecutor.classify(new RuntimeException("precanceled")))
                .isInstanceOf(GeminiPermanentException.class);
    }

    @Test
    void classify_timeout_isTransient() {
        assertThat(GeminiCallExecutor.classify(new RuntimeException("read timeout")))
                .isInstanceOf(GeminiTransientException.class);
    }

    @Test
    void classify_connectionRefused_isTransient() {
        assertThat(GeminiCallExecutor.classify(new RuntimeException("connection refused")))
                .isInstanceOf(GeminiTransientException.class);
    }

    @Test
    void classify_connectionReset_isTransient() {
        assertThat(GeminiCallExecutor.classify(new RuntimeException("connection reset by peer")))
                .isInstanceOf(GeminiTransientException.class);
    }

    @Test
    void classify_503_isTransient() {
        assertThat(GeminiCallExecutor.classify(new RuntimeException("503 service unavailable")))
                .isInstanceOf(GeminiTransientException.class);
    }

    @Test
    void classify_429_isTransient() {
        assertThat(GeminiCallExecutor.classify(new RuntimeException("429 rate limit exceeded")))
                .isInstanceOf(GeminiTransientException.class);
    }

    @Test
    void classify_quotaExceeded_isTransient() {
        assertThat(GeminiCallExecutor.classify(new RuntimeException("quota exceeded")))
                .isInstanceOf(GeminiTransientException.class);
    }

    @Test
    void classify_401_isPermanent() {
        assertThat(GeminiCallExecutor.classify(new RuntimeException("401 unauthenticated")))
                .isInstanceOf(GeminiPermanentException.class);
    }

    @Test
    void classify_403_isPermanent() {
        assertThat(GeminiCallExecutor.classify(new RuntimeException("403 permission denied")))
                .isInstanceOf(GeminiPermanentException.class);
    }

    @Test
    void classify_400_isPermanent() {
        assertThat(GeminiCallExecutor.classify(new RuntimeException("400 bad request")))
                .isInstanceOf(GeminiPermanentException.class);
    }

    @Test
    void classify_invalidApiKey_isPermanent() {
        assertThat(GeminiCallExecutor.classify(new RuntimeException("invalid api key")))
                .isInstanceOf(GeminiPermanentException.class);
    }

    @Test
    void classify_unknownException_isPermanent_notTransient() {
        assertThat(GeminiCallExecutor.classify(new NullPointerException("oops")))
                .isInstanceOf(GeminiPermanentException.class)
                .hasMessageContaining("unknown");
    }

    // =========================================================================
    // containsStatusCode — no false positives from adjacent digits
    // =========================================================================

    @Test
    void containsStatusCode_noFalsePositive_5001DoesNotMatch500() {
        assertThat(GeminiCallExecutor.containsStatusCode("5001 items", "500")).isFalse();
    }

    @Test
    void containsStatusCode_noFalsePositive_1400DoesNotMatch400() {
        assertThat(GeminiCallExecutor.containsStatusCode("1400 records", "400")).isFalse();
    }

    @Test
    void containsStatusCode_matchesBareCode() {
        assertThat(GeminiCallExecutor.containsStatusCode("HTTP/1.1 503 Service Unavailable", "503")).isTrue();
        assertThat(GeminiCallExecutor.containsStatusCode("status=429", "429")).isTrue();
    }

    // =========================================================================
    // wholeWord — no false positives from embedded words
    // =========================================================================

    @Test
    void wholeWord_noFalsePositive_prefix() {
        assertThat(GeminiCallExecutor.wholeWord("precanceled", "canceled")).isFalse();
    }

    @Test
    void wholeWord_noFalsePositive_suffix() {
        assertThat(GeminiCallExecutor.wholeWord("canceledOrder", "canceled")).isFalse();
    }

    @Test
    void wholeWord_matchesIsolatedAndSpaceSeparated() {
        assertThat(GeminiCallExecutor.wholeWord("request canceled", "canceled")).isTrue();
        assertThat(GeminiCallExecutor.wholeWord("canceled", "canceled")).isTrue();
    }
}

