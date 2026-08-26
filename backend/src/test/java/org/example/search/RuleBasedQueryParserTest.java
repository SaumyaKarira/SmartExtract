package org.example.search;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Month;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class RuleBasedQueryParserTest {

    private final RuleBasedQueryParser parser = new RuleBasedQueryParser();

    // ── Utility ──────────────────────────────────────────────────────────────

    private SearchQuery parse(String q) {
        Optional<SearchQuery> result = parser.parse(q);
        assertThat(result).as("Expected parser to handle: %s", q).isPresent();
        return result.get();
    }

    // ── 1. "Completed POs from August" ───────────────────────────────────────

    @Test
    void completedPOsFromAugust() {
        SearchQuery q = parse("Completed POs from August");
        assertThat(q.status()).isEqualTo("COMPLETED");
        assertThat(q.dateFrom()).isEqualTo(LocalDate.of(LocalDate.now().getYear(), Month.AUGUST, 1));
        assertThat(q.dateTo()).isEqualTo(LocalDate.of(LocalDate.now().getYear(), Month.AUGUST, 31));
        // Must NOT misinterpret "August" as a supplier
        assertThat(q.supplier()).isNull();
        // Must NOT misinterpret anything as PO number
        assertThat(q.poNumber()).isNull();
    }

    // ── 2. "Show POs from ABC Technologies" ──────────────────────────────────

    @Test
    void showPOsFromSupplier() {
        SearchQuery q = parse("Show POs from ABC Technologies");
        assertThat(q.supplier()).isEqualToIgnoringCase("ABC Technologies");
        assertThat(q.status()).isNull();
        assertThat(q.dateFrom()).isNull();
    }

    // ── 3. "Find POs above ₹50,000" ──────────────────────────────────────────

    @Test
    void findPOsAboveAmount() {
        SearchQuery q = parse("Find POs above ₹50,000");
        assertThat(q.minAmount()).isEqualByComparingTo(new BigDecimal("50000"));
        assertThat(q.maxAmount()).isNull();
        assertThat(q.supplier()).isNull();
    }

    // ── 4. "Show POs created this month" ─────────────────────────────────────

    @Test
    void showPOsThisMonth() {
        SearchQuery q = parse("Show POs created this month");
        LocalDate now = LocalDate.now();
        assertThat(q.dateFrom()).isEqualTo(now.withDayOfMonth(1));
        assertThat(q.dateTo()).isEqualTo(now.withDayOfMonth(now.lengthOfMonth()));
        assertThat(q.supplier()).isNull();
    }

    // ── 5. "POs with errors" ──────────────────────────────────────────────────

    @Test
    void posWithErrors() {
        SearchQuery q = parse("POs with errors");
        assertThat(q.status()).isEqualTo("FAILED");
        assertThat(q.supplier()).isNull();
    }

    // ── 6. "Largest purchase orders" ─────────────────────────────────────────

    @Test
    void largestPurchaseOrders() {
        SearchQuery q = parse("Largest purchase orders");
        assertThat(q.sortBy()).isEqualTo("amount");
        assertThat(q.sortDir()).isEqualTo("desc");
        assertThat(q.supplier()).isNull();
        assertThat(q.status()).isNull();
    }

    // ── 7. Amount range ───────────────────────────────────────────────────────

    @Test
    void betweenAmounts() {
        SearchQuery q = parse("POs between ₹10,000 and ₹50,000");
        assertThat(q.minAmount()).isEqualByComparingTo(new BigDecimal("10000"));
        assertThat(q.maxAmount()).isEqualByComparingTo(new BigDecimal("50000"));
    }

    @Test
    void belowAmount() {
        SearchQuery q = parse("Find POs below ₹1,00,000");
        assertThat(q.maxAmount()).isEqualByComparingTo(new BigDecimal("100000"));
        assertThat(q.minAmount()).isNull();
    }

    // ── 8. Status variants ────────────────────────────────────────────────────

    @Test
    void processingStatus() {
        SearchQuery q = parse("Show processing orders");
        assertThat(q.status()).isEqualTo("PROCESSING");
    }

    @Test
    void failedStatus() {
        SearchQuery q = parse("Show failed POs");
        assertThat(q.status()).isEqualTo("FAILED");
    }

    // ── 9. Date - last month ──────────────────────────────────────────────────

    @Test
    void lastMonth() {
        SearchQuery q = parse("POs from last month");
        LocalDate prev = LocalDate.now().minusMonths(1);
        assertThat(q.dateFrom()).isEqualTo(prev.withDayOfMonth(1));
        assertThat(q.dateTo()).isEqualTo(prev.withDayOfMonth(prev.lengthOfMonth()));
    }

    // ── 10. Sort smallest ─────────────────────────────────────────────────────

    @Test
    void smallestOrders() {
        SearchQuery q = parse("Smallest purchase orders");
        assertThat(q.sortBy()).isEqualTo("amount");
        assertThat(q.sortDir()).isEqualTo("asc");
    }

    // ── 11. Noise words not treated as suppliers ──────────────────────────────

    @Test
    void noFalseSupplierFromCompletedPOsFromAugust() {
        SearchQuery q = parse("Completed POs from August");
        assertThat(q.supplier()).isNull();
    }

    @Test
    void noFalseSupplierForThisMonth() {
        SearchQuery q = parse("Show POs created this month");
        assertThat(q.supplier()).isNull();
    }

    // ── 12. Ambiguous query returns empty ────────────────────────────────────

    @Test
    void ambiguousQueryReturnsEmpty() {
        Optional<SearchQuery> result = parser.parse("something completely random and unclear that has no patterns");
        assertThat(result).isEmpty();
    }

    // ── 13. Blank query returns empty ────────────────────────────────────────

    @Test
    void blankQueryReturnsEmpty() {
        assertThat(parser.parse("")).isEmpty();
        assertThat(parser.parse("   ")).isEmpty();
        assertThat(parser.parse(null)).isEmpty();
    }
}

