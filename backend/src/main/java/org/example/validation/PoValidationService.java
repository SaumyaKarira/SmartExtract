package org.example.validation;

import org.example.document.ExtractedPurchaseOrder;
import org.example.entity.DocumentStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic validation layer applied AFTER Gemini extraction and BEFORE saving the PO.
 *
 * <p>Rules:
 * <ul>
 *   <li>COMPLETED — all required info valid, no corrections needed.</li>
 *   <li>COMPLETED_WITH_CORRECTIONS — calculation errors auto-corrected from reliable source values.</li>
 *   <li>NEEDS_REVIEW — business/source fields missing, invalid, or ambiguous; or line-item sum
 *       does not match the extracted PO total and it is not safe to auto-correct.</li>
 *   <li>FAILED — handled upstream; this service never returns FAILED.</li>
 * </ul>
 */
@Service
public class PoValidationService {

    /** Maximum rounding tolerance for currency comparisons (0.02 = 2 paise/cents). */
    private static final BigDecimal ROUNDING_TOLERANCE = new BigDecimal("0.02");

    private static final String[] DATE_PATTERNS = {
            "yyyy-MM-dd", "dd/MM/yyyy", "MM/dd/yyyy", "dd-MM-yyyy",
            "dd MMM yyyy", "MMM dd, yyyy"
    };

    /**
     * Validate and optionally correct the extracted PO.
     *
     * @param extracted The raw data returned by Gemini.
     * @return A {@link ValidationResult} describing the outcome, any corrections, and any review reasons.
     */
    public ValidationResult validate(ExtractedPurchaseOrder extracted) {
        List<ValidationResult.Correction> corrections = new ArrayList<>();
        List<String> reviewReasons = new ArrayList<>();

        // ── 1. Source / business field validation (NEEDS_REVIEW triggers) ──────

        if (isBlank(extracted.poNumber())) {
            reviewReasons.add("PO number is missing or could not be extracted.");
        }

        if (isBlank(extracted.vendorName())) {
            reviewReasons.add("Vendor/supplier name is missing or could not be extracted.");
        }

        validateDate(extracted.poDate(), reviewReasons);

        // ── 2. Line-item validation & deterministic corrections ───────────────

        List<ExtractedPurchaseOrder.ExtractedLineItem> items =
                extracted.items() != null ? extracted.items() : List.of();

        // validLineTotals accumulates the "best known" total for each line.
        List<BigDecimal> validLineTotals = new ArrayList<>();

        // allLinesComputable: true only when EVERY line had qty+unitPrice available
        // (i.e. we computed the line total from first principles for all lines).
        // If even one line is missing qty or unitPrice we can still sum the line totals
        // Gemini reported, but we cannot safely override the grand total.
        boolean allLinesFullyComputed = true;

        for (int i = 0; i < items.size(); i++) {
            ExtractedPurchaseOrder.ExtractedLineItem item = items.get(i);
            String itemPrefix = "items[" + i + "]";

            Double qty = item.quantity();
            Double unitPrice = item.unitPrice();
            Double totalPrice = item.totalPrice();

            // Invalid / negative quantity → needs review; skip this line for sum
            if (qty != null && qty <= 0) {
                reviewReasons.add(itemPrefix + ": quantity " + qty + " is invalid (must be > 0).");
                allLinesFullyComputed = false;
                continue;
            }

            if (qty != null && unitPrice != null) {
                BigDecimal expectedTotal = bd(qty).multiply(bd(unitPrice)).setScale(2, RoundingMode.HALF_UP);

                if (totalPrice == null) {
                    // Missing line total — fill it in deterministically
                    corrections.add(new ValidationResult.Correction(
                            itemPrefix + ".totalPrice",
                            null,
                            expectedTotal.doubleValue(),
                            "Computed as quantity × unitPrice (" + qty + " × " + unitPrice + ")."
                    ));
                    validLineTotals.add(expectedTotal);
                } else {
                    BigDecimal extractedLineTotal = bd(totalPrice).setScale(2, RoundingMode.HALF_UP);
                    if (deviation(expectedTotal, extractedLineTotal).compareTo(ROUNDING_TOLERANCE) > 0) {
                        // Line total is inconsistent with qty × unitPrice — correct it
                        corrections.add(new ValidationResult.Correction(
                                itemPrefix + ".totalPrice",
                                totalPrice,
                                expectedTotal.doubleValue(),
                                "Corrected: " + qty + " × " + unitPrice + " = " + expectedTotal +
                                        " (extracted value was " + extractedLineTotal + ")."
                        ));
                        validLineTotals.add(expectedTotal);
                    } else {
                        // Within tolerance — keep original (normalised to 2dp)
                        validLineTotals.add(extractedLineTotal);
                    }
                }
            } else {
                // qty or unitPrice missing — cannot verify the line total from first principles
                allLinesFullyComputed = false;
                if (totalPrice != null && totalPrice > 0) {
                    validLineTotals.add(bd(totalPrice).setScale(2, RoundingMode.HALF_UP));
                }
                // If totalPrice is also null/zero, we simply have nothing reliable to add;
                // the grand-total check below will not be able to run a full cross-check.
            }
        }

        // ── 3. Grand-total cross-check ────────────────────────────────────────
        //
        // When ALL lines are fully computable the computed line sum is authoritative.
        //
        //  • lineSum > grandTotal → the stated total is too LOW to cover the items.
        //    This is definitively wrong (discounts can't make the total less than items).
        //    Auto-correct grandTotal = lineSum and mark COMPLETED_WITH_CORRECTIONS.
        //
        //  • grandTotal > lineSum by more than tolerance → could be tax/shipping, or
        //    could be a data error.  We cannot safely auto-correct, so flag NEEDS_REVIEW.
        //
        //  • grandTotal ≈ lineSum (within tolerance) → COMPLETED, no action.
        //
        //  • Negative grandTotal → always NEEDS_REVIEW regardless.
        //
        // When allLinesFullyComputed = false we only flag the impossible case where
        // even the partial sum already exceeds the stated total.

        if (!validLineTotals.isEmpty() && extracted.totalAmount() != null) {
            BigDecimal computedLineSum = validLineTotals.stream()
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .setScale(2, RoundingMode.HALF_UP);

            BigDecimal extractedGrandTotal = bd(extracted.totalAmount()).setScale(2, RoundingMode.HALF_UP);

            // Tax amount extracted from document (may be null or zero)
            BigDecimal taxAmount = (extracted.tax() != null && extracted.tax() > 0)
                    ? bd(extracted.tax()).setScale(2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;

            // Effective total = line item sum + tax. If tax is present in the document,
            // the grand total is expected to be lineSum + tax, not lineSum alone.
            BigDecimal effectiveLineTotal = computedLineSum.add(taxAmount).setScale(2, RoundingMode.HALF_UP);

            // Negative grand total
            if (extracted.totalAmount() < 0) {
                reviewReasons.add("The extracted PO total is negative (" + extractedGrandTotal + "). Please verify.");
            } else if (allLinesFullyComputed
                    && deviation(effectiveLineTotal, extractedGrandTotal).compareTo(ROUNDING_TOLERANCE) > 0) {

                if (effectiveLineTotal.compareTo(extractedGrandTotal) > 0) {
                    // Line sum + tax EXCEEDS stated total — definitively wrong; auto-correct
                    corrections.add(new ValidationResult.Correction(
                            "grandTotal",
                            extracted.totalAmount(),
                            effectiveLineTotal.doubleValue(),
                            "PO total corrected to match the sum of line items + tax ("
                                    + computedLineSum + (taxAmount.compareTo(BigDecimal.ZERO) > 0 ? " + " + taxAmount : "")
                                    + " = " + effectiveLineTotal + "). Extracted value was " + extractedGrandTotal + "."
                    ));
                } else {
                    // Grand total still HIGHER than lineSum + tax — unexplained discrepancy; flag for review
                    reviewReasons.add(
                            "The extracted PO total (" + extractedGrandTotal
                                    + ") is higher than the sum of line items"
                                    + (taxAmount.compareTo(BigDecimal.ZERO) > 0 ? " + tax (" + computedLineSum + " + " + taxAmount + " = " + effectiveLineTotal + ")" : " (" + computedLineSum + ")")
                                    + ". This may indicate additional charges not captured, or a data error. Please verify."
                    );
                }
            } else if (!allLinesFullyComputed
                    && effectiveLineTotal.compareTo(extractedGrandTotal) > 0
                    && deviation(effectiveLineTotal, extractedGrandTotal).compareTo(ROUNDING_TOLERANCE) > 0) {
                // Partial sum + tax already exceeds total — impossible
                reviewReasons.add(
                        "The sum of extractable line items" + (taxAmount.compareTo(BigDecimal.ZERO) > 0 ? " + tax" : "")
                                + " (" + effectiveLineTotal + ") already exceeds the PO total (" + extractedGrandTotal
                                + "). Please verify the totals."
                );
            }
        }

        // ── 3b. Explicit subtotal + tax = total cross-check ──────────────────────
        // When all three header-level amounts are present, verify their relationship.
        if (extracted.subtotal() != null && extracted.tax() != null && extracted.totalAmount() != null
                && extracted.subtotal() > 0 && extracted.tax() >= 0 && extracted.totalAmount() > 0) {
            BigDecimal subtotal = bd(extracted.subtotal()).setScale(2, RoundingMode.HALF_UP);
            BigDecimal tax      = bd(extracted.tax()).setScale(2, RoundingMode.HALF_UP);
            BigDecimal total    = bd(extracted.totalAmount()).setScale(2, RoundingMode.HALF_UP);
            BigDecimal expected = subtotal.add(tax).setScale(2, RoundingMode.HALF_UP);
            if (deviation(expected, total).compareTo(ROUNDING_TOLERANCE) > 0) {
                reviewReasons.add(
                        "Header-level amounts inconsistent: subtotal (" + subtotal
                                + ") + tax (" + tax + ") = " + expected
                                + " but extracted total is " + total + ". Please verify."
                );
            }
        }

        // ── 4. Determine outcome ──────────────────────────────────────────────

        DocumentStatus outcome;
        if (!reviewReasons.isEmpty()) {
            outcome = DocumentStatus.NEEDS_REVIEW;
        } else if (!corrections.isEmpty()) {
            outcome = DocumentStatus.COMPLETED_WITH_CORRECTIONS;
        } else {
            outcome = DocumentStatus.COMPLETED;
        }

        return new ValidationResult(outcome, List.copyOf(corrections), List.copyOf(reviewReasons));
    }

    // ── Private helpers ─────────────────────────���─────────────────────────────

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static BigDecimal bd(double v) {
        return BigDecimal.valueOf(v);
    }

    /** Absolute difference between two BigDecimals. */
    private static BigDecimal deviation(BigDecimal a, BigDecimal b) {
        return a.subtract(b).abs();
    }

    private static void validateDate(String dateStr, List<String> reviewReasons) {
        if (isBlank(dateStr)) {
            // Missing date is OK — not required for NEEDS_REVIEW on its own.
            return;
        }
        boolean parsed = false;
        for (String pattern : DATE_PATTERNS) {
            try {
                LocalDate.parse(dateStr.trim(), DateTimeFormatter.ofPattern(pattern));
                parsed = true;
                break;
            } catch (DateTimeParseException ignored) {
            }
        }
        if (!parsed) {
            reviewReasons.add("PO date \"" + dateStr + "\" could not be parsed to a known date format.");
        }
    }
}

