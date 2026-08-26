package org.example.export;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@RestController
@RequestMapping("/api/export")
public class ExportController {

    private final ExportService exportService;

    public ExportController(ExportService exportService) {
        this.exportService = exportService;
    }

    @GetMapping("/purchase-orders")
    public ResponseEntity<byte[]> export(
            @RequestParam(defaultValue = "csv") String format,
            Authentication authentication) throws IOException {

        Long userId = (Long) authentication.getPrincipal();
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));

        return switch (format.toLowerCase()) {
            case "xlsx" -> {
                byte[] bytes = exportService.exportXlsx(userId);
                yield ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_DISPOSITION,
                                "attachment; filename=\"purchase-orders-" + date + ".xlsx\"")
                        .contentType(MediaType.parseMediaType(
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                        .body(bytes);
            }
            case "pdf" -> {
                byte[] bytes = exportService.exportPdf(userId);
                yield ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_DISPOSITION,
                                "attachment; filename=\"purchase-orders-" + date + ".pdf\"")
                        .contentType(MediaType.APPLICATION_PDF)
                        .body(bytes);
            }
            default -> {
                byte[] bytes = exportService.exportCsv(userId);
                yield ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_DISPOSITION,
                                "attachment; filename=\"purchase-orders-" + date + ".csv\"")
                        .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                        .body(bytes);
            }
        };
    }
}

