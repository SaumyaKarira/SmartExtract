package org.example.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.Client;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class LlmExtractionService {

    private final Client geminiClient;
    private final String model;
    private final ObjectMapper objectMapper;

    private static final String PROMPT_TEMPLATE = """
            Extract the following structured data from the Purchase Order text below.
            Return ONLY a valid JSON object with no markdown, no code fences, no explanation.
            
            Required JSON schema:
            {
              "poNumber": "string or null",
              "vendorName": "string or null",
              "poDate": "string or null",
              "paymentTerms": "string or null",
              "totalAmount": number or null,
              "items": [
                {
                  "description": "string",
                  "quantity": number,
                  "unitPrice": number,
                  "totalPrice": number
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
            throw new RuntimeException("Gemini returned an empty response");
        }
        String raw = rawText.strip();

        // Strip markdown code fences if model returns them despite instructions
        if (raw.startsWith("```")) {
            raw = raw.replaceAll("^```(?:json)?\\s*", "").replaceAll("```\\s*$", "").strip();
        }

        try {
            return objectMapper.readValue(raw, ExtractedPurchaseOrder.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse Gemini response as JSON: " + e.getMessage(), e);
        }
    }
}

