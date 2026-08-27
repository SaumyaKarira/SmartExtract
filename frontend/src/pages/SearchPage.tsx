import { useState, useEffect, useCallback } from 'react';
import type { FormEvent } from 'react';
import { useNavigate, useLocation } from 'react-router-dom';
import { api } from '../api/client';
import styles from './SearchPage.module.css';

const CHIPS = [
  'Completed POs from August',
  'POs above ₹50,000',
  'POs from ABC Technologies',
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
  status: string | null;
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

interface FilterForm {
  supplier: string;
  status: string;
  minAmount: string;
  maxAmount: string;
  dateFrom: string;
  dateTo: string;
}

const EMPTY_FILTER: FilterForm = {
  supplier: '', status: '', minAmount: '', maxAmount: '', dateFrom: '', dateTo: '',
};

const fmtCurrency = (val: number | null) =>
  val != null
    ? `₹\u00A0${val.toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
    : '—';

const fmtDate = (val: string | null) => {
  if (!val) return '—';
  try {
    return new Date(val).toLocaleDateString('en-IN', { day: 'numeric', month: 'long', year: 'numeric' });
  } catch { return val; }
};

function StatusBadge({ status }: { status: string | null }) {
  if (!status) return null;
  const map: Record<string, { label: string; cls: string }> = {
    COMPLETED:                  { label: '✓ Completed',    cls: styles.badgeCompleted },
    COMPLETED_WITH_CORRECTIONS: { label: '✎ Corrected',    cls: styles.badgeCorrected },
    NEEDS_REVIEW:               { label: '⚠ Needs Review', cls: styles.badgeNeedsReview },
    PROCESSING:                 { label: '⏳ Processing',   cls: styles.badgeProcessing },
    FAILED:                     { label: '✕ Failed',       cls: styles.badgeFailed },
    UPLOADED:                   { label: '↑ Uploaded',     cls: styles.badgeUploaded },
  };
  const info = map[status] ?? { label: status, cls: styles.badgeUploaded };
  return <span className={`${styles.badge} ${info.cls}`}>{info.label}</span>;
}

export interface SearchState {
  query?: string;
  filters?: FilterForm;
  response?: SearchApiResponse;
  page?: number;
}

export default function SearchPage() {
  const navigate = useNavigate();
  const location = useLocation();

  const restored = (location.state as SearchState | null) ?? null;

  const [nlInput, setNlInput] = useState(restored?.query ?? '');
  const [submittedQuery, setSubmittedQuery] = useState(restored?.query ?? '');
  const [filterForm, setFilterForm] = useState<FilterForm>(restored?.filters ?? EMPTY_FILTER);
  const [activeMode, setActiveMode] = useState<'nl' | 'filter'>(restored?.filters ? 'filter' : 'nl');
  const [hasSearched, setHasSearched] = useState(!!restored?.response);
  const [loading, setLoading] = useState(false);
  const [geminiLoading, setGeminiLoading] = useState(false);
  const [response, setResponse] = useState<SearchApiResponse | null>(restored?.response ?? null);
  const [searchError, setSearchError] = useState('');
  const [currentPage, setCurrentPage] = useState(restored?.page ?? 0);
  const [suppliers, setSuppliers] = useState<string[]>([]);

  useEffect(() => {
    api.get<string[]>('/api/search/suppliers')
      .then(list => setSuppliers(list))
      .catch(() => {/* non-critical */});
  }, []);

  const runNlSearch = useCallback(async (query: string, page = 0) => {
    setLoading(true);
    setGeminiLoading(false);
    setSearchError('');
    const geminiTimer = setTimeout(() => setGeminiLoading(true), 400);
    try {
      const res = await api.post<SearchApiResponse>('/api/search', { query });
      clearTimeout(geminiTimer);
      setGeminiLoading(false);
      setResponse(res);
      setCurrentPage(page);
    } catch (err) {
      clearTimeout(geminiTimer);
      setGeminiLoading(false);
      setSearchError(err instanceof Error ? err.message : 'Search failed.');
    } finally {
      setLoading(false);
    }
  }, []);

  const runFilterSearch = useCallback(async (form: FilterForm, page = 0) => {
    setLoading(true);
    setSearchError('');
    try {
      const body: Record<string, unknown> = { page };
      if (form.supplier) body.supplier = form.supplier;
      if (form.status)   body.status   = form.status;
      if (form.minAmount) body.minAmount = form.minAmount;
      if (form.maxAmount) body.maxAmount = form.maxAmount;
      if (form.dateFrom)  body.dateFrom  = form.dateFrom;
      if (form.dateTo)    body.dateTo    = form.dateTo;
      const res = await api.post<SearchApiResponse>('/api/search/filter', body);
      setResponse(res);
      setCurrentPage(page);
    } catch (err) {
      setSearchError(err instanceof Error ? err.message : 'Filter search failed.');
    } finally {
      setLoading(false);
    }
  }, []);

  const handleNlSubmit = (e: FormEvent) => {
    e.preventDefault();
    if (!nlInput.trim()) return;
    setSubmittedQuery(nlInput.trim());
    setActiveMode('nl');
    setHasSearched(true);
    setResponse(null);
    runNlSearch(nlInput.trim(), 0);
  };

  const handleChip = (q: string) => {
    setNlInput(q);
    setSubmittedQuery(q);
    setActiveMode('nl');
    setHasSearched(true);
    setResponse(null);
    runNlSearch(q, 0);
  };

  const handleFilterApply = (e: FormEvent) => {
    e.preventDefault();
    setActiveMode('filter');
    setHasSearched(true);
    setResponse(null);
    runFilterSearch(filterForm, 0);
  };

  const handleFilterClear = () => {
    setFilterForm(EMPTY_FILTER);
    setHasSearched(false);
    setResponse(null);
    setSearchError('');
  };

  const handleClearAll = () => {
    setNlInput('');
    setSubmittedQuery('');
    setFilterForm(EMPTY_FILTER);
    setHasSearched(false);
    setResponse(null);
    setSearchError('');
  };

  const handlePageChange = (newPage: number) => {
    if (activeMode === 'nl') runNlSearch(submittedQuery, newPage);
    else runFilterSearch(filterForm, newPage);
  };

  const openPO = (poId: number) => {
    const state: SearchState = {
      query: activeMode === 'nl' ? submittedQuery : undefined,
      filters: activeMode === 'filter' ? filterForm : undefined,
      response: response ?? undefined,
      page: currentPage,
    };
    navigate(`/dashboard/po/${poId}`, { state: { fromSearch: true, searchState: state } });
  };

  const results = response?.results ?? [];
  const totalPages = response ? Math.ceil(response.totalResults / response.pageSize) : 0;
  const isFilterActive = Object.values(filterForm).some(v => v !== '');

  return (
    <div className={styles.page}>
      <div className={styles.pageHeader}>
        <h1 className={styles.heading}>Search Purchase Orders</h1>
        <p className={styles.subheading}>
          Find purchase orders by supplier, amount, date, status, or ask a question.
        </p>
      </div>

      {/* Natural-language search */}
      <div className={styles.searchCard}>
        <form onSubmit={handleNlSubmit} className={styles.searchForm}>
          <div className={styles.inputWrap}>
            <span className={styles.inputIcon}>🔍</span>
            <input
              type="text"
              className={styles.searchInput}
              placeholder="Ask about your purchase orders…"
              value={nlInput}
              onChange={e => { setNlInput(e.target.value); setActiveMode('nl'); }}
              autoFocus
              disabled={loading}
            />
            {nlInput && !loading && (
              <button type="button" className={styles.clearInputBtn} onClick={() => setNlInput('')}>✕</button>
            )}
          </div>
          <button type="submit" className={styles.searchBtn} disabled={!nlInput.trim() || loading}>
            {loading && activeMode === 'nl' ? '…' : 'Search'}
          </button>
        </form>
        <div className={styles.chips}>
          <span className={styles.chipsLabel}>Try:</span>
          {CHIPS.map(q => (
            <button key={q} className={styles.chip} onClick={() => handleChip(q)} disabled={loading}>
              {q}
            </button>
          ))}
        </div>
      </div>

      {/* Structured Filters */}
      <div className={styles.filterCard}>
        <div className={styles.filterCardHeader}>
          <span className={styles.filterCardTitle}>Search by filters</span>
          {isFilterActive && <span className={styles.filterActiveBadge}>Filters active</span>}
        </div>
        <form onSubmit={handleFilterApply} className={styles.filterGrid}>
          <div className={styles.filterField}>
            <label className={styles.filterLabel}>Supplier</label>
            <select
              className={styles.filterSelect}
              value={filterForm.supplier}
              onChange={e => { setFilterForm(f => ({ ...f, supplier: e.target.value })); setActiveMode('filter'); }}
              disabled={loading}
            >
              <option value="">All suppliers</option>
              {suppliers.map(s => <option key={s} value={s}>{s}</option>)}
            </select>
          </div>

          <div className={styles.filterField}>
            <label className={styles.filterLabel}>Status</label>
            <select
              className={styles.filterSelect}
              value={filterForm.status}
              onChange={e => { setFilterForm(f => ({ ...f, status: e.target.value })); setActiveMode('filter'); }}
              disabled={loading}
            >
              <option value="">All statuses</option>
              <option value="COMPLETED">Completed</option>
              <option value="PROCESSING">Processing</option>
              <option value="FAILED">Failed</option>
            </select>
          </div>

          <div className={styles.filterField}>
            <label className={styles.filterLabel}>Amount</label>
            <div className={styles.amountRow}>
              <input type="number" className={styles.filterInput} placeholder="Min ₹"
                value={filterForm.minAmount}
                onChange={e => { setFilterForm(f => ({ ...f, minAmount: e.target.value })); setActiveMode('filter'); }}
                disabled={loading} min="0" />
              <span className={styles.amountSep}>–</span>
              <input type="number" className={styles.filterInput} placeholder="Max ₹"
                value={filterForm.maxAmount}
                onChange={e => { setFilterForm(f => ({ ...f, maxAmount: e.target.value })); setActiveMode('filter'); }}
                disabled={loading} min="0" />
            </div>
          </div>

          <div className={styles.filterField}>
            <label className={styles.filterLabel}>Date</label>
            <div className={styles.amountRow}>
              <input type="date" className={styles.filterInput}
                value={filterForm.dateFrom}
                onChange={e => { setFilterForm(f => ({ ...f, dateFrom: e.target.value })); setActiveMode('filter'); }}
                disabled={loading} />
              <span className={styles.amountSep}>–</span>
              <input type="date" className={styles.filterInput}
                value={filterForm.dateTo}
                onChange={e => { setFilterForm(f => ({ ...f, dateTo: e.target.value })); setActiveMode('filter'); }}
                disabled={loading} />
            </div>
          </div>

          <div className={styles.filterActions}>
            <button type="submit" className={styles.applyBtn} disabled={!isFilterActive || loading}>
              Apply Filters
            </button>
            <button type="button" className={styles.clearFiltersBtn} onClick={handleFilterClear}>
              Clear
            </button>
          </div>
        </form>
      </div>

      {/* Loading */}
      {loading && (
        <div className={styles.loadingState}>
          <div className={styles.loadingSpinner} />
          <p className={styles.loadingText}>
            {geminiLoading && activeMode === 'nl' ? 'Understanding your search…' : 'Searching…'}
          </p>
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
              {activeMode === 'nl' && submittedQuery && (
                <span className={styles.queryTag}>"{submittedQuery}"</span>
              )}
              <span className={styles.resultCount}>
                {response.totalResults === 0
                  ? 'No results'
                  : `${response.totalResults} purchase order${response.totalResults !== 1 ? 's' : ''} found`}
              </span>
              {response.resolvedBy === 'gemini' && (
                <span className={styles.aiTag}>🤖 AI interpreted</span>
              )}
            </div>
            <button className={styles.newSearchBtn} onClick={handleClearAll}>Clear search</button>
          </div>

          {response.parsedQuery && (
            <p className={styles.parsedDesc}>{response.parsedQuery}</p>
          )}

          {results.length === 0 ? (
            <div className={styles.emptyResults}>
              <span className={styles.emptyIcon}>🔎</span>
              <p className={styles.emptyTitle}>No matching purchase orders</p>
              <p className={styles.emptySubtitle}>
                Try adjusting your filters or search with different terms.
              </p>
              <div className={styles.suggestionChips}>
                {CHIPS.slice(0, 3).map(q => (
                  <button key={q} className={styles.chip} onClick={() => handleChip(q)}>{q}</button>
                ))}
              </div>
            </div>
          ) : (
            <>
              <div className={styles.tableWrapper}>
                <table className={styles.resultsTable}>
                  <thead>
                    <tr>
                      <th className={styles.th}>PO Number</th>
                      <th className={styles.th}>Vendor</th>
                      <th className={styles.th}>PO Date</th>
                      <th className={`${styles.th} ${styles.thCenter}`}>Items</th>
                      <th className={`${styles.th} ${styles.thRight}`}>Amount</th>
                      <th className={styles.th}>Status</th>
                    </tr>
                  </thead>
                  <tbody>
                    {results.map(po => (
                      <tr key={po.id} className={styles.tr}
                        onClick={() => openPO(po.id)}
                        tabIndex={0}
                        onKeyDown={e => e.key === 'Enter' && openPO(po.id)}
                      >
                        <td className={`${styles.td} ${styles.tdPONumber}`}>
                          {po.poNumber ?? `#${po.id}`}
                        </td>
                        <td className={styles.td}>{po.supplier ?? '—'}</td>
                        <td className={styles.td}>{fmtDate(po.orderDate)}</td>
                        <td className={`${styles.td} ${styles.tdCenter}`}>{po.items.length}</td>
                        <td className={`${styles.td} ${styles.tdRight} ${styles.tdAmount}`}>
                          {fmtCurrency(po.total)}
                        </td>
                        <td className={styles.td}><StatusBadge status={po.status} /></td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>

              {totalPages > 1 && (
                <div className={styles.pagination}>
                  <button className={styles.pageBtn}
                    onClick={() => handlePageChange(currentPage - 1)}
                    disabled={currentPage === 0 || loading}>
                    ← Prev
                  </button>
                  <span className={styles.pageInfo}>Page {currentPage + 1} of {totalPages}</span>
                  <button className={styles.pageBtn}
                    onClick={() => handlePageChange(currentPage + 1)}
                    disabled={currentPage >= totalPages - 1 || loading}>
                    Next →
                  </button>
                </div>
              )}
            </>
          )}
        </div>
      )}

      {/* Landing hint */}
      {!hasSearched && !loading && (
        <div className={styles.landingHint}>
          <p className={styles.landingHintText}>
            Use the search bar above or apply filters to find purchase orders.
          </p>
        </div>
      )}
    </div>
  );
}
