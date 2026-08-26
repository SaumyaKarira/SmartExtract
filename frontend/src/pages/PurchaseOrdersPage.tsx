import { useState, useEffect, useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import { api } from '../api/client';
import styles from './PurchaseOrdersPage.module.css';

const PAGE_SIZE = 10;

interface PurchaseOrder {
  id: number;
  poNumber: string | null;
  supplier: string | null;
  orderDate: string | null;
  paymentTerms: string | null;
  total: number | null;
  createdAt: string;
}

function StatusBadge({ status }: { status: string }) {
  const cls =
    status === 'COMPLETED' ? styles.badgeCompleted
    : status === 'PROCESSING' ? styles.badgeProcessing
    : status === 'FAILED' ? styles.badgeFailed
    : styles.badgeDefault;
  const icon =
    status === 'COMPLETED' ? '✓ '
    : status === 'PROCESSING' ? '⟳ '
    : status === 'FAILED' ? '✕ '
    : '';
  const label =
    status === 'COMPLETED' ? 'Completed'
    : status === 'PROCESSING' ? 'Processing'
    : status === 'FAILED' ? 'Failed'
    : status;
  return <span className={`${styles.badge} ${cls}`}>{icon}{label}</span>;
}

const fmtCurrency = (val: number | null) =>
  val != null
    ? `₹ ${val.toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
    : '—';

const fmtDate = (val: string | null) => {
  if (!val) return '—';
  try { return new Date(val).toLocaleDateString('en-IN', { day: '2-digit', month: 'short', year: 'numeric' }); }
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
    <div className={styles.page}>
      <div className={styles.pageHeader}>
        <div>
          <h1 className={styles.heading}>Purchase Orders</h1>
          <p className={styles.subheading}>Your complete library of uploaded and processed purchase orders.</p>
        </div>
        <div className={styles.countBadge}>{pos.length} PO{pos.length !== 1 ? 's' : ''}</div>
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
                    <th className={styles.th} />
                  </tr>
                </thead>
                <tbody>
                  {paginated.map(po => (
                    <tr
                      key={po.id}
                      className={styles.tr}
                      onClick={() => navigate(`/dashboard/po/${po.id}`, { state: { from: 'po-list' } })}
                    >
                      <td className={styles.td}><span className={styles.poNumber}>{po.poNumber ?? '—'}</span></td>
                      <td className={styles.td}><span className={styles.supplier}>{po.supplier ?? '—'}</span></td>
                      <td className={styles.td}><span className={styles.date}>{fmtDate(po.orderDate)}</span></td>
                      <td className={`${styles.td} ${styles.tdRight}`}><span className={styles.total}>{fmtCurrency(po.total)}</span></td>
                      <td className={`${styles.td} ${styles.tdAction}`}><span className={styles.viewArrow}>→</span></td>
                    </tr>
                  ))}
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
  );
}
