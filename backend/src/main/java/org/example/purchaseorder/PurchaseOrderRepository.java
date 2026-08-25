package org.example.purchaseorder;

import org.example.entity.PurchaseOrder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, Long> {

    List<PurchaseOrder> findByUserId(Long userId);

    Optional<PurchaseOrder> findByIdAndUserId(Long id, Long userId);
}

