package org.example.document;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.example.auth.UserRepository;
import org.example.entity.Document;
import org.example.entity.DocumentStatus;
import org.example.entity.PurchaseOrder;
import org.example.entity.PurchaseOrderItem;
import org.example.entity.User;
import org.example.purchaseorder.PurchaseOrderRepository;
import org.example.validation.PoValidationService;
import org.example.validation.ValidationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

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
import java.util.Map;
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
    private final PoValidationService poValidationService;
    private final ObjectMapper objectMapper;

    public DocumentService(DocumentRepository documentRepository,
                           UserRepository userRepository,
                           LlmExtractionService llmExtractionService,
                           PurchaseOrderRepository purchaseOrderRepository,
                           PoValidationService poValidationService,
                           ObjectMapper objectMapper) {
        this.documentRepository = documentRepository;
        this.userRepository = userRepository;
        this.llmExtractionService = llmExtractionService;
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.poValidationService = poValidationService;
        this.objectMapper = objectMapper;
    }

    public List<DocumentResponse> getByUser(Long userId) {
        return documentRepository.findByUserId(userId).stream()
                .map(doc -> toResponse(doc, null, null, null, false))
                .toList();
    }

    // -------------------------------------------------------------------------
    // Delete
    // -------------------------------------------------------------------------

    @Transactional
    public void deleteDocument(Long documentId, Long userId) {
        log.info("Delete requested: docId={} userId={}", documentId, userId);

        Document doc = documentRepository.findById(documentId)
                .orElseThrow(() -> {
                    log.warn("Delete failed — document not found: docId={} userId={}", documentId, userId);
                    return new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found.");
                });

        // Ownership check — never trust userId from the request body
        if (!doc.getUser().getId().equals(userId)) {
            log.warn("Delete rejected — unauthorized: docId={} requestingUserId={}", documentId, userId);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "You are not authorised to delete this document.");
        }

        // CascadeType.ALL on Document.purchaseOrder and PurchaseOrder.items means
        // deleting the Document automatically deletes the PO and all its items.
        // Everything runs inside the @Transactional boundary — nothing is partially deleted.
        documentRepository.delete(doc);
        log.info("Delete succeeded: docId={} userId={}", documentId, userId);
    }

    // -------------------------------------------------------------------------
    // Upload
    // -------------------------------------------------------------------------

    @Transactional
    public DocumentResponse upload(MultipartFile file, Long userId) {
        long uploadStart = System.currentTimeMillis();
        String originalName = file != null ? file.getOriginalFilename() : null;
        log.info("Upload started: userId={} fileName={} declaredSize={}",
                userId, originalName, file != null ? file.getSize() : 0);

        // 1. Validate before creating any DB record
        byte[] fileBytes = validateAndReadBytes(file);
        String fileHash = sha256Hex(fileBytes);
        String detectedContentType = detectFileType(file);
        log.debug("Upload validation passed: userId={} fileName={} detectedType={} sizeBytes={}",
                userId, originalName, detectedContentType, fileBytes.length);

        // 2. Duplicate check
        Optional<Document> existing = documentRepository.findByUserIdAndFileHash(userId, fileHash);
        if (existing.isPresent()) {
            Document existingDoc = existing.get();
            Long existingPoId = existingDoc.getPurchaseOrder() != null
                    ? existingDoc.getPurchaseOrder().getId() : null;
            log.info("Duplicate upload detected: userId={} fileName={} existingDocId={} existingPoId={} elapsedMs={}",
                    userId, originalName, existingDoc.getId(), existingPoId,
                    System.currentTimeMillis() - uploadStart);
            return toResponse(existingDoc, null, null, existingPoId, true);
        }

        // 3. Extract text (still validation — no DB record yet)
        String extractedText = extractTextOrThrow(fileBytes, userId, originalName);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        // 4. Create DB record only after file is confirmed valid
        String defaultFileName = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                .equals(detectedContentType) ? "upload.docx" : "upload.pdf";
        Document doc = new Document();
        doc.setUser(user);
        doc.setFileName(file.getOriginalFilename() != null ? file.getOriginalFilename() : defaultFileName);
        doc.setFileType(detectedContentType != null ? detectedContentType : "application/pdf");
        doc.setStatus(DocumentStatus.PROCESSING);
        doc.setUploadedAt(LocalDateTime.now());
        doc.setFileHash(fileHash);
        doc = documentRepository.save(doc);
        log.info("Document record created: docId={} userId={} fileName={} fileType={}",
                doc.getId(), userId, doc.getFileName(), doc.getFileType());

        return processDocument(doc, extractedText, user, uploadStart);
    }

    // -------------------------------------------------------------------------
    // Retry
    // -------------------------------------------------------------------------

    @Transactional
    public DocumentResponse retry(Long documentId, Long userId, MultipartFile file) {
        long retryStart = System.currentTimeMillis();
        log.info("Retry started: docId={} userId={}", documentId, userId);

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
                    "The uploaded file does not match the original document. Please upload the same file.");
        }

        String extractedText = extractTextOrThrow(fileBytes, userId, doc.getFileName());

        // Reset state to PROCESSING
        doc.setStatus(DocumentStatus.PROCESSING);
        doc.setRetryable(false);
        doc.setErrorMessage(null);
        doc = documentRepository.save(doc);
        log.info("Retry: document reset to PROCESSING: docId={} userId={}", doc.getId(), userId);

        User user = doc.getUser();
        return processDocument(doc, extractedText, user, retryStart);
    }

    // -------------------------------------------------------------------------
    // Shared processing pipeline: text extraction → Gemini → validation → DB
    // -------------------------------------------------------------------------

    private DocumentResponse processDocument(Document doc, String extractedText, User user, long pipelineStartMs) {
        long docId = doc.getId();
        long userId = user.getId();

        // ── Gemini extraction ─────────────────────────────────────────────────
        log.info("Gemini extraction starting: docId={} userId={}", docId, userId);
        long geminiStart = System.currentTimeMillis();
        ExtractedPurchaseOrder extractedPO;
        try {
            extractedPO = llmExtractionService.extract(extractedText);
            log.info("Gemini extraction succeeded: docId={} userId={} durationMs={}",
                    docId, userId, System.currentTimeMillis() - geminiStart);
        } catch (Exception e) {
            long geminiMs = System.currentTimeMillis() - geminiStart;
            boolean retryable = isRetryableException(e);
            log.error("Gemini extraction failed: docId={} userId={} retryable={} durationMs={} exceptionClass={}",
                    docId, userId, retryable, geminiMs, e.getClass().getSimpleName());
            String userMsg = retryable
                    ? "AI extraction failed due to a temporary service issue. Please retry."
                    : "AI could not extract structured data from this document. It may be a scanned image or an unsupported format.";
            DocumentResponse resp = failDocument(doc, retryable, userMsg);
            log.info("Document processing finished: docId={} userId={} finalStatus=FAILED retryable={} totalDurationMs={}",
                    docId, userId, retryable, System.currentTimeMillis() - pipelineStartMs);
            return resp;
        }

        // ── Deterministic validation ──────────────────────────────────────────
        log.debug("Validation starting: docId={} userId={}", docId, userId);
        ValidationResult validationResult;
        try {
            validationResult = poValidationService.validate(extractedPO);
            log.info("Validation outcome: docId={} userId={} outcome={} corrections={} reviewReasons={}",
                    docId, userId, validationResult.outcome(),
                    validationResult.corrections().size(),
                    validationResult.reviewReasons().size());
        } catch (Exception e) {
            log.error("Validation service error: docId={} userId={} exceptionClass={}",
                    docId, userId, e.getClass().getSimpleName());
            DocumentResponse resp = failDocument(doc, true, "A temporary error occurred during validation. Please retry.");
            log.info("Document processing finished: docId={} userId={} finalStatus=FAILED retryable=true totalDurationMs={}",
                    docId, userId, System.currentTimeMillis() - pipelineStartMs);
            return resp;
        }

        // ── Persist purchase order ──���─────────────────────────────────────────
        List<ValidationResult.Correction> corrections = validationResult.corrections();
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
                for (int i = 0; i < extractedPO.items().size(); i++) {
                    ExtractedPurchaseOrder.ExtractedLineItem lineItem = extractedPO.items().get(i);
                    PurchaseOrderItem item = new PurchaseOrderItem();
                    item.setPurchaseOrder(po);
                    item.setDescription(lineItem.description());
                    item.setQuantity(lineItem.quantity() != null
                            ? BigDecimal.valueOf(lineItem.quantity()) : null);
                    item.setUnitPrice(lineItem.unitPrice() != null
                            ? BigDecimal.valueOf(lineItem.unitPrice()) : null);

                    // Apply correction for totalPrice if present
                    Double effectiveTotalPrice = lineItem.totalPrice();
                    final String correctionKey = "items[" + i + "].totalPrice";
                    for (ValidationResult.Correction c : corrections) {
                        if (correctionKey.equals(c.field())) {
                            effectiveTotalPrice = c.correctedValue();
                            break;
                        }
                    }
                    item.setTotalPrice(effectiveTotalPrice != null
                            ? BigDecimal.valueOf(effectiveTotalPrice) : null);
                    po.getItems().add(item);
                }
            }

            // Persist validation metadata as JSON
            if (!corrections.isEmpty()) {
                po.setValidationCorrections(toJson(corrections));
            }
            if (!validationResult.reviewReasons().isEmpty()) {
                po.setValidationReviewReasons(toJson(validationResult.reviewReasons()));
            }

            PurchaseOrder savedPo = purchaseOrderRepository.save(po);
            doc.setStatus(validationResult.outcome());
            doc.setRetryable(false);
            doc.setErrorMessage(null);
            documentRepository.save(doc);

            log.info("Document processing finished: docId={} userId={} finalStatus={} poId={} lineItems={} totalDurationMs={}",
                    docId, userId, validationResult.outcome(), savedPo.getId(),
                    po.getItems().size(), System.currentTimeMillis() - pipelineStartMs);

            return toResponse(doc, extractedText, extractedPO, savedPo.getId(), false);
        } catch (Exception e) {
            log.error("Purchase order persistence failed: docId={} userId={} exceptionClass={}",
                    docId, userId, e.getClass().getSimpleName());
            DocumentResponse resp = failDocument(doc, true, "A temporary error occurred while saving the purchase order. Please retry.");
            log.info("Document processing finished: docId={} userId={} finalStatus=FAILED retryable=true totalDurationMs={}",
                    docId, userId, System.currentTimeMillis() - pipelineStartMs);
            return resp;
        }
    }

    // -------------------------------------------------------------------------
    // Validation helpers (no DB side-effects)
    // -------------------------------------------------------------------------

    // Determine if file is PDF or DOCX; returns detected content type string
    private String detectFileType(MultipartFile file) {
        String contentType = file.getContentType() != null ? file.getContentType().toLowerCase() : "";
        String originalName = file.getOriginalFilename() != null ? file.getOriginalFilename().toLowerCase() : "";
        if ("application/pdf".equals(contentType) || originalName.endsWith(".pdf")) {
            return "application/pdf";
        }
        if ("application/vnd.openxmlformats-officedocument.wordprocessingml.document".equals(contentType)
                || originalName.endsWith(".docx")) {
            return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        }
        return null;
    }

    private byte[] validateAndReadBytes(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "No file was provided. Please select a PDF or DOCX file to upload.");
        }

        String detectedType = detectFileType(file);
        if (detectedType == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Only PDF and DOCX files are accepted. Please upload a valid PDF or DOCX document.");
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
                    "The uploaded file is empty. Please upload a valid PDF or DOCX document.");
        }

        if ("application/pdf".equals(detectedType)) {
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
        } else {
            // Validate DOCX structure
            try {
                XWPFDocument docx = new XWPFDocument(new java.io.ByteArrayInputStream(fileBytes));
                try {
                    docx.getParagraphs(); // parse document structure
                } finally {
                    try { docx.close(); } catch (Exception ignored) {}
                }
            } catch (ResponseStatusException rse) {
                throw rse;
            } catch (Exception e) {
                log.warn("Apache POI could not load DOCX for validation: {}", e.getMessage());
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "The file does not appear to be a valid or readable DOCX. It may be corrupt or an unsupported format.");
            }
        }

        return fileBytes;
    }

    /**
     * Extract plain text from PDF or DOCX bytes.
     * userId and fileName are used only for safe logging — document content is never logged.
     */
    private String extractTextOrThrow(byte[] fileBytes, Long userId, String fileName) {
        // Detect type by magic bytes: PDF starts with %PDF
        boolean isPdf = fileBytes.length >= 4
                && fileBytes[0] == '%' && fileBytes[1] == 'P' && fileBytes[2] == 'D' && fileBytes[3] == 'F';

        String fileType = isPdf ? "PDF" : "DOCX";
        log.debug("Text extraction starting: userId={} fileName={} fileType={} sizeBytes={}",
                userId, fileName, fileType, fileBytes.length);
        long extractStart = System.currentTimeMillis();

        String text;
        if (isPdf) {
            try (PDDocument pdDocument = Loader.loadPDF(fileBytes)) {
                PDFTextStripper stripper = new PDFTextStripper();
                text = stripper.getText(pdDocument).strip();
            } catch (IOException e) {
                log.warn("PDFBox text extraction failed: userId={} fileName={} durationMs={} reason={}",
                        userId, fileName, System.currentTimeMillis() - extractStart,
                        e.getClass().getSimpleName());
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "The PDF could not be parsed. It may be corrupt, password-protected, or an unsupported format.");
            }
            if (text.isBlank()) {
                log.warn("PDF produced no extractable text: userId={} fileName={}", userId, fileName);
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "No text could be extracted from this PDF. It may be a scanned image-only document and is not supported at this time.");
            }
        } else {
            // DOCX — extract via Apache POI XWPFWordExtractor.
            // Note: XWPFWordExtractor.close() also closes the underlying XWPFDocument,
            // so we only wrap the extractor (not the document) in try-with-resources
            // to avoid double-close exceptions escaping the block.
            try {
                XWPFDocument docx = new XWPFDocument(new java.io.ByteArrayInputStream(fileBytes));
                try (XWPFWordExtractor extractor = new XWPFWordExtractor(docx)) {
                    text = extractor.getText().strip();
                }
            } catch (Exception e) {
                log.warn("Apache POI DOCX text extraction failed: userId={} fileName={} durationMs={} reason={}",
                        userId, fileName, System.currentTimeMillis() - extractStart,
                        e.getClass().getSimpleName());
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "The DOCX file could not be parsed. It may be corrupt or an unsupported format.");
            }
            if (text.isBlank()) {
                log.warn("DOCX produced no extractable text: userId={} fileName={}", userId, fileName);
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "No text could be extracted from this DOCX. The document appears to be empty.");
            }
        }

        log.debug("Text extraction succeeded: userId={} fileName={} fileType={} charCount={} durationMs={}",
                userId, fileName, fileType, text.length(), System.currentTimeMillis() - extractStart);
        return text;
    }

    private DocumentResponse failDocument(Document doc, boolean retryable, String userMessage) {
        doc.setStatus(DocumentStatus.FAILED);
        doc.setRetryable(retryable);
        doc.setErrorMessage(userMessage);
        documentRepository.save(doc);
        return toResponse(doc, null, null, null, false);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialise validation metadata to JSON: {}", e.getMessage());
            return null;
        }
    }

    private boolean isRetryableException(Exception e) {
        // Typed exceptions from GeminiCallExecutor take priority
        if (e instanceof GeminiTransientException) return true;
        if (e instanceof GeminiPermanentException) return false;
        // Fallback for any other wrapped exception
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
