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
        return new DashboardStats(
                documentRepository.countByUserId(userId),
                documentRepository.countByUserIdAndStatus(userId, DocumentStatus.COMPLETED),
                documentRepository.countByUserIdAndStatus(userId, DocumentStatus.PROCESSING),
                documentRepository.countByUserIdAndStatus(userId, DocumentStatus.FAILED)
        );
    }
}

