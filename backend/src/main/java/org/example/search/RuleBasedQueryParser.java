package org.example.search;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Month;
import java.time.Year;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight rule-based natural-language parser.
 * Returns an Optional — empty means "I'm not confident, use Gemini".
 */
@Component
public class RuleBasedQueryParser {

    // Amount patterns: "above ₹50000", "over $1,000", "more than 50000", "under ₹10000"
    private static final Pattern ABOVE = Pattern.compile(
            "(?:above|over|more than|greater than)\\s*[₹$]?\\s*([\\d,]+(?:\\.\\d+)?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern BELOW = Pattern.compile(
            "(?:below|under|less than|cheaper than)\\s*[₹$]?\\s*([\\d,]+(?:\\.\\d+)?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern BETWEEN = Pattern.compile(
            "between\\s*[₹$]?\\s*([\\d,]+)\\s*(?:and|to|-|–)\\s*[₹$]?\\s*([\\d,]+)", Pattern.CASE_INSENSITIVE);

    // Date patterns
    private static final Pattern MONTH_YEAR = Pattern.compile(
            "(january|february|march|april|may|june|july|august|september|october|november|december)" +
            "(?:\\s+(\\d{4}))?", Pattern.CASE_INSENSITIVE);
    private static final Pattern THIS_MONTH = Pattern.compile("\\bthis month\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern THIS_YEAR = Pattern.compile("\\bthis year\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern LAST_MONTH = Pattern.compile("\\blast month\\b", Pattern.CASE_INSENSITIVE);

    // Status
    private static final Pattern STATUS_COMPLETED = Pattern.compile(
            "\\b(completed|processed|done|finished)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern STATUS_PROCESSING = Pattern.compile(
            "\\b(processing|pending|in progress)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern STATUS_FAILED = Pattern.compile(
            "\\b(failed|error|errors)\\b", Pattern.CASE_INSENSITIVE);

    // Sorting
    private static final Pattern SORT_LARGEST = Pattern.compile(
            "\\b(largest|biggest|highest|most expensive|top)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern SORT_SMALLEST = Pattern.compile(
            "\\b(smallest|cheapest|lowest)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern SORT_RECENT = Pattern.compile(
            "\\b(recent|latest|newest|last)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern SORT_OLDEST = Pattern.compile(
            "\\b(oldest|earliest|first)\\b", Pattern.CASE_INSENSITIVE);

    // PO number: "PO-12345" or "PO #123"
    private static final Pattern PO_NUMBER = Pattern.compile(
            "\\bPO[-#\\s]*(\\w+)\\b", Pattern.CASE_INSENSITIVE);

    // Supplier: "from <Supplier Name>" or "by <Supplier Name>"
    private static final Pattern SUPPLIER = Pattern.compile(
            "(?:from|by|supplier|vendor)\\s+([A-Za-z][\\w\\s&,.'-]{2,40}?)(?:\\s+(?:above|below|over|under|this|in|from|by|processed|completed|failed|processing)|$)",
            Pattern.CASE_INSENSITIVE);

    /**
     * Returns Optional.empty() when the query is ambiguous and should be sent to Gemini.
     */
    public Optional<SearchQuery> parse(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) return Optional.empty();
        String q = rawQuery.trim();

        BigDecimal minAmount = null;
        BigDecimal maxAmount = null;
        LocalDate dateFrom = null;
        LocalDate dateTo = null;
        String status = null;
        String supplier = null;
        String poNumber = null;
        String sortBy = "date";
        String sortDir = "desc";
        boolean matched = false;

        // ── Amount ──────────────────────────────────────────────────────────
        Matcher between = BETWEEN.matcher(q);
        if (between.find()) {
            minAmount = parseMoney(between.group(1));
            maxAmount = parseMoney(between.group(2));
            matched = true;
        } else {
            Matcher above = ABOVE.matcher(q);
            if (above.find()) { minAmount = parseMoney(above.group(1)); matched = true; }
            Matcher below = BELOW.matcher(q);
            if (below.find()) { maxAmount = parseMoney(below.group(1)); matched = true; }
        }

        // ── Date ────────────────────────────────────────────────────────────
        if (THIS_MONTH.matcher(q).find()) {
            LocalDate now = LocalDate.now();
            dateFrom = now.withDayOfMonth(1);
            dateTo = now.withDayOfMonth(now.lengthOfMonth());
            matched = true;
        } else if (LAST_MONTH.matcher(q).find()) {
            LocalDate now = LocalDate.now().minusMonths(1);
            dateFrom = now.withDayOfMonth(1);
            dateTo = now.withDayOfMonth(now.lengthOfMonth());
            matched = true;
        } else if (THIS_YEAR.matcher(q).find()) {
            int year = Year.now().getValue();
            dateFrom = LocalDate.of(year, 1, 1);
            dateTo = LocalDate.of(year, 12, 31);
            matched = true;
        } else {
            Matcher mMonth = MONTH_YEAR.matcher(q);
            if (mMonth.find()) {
                Month month = Month.valueOf(mMonth.group(1).toUpperCase());
                int year = mMonth.group(2) != null ? Integer.parseInt(mMonth.group(2)) : LocalDate.now().getYear();
                dateFrom = LocalDate.of(year, month, 1);
                dateTo = dateFrom.withDayOfMonth(dateFrom.lengthOfMonth());
                matched = true;
            }
        }

        // ── Status ──────────────────────────────────────────────────────────
        if (STATUS_COMPLETED.matcher(q).find()) { status = "COMPLETED"; matched = true; }
        else if (STATUS_PROCESSING.matcher(q).find()) { status = "PROCESSING"; matched = true; }
        else if (STATUS_FAILED.matcher(q).find()) { status = "FAILED"; matched = true; }

        // ── Sort ────────────────────────────────────────────────────────────
        if (SORT_LARGEST.matcher(q).find()) { sortBy = "amount"; sortDir = "desc"; matched = true; }
        else if (SORT_SMALLEST.matcher(q).find()) { sortBy = "amount"; sortDir = "asc"; matched = true; }
        else if (SORT_RECENT.matcher(q).find()) { sortBy = "date"; sortDir = "desc"; matched = true; }
        else if (SORT_OLDEST.matcher(q).find()) { sortBy = "date"; sortDir = "asc"; matched = true; }

        // ── PO Number ───────────────────────────────────────────────────────
        Matcher mPo = PO_NUMBER.matcher(q);
        if (mPo.find()) { poNumber = mPo.group(1); matched = true; }

        // ── Supplier ────────────────────────────────────────────────────────
        Matcher mSupplier = SUPPLIER.matcher(q);
        if (mSupplier.find()) { supplier = mSupplier.group(1).trim(); matched = true; }

        // If nothing matched and it's a short string, treat as generic keyword → supplier search
        if (!matched && q.length() >= 2 && q.length() <= 60 && !q.contains(" ")) {
            supplier = q;
            matched = true;
        }

        if (!matched) return Optional.empty();

        return Optional.of(new SearchQuery(
                poNumber, supplier, null,
                minAmount, maxAmount, dateFrom, dateTo,
                status, sortBy, sortDir, 0, 20));
    }

    private BigDecimal parseMoney(String s) {
        return new BigDecimal(s.replace(",", ""));
    }
}

