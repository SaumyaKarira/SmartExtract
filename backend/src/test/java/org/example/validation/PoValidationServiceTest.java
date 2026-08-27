package org.example.validation;

import org.example.document.ExtractedPurchaseOrder;
import org.example.entity.DocumentStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for PoValidationService — deterministic validation layer.
 */
class PoValidationServiceTest {

    private final PoValidationService svc = new PoValidationService();

    // ── Helper builders ───────────────────────────────────────────────────────

    private static ExtractedPurchaseOrder.ExtractedLineItem item(
            String desc, Double qty, Double unitPrice, Double totalPrice) {
        return new ExtractedPurchaseOrder.ExtractedLineItem(desc, qty, unitPrice, totalPrice);
    }

    private static ExtractedPurchaseOrder po(String poNum, String vendor, String date,
                                              Double total,
                                              List<ExtractedPurchaseOrder.ExtractedLineItem> items) {
        return new ExtractedPurchaseOrder(poNum, vendor, date, "Net 30", total, items);
    }

    // ── COMPLETED ─────────────────────────────────────────────────────────────

    @Test
    void completed_whenAllFieldsValid() {
        var result = svc.validate(po("PO-001", "ACME Corp", "2024-01-15", 250000.00,
                List.of(item("Widget", 5.0, 50000.0, 250000.0))));

        assertThat(result.outcome()).isEqualTo(DocumentStatus.COMPLETED);
        assertThat(result.corrections()).isEmpty();
        assertThat(result.reviewReasons()).isEmpty();
    }

    @Test
    void completed_missingOptionalFieldsDoNotFail() {
        // deliveryDate, currency, subtotal, tax are optional — should not cause NEEDS_REVIEW
        var result = svc.validate(po("PO-001", "ACME Corp", null, null, List.of()));

        // Missing date is OK; missing total is OK
        assertThat(result.outcome()).isEqualTo(DocumentStatus.COMPLETED);
    }

    // ── Correct line totals ───────────────────────────────────────────────────

    @Test
    void completed_correctLineTotalNoCorrection() {
        // 5 × 50000 = 250000 — extracted value is correct
        var result = svc.validate(po("PO-001", "ACME", "2024-01-15", 250000.0,
                List.of(item("Item A", 5.0, 50000.0, 250000.0))));

        assertThat(result.outcome()).isEqualTo(DocumentStatus.COMPLETED);
        assertThat(result.corrections()).isEmpty();
    }

    // ── Incorrect line totals → automatic correction ──────────────────────────

    @Test
    void correctedWithCorrections_whenLineTotalWrong() {
        // 5 × 50000 = 250000, but Gemini returned 200000
        var result = svc.validate(po("PO-001", "ACME", "2024-01-15", 200000.0,
                List.of(item("Item A", 5.0, 50000.0, 200000.0))));

        assertThat(result.outcome()).isEqualTo(DocumentStatus.COMPLETED_WITH_CORRECTIONS);
        assertThat(result.corrections()).hasSize(1);

        var correction = result.corrections().get(0);
        assertThat(correction.field()).isEqualTo("items[0].totalPrice");
        assertThat(correction.originalValue()).isEqualTo(200000.0);
        assertThat(correction.correctedValue()).isEqualTo(250000.0);
        assertThat(correction.reason()).contains("5.0 × 50000.0");
    }

    @Test
    void correctedWithCorrections_multipleLineTotals() {
        // Line 0 correct, Line 1 wrong
        var result = svc.validate(po("PO-002", "Vendor", "2024-02-01", null, List.of(
                item("A", 2.0, 100.0, 200.0),   // correct
                item("B", 3.0, 100.0, 200.0)    // wrong: should be 300
        )));

        assertThat(result.outcome()).isEqualTo(DocumentStatus.COMPLETED_WITH_CORRECTIONS);
        assertThat(result.corrections()).hasSize(1);
        assertThat(result.corrections().get(0).field()).isEqualTo("items[1].totalPrice");
        assertThat(result.corrections().get(0).correctedValue()).isEqualTo(300.0);
    }

    // ── Missing line total → filled in ───────────────────────────────────────

    @Test
    void correctedWithCorrections_missingLineTotalFilledIn() {
        var result = svc.validate(po("PO-003", "Vendor", "2024-01-01", null,
                List.of(item("Widget", 10.0, 25.0, null))));

        assertThat(result.outcome()).isEqualTo(DocumentStatus.COMPLETED_WITH_CORRECTIONS);
        assertThat(result.corrections()).hasSize(1);
        assertThat(result.corrections().get(0).correctedValue()).isEqualTo(250.0);
        assertThat(result.corrections().get(0).originalValue()).isNull();
    }

    // ── Subtotal correction ───────────────────────────────────────────────────

    // The current schema doesn't have a separate subtotal field in ExtractedPurchaseOrder;
    // grand-total correction is handled in the next test.

    // ── Grand total correction when deterministically calculable ──────────────

    @Test
    void noGrandTotalCorrection_whenTaxDiscountMayExplainDifference() {
        // Lines sum to 250000 but grand total is 290000 (likely tax included)
        // We must NOT auto-correct this — the difference could be tax/shipping.
        var result = svc.validate(po("PO-001", "ACME", "2024-01-15", 290000.0,
                List.of(item("Item A", 5.0, 50000.0, 250000.0))));

        // Should be COMPLETED (lines correct, grand total not touched)
        assertThat(result.outcome()).isEqualTo(DocumentStatus.COMPLETED);
        assertThat(result.corrections()).isEmpty();
    }

    // ── Rounding tolerance ────────────────────────────────────────────────────

    @Test
    void completed_withinRoundingTolerance() {
        // 3 × 33.33 = 99.99 but extracted says 100.00 (within 0.02 tolerance)
        var result = svc.validate(po("PO-001", "ACME", "2024-01-15", null,
                List.of(item("Service", 3.0, 33.33, 100.00))));

        assertThat(result.outcome()).isEqualTo(DocumentStatus.COMPLETED);
        assertThat(result.corrections()).isEmpty();
    }

    @Test
    void corrected_outsideRoundingTolerance() {
        // 3 × 33.33 = 99.99, but extracted says 98.00 (outside 0.02 tolerance)
        var result = svc.validate(po("PO-001", "ACME", "2024-01-15", null,
                List.of(item("Service", 3.0, 33.33, 98.00))));

        assertThat(result.outcome()).isEqualTo(DocumentStatus.COMPLETED_WITH_CORRECTIONS);
        assertThat(result.corrections()).hasSize(1);
    }

    // ── Original extracted value and correction reason are preserved ──────────

    @Test
    void correctionPreservesOriginalValueAndReason() {
        var result = svc.validate(po("PO-001", "ACME", "2024-01-15", 200000.0,
                List.of(item("Item A", 5.0, 50000.0, 200000.0))));

        var c = result.corrections().get(0);
        assertThat(c.originalValue()).isEqualTo(200000.0);
        assertThat(c.correctedValue()).isEqualTo(250000.0);
        assertThat(c.reason()).isNotBlank();
    }

    // ── NEEDS_REVIEW ─────────────────────────────────────────────────────────

    @Test
    void needsReview_missingPoNumber() {
        var result = svc.validate(po(null, "ACME", "2024-01-15", 1000.0, List.of()));

        assertThat(result.outcome()).isEqualTo(DocumentStatus.NEEDS_REVIEW);
        assertThat(result.reviewReasons()).anyMatch(r -> r.contains("PO number"));
    }

    @Test
    void needsReview_blankPoNumber() {
        var result = svc.validate(po("   ", "ACME", "2024-01-15", 1000.0, List.of()));

        assertThat(result.outcome()).isEqualTo(DocumentStatus.NEEDS_REVIEW);
    }

    @Test
    void needsReview_missingVendor() {
        var result = svc.validate(po("PO-001", null, "2024-01-15", 1000.0, List.of()));

        assertThat(result.outcome()).isEqualTo(DocumentStatus.NEEDS_REVIEW);
        assertThat(result.reviewReasons()).anyMatch(r -> r.contains("Vendor"));
    }

    @Test
    void needsReview_invalidDate() {
        var result = svc.validate(po("PO-001", "ACME", "not-a-date", 1000.0, List.of()));

        assertThat(result.outcome()).isEqualTo(DocumentStatus.NEEDS_REVIEW);
        assertThat(result.reviewReasons()).anyMatch(r -> r.contains("date"));
    }

    @Test
    void needsReview_negativeQuantity() {
        var result = svc.validate(po("PO-001", "ACME", "2024-01-15", null,
                List.of(item("Widget", -1.0, 100.0, -100.0))));

        assertThat(result.outcome()).isEqualTo(DocumentStatus.NEEDS_REVIEW);
        assertThat(result.reviewReasons()).anyMatch(r -> r.contains("quantity"));
    }

    @Test
    void needsReview_zeroQuantity() {
        var result = svc.validate(po("PO-001", "ACME", "2024-01-15", null,
                List.of(item("Widget", 0.0, 100.0, 0.0))));

        assertThat(result.outcome()).isEqualTo(DocumentStatus.NEEDS_REVIEW);
        assertThat(result.reviewReasons()).anyMatch(r -> r.contains("quantity"));
    }

    // ── Missing optional fields should NOT cause failure ─────────────────────

    @Test
    void completed_whenOptionalFieldsMissing() {
        // paymentTerms, currency, deliveryDate, subtotal, tax, totalAmount all null
        var extracted = new ExtractedPurchaseOrder("PO-001", "ACME", "2024-01-15",
                null, null, List.of());
        var result = svc.validate(extracted);

        assertThat(result.outcome()).isEqualTo(DocumentStatus.COMPLETED);
    }

    // ── Discounts/tax/shipping handling ──────────────────────────────────────

    @Test
    void doesNotCorrectGrandTotal_whenTaxPresent() {
        // Items sum to 1000, grand total is 1180 (likely tax = 180)
        var result = svc.validate(po("PO-100", "Supplier", "2024-03-01", 1180.0,
                List.of(
                        item("Product A", 10.0, 50.0, 500.0),
                        item("Product B", 10.0, 50.0, 500.0)
                )));

        // Lines are correct, grand total differs — but we should not auto-correct
        assertThat(result.outcome()).isEqualTo(DocumentStatus.COMPLETED);
        assertThat(result.corrections()).isEmpty();
    }

    // ── NEEDS_REVIEW does not turn to FAILED ────────────────────────────────

    @Test
    void needsReview_notFailed() {
        var result = svc.validate(po(null, null, "2024-01-15", 500.0, List.of()));

        assertThat(result.outcome()).isEqualTo(DocumentStatus.NEEDS_REVIEW);
        assertThat(result.outcome()).isNotEqualTo(DocumentStatus.FAILED);
    }
}

