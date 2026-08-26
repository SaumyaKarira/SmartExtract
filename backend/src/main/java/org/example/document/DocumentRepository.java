package org.example.document;

import org.example.entity.Document;
import org.example.entity.DocumentStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DocumentRepository extends JpaRepository<Document, Long> {

    List<Document> findByUserId(Long userId);

    java.util.Optional<Document> findByUserIdAndFileHash(Long userId, String fileHash);

    long countByUserId(Long userId);

    long countByUserIdAndStatus(Long userId, DocumentStatus status);
}

