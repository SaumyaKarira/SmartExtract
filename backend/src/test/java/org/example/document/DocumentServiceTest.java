package org.example.document;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.example.auth.UserRepository;
import org.example.entity.Document;
import org.example.entity.DocumentStatus;
import org.example.entity.User;
import org.example.purchaseorder.PurchaseOrderRepository;
import org.example.validation.PoValidationService;
import org.example.validation.ValidationResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.example.entity.PurchaseOrder;
import org.example.entity.PurchaseOrderItem;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for DocumentService — PDF validation, failure classification, retry logic.
 */
@ExtendWith(MockitoExtension.class)
class DocumentServiceTest {

    @Mock DocumentRepository documentRepository;
    @Mock UserRepository userRepository;
    @Mock LlmExtractionService llmExtractionService;
    @Mock PurchaseOrderRepository purchaseOrderRepository;
    @Mock PoValidationService poValidationService;
    @Mock ObjectMapper objectMapper;

    @InjectMocks DocumentService documentService;

    private User testUser;

    @BeforeEach
    void setUp() {
        testUser = new User();
        testUser.setId(1L);
        testUser.setName("Test User");
        testUser.setEmail("test@example.com");
    }

    // -------------------------------------------------------------------------
    // deleteDocument tests
    // -------------------------------------------------------------------------

    @Test
    void deleteDocument_successfulDeletion() {
        Document doc = makeDoc(99L, testUser, DocumentStatus.COMPLETED);
        when(documentRepository.findById(99L)).thenReturn(Optional.of(doc));

        documentService.deleteDocument(99L, 1L);

        verify(documentRepository).delete(doc);
    }

    @Test
    void deleteDocument_cascadesPoAndItems() {
        // PurchaseOrder with items is attached to the document.
        // CascadeType.ALL on the mapping means documentRepository.delete(doc)
        // removes the PO and its items atomically. We verify delete is called once
        // and that the PO/items repos are NOT called directly (JPA cascade handles it).
        PurchaseOrderItem item = new PurchaseOrderItem();
        PurchaseOrder po = new PurchaseOrder();
        po.setId(55L);
        po.getItems().add(item);

        Document doc = makeDoc(99L, testUser, DocumentStatus.COMPLETED);
        doc.setPurchaseOrder(po);

        when(documentRepository.findById(99L)).thenReturn(Optional.of(doc));

        documentService.deleteDocument(99L, 1L);

        // Only documentRepository.delete is called — cascade handles the rest
        verify(documentRepository).delete(doc);
        verifyNoInteractions(purchaseOrderRepository);
    }

    @Test
    void deleteDocument_unauthorized_throwsForbidden() {
        User otherUser = new User();
        otherUser.setId(999L);
        Document doc = makeDoc(99L, otherUser, DocumentStatus.COMPLETED);

        when(documentRepository.findById(99L)).thenReturn(Optional.of(doc));

        ResponseStatusException ex = catchThrowableOfType(
                () -> documentService.deleteDocument(99L, 1L), // userId=1, but doc owned by 999
                ResponseStatusException.class);

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(documentRepository, never()).delete(any());
    }

    @Test
    void deleteDocument_notFound_throwsNotFound() {
        when(documentRepository.findById(404L)).thenReturn(Optional.empty());

        ResponseStatusException ex = catchThrowableOfType(
                () -> documentService.deleteDocument(404L, 1L),
                ResponseStatusException.class);

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(documentRepository, never()).delete(any());
    }

    @Test
    void deleteDocument_repositoryThrows_propagatesException() {
        Document doc = makeDoc(99L, testUser, DocumentStatus.COMPLETED);
        when(documentRepository.findById(99L)).thenReturn(Optional.of(doc));
        doThrow(new RuntimeException("DB connection lost")).when(documentRepository).delete(doc);

        // Exception must propagate — nothing silently swallowed
        assertThatThrownBy(() -> documentService.deleteDocument(99L, 1L))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("DB connection lost");
    }

    @Test
    void deleteDocument_onlyOwnerCanDelete_multipleUsers() {
        User alice = new User(); alice.setId(1L);
        User bob   = new User(); bob.setId(2L);

        Document doc = makeDoc(10L, alice, DocumentStatus.COMPLETED);
        when(documentRepository.findById(10L)).thenReturn(Optional.of(doc));

        // Bob must not be able to delete Alice's document
        ResponseStatusException ex = catchThrowableOfType(
                () -> documentService.deleteDocument(10L, 2L),
                ResponseStatusException.class);

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(documentRepository, never()).delete(any());

        // Alice can delete her own document
        documentService.deleteDocument(10L, 1L);
        verify(documentRepository).delete(doc);
    }

    private Document makeDoc(Long id, User owner, DocumentStatus status) {
        Document doc = new Document();
        doc.setId(id);
        doc.setUser(owner);
        doc.setFileName("test.pdf");
        doc.setFileType("application/pdf");
        doc.setStatus(status);
        doc.setUploadedAt(LocalDateTime.now());
        return doc;
    }

    // -------------------------------------------------------------------------
    // Upload validation tests
    // -------------------------------------------------------------------------

    @Test
    void upload_rejectsNonPdfFile() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "doc.txt", "text/plain", "hello".getBytes());

        assertThatThrownBy(() -> documentService.upload(file, 1L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Only PDF and DOCX files are accepted");

        verifyNoInteractions(documentRepository);
    }

    @Test
    void upload_rejectsEmptyFile() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "empty.pdf", "application/pdf", new byte[0]);

        assertThatThrownBy(() -> documentService.upload(file, 1L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("No file was provided");

        verifyNoInteractions(documentRepository);
    }

    @Test
    void upload_rejectsOversizedFile() {
        // 11 MB of bytes
        byte[] bigBytes = new byte[11 * 1024 * 1024];
        MockMultipartFile file = new MockMultipartFile(
                "file", "big.pdf", "application/pdf", bigBytes);

        assertThatThrownBy(() -> documentService.upload(file, 1L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("10 MB");

        verifyNoInteractions(documentRepository);
    }

    @Test
    void upload_rejectsCorruptPdf() {
        // Bytes that look like PDF by name but aren't valid
        byte[] corruptBytes = "not-a-pdf-content".getBytes();
        MockMultipartFile file = new MockMultipartFile(
                "file", "bad.pdf", "application/pdf", corruptBytes);

        assertThatThrownBy(() -> documentService.upload(file, 1L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("valid or readable PDF");

        verifyNoInteractions(documentRepository);
    }

    @Test
    void upload_returnsDuplicateWhenSameHash() throws IOException {
        byte[] pdfBytes = createMinimalPdf("Hello World Purchase Order with some content to extract");
        MockMultipartFile file = new MockMultipartFile(
                "file", "po.pdf", "application/pdf", pdfBytes);

        Document existing = new Document();
        existing.setId(42L);
        existing.setUser(testUser);
        existing.setFileName("po.pdf");
        existing.setFileType("application/pdf");
        existing.setStatus(DocumentStatus.COMPLETED);
        existing.setUploadedAt(LocalDateTime.now());

        when(documentRepository.findByUserIdAndFileHash(eq(1L), anyString()))
                .thenReturn(Optional.of(existing));

        DocumentResponse response = documentService.upload(file, 1L);

        assertThat(response.duplicate()).isTrue();
        assertThat(response.id()).isEqualTo(42L);
        verify(documentRepository, never()).save(any());
    }

    // -------------------------------------------------------------------------
    // Retry tests
    // -------------------------------------------------------------------------

    @Test
    void retry_failsIfDocumentNotOwned() throws IOException {
        byte[] pdfBytes = createMinimalPdf("Purchase order content");
        MockMultipartFile file = new MockMultipartFile(
                "file", "po.pdf", "application/pdf", pdfBytes);

        Document doc = new Document();
        doc.setId(10L);
        doc.setUser(testUser); // owner is user 1
        doc.setStatus(DocumentStatus.FAILED);
        doc.setRetryable(true);
        doc.setFileName("po.pdf");
        doc.setFileHash("abc");

        when(documentRepository.findById(10L)).thenReturn(Optional.of(doc));

        // User 2 tries to retry
        assertThatThrownBy(() -> documentService.retry(10L, 2L, file))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Access denied");
    }

    @Test
    void retry_failsIfNotRetryable() throws IOException {
        byte[] pdfBytes = createMinimalPdf("Purchase order content");
        MockMultipartFile file = new MockMultipartFile(
                "file", "po.pdf", "application/pdf", pdfBytes);

        Document doc = new Document();
        doc.setId(10L);
        doc.setUser(testUser);
        doc.setStatus(DocumentStatus.FAILED);
        doc.setRetryable(false); // permanent failure
        doc.setFileName("po.pdf");

        when(documentRepository.findById(10L)).thenReturn(Optional.of(doc));

        assertThatThrownBy(() -> documentService.retry(10L, 1L, file))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not eligible for retry");
    }

    @Test
    void retry_failsIfHashMismatch() throws IOException {
        byte[] pdfBytes = createMinimalPdf("Purchase order content");
        MockMultipartFile file = new MockMultipartFile(
                "file", "po.pdf", "application/pdf", pdfBytes);

        Document doc = new Document();
        doc.setId(10L);
        doc.setUser(testUser);
        doc.setStatus(DocumentStatus.FAILED);
        doc.setRetryable(true);
        doc.setFileName("po.pdf");
        doc.setFileHash("0000000000000000000000000000000000000000000000000000000000000000");

        when(documentRepository.findById(10L)).thenReturn(Optional.of(doc));

        assertThatThrownBy(() -> documentService.retry(10L, 1L, file))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("does not match the original");
    }

    // -------------------------------------------------------------------------
    // Failure classification tests
    // -------------------------------------------------------------------------

    @Test
    void upload_setsRetryableTrueOnGeminiTimeout() throws IOException {
        byte[] pdfBytes = createMinimalPdf("Purchase order text content");
        MockMultipartFile file = new MockMultipartFile(
                "file", "po.pdf", "application/pdf", pdfBytes);

        when(documentRepository.findByUserIdAndFileHash(eq(1L), anyString()))
                .thenReturn(Optional.empty());
        when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));

        Document savedDoc = new Document();
        savedDoc.setId(99L);
        savedDoc.setUser(testUser);
        savedDoc.setFileName("po.pdf");
        savedDoc.setFileType("application/pdf");
        savedDoc.setStatus(DocumentStatus.PROCESSING);
        savedDoc.setUploadedAt(LocalDateTime.now());
        when(documentRepository.save(any())).thenAnswer(inv -> {
            Document d = inv.getArgument(0);
            d.setId(99L);
            return d;
        });

        // Gemini throws timeout
        when(llmExtractionService.extract(anyString()))
                .thenThrow(new RuntimeException("connection timeout exceeded"));

        DocumentResponse response = documentService.upload(file, 1L);

        assertThat(response.status()).isEqualTo(DocumentStatus.FAILED);
        assertThat(response.retryable()).isTrue();
        assertThat(response.errorMessage()).contains("temporary service issue");
    }

    @Test
    void upload_setsRetryableFalseOnPermanentGeminiFailure() throws IOException {
        byte[] pdfBytes = createMinimalPdf("Purchase order text content");
        MockMultipartFile file = new MockMultipartFile(
                "file", "po.pdf", "application/pdf", pdfBytes);

        when(documentRepository.findByUserIdAndFileHash(eq(1L), anyString()))
                .thenReturn(Optional.empty());
        when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));

        when(documentRepository.save(any())).thenAnswer(inv -> {
            Document d = inv.getArgument(0);
            d.setId(99L);
            return d;
        });

        // Gemini throws a permanent parse error (not timeout/connection)
        when(llmExtractionService.extract(anyString()))
                .thenThrow(new RuntimeException("Failed to parse Gemini response as JSON: unexpected token"));

        DocumentResponse response = documentService.upload(file, 1L);

        assertThat(response.status()).isEqualTo(DocumentStatus.FAILED);
        assertThat(response.retryable()).isFalse();
        assertThat(response.errorMessage()).contains("scanned image or an unsupported format");
    }

    @Test
    void getByUser_returnsListWithRetryableFields() {
        Document doc = new Document();
        doc.setId(1L);
        doc.setUser(testUser);
        doc.setFileName("po.pdf");
        doc.setFileType("application/pdf");
        doc.setStatus(DocumentStatus.FAILED);
        doc.setUploadedAt(LocalDateTime.now());
        doc.setRetryable(true);
        doc.setErrorMessage("Temporary failure");

        when(documentRepository.findByUserId(1L)).thenReturn(List.of(doc));

        List<DocumentResponse> responses = documentService.getByUser(1L);
        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).retryable()).isTrue();
        assertThat(responses.get(0).errorMessage()).isEqualTo("Temporary failure");
    }

    // -------------------------------------------------------------------------
    // Helper: create a minimal valid PDF with extractable text
    // -------------------------------------------------------------------------

    private byte[] createMinimalPdf(String text) throws IOException {
        try (PDDocument doc = new PDDocument();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            org.apache.pdfbox.pdmodel.PDPage page = new org.apache.pdfbox.pdmodel.PDPage();
            doc.addPage(page);
            try (org.apache.pdfbox.pdmodel.PDPageContentStream cs =
                         new org.apache.pdfbox.pdmodel.PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(
                        new org.apache.pdfbox.pdmodel.font.PDType1Font(
                                org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA),
                        12
                );
                cs.newLineAtOffset(50, 700);
                cs.showText(text);
                cs.endText();
            }
            doc.save(baos);
            return baos.toByteArray();
        }
    }
}



