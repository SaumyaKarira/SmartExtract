package org.example.purchaseorder;

import org.example.document.DocumentRepository;
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

@ExtendWith(MockitoExtension.class)
class PurchaseOrderServiceAuthTest {

    @Mock PurchaseOrderRepository purchaseOrderRepository;
    @Mock DocumentRepository documentRepository;

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
        assertThat(ex.getReason()).doesNotContainIgnoringCase("user b");
        assertThat(ex.getReason()).doesNotContainIgnoringCase("another user");
        assertThat(ex.getReason()).doesNotContainIgnoringCase("forbidden");
    }

    @Test
    void getByUser_onlyReturnsOwnDocuments() {
        Document doc = new Document();
        doc.setId(20L);
        doc.setUser(userA);
        doc.setFileName("my.pdf");
        doc.setFileType("application/pdf");
        doc.setStatus(DocumentStatus.COMPLETED);
        doc.setUploadedAt(LocalDateTime.now());

        PurchaseOrder ownPO = new PurchaseOrder();
        ownPO.setId(200L);
        ownPO.setUser(userA);
        ownPO.setDocument(doc);
        ownPO.setCreatedAt(LocalDateTime.now());
        doc.setPurchaseOrder(ownPO);

        when(documentRepository.findByUserId(1L)).thenReturn(List.of(doc));

        List<PurchaseOrderResponse> results = purchaseOrderService.getByUser(1L);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).id()).isEqualTo(200L);
        assertThat(results.get(0).documentId()).isEqualTo(20L);
        verify(documentRepository).findByUserId(1L);
        verify(documentRepository, never()).findByUserId(2L);
    }

    @Test
    void getByUser_includesFailedDocumentWithNoPo() {
        Document failedDoc = new Document();
        failedDoc.setId(30L);
        failedDoc.setUser(userA);
        failedDoc.setFileName("broken.pdf");
        failedDoc.setFileType("application/pdf");
        failedDoc.setStatus(DocumentStatus.FAILED);
        failedDoc.setUploadedAt(LocalDateTime.now());
        failedDoc.setRetryable(true);
        failedDoc.setErrorMessage("AI extraction failed due to a temporary service issue.");
        // purchaseOrder not set — getPurchaseOrder() returns null

        when(documentRepository.findByUserId(1L)).thenReturn(List.of(failedDoc));

        List<PurchaseOrderResponse> results = purchaseOrderService.getByUser(1L);
        assertThat(results).hasSize(1);

        PurchaseOrderResponse row = results.get(0);
        assertThat(row.id()).isNull();
        assertThat(row.documentId()).isEqualTo(30L);
        assertThat(row.status()).isEqualTo("FAILED");
        assertThat(row.retryable()).isTrue();
        assertThat(row.errorMessage()).contains("temporary service issue");
        assertThat(row.poNumber()).isNull();
        assertThat(row.total()).isNull();
    }
}
