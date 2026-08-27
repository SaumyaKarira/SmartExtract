package org.example.document;

import java.util.List;

public record ExtractedPurchaseOrder(
        String poNumber,
        String vendorName,
        String poDate,
        String deliveryDate,
        String paymentTerms,
        String currency,
        Double subtotal,
        Double tax,
        Double totalAmount,
        List<ExtractedLineItem> items
) {
    public record ExtractedLineItem(
            String description,
            Double quantity,
            Double unitPrice,
            Double totalPrice
    ) {}
}
