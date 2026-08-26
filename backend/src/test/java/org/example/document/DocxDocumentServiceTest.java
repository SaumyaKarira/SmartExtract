package org.example.document;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.example.auth.UserRepository;
import org.example.entity.Document;
import org.example.entity.DocumentStatus;
import org.example.entity.PurchaseOrder;
import org.example.entity.User;
import org.example.purchaseorder.PurchaseOrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for DocumentService — DOCX validation and text extraction.
 */
@ExtendWith(MockitoExtension.class)
class DocxDocumentServiceTest {

    @Mock DocumentRepository documentRepository;
    @Mock UserRepository userRepository;
    @Mock LlmExtractionService llmExtractionService;
    @Mock PurchaseOrderRepository purchaseOrderRepository;

    @InjectMocks DocumentService documentService;

    private User testUser;

    @BeforeEach
    void setUp() {
        testUser = new User();
        testUser.setId(1L);
        testUser.setEmail("test@example.com");
    }

    // -----------------------------------------------------------------------
    // Helper: create a real DOCX byte array with given text content
    // -----------------------------------------------------------------------
    private byte[] buildDocxBytes(String paragraphText) throws IOException {
        try (XWPFDocument doc = new XWPFDocument()) {
            if (paragraphText != null && !paragraphText.isBlank()) {
                doc.createParagraph().createRun().setText(paragraphText);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.write(out);
            return out.toByteArray();
        }
    }

    private byte[] buildEmptyDocxBytes() throws IOException {
        return buildDocxBytes(null);
    }

    // -----------------------------------------------------------------------
    // DOCX validation — accepted content type
    // -----------------------------------------------------------------------

    @Test
    void upload_docxContentType_acceptedAndPersisted() throws Exception {
        byte[] docxBytes = buildDocxBytes("PO Number: 12345\nVendor: ACME Corp\nTotal: 500.00");

        MockMultipartFile file = new MockMultipartFile(
                "file", "order.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                docxBytes);

        when(documentRepository.findByUserIdAndFileHash(anyLong(), anyString()))
                .thenReturn(Optional.empty());
        when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));

        Document savedDoc = new Document();
        savedDoc.setId(10L);
        savedDoc.setUser(testUser);
        savedDoc.setFileName("order.docx");
        savedDoc.setFileType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        savedDoc.setStatus(DocumentStatus.PROCESSING);
        savedDoc.setUploadedAt(LocalDateTime.now());
        when(documentRepository.save(any(Document.class))).thenReturn(savedDoc);

        ExtractedPurchaseOrder extracted = new ExtractedPurchaseOrder(
                "12345", "ACME Corp", null, null, 500.0, null);
        when(llmExtractionService.extract(anyString())).thenReturn(extracted);

        PurchaseOrder savedPo = new PurchaseOrder();
        savedPo.setId(5L);
        savedPo.setUser(testUser);
        savedPo.setDocument(savedDoc);
        when(purchaseOrderRepository.save(any(PurchaseOrder.class))).thenReturn(savedPo);

        DocumentResponse response = documentService.upload(file, 1L);

        assertThat(response).isNotNull();
        assertThat(response.fileName()).isEqualTo("order.docx");
        verify(documentRepository, atLeastOnce()).save(any(Document.class));
    }

    @Test
    void upload_docxByExtension_accepted() throws Exception {
        byte[] docxBytes = buildDocxBytes("PO Number: 99\nVendor: Widgets Inc\nTotal: 1200.00");

        // Content type is generic; extension is .docx
        MockMultipartFile file = new MockMultipartFile(
                "file", "purchase_order.docx",
                "application/octet-stream",
                docxBytes);

        when(documentRepository.findByUserIdAndFileHash(anyLong(), anyString()))
                .thenReturn(Optional.empty());
        when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));

        Document savedDoc = new Document();
        savedDoc.setId(11L);
        savedDoc.setUser(testUser);
        savedDoc.setFileName("purchase_order.docx");
        savedDoc.setFileType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        savedDoc.setStatus(DocumentStatus.PROCESSING);
        savedDoc.setUploadedAt(LocalDateTime.now());
        when(documentRepository.save(any(Document.class))).thenReturn(savedDoc);

        ExtractedPurchaseOrder extracted = new ExtractedPurchaseOrder(
                "99", "Widgets Inc", null, null, 1200.0, null);
        when(llmExtractionService.extract(anyString())).thenReturn(extracted);

        PurchaseOrder savedPo = new PurchaseOrder();
        savedPo.setId(6L);
        savedPo.setUser(testUser);
        savedPo.setDocument(savedDoc);
        when(purchaseOrderRepository.save(any(PurchaseOrder.class))).thenReturn(savedPo);

        DocumentResponse response = documentService.upload(file, 1L);

        assertThat(response).isNotNull();
    }

    // -----------------------------------------------------------------------
    // DOCX validation — rejection cases
    // -----------------------------------------------------------------------

    @Test
    void upload_docFile_rejected() {
        // .doc (legacy Word) should be rejected
        MockMultipartFile file = new MockMultipartFile(
                "file", "order.doc",
                "application/msword",
                new byte[]{0x4D, 0x5A, 0x00, 0x00}); // random non-PDF bytes

        assertThatThrownBy(() -> documentService.upload(file, 1L))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(rse.getReason()).containsIgnoringCase("PDF and DOCX");
                });
    }

    @Test
    void upload_txtFile_rejected() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "order.txt",
                "text/plain",
                "some purchase order text".getBytes());

        assertThatThrownBy(() -> documentService.upload(file, 1L))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void upload_corruptDocx_rejected() {
        // Bytes that look like DOCX name but contain garbage
        MockMultipartFile file = new MockMultipartFile(
                "file", "corrupt.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                new byte[]{0x00, 0x01, 0x02, 0x03, 0x04});

        assertThatThrownBy(() -> documentService.upload(file, 1L))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                });
    }

    @Test
    void upload_emptyDocx_textExtractionFails() throws Exception {
        byte[] docxBytes = buildEmptyDocxBytes();

        MockMultipartFile file = new MockMultipartFile(
                "file", "empty.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                docxBytes);

        when(documentRepository.findByUserIdAndFileHash(anyLong(), anyString()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> documentService.upload(file, 1L))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(rse.getReason()).containsIgnoringCase("No text");
                });
    }

    @Test
    void upload_docxOversized_rejected() {
        byte[] oversized = new byte[11 * 1024 * 1024]; // 11 MB

        MockMultipartFile file = new MockMultipartFile(
                "file", "big.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                oversized);

        assertThatThrownBy(() -> documentService.upload(file, 1L))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(rse.getReason()).containsIgnoringCase("10 MB");
                });
    }

    // -----------------------------------------------------------------------
    // Duplicate detection — DOCX hash
    // -----------------------------------------------------------------------

    @Test
    void upload_docxDuplicate_returnsDuplicateResponse() throws Exception {
        byte[] docxBytes = buildDocxBytes("Duplicate purchase order content");

        MockMultipartFile file = new MockMultipartFile(
                "file", "dup.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                docxBytes);

        Document existingDoc = new Document();
        existingDoc.setId(20L);
        existingDoc.setUser(testUser);
        existingDoc.setFileName("dup.docx");
        existingDoc.setFileType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        existingDoc.setStatus(DocumentStatus.COMPLETED);
        existingDoc.setUploadedAt(LocalDateTime.now());

        PurchaseOrder existingPo = new PurchaseOrder();
        existingPo.setId(30L);
        existingPo.setUser(testUser);
        existingPo.setDocument(existingDoc);
        existingDoc.setPurchaseOrder(existingPo);

        when(documentRepository.findByUserIdAndFileHash(anyLong(), anyString()))
                .thenReturn(Optional.of(existingDoc));

        DocumentResponse response = documentService.upload(file, 1L);

        assertThat(response.duplicate()).isTrue();
        assertThat(response.purchaseOrderId()).isEqualTo(30L);
    }

    // -----------------------------------------------------------------------
    // Retry — hash must match original
    // -----------------------------------------------------------------------

    @Test
    void retry_docxHashMismatch_rejected() throws Exception {
        byte[] originalBytes = buildDocxBytes("Original content");
        byte[] differentBytes = buildDocxBytes("Different content");

        // Compute hash of original
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        String originalHash = java.util.HexFormat.of().formatHex(digest.digest(originalBytes));

        Document failedDoc = new Document();
        failedDoc.setId(40L);
        failedDoc.setUser(testUser);
        failedDoc.setFileName("order.docx");
        failedDoc.setFileType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        failedDoc.setStatus(DocumentStatus.FAILED);
        failedDoc.setRetryable(true);
        failedDoc.setFileHash(originalHash);
        failedDoc.setUploadedAt(LocalDateTime.now());

        when(documentRepository.findById(40L)).thenReturn(Optional.of(failedDoc));

        MockMultipartFile differentFile = new MockMultipartFile(
                "file", "order.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                differentBytes);

        assertThatThrownBy(() -> documentService.retry(40L, 1L, differentFile))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(rse.getReason()).containsIgnoringCase("does not match");
                });
    }
}

