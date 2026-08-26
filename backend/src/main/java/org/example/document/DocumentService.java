package org.example.document;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.example.auth.UserRepository;
import org.example.entity.Document;
import org.example.entity.DocumentStatus;
import org.example.entity.PurchaseOrder;
import org.example.entity.PurchaseOrderItem;
import org.example.entity.User;
import org.example.purchaseorder.PurchaseOrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

@Service
@Transactional(readOnly = true)
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    private static final long MAX_FILE_SIZE_BYTES = 10L * 1024 * 1024; // 10 MB
    private static final int MAX_PAGES = 100;

    private final DocumentRepository documentRepository;
    private final UserRepository userRepository;
    private final LlmExtractionService llmExtractionService;
    private final PurchaseOrderRepository purchaseOrderRepository;

    public DocumentService(DocumentRepository documentRepository,
                           UserRepository userRepository,
                           LlmExtractionService llmExtractionService,
                           PurchaseOrderRepository purchaseOrderRepository) {
        this.documentRepository = documentRepository;
        this.userRepository = userRepository;
        this.llmExtractionService = llmExtractionService;
        this.purchaseOrderRepository = purchaseOrderRepository;
    }

    public List<DocumentResponse> getByUser(Long userId) {
        return documentRepository.findByUserId(userId).stream()
                .map(doc -> toResponse(doc, null, null, null, false))
                .toList();
    }

    // -------------------------------------------------------------------------
    // Upload
    // -------------------------------------------------------------------------

    @Transactional
    public DocumentResponse upload(MultipartFile file, Long userId) {
        // 1. Validate before creating any DB record
        byte[] fileBytes = validateAndReadBytes(file);
        String fileHash = sha256Hex(fileBytes);

        // 2. Duplicate check
        Optional<Document> existing = documentRepository.findByUserIdAndFileHash(userId, fileHash);
        if (existing.isPresent()) {
            Document existingDoc = existing.get();
            Long existingPoId = existingDoc.getPurchaseOrder() != null
                    ? existingDoc.getPurchaseOrder().getId() : null;
            return toResponse(existingDoc, null, null, existingPoId, true);
        }

        // 3. Extract text (still validation — no DB record yet)
        String extractedText = extractTextOrThrow(fileBytes);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        // 4. Create DB record only after file is confirmed valid
        Document doc = new Document();
        doc.setUser(user);
        doc.setFileName(file.getOriginalFilename() != null ? file.getOriginalFilename() : "upload.pdf");
        doc.setFileType("application/pdf");
        doc.setStatus(DocumentStatus.PROCESSING);
        doc.setUploadedAt(LocalDateTime.now());
        doc.setFileHash(fileHash);
        doc = documentRepository.save(doc);

        return processDocument(doc, extractedText, user);
    }

    // -------------------------------------------------------------------------
    // Retry
    // -------------------------------------------------------------------------

    @Transactional
    public DocumentResponse retry(Long documentId, Long userId, MultipartFile file) {
        Document doc = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found"));

        // Ownership check
        if (!doc.getUser().getId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }

        // Must be a retryable FAILED document
        if (doc.getStatus() != DocumentStatus.FAILED || !doc.isRetryable()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This document is not eligible for retry.");
        }

        // Prevent concurrent processing
        if (doc.getPurchaseOrder() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A purchase order already exists for this document.");
        }

        // Validate the re-submitted file
        byte[] fileBytes = validateAndReadBytes(file);
        String fileHash = sha256Hex(fileBytes);

        // Hash must match the original
        if (doc.getFileHash() != null && !doc.getFileHash().equals(fileHash)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "The uploaded file does not match the original document. Please upload the same PDF.");
        }

        String extractedText = extractTextOrThrow(fileBytes);

        // Reset state to PROCESSING
        doc.setStatus(DocumentStatus.PROCESSING);
        doc.setRetryable(false);
        doc.setErrorMessage(null);
        doc = documentRepository.save(doc);

        User user = doc.getUser();
        return processDocument(doc, extractedText, user);
    }

    // -------------------------------------------------------------------------
    // Shared processing pipeline: PDF → PDFBox → Gemini → validation → DB
    // -------------------------------------------------------------------------

    private DocumentResponse processDocument(Document doc, String extractedText, User user) {
        ExtractedPurchaseOrder extractedPO;
        try {
            extractedPO = llmExtractionService.extract(extractedText);
        } catch (Exception e) {
            log.error("Gemini extraction failed for document {}: {}", doc.getId(), e.getMessage(), e);
            boolean retryable = isRetryableException(e);
            String userMsg = retryable
                    ? "AI extraction failed due to a temporary service issue. Please retry."
                    : "AI could not extract structured data from this document. It may be a scanned image or an unsupported format.";
            return failDocument(doc, retryable, userMsg);
        }

        try {
            PurchaseOrder po = new PurchaseOrder();
            po.setUser(user);
            po.setDocument(doc);
            po.setPoNumber(extractedPO.poNumber());
            po.setSupplier(extractedPO.vendorName());
            po.setPaymentTerms(extractedPO.paymentTerms());
            po.setTotal(extractedPO.totalAmount() != null
                    ? BigDecimal.valueOf(extractedPO.totalAmount()) : null);
            po.setCreatedAt(LocalDateTime.now());
            po.setOrderDate(parseDate(extractedPO.poDate()));

            if (extractedPO.items() != null) {
                for (ExtractedPurchaseOrder.ExtractedLineItem lineItem : extractedPO.items()) {
                    PurchaseOrderItem item = new PurchaseOrderItem();
                    item.setPurchaseOrder(po);
                    item.setDescription(lineItem.description());
                    item.setQuantity(lineItem.quantity() != null
                            ? BigDecimal.valueOf(lineItem.quantity()) : null);
                    item.setUnitPrice(lineItem.unitPrice() != null
                            ? BigDecimal.valueOf(lineItem.unitPrice()) : null);
                    item.setTotalPrice(lineItem.totalPrice() != null
                            ? BigDecimal.valueOf(lineItem.totalPrice()) : null);
                    po.getItems().add(item);
                }
            }

            PurchaseOrder savedPo = purchaseOrderRepository.save(po);
            doc.setStatus(DocumentStatus.COMPLETED);
            doc.setRetryable(false);
            doc.setErrorMessage(null);
            documentRepository.save(doc);
            return toResponse(doc, extractedText, extractedPO, savedPo.getId(), false);
        } catch (Exception e) {
            log.error("Failed to persist purchase order for document {}: {}", doc.getId(), e.getMessage(), e);
            return failDocument(doc, true, "A temporary error occurred while saving the purchase order. Please retry.");
        }
    }

    // -------------------------------------------------------------------------
    // Validation helpers (no DB side-effects)
    // -------------------------------------------------------------------------

    private byte[] validateAndReadBytes(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "No file was provided. Please select a PDF to upload.");
        }

        String contentType = file.getContentType();
        String originalName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "";
        boolean isPdf = "application/pdf".equalsIgnoreCase(contentType)
                || originalName.toLowerCase().endsWith(".pdf");

        if (!isPdf) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Only PDF files are accepted. Please upload a valid PDF document.");
        }

        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "File is too large. The maximum allowed size is 10 MB.");
        }

        byte[] fileBytes;
        try {
            fileBytes = file.getBytes();
        } catch (IOException e) {
            log.warn("Could not read uploaded file bytes: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "The file could not be read. It may be corrupt or empty. Please try uploading again.");
        }

        if (fileBytes.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "The uploaded file is empty. Please upload a valid PDF document.");
        }

        // Validate PDF structure and page count
        try (PDDocument pdDoc = Loader.loadPDF(fileBytes)) {
            int pages = pdDoc.getNumberOfPages();
            if (pages == 0) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "The PDF contains no pages. Please upload a valid PDF document.");
            }
            if (pages > MAX_PAGES) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "The PDF has " + pages + " pages. The maximum allowed is " + MAX_PAGES + " pages.");
            }
        } catch (ResponseStatusException rse) {
            throw rse;
        } catch (IOException e) {
            log.warn("PDFBox could not load PDF for validation: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "The file does not appear to be a valid or readable PDF. It may be corrupt or password-protected.");
        }

        return fileBytes;
    }

    private String extractTextOrThrow(byte[] fileBytes) {
        String text;
        try (PDDocument pdDocument = Loader.loadPDF(fileBytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            text = stripper.getText(pdDocument).strip();
        } catch (IOException e) {
            log.warn("PDFBox text extraction failed: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "The PDF could not be parsed. It may be corrupt, password-protected, or an unsupported format.");
        }

        if (text.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "No text could be extracted from this PDF. It may be a scanned image-only document and is not supported at this time.");
        }

        return text;
    }

    private DocumentResponse failDocument(Document doc, boolean retryable, String userMessage) {
        doc.setStatus(DocumentStatus.FAILED);
        doc.setRetryable(retryable);
        doc.setErrorMessage(userMessage);
        documentRepository.save(doc);
        return toResponse(doc, null, null, null, false);
    }

    private boolean isRetryableException(Exception e) {
        String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        return msg.contains("timeout") || msg.contains("timed out")
                || msg.contains("connection") || msg.contains("network")
                || msg.contains("503") || msg.contains("429") || msg.contains("500")
                || msg.contains("unavailable") || msg.contains("socket");
    }

    private String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private LocalDate parseDate(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) return null;
        String[] patterns = {"yyyy-MM-dd", "dd/MM/yyyy", "MM/dd/yyyy", "dd-MM-yyyy", "dd MMM yyyy", "MMM dd, yyyy"};
        for (String pattern : patterns) {
            try {
                return LocalDate.parse(dateStr.trim(), DateTimeFormatter.ofPattern(pattern));
            } catch (DateTimeParseException ignored) {}
        }
        return null;
    }

    private DocumentResponse toResponse(Document doc, String extractedText, ExtractedPurchaseOrder extractedPO,
                                         Long purchaseOrderId, boolean duplicate) {
        return new DocumentResponse(
                doc.getId(),
                doc.getUser().getId(),
                purchaseOrderId,
                doc.getFileName(),
                doc.getFileType(),
                doc.getStatus(),
                doc.getUploadedAt(),
                extractedText,
                extractedPO,
                duplicate,
                doc.isRetryable(),
                doc.getErrorMessage()
        );
    }
}
