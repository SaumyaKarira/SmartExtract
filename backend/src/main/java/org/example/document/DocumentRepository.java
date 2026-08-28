package org.example.document;

import org.example.entity.Document;
import org.example.entity.DocumentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface DocumentRepository extends JpaRepository<Document, Long> {

    List<Document> findByUserId(Long userId);

    java.util.Optional<Document> findByUserIdAndFileHash(Long userId, String fileHash);

    long countByUserId(Long userId);

    long countByUserIdAndStatus(Long userId, DocumentStatus status);

    /** Find all PROCESSING documents older than the given cutoff — used for stale-record cleanup. */
    List<Document> findByStatusAndUploadedAtBefore(DocumentStatus status, LocalDateTime cutoff);

    /** Bulk-update stale PROCESSING documents to FAILED in one query. */
    @Modifying
    @Query("UPDATE Document d SET d.status = 'FAILED', d.retryable = true, " +
           "d.errorMessage = 'Processing was interrupted. Please retry.' " +
           "WHERE d.status = 'PROCESSING' AND d.uploadedAt < :cutoff")
    int markStaleProcessingAsFailed(@Param("cutoff") LocalDateTime cutoff);
}

