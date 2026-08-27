import { useState, useEffect, useMemo, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import { api } from '../api/client';
import styles from './PurchaseOrdersPage.module.css';
import DeleteDocumentModal from '../components/DeleteDocumentModal';

const PAGE_SIZE = 10;

interface PurchaseOrder {
  id: number | null;
  documentId: number;
  fileName: string;
  poNumber: string | null;
  supplier: string | null;
  orderDate: string | null;
  paymentTerms: string | null;
  total: number | null;
  status: string | null;
  createdAt: string;
  retryable: boolean | null;
  errorMessage: string | null;
}

function StatusBadge({ status }: { status: string }) {
  const cls =
    status === 'COMPLETED' ? styles.badgeCompleted
    : status === 'COMPLETED_WITH_CORRECTIONS' ? styles.badgeCorrected
    : status === 'NEEDS_REVIEW' ? styles.badgeNeedsReview
    : status === 'PROCESSING' ? styles.badgeProcessing
    : status === 'FAILED' ? styles.badgeFailed
    : styles.badgeDefault;
  const icon =
    status === 'COMPLETED' ? '✓ '
    : status === 'COMPLETED_WITH_CORRECTIONS' ? '✎ '
    : status === 'NEEDS_REVIEW' ? '⚠ '
    : status === 'PROCESSING' ? '⟳ '
    : status === 'FAILED' ? '✕ '
    : '';
  const label =
    status === 'COMPLETED' ? 'Completed'
    : status === 'COMPLETED_WITH_CORRECTIONS' ? 'Corrected'
    : status === 'NEEDS_REVIEW' ? 'Needs Review'
    : status === 'PROCESSING' ? 'Processing'
    : status === 'FAILED' ? 'Failed'
    : status;
  return <span className={`${styles.badge} ${cls}`}>{icon}{label}</span>;
}

const TrashIcon = () => (
  <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
    <polyline points="3 6 5 6 21 6"/>
    <path d="M19 6l-1 14H6L5 6"/>
    <path d="M10 11v6M14 11v6"/>
    <path d="M9 6V4h6v2"/>
  </svg>
);

const DownloadIcon = () => (
  <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
    <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/>
    <polyline points="7 10 12 15 17 10"/>
    <line x1="12" y1="15" x2="12" y2="3"/>
  </svg>
);

const ChevronDownIcon = () => (
  <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
    <polyline points="6 9 12 15 18 9"/>
  </svg>
);

const RetryIcon = () => (
  <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
    <polyline points="1 4 1 10 7 10"/>
    <path d="M3.51 15a9 9 0 1 0 .49-4.5"/>
  </svg>
);

const fmtCurrency = (val: number | null) =>
  val != null
    ? `₹ ${val.toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
    : '—';

const fmtDate = (val: string | null) => {
  if (!val) return '—';
  try { return new Date(val).toLocaleDateString('en-IN', { day: 'numeric', month: 'long', year: 'numeric' }); }
  catch { return val; }
};

export default function PurchaseOrdersPage() {
  const navigate = useNavigate();
  const [pos, setPos] = useState<PurchaseOrder[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(1);
  const [sortKey, setSortKey] = useState<'orderDate' | 'total' | 'poNumber'>('orderDate');
  const [sortDir, setSortDir] = useState<'asc' | 'desc'>('desc');
  const [exportMenuOpen, setExportMenuOpen] = useState(false);
  const [exportLoading, setExportLoading] = useState(false);
  const exportRef = useRef<HTMLDivElement>(null);
  const [deleteTarget, setDeleteTarget] = useState<{ documentId: number; fileName: string; poId: number | null } | null>(null);
  const [retryingDocId, setRetryingDocId] = useState<number | null>(null);
  const [retryErrors, setRetryErrors] = useState<Record<number, string>>({});

  useEffect(() => {
    const handler = (e: MouseEvent) => {
      if (exportRef.current && !exportRef.current.contains(e.target as Node)) {
        setExportMenuOpen(false);
      }
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, []);

  const handleExport = async (format: 'csv' | 'xlsx' | 'pdf') => {
    setExportMenuOpen(false);
    setExportLoading(true);
    try {
      const blob = await api.getBlob(`/api/export/purchase-orders?format=${format}`);
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      const date = new Date().toISOString().slice(0, 10);
      a.href = url;
      a.download = `purchase-orders-${date}.${format}`;
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      URL.revokeObjectURL(url);
    } catch (err: unknown) {
      alert(err instanceof Error ? err.message : 'Export failed.');
    } finally {
      setExportLoading(false);
    }
  };

  const handleRetry = (documentId: number) => {
    const input = document.createElement('input');
    input.type = 'file';
    input.accept = '.pdf,.docx';
    input.onchange = async () => {
      const file = input.files?.[0];
      if (!file) return;
      setRetryingDocId(documentId);
      setRetryErrors(prev => { const n = { ...prev }; delete n[documentId]; return n; });
      const formData = new FormData();
      formData.append('file', file);
      try {
        await api.postForm(`/api/documents/${documentId}/retry`, formData);
        const data = await api.get<PurchaseOrder[]>('/api/purchase-orders');
        setPos(data);
      } catch (err) {
        setRetryErrors(prev => ({ ...prev, [documentId]: err instanceof Error ? err.message : 'Retry failed.' }));
      } finally {
        setRetryingDocId(null);
      }
    };
    input.click();
  };

  useEffect(() => {
    api.get<PurchaseOrder[]>('/api/purchase-orders')
      .then(data => { setPos(data); setLoading(false); })
      .catch(err => { setError(err instanceof Error ? err.message : 'Failed to load purchase orders.'); setLoading(false); });
  }, []);

  const filtered = useMemo(() => {
    const q = search.toLowerCase();
    return pos
      .filter(po => !q || (po.poNumber ?? '').toLowerCase().includes(q) || (po.supplier ?? '').toLowerCase().includes(q))
      .sort((a, b) => {
        let cmp = 0;
        if (sortKey === 'orderDate') cmp = (a.orderDate ?? '').localeCompare(b.orderDate ?? '');
        else if (sortKey === 'total') cmp = (a.total ?? 0) - (b.total ?? 0);
        else if (sortKey === 'poNumber') cmp = (a.poNumber ?? '').localeCompare(b.poNumber ?? '');
        return sortDir === 'asc' ? cmp : -cmp;
      });
  }, [pos, search, sortKey, sortDir]);

  const totalPages = Math.max(1, Math.ceil(filtered.length / PAGE_SIZE));
  const paginated = filtered.slice((page - 1) * PAGE_SIZE, page * PAGE_SIZE);

  const handleSort = (key: typeof sortKey) => {
    if (sortKey === key) setSortDir(d => d === 'asc' ? 'desc' : 'asc');
    else { setSortKey(key); setSortDir('desc'); }
    setPage(1);
  };

  const sortIcon = (key: typeof sortKey) =>
    sortKey !== key
      ? <span className={styles.sortNeutral}>↕</span>
      : <span className={styles.sortActive}>{sortDir === 'asc' ? '↑' : '↓'}</span>;

  if (loading) return (
    <div className={styles.page}>
      <div className={styles.stateBox}>
        <span className={styles.emptyIcon}>⏳</span>
        <p className={styles.emptyText}>Loading purchase orders…</p>
      </div>
    </div>
  );

  if (error) return (
    <div className={styles.page}>
      <div className={styles.stateBox}>
        <span className={styles.emptyIcon}>⚠️</span>
        <p className={styles.emptyText} style={{ color: '#dc2626' }}>{error}</p>
        <button className={styles.clearFiltersBtn} onClick={() => { setError(''); setLoading(true); api.get<PurchaseOrder[]>('/api/purchase-orders').then(d => { setPos(d); setLoading(false); }).catch(e => { setError(e.message); setLoading(false); }); }}>Retry</button>
      </div>
    </div>
  );

  return (
    <>
    <div className={styles.page}>
      <div className={styles.pageHeader}>
        <div>
          <h1 className={styles.heading}>Purchase Orders</h1>
          <p className={styles.subheading}>Your complete library of uploaded and processed purchase orders.</p>
        </div>
        <div className={styles.pageHeaderActions}>
          <div className={styles.countBadge}>{pos.length} PO{pos.length !== 1 ? 's' : ''}</div>
          {pos.length > 0 && (
            <div ref={exportRef} className={styles.exportWrap}>
              <button
                className={styles.exportBtn}
                onClick={() => !exportLoading && setExportMenuOpen(v => !v)}
                disabled={exportLoading}
                aria-label="Export purchase orders"
              >
                <DownloadIcon />
                <span>{exportLoading ? 'Exporting…' : 'Export'}</span>
                <ChevronDownIcon />
              </button>
              {exportMenuOpen && (
                <div className={styles.exportMenu}>
                  {([
                    { fmt: 'csv',  label: 'CSV',   ext: '.csv',  desc: 'Comma-separated values' },
                    { fmt: 'xlsx', label: 'Excel', ext: '.xlsx', desc: 'Microsoft Excel workbook' },
                    { fmt: 'pdf',  label: 'PDF',   ext: '.pdf',  desc: 'Printable report' },
                  ] as const).map(({ fmt, label, ext, desc }) => (
                    <button
                      key={fmt}
                      className={styles.exportMenuItem}
                      onClick={() => handleExport(fmt)}
                    >
                      <span className={styles.exportMenuLabel}>{label} <span className={styles.exportMenuExt}>{ext}</span></span>
                      <span className={styles.exportMenuDesc}>{desc}</span>
                    </button>
                  ))}
                </div>
              )}
            </div>
          )}
        </div>
      </div>

      <div className={styles.controls}>
        <div className={styles.searchWrap}>
          <span className={styles.searchIcon}>🔍</span>
          <input
            type="text"
            className={styles.searchInput}
            placeholder="Search by PO number or supplier…"
            value={search}
            onChange={e => { setSearch(e.target.value); setPage(1); }}
          />
          {search && <button className={styles.clearBtn} onClick={() => { setSearch(''); setPage(1); }}>✕</button>}
        </div>
      </div>

      {search && (
        <p className={styles.resultsInfo}>
          Showing <strong>{filtered.length}</strong> of {pos.length} purchase orders
        </p>
      )}

      <div className={styles.tableCard}>
        {pos.length === 0 ? (
          <div className={styles.empty}>
            <span className={styles.emptyIcon}>📭</span>
            <p className={styles.emptyText}>No purchase orders yet. Upload a PDF to get started.</p>
          </div>
        ) : paginated.length === 0 ? (
          <div className={styles.empty}>
            <span className={styles.emptyIcon}>🔍</span>
            <p className={styles.emptyText}>No purchase orders match your search.</p>
            <button className={styles.clearFiltersBtn} onClick={() => setSearch('')}>Clear search</button>
          </div>
        ) : (
          <>
            <div className={styles.tableWrapper}>
              <table className={styles.table}>
                <thead>
                  <tr>
                    <th className={styles.th}>
                      <button className={styles.sortBtn} onClick={() => handleSort('poNumber')}>PO Number {sortIcon('poNumber')}</button>
                    </th>
                    <th className={styles.th}>Vendor</th>
                    <th className={styles.th}>
                      <button className={styles.sortBtn} onClick={() => handleSort('orderDate')}>PO Date {sortIcon('orderDate')}</button>
                    </th>
                    <th className={`${styles.th} ${styles.thRight}`}>
                      <button className={styles.sortBtn} onClick={() => handleSort('total')}>Total Amount {sortIcon('total')}</button>
                    </th>
                    <th className={styles.th}>Status</th>
                    <th className={`${styles.th} ${styles.thAction}`}></th>
                  </tr>
                </thead>
                <tbody>
                  {paginated.map(po => {
                    const isFailed = po.id == null;
                    const rowKey = po.id != null ? `po-${po.id}` : `doc-${po.documentId}`;

                    if (isFailed) {
                      return (
                        <tr key={rowKey} className={styles.trFailedOuter}>
                          <td colSpan={6} className={styles.tdFailedCell}>
                            <div className={styles.failedCard}>
                              <div className={styles.failedCardLeft}>
                                <span className={styles.failedFileIcon}>📄</span>
                                <div className={styles.failedCardBody}>
                                  <span className={styles.failedFileName}>{po.fileName}</span>
                                  {po.errorMessage && (
                                    <span className={styles.failedErrorMsg}>{po.errorMessage}</span>
                                  )}
                                  {retryErrors[po.documentId] && (
                                    <span className={styles.failedRetryError}>{retryErrors[po.documentId]}</span>
                                  )}
                                </div>
                              </div>
                              <div className={styles.failedCardRight}>
                                <span className={`${styles.badge} ${styles.badgeFailed}`}>✕ Failed</span>
                                {po.retryable && (
                                  <button
                                    className={styles.retryBtn}
                                    disabled={retryingDocId === po.documentId}
                                    onClick={() => handleRetry(po.documentId)}
                                    title="Re-upload and reprocess this document"
                                  >
                                    <RetryIcon />
                                    {retryingDocId === po.documentId ? 'Retrying…' : 'Retry'}
                                  </button>
                                )}
                                <button
                                  className={styles.deleteRowBtn}
                                  title={`Delete ${po.fileName}`}
                                  aria-label={`Delete ${po.fileName}`}
                                  onClick={() => setDeleteTarget({ documentId: po.documentId, fileName: po.fileName, poId: null })}
                                >
                                  <TrashIcon />
                                </button>
                              </div>
                            </div>
                          </td>
                        </tr>
                      );
                    }

                    return (
                      <tr
                        key={rowKey}
                        className={styles.tr}
                        onClick={() => navigate(`/dashboard/po/${po.id}`, { state: { from: 'po-list' } })}
                      >
                        <td className={styles.td}><span className={styles.poNumber}>{po.poNumber ?? '—'}</span></td>
                        <td className={styles.td}><span className={styles.supplier}>{po.supplier ?? '—'}</span></td>
                        <td className={styles.td}><span className={styles.date}>{fmtDate(po.orderDate)}</span></td>
                        <td className={`${styles.td} ${styles.tdRight}`}><span className={styles.total}>{fmtCurrency(po.total)}</span></td>
                        <td className={styles.td}>{po.status ? <StatusBadge status={po.status} /> : '—'}</td>
                        <td className={`${styles.td} ${styles.tdAction}`} onClick={e => e.stopPropagation()}>
                          <button
                            className={styles.deleteRowBtn}
                            title={`Delete ${po.fileName ?? 'document'}`}
                            aria-label={`Delete ${po.fileName ?? 'document'}`}
                            onClick={() => setDeleteTarget({ documentId: po.documentId, fileName: po.fileName, poId: po.id })}
                          >
                            <TrashIcon />
                          </button>
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>

            {totalPages > 1 && (
              <div className={styles.pagination}>
                <span className={styles.paginationInfo}>Page {page} of {totalPages}</span>
                <div className={styles.paginationBtns}>
                  <button className={styles.pageBtn} disabled={page === 1} onClick={() => setPage(p => p - 1)}>← Prev</button>
                  {Array.from({ length: totalPages }, (_, i) => i + 1).map(p => (
                    <button key={p} className={`${styles.pageBtn} ${p === page ? styles.pageBtnActive : ''}`} onClick={() => setPage(p)}>{p}</button>
                  ))}
                  <button className={styles.pageBtn} disabled={page === totalPages} onClick={() => setPage(p => p + 1)}>Next →</button>
                </div>
              </div>
            )}
          </>
        )}
      </div>
    </div>

    {deleteTarget && (
      <DeleteDocumentModal
        documentId={deleteTarget.documentId}
        documentName={deleteTarget.fileName}
        onCancel={() => setDeleteTarget(null)}
        onDeleted={() => {
          setPos(prev => prev.filter(p =>
            deleteTarget.poId != null ? p.id !== deleteTarget.poId : p.documentId !== deleteTarget.documentId
          ));
          setDeleteTarget(null);
        }}
      />
    )}
  </>
  );
}
