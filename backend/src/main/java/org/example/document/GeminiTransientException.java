package org.example.document;

/**
 * Thrown when a Gemini API call fails with a transient, retryable error:
 * HTTP 429, 5xx, timeout, or temporary network/IO failure.
 *
 * <p>After all retry attempts are exhausted this exception propagates to
 * {@link DocumentService}, which marks the document as retryable FAILED so
 * the UI can offer the user a Retry button.
 */
public class GeminiTransientException extends RuntimeException {

    /**
     * Retry-After delay in milliseconds extracted from the server response,
     * or {@code 0} if the server did not provide one.
     * Only populated for HTTP 429 rate-limit responses.
     */
    private final long retryAfterMs;

    public GeminiTransientException(String message, Throwable cause) {
        super(message, cause);
        this.retryAfterMs = 0;
    }

    public GeminiTransientException(String message, Throwable cause, long retryAfterMs) {
        super(message, cause);
        this.retryAfterMs = retryAfterMs;
    }

    public long getRetryAfterMs() {
        return retryAfterMs;
    }
}

