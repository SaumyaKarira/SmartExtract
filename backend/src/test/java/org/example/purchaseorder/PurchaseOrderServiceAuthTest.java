package org.example.purchaseorder;

import org.example.entity.Document;
import org.example.entity.DocumentStatus;
import org.example.entity.PurchaseOrder;
import org.example.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for PurchaseOrderService — user-data isolation.
 * Verifies that User A cannot access User B's purchase orders.
 */
@ExtendWith(MockitoExtension.class)
class PurchaseOrderServiceAuthTest {

    @Mock PurchaseOrderRepository purchaseOrderRepository;

    @InjectMocks PurchaseOrderService purchaseOrderService;

    private User userA;
    private User userB;
    private PurchaseOrder poOwnedByUserB;

    @BeforeEach
    void setUp() {
        userA = new User();
        userA.setId(1L);
        userA.setName("User A");
        userA.setEmail("a@example.com");

        userB = new User();
        userB.setId(2L);
        userB.setName("User B");
        userB.setEmail("b@example.com");

        Document doc = new Document();
        doc.setId(10L);
        doc.setUser(userB);
        doc.setFileName("po.pdf");
        doc.setFileType("application/pdf");
        doc.setStatus(DocumentStatus.COMPLETED);
        doc.setUploadedAt(LocalDateTime.now());

        poOwnedByUserB = new PurchaseOrder();
        poOwnedByUserB.setId(100L);
        poOwnedByUserB.setUser(userB);
        poOwnedByUserB.setDocument(doc);
        poOwnedByUserB.setCreatedAt(LocalDateTime.now());
    }

    @Test
    void getByIdAndUser_allowsOwnerAccess() {
        when(purchaseOrderRepository.findByIdAndUserId(100L, 2L))
                .thenReturn(Optional.of(poOwnedByUserB));

        PurchaseOrderResponse response = purchaseOrderService.getByIdAndUser(100L, 2L);
        assertThat(response.id()).isEqualTo(100L);
    }

    @Test
    void getByIdAndUser_deniesAccessToOtherUser() {
        // User A tries to access User B's PO — repository returns empty because userId filter
        when(purchaseOrderRepository.findByIdAndUserId(100L, 1L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> purchaseOrderService.getByIdAndUser(100L, 1L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Purchase order not found");
    }

    @Test
    void getByIdAndUser_errorMessageDoesNotRevealOwnership() {
        when(purchaseOrderRepository.findByIdAndUserId(100L, 1L))
                .thenReturn(Optional.empty());

        ResponseStatusException ex = catchThrowableOfType(
                () -> purchaseOrderService.getByIdAndUser(100L, 1L),
                ResponseStatusException.class);

        // Must not say "belongs to another user" or reveal cross-user info
        assertThat(ex.getReason()).doesNotContainIgnoringCase("user b");
        assertThat(ex.getReason()).doesNotContainIgnoringCase("another user");
        assertThat(ex.getReason()).doesNotContainIgnoringCase("forbidden");
    }

    @Test
    void getByUser_onlyReturnsOwnPOs() {
        PurchaseOrder ownPO = new PurchaseOrder();
        ownPO.setId(200L);
        ownPO.setUser(userA);
        Document doc = new Document();
        doc.setId(20L);
        doc.setUser(userA);
        doc.setFileName("my.pdf");
        doc.setFileType("application/pdf");
        doc.setStatus(DocumentStatus.COMPLETED);
        doc.setUploadedAt(LocalDateTime.now());
        ownPO.setDocument(doc);
        ownPO.setCreatedAt(LocalDateTime.now());

        when(purchaseOrderRepository.findByUserId(1L)).thenReturn(List.of(ownPO));

        List<PurchaseOrderResponse> results = purchaseOrderService.getByUser(1L);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).id()).isEqualTo(200L);
        verify(purchaseOrderRepository).findByUserId(1L);
        verify(purchaseOrderRepository, never()).findByUserId(2L);
    }
}

