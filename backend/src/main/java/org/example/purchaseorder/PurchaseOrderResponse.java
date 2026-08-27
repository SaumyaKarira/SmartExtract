package org.example.purchaseorder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record PurchaseOrderResponse(
        Long id,
        Long userId,
        Long documentId,
        String fileName,
        String poNumber,
        String supplier,
        LocalDate orderDate,
        LocalDate deliveryDate,
        String paymentTerms,
        String currency,
        BigDecimal subtotal,
        BigDecimal tax,
        BigDecimal total,
        LocalDateTime createdAt,
        List<PurchaseOrderItemResponse> items,
        String status,
        /** JSON string of ValidationResult.Correction list; null if not COMPLETED_WITH_CORRECTIONS. */
        String validationCorrections,
        /** JSON string of review-reason strings; null if not NEEDS_REVIEW. */
        String validationReviewReasons,
        /** True when status=FAILED and the document is eligible for retry; null for non-FAILED rows. */
        Boolean retryable,
        /** User-facing error message when status=FAILED; null for non-FAILED rows. */
        String errorMessage
) {
    public record PurchaseOrderItemResponse(
            Long id,
            String description,
            BigDecimal quantity,
            BigDecimal unitPrice,
            BigDecimal totalPrice
    ) {}
}

