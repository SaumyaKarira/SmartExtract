package org.example.document;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @GetMapping
    public List<DocumentResponse> listByUser(Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        return documentService.getByUser(userId);
    }

    @PostMapping("/upload")
    public ResponseEntity<DocumentResponse> upload(
            @RequestParam("file") MultipartFile file,
            Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        DocumentResponse response = documentService.upload(file, userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable("id") Long documentId,
            Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        documentService.deleteDocument(documentId, userId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/retry")
    public ResponseEntity<DocumentResponse> retry(
            @PathVariable("id") Long documentId,
            @RequestParam("file") MultipartFile file,
            Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        DocumentResponse response = documentService.retry(documentId, userId, file);
        return ResponseEntity.ok(response);
    }
}
