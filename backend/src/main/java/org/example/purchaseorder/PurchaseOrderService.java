package org.example.purchaseorder;

import org.example.document.DocumentRepository;
import org.example.entity.Document;
import org.example.entity.DocumentStatus;
import org.example.entity.PurchaseOrder;
import org.example.entity.PurchaseOrderItem;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
@Transactional(readOnly = true)
public class PurchaseOrderService {

    private final PurchaseOrderRepository purchaseOrderRepository;
    private final DocumentRepository documentRepository;

    public PurchaseOrderService(PurchaseOrderRepository purchaseOrderRepository,
                                DocumentRepository documentRepository) {
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.documentRepository = documentRepository;
    }

    /**
     * Fetch all documents for the user, then use the associated PO (if present).
     * This mirrors a LEFT JOIN from documents → purchase_orders: documents without
     * a PO (e.g. FAILED before Gemini produced a result) are included with null PO fields.
     */
    public List<PurchaseOrderResponse> getByUser(Long userId) {
        return documentRepository.findByUserId(userId).stream()
                // Exclude documents still being processed — they are transient and should not
                // appear in the list or affect counts until processing completes or fails.
                .filter(doc -> doc.getStatus() != DocumentStatus.PROCESSING)
                .map(this::documentToResponse)
                .toList();
    }

    public PurchaseOrderResponse getByIdAndUser(Long id, Long userId) {
        PurchaseOrder po = purchaseOrderRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Purchase order not found"));
        return poToResponse(po, true);
    }

    /**
     * Build a response from a Document, using its PurchaseOrder if one exists.
     * When purchaseOrder is null the PO fields are null — this covers FAILED documents.
     */
    private PurchaseOrderResponse documentToResponse(Document doc) {
        PurchaseOrder po = doc.getPurchaseOrder();
        if (po != null) {
            return poToResponse(po, false);
        }
        // Document has no PO — return document metadata with null PO fields
        return new PurchaseOrderResponse(
                null,
                doc.getUser().getId(),
                doc.getId(),
                doc.getFileName(),
                null, null, null, null, null, null,
                null, null, null,
                doc.getUploadedAt(),
                List.of(),
                doc.getStatus() != null ? doc.getStatus().name() : null,
                null,
                null,
                doc.isRetryable(),
                doc.getErrorMessage()
        );
    }

    private PurchaseOrderResponse poToResponse(PurchaseOrder po, boolean includeItems) {
        List<PurchaseOrderResponse.PurchaseOrderItemResponse> items = includeItems
                ? po.getItems().stream().map(this::toItemResponse).toList()
                : List.of();

        return new PurchaseOrderResponse(
                po.getId(),
                po.getUser().getId(),
                po.getDocument().getId(),
                po.getDocument().getFileName(),
                po.getPoNumber(),
                po.getSupplier(),
                po.getOrderDate(),
                po.getDeliveryDate(),
                po.getPaymentTerms(),
                po.getCurrency(),
                po.getSubtotal(),
                po.getTax(),
                po.getTotal(),
                po.getCreatedAt(),
                items,
                po.getDocument().getStatus() != null ? po.getDocument().getStatus().name() : null,
                po.getValidationCorrections(),
                po.getValidationReviewReasons(),
                null,
                null
        );
    }

    private PurchaseOrderResponse.PurchaseOrderItemResponse toItemResponse(PurchaseOrderItem item) {
        return new PurchaseOrderResponse.PurchaseOrderItemResponse(
                item.getId(),
                item.getDescription(),
                item.getQuantity(),
                item.getUnitPrice(),
                item.getTotalPrice()
        );
    }
}

