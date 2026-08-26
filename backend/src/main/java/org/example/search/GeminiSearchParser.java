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
              "status":          "COMPLETED" | "PROCESSING" | "FAILED" | null,
              "sortBy":          "date" | "amount" | "poNumber" | "supplier" | null,
              "sortDir":         "asc" | "desc" | null
            }
            
            Rules:
            - status must be exactly one of: COMPLETED, PROCESSING, FAILED, or null
            - sortBy must be exactly one of: date, amount, poNumber, supplier, or null
            - sortDir must be "asc" or "desc" or null
            - dates must be ISO format YYYY-MM-DD
            - amounts are plain numbers (no currency symbols)
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
                    0, 20
            );
        }

        private static LocalDate safeDate(String s) {
            if (s == null || s.isBlank()) return null;
            try { return LocalDate.parse(s); } catch (Exception e) { return null; }
        }

        private static String sanitizeStatus(String s) {
            if (s == null) return null;
            return switch (s.toUpperCase()) {
                case "COMPLETED", "PROCESSED" -> "COMPLETED";
                case "PROCESSING", "PENDING" -> "PROCESSING";
                case "FAILED", "ERROR" -> "FAILED";
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

