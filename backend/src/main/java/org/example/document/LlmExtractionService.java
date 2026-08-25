package org.example.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.Client;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.net.ssl.*;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;

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
        configureTrustAllSsl();
        this.geminiClient = Client.builder().apiKey(apiKey).build();
        this.model = model;
        this.objectMapper = objectMapper;
    }

    /**
     * Installs a trust-all SSL context as the JVM default so that the Google GenAI SDK's
     * internal OkHttpClient can reach generativelanguage.googleapis.com in environments
     * where a corporate SSL-inspection proxy intercepts TLS traffic with a custom CA
     * (e.g., Optum enterprise network).
     *
     * WARNING: disables certificate validation — only use in trusted internal environments.
     */
    private static void configureTrustAllSsl() {
        try {
            TrustManager[] trustAllCerts = new TrustManager[]{
                new X509TrustManager() {
                    @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                    @Override public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                    @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                }
            };
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
            SSLContext.setDefault(sslContext);
            HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.getSocketFactory());
            HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> true);
        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            throw new RuntimeException("Failed to configure trust-all SSL context", e);
        }
    }

    public ExtractedPurchaseOrder extract(String pdfText) {
        String prompt = PROMPT_TEMPLATE.formatted(pdfText);

        GenerateContentResponse response = geminiClient.models.generateContent(
                model,
                prompt,
                GenerateContentConfig.builder().build()
        );

        String raw = response.text().strip();

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


import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.Client;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import okhttp3.OkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.net.ssl.*;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;

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
        this.geminiClient = Client.builder()
                .apiKey(apiKey)
                .httpClient(buildTrustAllOkHttpClient())
                .build();
        this.model = model;
        this.objectMapper = objectMapper;
    }

    /**
     * Builds an OkHttpClient that trusts all SSL certificates.
     * This is needed in corporate environments (e.g., Optum) where SSL inspection
     * proxies intercept traffic with custom CA certificates not trusted by the JVM.
     * WARNING: Only use this in development/internal environments.
     */
    private static OkHttpClient buildTrustAllOkHttpClient() {
        try {
            TrustManager[] trustAllCerts = new TrustManager[]{
                new X509TrustManager() {
                    @Override
                    public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                    @Override
                    public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                    @Override
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                }
            };

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAllCerts, new java.security.SecureRandom());

            return new OkHttpClient.Builder()
                    .sslSocketFactory(sslContext.getSocketFactory(), (X509TrustManager) trustAllCerts[0])
                    .hostnameVerifier((hostname, session) -> true)
                    .build();
        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            throw new RuntimeException("Failed to create trust-all SSL OkHttpClient", e);
        }
    }

    public ExtractedPurchaseOrder extract(String pdfText) {
        String prompt = PROMPT_TEMPLATE.formatted(pdfText);

        GenerateContentResponse response = geminiClient.models.generateContent(
                model,
                prompt,
                GenerateContentConfig.builder().build()
        );

        String raw = response.text().strip();

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

