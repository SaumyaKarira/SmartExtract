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
        // Policy (applies only when allLinesFullyComputed = true, so every line total
        // was derived from qty × unitPrice and we have a reliable line sum):
        //
        //  A) Line sum > grand total (by more than tolerance):
        //     Definitively wrong — surcharges cannot reduce the total below the item sum.
        //     → NEEDS_REVIEW.
        //
        //  B) Grand total > line sum but within a plausible surcharge margin (≤ 50%):
        //     Acceptable — the difference may be tax, shipping, or other surcharges.
        //     → No flag.
        //
        //  C) Grand total > line sum by more than 50% of the line sum:
        //     Implausibly large surcharge — almost certainly a data error.
        //     → NEEDS_REVIEW.
        //
        //  D) Grand total is negative → always NEEDS_REVIEW.
        //
        // When allLinesFullyComputed = false we can only apply rule A (partial sum
        // already exceeds total → definitively wrong) and D.

        if (!validLineTotals.isEmpty() && extracted.totalAmount() != null) {
            BigDecimal computedLineSum = validLineTotals.stream()
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .setScale(2, RoundingMode.HALF_UP);

            BigDecimal extractedGrandTotal = bd(extracted.totalAmount()).setScale(2, RoundingMode.HALF_UP);

            // (D) Negative grand total
            if (extracted.totalAmount() < 0) {
                reviewReasons.add("Grand total is negative (" + extracted.totalAmount() + "). Please review.");
            }
            // (A) Line sum EXCEEDS grand total — definitively wrong
            else if (computedLineSum.compareTo(extractedGrandTotal) > 0
                    && deviation(computedLineSum, extractedGrandTotal).compareTo(ROUNDING_TOLERANCE) > 0) {
                reviewReasons.add(
                        "Line-item sum (" + computedLineSum + ") exceeds the extracted PO total ("
                                + extractedGrandTotal + "). "
                                + "The shortfall (" + deviation(computedLineSum, extractedGrandTotal)
                                + ") cannot be explained by tax or surcharges. Please verify the PO total."
                );
            }
            // (C) Grand total implausibly larger than line sum (only when all lines were fully computed)
            else if (allLinesFullyComputed && computedLineSum.compareTo(BigDecimal.ZERO) > 0) {
                // Compute ratio: grandTotal / lineSum
                BigDecimal ratio = extractedGrandTotal.divide(computedLineSum, 4, RoundingMode.HALF_UP);
                // If grand total > 1.5 × line sum, flag as suspicious
                if (ratio.compareTo(new BigDecimal("1.50")) > 0) {
                    reviewReasons.add(
                            "Extracted PO total (" + extractedGrandTotal + ") is " + ratio
                                    + "× the line-item sum (" + computedLineSum + "). "
                                    + "This is implausibly large for tax/surcharges alone. Please verify the PO total."
                    );
                }
            }
            // (B) Grand total >= line sum within plausible range — acceptable (tax/discount/shipping).
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

