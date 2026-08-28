package org.example.search;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Month;
import java.time.Year;
import java.util.Optional;
import java.util.Set;
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
            "\\b(january|february|march|april|may|june|july|august|september|october|november|december)" +
            "(?:\\s+(\\d{4}))?\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern THIS_MONTH = Pattern.compile("\\bthis\\s+month\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern THIS_YEAR = Pattern.compile("\\bthis\\s+year\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern LAST_MONTH = Pattern.compile("\\blast\\s+month\\b", Pattern.CASE_INSENSITIVE);

    // Status
    private static final Pattern STATUS_COMPLETED = Pattern.compile(
            "\\b(completed|processed|done|finished)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern STATUS_CORRECTED = Pattern.compile(
            "\\b(corrected|with\\s+corrections?|auto[-\\s]?corrected|fixed)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern STATUS_NEEDS_REVIEW = Pattern.compile(
            "\\b(needs?\\s+review|review|needs?\\s+attention|flagged)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern STATUS_PROCESSING = Pattern.compile(
            "\\b(processing|pending|in[-\\s]?progress)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern STATUS_FAILED = Pattern.compile(
            "\\b(failed|error|errors|with\\s+errors?)\\b", Pattern.CASE_INSENSITIVE);

    // Sorting
    private static final Pattern SORT_LARGEST = Pattern.compile(
            "\\b(largest|biggest|highest|most expensive|top)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern SORT_SMALLEST = Pattern.compile(
            "\\b(smallest|cheapest|lowest)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern SORT_RECENT = Pattern.compile(
            "\\b(recent|latest|newest)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern SORT_OLDEST = Pattern.compile(
            "\\b(oldest|earliest|first)\\b", Pattern.CASE_INSENSITIVE);

    // PO number: explicit "PO-12345" or "PO #123" (must have a non-alpha suffix)
    private static final Pattern PO_NUMBER = Pattern.compile(
            "\\bPO[-#\\s]*(\\w+)\\b", Pattern.CASE_INSENSITIVE);

    // Supplier: "from <Name>", "by <Name>", "supplier <Name>", "vendor <Name>"
    // The captured name must NOT be a pure noise word or month name.
    private static final Pattern SUPPLIER_RAW = Pattern.compile(
            "\\b(?:from|by|supplier|vendor)\\s+([A-Za-z][\\w\\s&,.'-]{1,60})",
            Pattern.CASE_INSENSITIVE);

    /**
     * Words that are NOT supplier names even if they appear after "from/by".
     * Month names are also excluded (checked separately).
     */
    private static final Set<String> NOISE_WORDS = Set.of(
            "pos", "orders", "purchase", "all", "the", "a", "an",
            "this", "last", "next", "today", "yesterday",
            "above", "below", "over", "under", "between",
            "completed", "processing", "failed", "pending", "done", "processed", "finished",
            "corrected", "corrections", "fixed",
            "largest", "biggest", "smallest", "cheapest", "highest", "lowest",
            "recent", "latest", "newest", "oldest", "earliest",
            "january", "february", "march", "april", "may", "june",
            "july", "august", "september", "october", "november", "december",
            "jan", "feb", "mar", "apr", "jun", "jul", "aug", "sep", "oct", "nov", "dec",
            "errors", "error", "month", "year", "date"
    );

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
        int pageSize = 20; // default; overridden for top-N intents

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
        if (STATUS_CORRECTED.matcher(q).find())          { status = "COMPLETED_WITH_CORRECTIONS"; matched = true; }
        else if (STATUS_COMPLETED.matcher(q).find())     { status = "COMPLETED"; matched = true; }
        else if (STATUS_NEEDS_REVIEW.matcher(q).find()) { status = "NEEDS_REVIEW"; matched = true; }
        else if (STATUS_PROCESSING.matcher(q).find())   { status = "PROCESSING";   matched = true; }
        else if (STATUS_FAILED.matcher(q).find())        { status = "FAILED";       matched = true; }

        // ── Sort ────────────────────────────────────────────────────────────
        if (SORT_LARGEST.matcher(q).find()) {
            sortBy = "amount"; sortDir = "desc"; matched = true;
            pageSize = 5; // "largest" = show top 5, not all sorted
        } else if (SORT_SMALLEST.matcher(q).find()) {
            sortBy = "amount"; sortDir = "asc"; matched = true;
            pageSize = 5; // "smallest" = show bottom 5
        } else if (SORT_RECENT.matcher(q).find()) {
            sortBy = "date"; sortDir = "desc"; matched = true;
        } else if (SORT_OLDEST.matcher(q).find()) {
            sortBy = "date"; sortDir = "asc"; matched = true;
        }

        // ── PO Number ───────────────────────────────────────────────────────
        Matcher mPo = PO_NUMBER.matcher(q);
        if (mPo.find()) {
            String candidate = mPo.group(1);
            // Only treat as PO number if it has a digit (actual PO numbers like "10234")
            if (candidate.matches(".*\\d.*")) {
                poNumber = candidate;
                matched = true;
            }
        }

        // ── Supplier ─────────────────────────────────────────────────────────
        // Only extract supplier when the captured text is NOT a noise/date/status word.
        // Also, if we already resolved a date from a "from <month>" phrase, skip that match.
        Matcher mSupplier = SUPPLIER_RAW.matcher(q);
        while (mSupplier.find()) {
            String raw = mSupplier.group(1).trim();
            // Strip trailing noise: anything from a noise word onward
            String cleaned = stripTrailingNoise(raw);
            if (cleaned.isEmpty()) continue;
            String firstWord = cleaned.split("\\s+")[0].toLowerCase();
            if (NOISE_WORDS.contains(firstWord)) continue;
            // If the first (and only) word is a month name, skip — that's a date filter
            if (cleaned.split("\\s+").length == 1 && isMonthName(firstWord)) continue;
            supplier = cleaned;
            matched = true;
            break;
        }

        // If nothing matched and it's a single token without spaces, treat as keyword search
        if (!matched && q.length() >= 2 && q.length() <= 60 && !q.contains(" ")) {
            supplier = q;
            matched = true;
        }

        if (!matched) return Optional.empty();

        return Optional.of(new SearchQuery(
                poNumber, supplier, null,
                minAmount, maxAmount, dateFrom, dateTo,
                status, sortBy, sortDir, 0, pageSize, false));
    }

    /** Strip any trailing noise words from a captured supplier string. */
    private String stripTrailingNoise(String raw) {
        // Remove trailing noise phrases that got captured
        String[] noiseTerms = {
            " above ", " below ", " over ", " under ", " from ", " by ",
            " completed", " processing", " failed", " this ", " in ",
            " january", " february", " march", " april", " may", " june",
            " july", " august", " september", " october", " november", " december"
        };
        String result = " " + raw.toLowerCase();
        int cutAt = raw.length();
        for (String term : noiseTerms) {
            int idx = result.indexOf(term);
            if (idx > 0 && idx < cutAt) cutAt = idx;
        }
        return raw.substring(0, cutAt).trim();
    }

    private boolean isMonthName(String word) {
        return Set.of("january","february","march","april","may","june","july",
                      "august","september","october","november","december").contains(word.toLowerCase());
    }

    private BigDecimal parseMoney(String s) {
        return new BigDecimal(s.replace(",", ""));
    }
}

