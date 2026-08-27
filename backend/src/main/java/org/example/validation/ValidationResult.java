package org.example.validation;

import org.example.entity.DocumentStatus;

import java.util.List;

/**
 * Immutable result of the deterministic validation layer applied after Gemini extraction
 * and before persisting the PurchaseOrder.
 */
public record ValidationResult(
        DocumentStatus outcome,
        List<Correction> corrections,
        List<String> reviewReasons
) {

    /**
     * A single deterministic correction applied to a calculated field.
     *
     * @param field         e.g. "items[0].totalPrice", "subtotal", "grandTotal"
     * @param originalValue The value as returned by Gemini (may be null).
     * @param correctedValue The deterministically computed correct value.
     * @param reason        Human-readable explanation.
     */
    public record Correction(
            String field,
            Double originalValue,
            Double correctedValue,
            String reason
    ) {}
}

