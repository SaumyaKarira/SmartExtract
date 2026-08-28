package org.example.document;

import org.example.entity.DocumentStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DocumentRepository documentRepository;

    public DashboardController(DocumentRepository documentRepository) {
        this.documentRepository = documentRepository;
    }

    @GetMapping("/stats")
    public DashboardStats stats(Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();

        // "completed" covers both clean completions and auto-corrected ones
        long completed = documentRepository.countByUserIdAndStatus(userId, DocumentStatus.COMPLETED)
                + documentRepository.countByUserIdAndStatus(userId, DocumentStatus.COMPLETED_WITH_CORRECTIONS);

        // "needsReview" covers only NEEDS_REVIEW (data-quality issues)
        long needsReview = documentRepository.countByUserIdAndStatus(userId, DocumentStatus.NEEDS_REVIEW);

        long failed = documentRepository.countByUserIdAndStatus(userId, DocumentStatus.FAILED);

        // Total excludes PROCESSING documents — they are transient and should not inflate
        // the count until processing completes or fails (consistent with the PO list view).
        long total = completed + needsReview + failed;

        return new DashboardStats(
                total,
                completed,
                needsReview,
                failed
        );
    }
}
