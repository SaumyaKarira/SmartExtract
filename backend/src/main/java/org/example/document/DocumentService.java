package org.example.document;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.example.auth.UserRepository;
import org.example.entity.Document;
import org.example.entity.DocumentStatus;
import org.example.entity.User;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;

@Service
@Transactional(readOnly = true)
public class DocumentService {

    private final DocumentRepository documentRepository;
    private final UserRepository userRepository;
    private final LlmExtractionService llmExtractionService;

    public DocumentService(DocumentRepository documentRepository,
                           UserRepository userRepository,
                           LlmExtractionService llmExtractionService) {
        this.documentRepository = documentRepository;
        this.userRepository = userRepository;
        this.llmExtractionService = llmExtractionService;
    }

    public List<DocumentResponse> getByUser(Long userId) {
        return documentRepository.findByUserId(userId).stream()
                .map(doc -> toResponse(doc, null, null))
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

        String extractedText;
        try (PDDocument pdDocument = Loader.loadPDF(file.getBytes())) {
            PDFTextStripper stripper = new PDFTextStripper();
            extractedText = stripper.getText(pdDocument).strip();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not parse PDF: " + e.getMessage());
        }

        if (extractedText.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "PDF contains no extractable text. It may be a scanned image-only document.");
        }

        // LLM extraction
        ExtractedPurchaseOrder extractedPO = llmExtractionService.extract(extractedText);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        Document doc = new Document();
        doc.setUser(user);
        doc.setFileName(originalName);
        doc.setFileType("application/pdf");
        doc.setStatus(DocumentStatus.PROCESSING);
        doc.setUploadedAt(LocalDateTime.now());

        return toResponse(documentRepository.save(doc), extractedText, extractedPO);
    }

    private DocumentResponse toResponse(Document doc, String extractedText, ExtractedPurchaseOrder extractedPO) {
        return new DocumentResponse(
                doc.getId(),
                doc.getUser().getId(),
                doc.getFileName(),
                doc.getFileType(),
                doc.getStatus(),
                doc.getUploadedAt(),
                extractedText,
                extractedPO
        );
    }
}
