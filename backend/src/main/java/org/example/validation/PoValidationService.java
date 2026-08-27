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
 *   <li>NEEDS_REVIEW — business/source fields missing, invalid, or ambiguous.</li>
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

        List<BigDecimal> validLineTotals = new ArrayList<>();
        boolean subtotalComputable = true; // flip to false if any line is not correctable

        for (int i = 0; i < items.size(); i++) {
            ExtractedPurchaseOrder.ExtractedLineItem item = items.get(i);
            String itemPrefix = "items[" + i + "]";

            Double qty = item.quantity();
            Double unitPrice = item.unitPrice();
            Double totalPrice = item.totalPrice();

            // Invalid / negative quantity → needs review
            if (qty != null && qty <= 0) {
                reviewReasons.add(itemPrefix + ": quantity " + qty + " is invalid (must be > 0).");
                subtotalComputable = false;
                continue; // can't reliably compute line total
            }

            if (qty != null && unitPrice != null) {
                BigDecimal expectedTotal = bd(qty).multiply(bd(unitPrice)).setScale(2, RoundingMode.HALF_UP);

                if (totalPrice == null) {
                    // Missing line total — fill it in
                    corrections.add(new ValidationResult.Correction(
                            itemPrefix + ".totalPrice",
                            null,
                            expectedTotal.doubleValue(),
                            "Computed as quantity × unitPrice (" + qty + " × " + unitPrice + ")."
                    ));
                    validLineTotals.add(expectedTotal);
                } else {
                    BigDecimal extractedTotal = bd(totalPrice).setScale(2, RoundingMode.HALF_UP);
                    if (deviation(expectedTotal, extractedTotal).compareTo(ROUNDING_TOLERANCE) > 0) {
                        corrections.add(new ValidationResult.Correction(
                                itemPrefix + ".totalPrice",
                                totalPrice,
                                expectedTotal.doubleValue(),
                                "Corrected: " + qty + " × " + unitPrice + " = " + expectedTotal +
                                        " (extracted value was " + extractedTotal + ")."
                        ));
                        validLineTotals.add(expectedTotal);
                    } else {
                        // Within tolerance — keep original (normalised to 2dp)
                        validLineTotals.add(extractedTotal);
                    }
                }
            } else {
                // qty or unitPrice missing — can't verify; keep as-is but track for subtotal
                if (totalPrice != null && totalPrice > 0) {
                    validLineTotals.add(bd(totalPrice).setScale(2, RoundingMode.HALF_UP));
                } else {
                    subtotalComputable = false;
                }
            }
        }

        // ── 3. Subtotal correction ────────────────────────────────────────────

        if (subtotalComputable && !validLineTotals.isEmpty()) {
            BigDecimal computedSubtotal = validLineTotals.stream()
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .setScale(2, RoundingMode.HALF_UP);

            // We don't have a separate subtotal field in ExtractedPurchaseOrder right now,
            // but we may derive/correct the grand total using it (see step 4).
            // Expose computed subtotal via corrections if totalAmount was provided.
        }

        // ── 4. Grand-total correction (only when deterministically computable) ─

        if (subtotalComputable && !validLineTotals.isEmpty()) {
            BigDecimal computedSubtotal = validLineTotals.stream()
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .setScale(2, RoundingMode.HALF_UP);

            Double extractedTotal = extracted.totalAmount();

            if (extractedTotal != null) {
                BigDecimal extTotalBd = bd(extractedTotal).setScale(2, RoundingMode.HALF_UP);

                // Grand total may include tax, discount, shipping etc.
                // We can ONLY auto-correct if there is exactly one component: the line-item sum.
                // i.e. grandTotal should equal computedSubtotal (no other adjustments).
                // We detect "no other adjustments" by checking if the extracted grand total
                // is close to the sum of line totals.  If adjustments are present the
                // extracted total will differ by more than tolerance — leave it alone.
                if (deviation(computedSubtotal, extTotalBd).compareTo(ROUNDING_TOLERANCE) > 0) {
                    // Grand total differs from sum of lines.
                    // If ALL lines were reliable (no qty issues), we assume line totals
                    // are the ground truth and flag the grand total.
                    // But we CANNOT know if the difference is tax/discount/shipping.
                    // DO NOT auto-correct — flag for review only if the total looks impossible.

                    // Negative grand total is always a review reason.
                    if (extractedTotal < 0) {
                        reviewReasons.add("Grand total is negative (" + extractedTotal + ").");
                    }
                    // Otherwise, trust Gemini's grand total — differences might be tax/discount/shipping.
                    // No correction applied.
                } else if (deviation(computedSubtotal, extTotalBd).compareTo(ROUNDING_TOLERANCE) <= 0
                        && items.size() > 0) {
                    // Grand total matches computed subtotal within tolerance — no correction needed.
                }
            }
        }

        // ── 5. Determine outcome ──────────────────────────────────────────────

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

    // ── Private helpers ───────────────────────────────────────────────────────

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

