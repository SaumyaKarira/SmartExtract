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

@Service
@Transactional(readOnly = true)
public class DocumentService {

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

    @Transactional
    public DocumentResponse upload(MultipartFile file, Long userId) {
        String contentType = file.getContentType();
        String originalName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "";

        boolean isPdf = "application/pdf".equalsIgnoreCase(contentType)
                || originalName.toLowerCase().endsWith(".pdf");

        if (!isPdf) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only PDF files are accepted");
        }

        // Read bytes once — used for hash and PDFBox
        byte[] fileBytes;
        try {
            fileBytes = file.getBytes();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read file: " + e.getMessage());
        }

        // Compute SHA-256 hash from actual bytes
        String fileHash = sha256Hex(fileBytes);

        // Duplicate check — same user, same file content
        var existing = documentRepository.findByUserIdAndFileHash(userId, fileHash);
        if (existing.isPresent()) {
            Document existingDoc = existing.get();
            Long existingPoId = existingDoc.getPurchaseOrder() != null
                    ? existingDoc.getPurchaseOrder().getId() : null;
            return toResponse(existingDoc, null, null, existingPoId, true);
        }

        // Extract text via PDFBox
        String extractedText;
        try (PDDocument pdDocument = Loader.loadPDF(fileBytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            extractedText = stripper.getText(pdDocument).strip();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not parse PDF: " + e.getMessage());
        }

        if (extractedText.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "PDF contains no extractable text. It may be a scanned image-only document.");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        // Save document with PROCESSING status + hash
        Document doc = new Document();
        doc.setUser(user);
        doc.setFileName(originalName);
        doc.setFileType("application/pdf");
        doc.setStatus(DocumentStatus.PROCESSING);
        doc.setUploadedAt(LocalDateTime.now());
        doc.setFileHash(fileHash);
        doc = documentRepository.save(doc);

        // LLM extraction — on failure mark FAILED and return
        ExtractedPurchaseOrder extractedPO;
        try {
            extractedPO = llmExtractionService.extract(extractedText);
        } catch (Exception e) {
            doc.setStatus(DocumentStatus.FAILED);
            documentRepository.save(doc);
            return toResponse(doc, extractedText, null, null, false);
        }

        // Persist PurchaseOrder + items
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
            documentRepository.save(doc);
            return toResponse(doc, extractedText, extractedPO, savedPo.getId(), false);
        } catch (Exception e) {
            doc.setStatus(DocumentStatus.FAILED);
            documentRepository.save(doc);
            return toResponse(doc, extractedText, null, null, false);
        }
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
                duplicate
        );
    }
}
