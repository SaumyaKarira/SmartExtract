package org.example.purchaseorder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record PurchaseOrderResponse(
        Long id,
        Long userId,
        Long documentId,
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
        String status
) {
    public record PurchaseOrderItemResponse(
            Long id,
            String description,
            BigDecimal quantity,
            BigDecimal unitPrice,
            BigDecimal totalPrice
    ) {}
}

