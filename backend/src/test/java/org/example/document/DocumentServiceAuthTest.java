package org.example.document;

import org.example.auth.UserRepository;
import org.example.entity.Document;
import org.example.entity.DocumentStatus;
import org.example.entity.User;
import org.example.purchaseorder.PurchaseOrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Authorization-focused tests for DocumentService.
 * Covers cross-user isolation for document access and retry.
 */
@ExtendWith(MockitoExtension.class)
class DocumentServiceAuthTest {

    @Mock DocumentRepository documentRepository;
    @Mock UserRepository userRepository;
    @Mock LlmExtractionService llmExtractionService;
    @Mock PurchaseOrderRepository purchaseOrderRepository;

    @InjectMocks DocumentService documentService;

    private User userA;
    private User userB;

    @BeforeEach
    void setUp() {
        userA = new User();
        userA.setId(1L);
        userA.setEmail("a@example.com");

        userB = new User();
        userB.setId(2L);
        userB.setEmail("b@example.com");
    }

    @Test
    void retry_byNonOwner_returnsForbidden() {
        Document doc = buildFailedRetryableDoc(userB, 10L);
        when(documentRepository.findById(10L)).thenReturn(Optional.of(doc));

        MockMultipartFile file = new MockMultipartFile(
                "file", "po.pdf", "application/pdf", new byte[]{1, 2, 3});

        // User A tries to retry User B's document
        assertThatThrownBy(() -> documentService.retry(10L, 1L, file))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode().value()).isEqualTo(403);
                    assertThat(rse.getReason()).isEqualTo("Access denied");
                });
    }

    @Test
    void retry_errorResponseDoesNotLeakOwnerInfo() {
        Document doc = buildFailedRetryableDoc(userB, 10L);
        when(documentRepository.findById(10L)).thenReturn(Optional.of(doc));

        MockMultipartFile file = new MockMultipartFile(
                "file", "po.pdf", "application/pdf", new byte[]{1, 2, 3});

        ResponseStatusException ex = catchThrowableOfType(
                () -> documentService.retry(10L, 1L, file),
                ResponseStatusException.class);

        // Must not expose who the real owner is
        assertThat(ex.getReason()).doesNotContainIgnoringCase("user b");
        assertThat(ex.getReason()).doesNotContainIgnoringCase("b@example.com");
        assertThat(ex.getReason()).doesNotContainIgnoringCase("user id");
    }

    @Test
    void retry_notFound_returns404() {
        when(documentRepository.findById(999L)).thenReturn(Optional.empty());

        MockMultipartFile file = new MockMultipartFile(
                "file", "po.pdf", "application/pdf", new byte[]{1});

        assertThatThrownBy(() -> documentService.retry(999L, 1L, file))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode().value()).isEqualTo(404);
                });
    }

    @Test
    void getByUser_neverReturnsCrossUserDocuments() {
        // Repository must be called with userId — verify it's not called with the wrong one
        when(documentRepository.findByUserId(1L)).thenReturn(java.util.List.of());

        documentService.getByUser(1L);

        verify(documentRepository).findByUserId(1L);
        verify(documentRepository, never()).findByUserId(2L);
        verify(documentRepository, never()).findAll();
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private Document buildFailedRetryableDoc(User owner, Long id) {
        Document doc = new Document();
        doc.setId(id);
        doc.setUser(owner);
        doc.setFileName("po.pdf");
        doc.setFileType("application/pdf");
        doc.setStatus(DocumentStatus.FAILED);
        doc.setRetryable(true);
        doc.setUploadedAt(LocalDateTime.now());
        doc.setFileHash("abc123");
        return doc;
    }
}

