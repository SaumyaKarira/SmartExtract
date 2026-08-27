package org.example.document;

/**
 * Thrown when a Gemini API call fails with a permanent, non-retryable error:
 * HTTP 4xx (bad request, authentication, authorization, not found), an
 * unrecognised exception type, or any other failure that will not resolve
 * by retrying with the same inputs.
 *
 * <p>The document is marked non-retryable FAILED. The user must fix the
 * underlying issue (e.g. update the API key, upload a different document)
 * before processing can succeed.
 */
public class GeminiPermanentException extends RuntimeException {

    public GeminiPermanentException(String message, Throwable cause) {
        super(message, cause);
    }
}

