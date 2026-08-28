package org.example.document;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Cleans up documents that are permanently stuck in PROCESSING status.
 *
 * <p>This can happen when:
 * <ul>
 *   <li>The user refreshes the browser mid-upload — the backend thread may continue
 *       and finish normally, but if the server crashes or the thread is interrupted
 *       the document stays in PROCESSING forever.</li>
 *   <li>The server is restarted while a document is being processed.</li>
 * </ul>
 *
 * <p>Any document still in PROCESSING after {@value #STALE_THRESHOLD_MINUTES} minutes
 * is reset to FAILED (retryable=true) so the user can re-upload.
 */
@Component
public class StaleProcessingCleanupService {

    private static final Logger log = LoggerFactory.getLogger(StaleProcessingCleanupService.class);

    /** Documents stuck in PROCESSING longer than this are considered stale. */
    private static final int STALE_THRESHOLD_MINUTES = 10;

    private final DocumentRepository documentRepository;

    public StaleProcessingCleanupService(DocumentRepository documentRepository) {
        this.documentRepository = documentRepository;
    }

    /**
     * Run once at startup to clean up any documents left in PROCESSING from
     * a previous server run that was interrupted.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void cleanupOnStartup() {
        int updated = cleanupStale();
        if (updated > 0) {
            log.warn("Startup cleanup: reset {} stale PROCESSING document(s) to FAILED (retryable).", updated);
        } else {
            log.info("Startup cleanup: no stale PROCESSING documents found.");
        }
    }

    /**
     * Periodic cleanup every 5 minutes to catch any documents that got stuck
     * while the server was running (e.g. thread killed, OOM, etc.).
     */
    @Scheduled(fixedDelay = 5 * 60 * 1000)
    @Transactional
    public void cleanupPeriodically() {
        int updated = cleanupStale();
        if (updated > 0) {
            log.warn("Periodic cleanup: reset {} stale PROCESSING document(s) to FAILED (retryable).", updated);
        }
    }

    private int cleanupStale() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(STALE_THRESHOLD_MINUTES);
        return documentRepository.markStaleProcessingAsFailed(cutoff);
    }
}

