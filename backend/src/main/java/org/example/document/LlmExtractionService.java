package org.example.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.Client;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class LlmExtractionService {

    private static final Logger log = LoggerFactory.getLogger(LlmExtractionService.class);

    private final Client geminiClient;
    private final String model;
    private final ObjectMapper objectMapper;

    private static final String PROMPT_TEMPLATE = """
            Extract the following structured data from the Purchase Order text below.
            Return ONLY a valid JSON object with no markdown, no code fences, no explanation.

            Date rules (important):
            - Always return dates in ISO 8601 format: yyyy-MM-dd (e.g. 2026-08-12 for 12 August 2026).
            - Convert any date format found in the document to yyyy-MM-dd.
              Examples: "12 Aug 2026" → "2026-08-12", "August 12, 2026" → "2026-08-12",
              "12/08/2026" → "2026-08-12", "8/12/2026" → use document context to determine
              day vs month order; default to dd/MM/yyyy (day first) if ambiguous.
            - If no date can be determined, return null.

            Number rules:
            - All monetary values must be plain numbers with no currency symbols or commas.
              Example: 2,50,000 → 250000.

            Required JSON schema:
            {
              "poNumber":     "string or null",
              "vendorName":   "string or null",
              "poDate":       "yyyy-MM-dd or null",
              "deliveryDate": "yyyy-MM-dd or null",
              "paymentTerms": "string or null",
              "currency":     "string or null (e.g. INR, USD)",
              "subtotal":     number or null,
              "tax":          number or null,
              "totalAmount":  number or null,
              "items": [
                {
                  "description": "string or null",
                  "quantity":    number or null,
                  "unitPrice":   number or null,
                  "totalPrice":  number or null
                }
              ]
            }

            Purchase Order text:
            ---
            %s
            ---
            """;

    public LlmExtractionService(
            @Value("${app.gemini.api-key}") String apiKey,
            @Value("${app.gemini.model}") String model,
            ObjectMapper objectMapper) {
        this.geminiClient = Client.builder().apiKey(apiKey).build();
        this.model = model;
        this.objectMapper = objectMapper;
    }

    public ExtractedPurchaseOrder extract(String pdfText) {
        String prompt = PROMPT_TEMPLATE.formatted(pdfText);

        GenerateContentResponse response = geminiClient.models.generateContent(
                model,
                prompt,
                GenerateContentConfig.builder().build()
        );

        String rawText = response.text();
        if (rawText == null || rawText.isBlank()) {
            log.warn("Gemini returned an empty response for po-extraction");
            throw new RuntimeException("Gemini returned an empty response");
        }
        String raw = rawText.strip();

        // Strip markdown code fences if model returns them despite instructions
        if (raw.startsWith("```")) {
            raw = raw.replaceAll("^```(?:json)?\\s*", "").replaceAll("```\\s*$", "").strip();
        }

        try {
            ExtractedPurchaseOrder result = objectMapper.readValue(raw, ExtractedPurchaseOrder.class);
            log.debug("Gemini response parsed successfully: itemCount={}",
                    result.items() != null ? result.items().size() : 0);
            return result;
        } catch (Exception e) {
            log.warn("Gemini response JSON parse failed: reason={}", e.getClass().getSimpleName());
            throw new RuntimeException("Failed to parse Gemini response as JSON: " + e.getMessage(), e);
        }
    }
}

