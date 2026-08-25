package org.example.purchaseorder;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/purchase-orders")
public class PurchaseOrderController {

    private final PurchaseOrderService purchaseOrderService;

    public PurchaseOrderController(PurchaseOrderService purchaseOrderService) {
        this.purchaseOrderService = purchaseOrderService;
    }

    @GetMapping
    public List<PurchaseOrderResponse> listByUser(Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        return purchaseOrderService.getByUser(userId);
    }

    @GetMapping("/{id}")
    public ResponseEntity<PurchaseOrderResponse> getById(
            @PathVariable Long id,
            Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        return ResponseEntity.ok(purchaseOrderService.getByIdAndUser(id, userId));
    }
}

