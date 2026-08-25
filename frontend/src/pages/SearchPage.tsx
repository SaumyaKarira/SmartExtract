import { useState, useMemo } from 'react';
import type { FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { MOCK_POS } from '../data/mockPOs';
import type { PurchaseOrder } from '../data/mockPOs';
import styles from './SearchPage.module.css';

const EXAMPLE_QUERIES = [
  'Show POs from ABC Technologies',
  'Find POs above $50,000',
  'Show POs created this month',
  'Processed POs from August',
  'POs with errors',
  'Largest purchase orders',
];

// Simple mock query matcher — parses natural-language-ish queries against the PO fields
function runQuery(query: string, pos: PurchaseOrder[]): PurchaseOrder[] {
  const q = query.toLowerCase().trim();
  if (!q) return [];

  return pos.filter((po) => {
    const supplier = po.supplier.toLowerCase();
    const orderDate = po.orderDate.toLowerCase();

    // supplier match
    if (supplier.includes(q)) return true;
    // PO number match
    if (po.poNumber.toLowerCase().includes(q)) return true;

    // Amount threshold: "above X" / "over X" / "more than X"
    const aboveMatch = q.match(/(?:above|over|more than|greater than)\s*[\$₹]?\s*([\d,]+)/);
    if (aboveMatch) {
      const threshold = parseFloat(aboveMatch[1].replace(/,/g, ''));
      return po.total > threshold;
    }

    // Amount threshold: "below X" / "under X"
    const belowMatch = q.match(/(?:below|under|less than)\s*[\$₹]?\s*([\d,]+)/);
    if (belowMatch) {
      const threshold = parseFloat(belowMatch[1].replace(/,/g, ''));
      return po.total < threshold;
    }

    // Status matches
    if (q.includes('processed') && !q.includes('processing')) return po.status === 'Processed';
    if (q.includes('processing')) return po.status === 'Processing';
    if (q.includes('error') || q.includes('failed')) return po.status === 'Error';

    // Date / month matches
    const months: Record<string, string> = {
      january: 'jan', february: 'feb', march: 'mar', april: 'apr',
      may: 'may', june: 'jun', july: 'jul', august: 'aug',
      september: 'sep', october: 'oct', november: 'nov', december: 'dec',
    };

    for (const [full, short] of Object.entries(months)) {
      if (q.includes(full) || q.includes(short)) {
        return orderDate.includes(short);
      }
    }

    if (q.includes('this month')) {
      return orderDate.includes('aug'); // Aug 2026 is "this month"
    }

    // Keyword: "largest" / "biggest" — return top 3 by total
    if (q.includes('largest') || q.includes('biggest') || q.includes('highest')) {
      const sorted = [...pos].sort((a, b) => b.total - a.total).slice(0, 3);
      return sorted.some((p) => p.poNumber === po.poNumber);
    }

    // Generic keyword fallback — search across all text fields
    const haystack = [
      po.poNumber, po.supplier, po.orderDate, po.deliveryDate,
      po.paymentTerms, po.status, ...po.items.map((i) => i.description),
    ].join(' ').toLowerCase();
    return haystack.includes(q);
  });
}

const fmt = (n: number) =>
  n.toLocaleString('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 0 });

function StatusBadge({ status }: { status: string }) {
  const cls =
    status === 'Processed' ? styles.badgeProcessed
    : status === 'Processing' ? styles.badgeProcessing
    : styles.badgeError;
  return <span className={`${styles.badge} ${cls}`}>{status}</span>;
}

export default function SearchPage() {
  const navigate = useNavigate();
  const [inputValue, setInputValue] = useState('');
  const [submittedQuery, setSubmittedQuery] = useState('');
  const [hasSearched, setHasSearched] = useState(false);

  const results = useMemo(
    () => (hasSearched ? runQuery(submittedQuery, MOCK_POS) : []),
    [submittedQuery, hasSearched],
  );

  const handleSubmit = (e: FormEvent) => {
    e.preventDefault();
    if (!inputValue.trim()) return;
    setSubmittedQuery(inputValue.trim());
    setHasSearched(true);
  };

  const handleExample = (q: string) => {
    setInputValue(q);
    setSubmittedQuery(q);
    setHasSearched(true);
  };

  const handleClear = () => {
    setInputValue('');
    setSubmittedQuery('');
    setHasSearched(false);
  };

  return (
    <div className={styles.page}>
      {/* Page header */}
      <div className={styles.pageHeader}>
        <h1 className={styles.heading}>Search Purchase Orders</h1>
        <p className={styles.subheading}>
          Query across all your POs using plain language — by supplier, amount, date, or status.
        </p>
      </div>

      {/* Search box */}
      <div className={styles.searchCard}>
        <form onSubmit={handleSubmit} className={styles.searchForm}>
          <div className={styles.inputWrap}>
            <span className={styles.inputIcon}>🔍</span>
            <input
              type="text"
              className={styles.searchInput}
              placeholder="Ask anything about your purchase orders…"
              value={inputValue}
              onChange={(e) => setInputValue(e.target.value)}
              autoFocus
            />
            {inputValue && (
              <button type="button" className={styles.clearBtn} onClick={handleClear}>✕</button>
            )}
          </div>
          <button type="submit" className={styles.searchBtn} disabled={!inputValue.trim()}>
            Search
          </button>
        </form>

        {/* Example queries */}
        {!hasSearched && (
          <div className={styles.examples}>
            <p className={styles.examplesLabel}>Try these examples:</p>
            <div className={styles.exampleChips}>
              {EXAMPLE_QUERIES.map((q) => (
                <button key={q} className={styles.exampleChip} onClick={() => handleExample(q)}>
                  {q}
                </button>
              ))}
            </div>
          </div>
        )}
      </div>

      {/* Results */}
      {hasSearched && (
        <div className={styles.resultsSection}>
          <div className={styles.resultsHeader}>
            <div className={styles.resultsHeaderLeft}>
              <span className={styles.queryTag}>"{submittedQuery}"</span>
              <span className={styles.resultCount}>
                {results.length === 0
                  ? 'No results'
                  : `${results.length} result${results.length !== 1 ? 's' : ''}`}
              </span>
            </div>
            <button className={styles.newSearchBtn} onClick={handleClear}>
              New search
            </button>
          </div>

          {results.length === 0 ? (
            <div className={styles.emptyResults}>
              <span className={styles.emptyIcon}>🔎</span>
              <p className={styles.emptyTitle}>No matching purchase orders</p>
              <p className={styles.emptySubtitle}>
                Try different keywords, a supplier name, a status, or an amount range.
              </p>
              <div className={styles.suggestionChips}>
                {EXAMPLE_QUERIES.slice(0, 3).map((q) => (
                  <button key={q} className={styles.exampleChip} onClick={() => handleExample(q)}>
                    {q}
                  </button>
                ))}
              </div>
            </div>
          ) : (
            <div className={styles.resultCards}>
              {results.map((po) => (
                <div
                  key={po.poNumber}
                  className={styles.resultCard}
                  onClick={() => navigate(`/dashboard/po/${po.poNumber}`, { state: { from: 'po-list' } })}
                >
                  <div className={styles.resultCardTop}>
                    <div className={styles.resultCardLeft}>
                      <span className={styles.resultPONumber}>{po.poNumber}</span>
                      <span className={styles.resultSupplier}>{po.supplier}</span>
                    </div>
                    <div className={styles.resultCardRight}>
                      <span className={styles.resultTotal}>{fmt(po.total)}</span>
                      <StatusBadge status={po.status} />
                    </div>
                  </div>
                  <div className={styles.resultCardMeta}>
                    <span className={styles.metaItem}>📅 {po.orderDate}</span>
                    <span className={styles.metaItem}>🚚 {po.deliveryDate}</span>
                    <span className={styles.metaItem}>💳 {po.paymentTerms}</span>
                    <span className={styles.metaItem}>📦 {po.items.length} line items</span>
                  </div>
                  <div className={styles.resultCardArrow}>View details →</div>
                </div>
              ))}
            </div>
          )}
        </div>
      )}

      {/* Empty landing state */}
      {!hasSearched && (
        <div className={styles.landingHint}>
          <div className={styles.hintGrid}>
            {[
              { icon: '🏢', title: 'By supplier', example: 'Show POs from Acme Industrial' },
              { icon: '💰', title: 'By amount', example: 'Find POs above $50,000' },
              { icon: '📅', title: 'By date', example: 'Show POs from August' },
              { icon: '🔖', title: 'By status', example: 'Find all processing POs' },
            ].map((h) => (
              <button key={h.title} className={styles.hintCard} onClick={() => handleExample(h.example)}>
                <span className={styles.hintIcon}>{h.icon}</span>
                <span className={styles.hintTitle}>{h.title}</span>
                <span className={styles.hintExample}>{h.example}</span>
              </button>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}




