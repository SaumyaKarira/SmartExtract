package org.example.purchaseorder;

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

    public PurchaseOrderService(PurchaseOrderRepository purchaseOrderRepository) {
        this.purchaseOrderRepository = purchaseOrderRepository;
    }

    public List<PurchaseOrderResponse> getByUser(Long userId) {
        return purchaseOrderRepository.findByUserId(userId).stream()
                .map(po -> toResponse(po, false))
                .toList();
    }

    public PurchaseOrderResponse getByIdAndUser(Long id, Long userId) {
        PurchaseOrder po = purchaseOrderRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Purchase order not found"));
        return toResponse(po, true);
    }

    private PurchaseOrderResponse toResponse(PurchaseOrder po, boolean includeItems) {
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
                po.getDocument() != null && po.getDocument().getStatus() != null
                        ? po.getDocument().getStatus().name() : null,
                po.getValidationCorrections(),
                po.getValidationReviewReasons()
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

