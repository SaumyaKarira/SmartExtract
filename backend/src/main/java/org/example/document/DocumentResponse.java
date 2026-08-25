package org.example.document;

import org.example.entity.DocumentStatus;

import java.time.LocalDateTime;

public record DocumentResponse(
        Long id,
        Long userId,
        String fileName,
        String fileType,
        DocumentStatus status,
        LocalDateTime uploadedAt,
        String extractedText,
        ExtractedPurchaseOrder extractedPurchaseOrder
) {}

