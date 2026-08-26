import { useState } from 'react';
import type { FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { api } from '../api/client';
import styles from './SearchPage.module.css';

const EXAMPLE_QUERIES = [
  'Show POs from ABC Technologies',
  'Find POs above ₹50,000',
  'Show POs created this month',
  'Completed POs from August',
  'POs with errors',
  'Largest purchase orders',
];

interface POItem {
  id: number;
  description: string | null;
  quantity: number | null;
  unitPrice: number | null;
  totalPrice: number | null;
}

interface POResult {
  id: number;
  poNumber: string | null;
  supplier: string | null;
  orderDate: string | null;
  total: number | null;
  items: POItem[];
}

interface SearchApiResponse {
  parsedQuery: string;
  resolvedBy: string;
  totalResults: number;
  page: number;
  pageSize: number;
  results: POResult[];
}

const fmtCurrency = (val: number | null) =>
  val != null
    ? `₹\u00A0${val.toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
    : '—';

const fmtDate = (val: string | null) => {
  if (!val) return '—';
  try { return new Date(val).toLocaleDateString('en-IN', { day: '2-digit', month: 'short', year: 'numeric' }); }
  catch { return val; }
};

export default function SearchPage() {
  const navigate = useNavigate();
  const [inputValue, setInputValue] = useState('');
  const [submittedQuery, setSubmittedQuery] = useState('');
  const [hasSearched, setHasSearched] = useState(false);
  const [loading, setLoading] = useState(false);
  const [response, setResponse] = useState<SearchApiResponse | null>(null);
  const [searchError, setSearchError] = useState('');

  const runSearch = async (query: string) => {
    setLoading(true);
    setSearchError('');
    setResponse(null);
    try {
      const res = await api.post<SearchApiResponse>('/api/search', { query });
      setResponse(res);
    } catch (err) {
      setSearchError(err instanceof Error ? err.message : 'Search failed.');
    } finally {
      setLoading(false);
    }
  };

  const handleSubmit = (e: FormEvent) => {
    e.preventDefault();
    if (!inputValue.trim()) return;
    setSubmittedQuery(inputValue.trim());
    setHasSearched(true);
    runSearch(inputValue.trim());
  };

  const handleExample = (q: string) => {
    setInputValue(q);
    setSubmittedQuery(q);
    setHasSearched(true);
    runSearch(q);
  };

  const handleClear = () => {
    setInputValue('');
    setSubmittedQuery('');
    setHasSearched(false);
    setResponse(null);
    setSearchError('');
  };

  const results = response?.results ?? [];

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
              disabled={loading}
            />
            {inputValue && !loading && (
              <button type="button" className={styles.clearBtn} onClick={handleClear}>✕</button>
            )}
          </div>
          <button type="submit" className={styles.searchBtn} disabled={!inputValue.trim() || loading}>
            {loading ? '…' : 'Search'}
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

      {/* Loading */}
      {loading && (
        <div className={styles.loadingState}>
          <div className={styles.loadingSpinner} />
          <p className={styles.loadingText}>Searching…</p>
        </div>
      )}

      {/* Error */}
      {!loading && searchError && (
        <div className={styles.errorState}>
          <span>⚠️</span>
          <p>{searchError}</p>
        </div>
      )}

      {/* Results */}
      {!loading && !searchError && hasSearched && response && (
        <div className={styles.resultsSection}>
          <div className={styles.resultsHeader}>
            <div className={styles.resultsHeaderLeft}>
              <span className={styles.queryTag}>"{submittedQuery}"</span>
              <span className={styles.resultCount}>
                {response.totalResults === 0
                  ? 'No results'
                  : `${response.totalResults} result${response.totalResults !== 1 ? 's' : ''}`}
              </span>
              {response.resolvedBy === 'gemini' && (
                <span className={styles.aiTag}>🤖 AI interpreted</span>
              )}
            </div>
            <button className={styles.newSearchBtn} onClick={handleClear}>New search</button>
          </div>

          {response.parsedQuery && (
            <p className={styles.parsedDesc}>Searching for: {response.parsedQuery}</p>
          )}

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
                  key={po.id}
                  className={styles.resultCard}
                  onClick={() => navigate(`/dashboard/po/${po.id}`)}
                >
                  <div className={styles.resultCardTop}>
                    <div className={styles.resultCardLeft}>
                      <span className={styles.resultPONumber}>{po.poNumber ?? `PO #${po.id}`}</span>
                      <span className={styles.resultSupplier}>{po.supplier ?? '—'}</span>
                    </div>
                    <div className={styles.resultCardRight}>
                      <span className={styles.resultTotal}>{fmtCurrency(po.total)}</span>
                    </div>
                  </div>
                  <div className={styles.resultCardMeta}>
                    <span className={styles.metaItem}>📅 {fmtDate(po.orderDate)}</span>
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
              { icon: '💰', title: 'By amount', example: 'Find POs above ₹50,000' },
              { icon: '📅', title: 'By date', example: 'Show POs from August' },
              { icon: '🔖', title: 'By status', example: 'Find all completed POs' },
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
