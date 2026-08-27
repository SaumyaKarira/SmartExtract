package org.example.search;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.Client;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Uses Gemini to convert a natural-language search query into a SearchQuery DTO.
 * Gemini is ONLY asked to return structured criteria — never SQL or code.
 */
@Service
public class GeminiSearchParser {

    private final Client geminiClient;
    private final String model;
    private final ObjectMapper objectMapper;

    private static final String PROMPT = """
            Convert the following natural-language purchase order search query into a JSON object.
            Return ONLY valid JSON — no markdown, no code fences, no explanation.

            JSON schema (all fields optional/nullable):
            {
              "poNumber":        string or null,
              "supplier":        string or null,
              "itemDescription": string or null,
              "minAmount":       number or null,
              "maxAmount":       number or null,
              "dateFrom":        "YYYY-MM-DD" or null,
              "dateTo":          "YYYY-MM-DD" or null,
              "status":          "COMPLETED" | "COMPLETED_WITH_CORRECTIONS" | "NEEDS_REVIEW" | "PROCESSING" | "FAILED" | null,
              "sortBy":          "date" | "amount" | "poNumber" | "supplier" | null,
              "sortDir":         "asc" | "desc" | null
            }

            Rules:
            - status must be exactly one of: COMPLETED, COMPLETED_WITH_CORRECTIONS, NEEDS_REVIEW, PROCESSING, FAILED, or null
            - Use COMPLETED for: "completed", "done", "processed", "finished" (no corrections)
            - Use COMPLETED_WITH_CORRECTIONS for: "corrected", "with corrections", "auto-corrected", "fixed", "corrected data"
            - Use NEEDS_REVIEW for: "needs review", "flagged", "review required", "needs attention"
            - Use FAILED for: "failed", "error", "errors", "failed data"
            - For amount filters: "above/over/more than X" → minAmount = X; "below/under/less than X" → maxAmount = X
            - For sorting: "largest/biggest/highest" → sortBy = "amount", sortDir = "desc"
            - For sorting: "smallest/lowest/cheapest" → sortBy = "amount", sortDir = "asc"
            - For sorting: "recent/latest/newest" → sortBy = "date", sortDir = "desc"
            - For sorting: "oldest/earliest" → sortBy = "date", sortDir = "asc"
            - dates must be ISO format YYYY-MM-DD
            - amounts are plain numbers — strip any currency symbols (₹, $, etc.) and commas
            - Never generate SQL, code, or explanations

            Query: "%s"
            """;

    public GeminiSearchParser(
            @Value("${app.gemini.api-key}") String apiKey,
            @Value("${app.gemini.model}") String model,
            ObjectMapper objectMapper) {
        this.geminiClient = Client.builder().apiKey(apiKey).build();
        this.model = model;
        this.objectMapper = objectMapper;
    }

    public SearchQuery parse(String query) {
        String prompt = PROMPT.formatted(query);
        GenerateContentResponse response = geminiClient.models.generateContent(
                model, prompt, GenerateContentConfig.builder().build());

        String raw = response.text();
        if (raw == null || raw.isBlank()) throw new RuntimeException("Gemini returned empty response");
        raw = raw.strip();
        if (raw.startsWith("```")) {
            raw = raw.replaceAll("^```(?:json)?\\s*", "").replaceAll("```\\s*$", "").strip();
        }

        try {
            GeminiSearchDto dto = objectMapper.readValue(raw, GeminiSearchDto.class);
            return dto.toSearchQuery();
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse Gemini search response: " + e.getMessage(), e);
        }
    }

    /** Internal DTO matching what Gemini returns — tolerates extra fields */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GeminiSearchDto(
            String poNumber,
            String supplier,
            String itemDescription,
            Double minAmount,
            Double maxAmount,
            String dateFrom,
            String dateTo,
            String status,
            String sortBy,
            String sortDir
    ) {
        SearchQuery toSearchQuery() {
            return new SearchQuery(
                    poNumber,
                    supplier,
                    itemDescription,
                    minAmount != null ? BigDecimal.valueOf(minAmount) : null,
                    maxAmount != null ? BigDecimal.valueOf(maxAmount) : null,
                    safeDate(dateFrom),
                    safeDate(dateTo),
                    sanitizeStatus(status),
                    sanitizeSortBy(sortBy),
                    sanitizeSortDir(sortDir),
                    0, 20, false // Gemini NL search uses strict > / <
            );
        }

        private static LocalDate safeDate(String s) {
            if (s == null || s.isBlank()) return null;
            try { return LocalDate.parse(s); } catch (Exception e) { return null; }
        }

        private static String sanitizeStatus(String s) {
            if (s == null) return null;
            return switch (s.toUpperCase()) {
                case "COMPLETED", "PROCESSED", "DONE", "FINISHED" -> "COMPLETED_ANY";
                case "COMPLETED_WITH_CORRECTIONS", "CORRECTED", "FIXED", "WITH_CORRECTIONS" -> "COMPLETED_WITH_CORRECTIONS";
                case "NEEDS_REVIEW", "REVIEW", "FLAGGED"           -> "NEEDS_REVIEW";
                case "PROCESSING", "PENDING"                        -> "PROCESSING";
                case "FAILED", "ERROR"                              -> "FAILED";
                default -> null;
            };
        }

        private static String sanitizeSortBy(String s) {
            if (s == null) return null;
            return switch (s.toLowerCase()) {
                case "date", "amount", "ponumber", "supplier" -> s.toLowerCase();
                default -> null;
            };
        }

        private static String sanitizeSortDir(String s) {
            if (s == null) return null;
            return "asc".equalsIgnoreCase(s) ? "asc" : "desc";
        }
    }
}

