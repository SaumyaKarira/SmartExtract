import { useState, useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import { MOCK_POS } from '../data/mockPOs';
import type { POStatus } from '../data/mockPOs';
import styles from './PurchaseOrdersPage.module.css';

const PAGE_SIZE = 5;

const STATUS_OPTIONS: (POStatus | 'All')[] = ['All', 'Processed', 'Processing', 'Error'];

function StatusBadge({ status }: { status: POStatus }) {
  return (
    <span className={`${styles.badge} ${styles[`badge${status}`]}`}>
      {status === 'Processed' && '✓ '}
      {status === 'Processing' && '⟳ '}
      {status === 'Error' && '✕ '}
      {status}
    </span>
  );
}

const fmt = (n: number) =>
  n.toLocaleString('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 0 });

export default function PurchaseOrdersPage() {
  const navigate = useNavigate();
  const [search, setSearch] = useState('');
  const [statusFilter, setStatusFilter] = useState<POStatus | 'All'>('All');
  const [page, setPage] = useState(1);
  const [sortKey, setSortKey] = useState<'uploadedAt' | 'total' | 'poNumber'>('uploadedAt');
  const [sortDir, setSortDir] = useState<'asc' | 'desc'>('desc');

  const filtered = useMemo(() => {
    const q = search.toLowerCase();
    return MOCK_POS
      .filter((po) => {
        const matchesSearch =
          !q ||
          po.poNumber.toLowerCase().includes(q) ||
          po.supplier.toLowerCase().includes(q);
        const matchesStatus = statusFilter === 'All' || po.status === statusFilter;
        return matchesSearch && matchesStatus;
      })
      .sort((a, b) => {
        let cmp = 0;
        if (sortKey === 'uploadedAt') cmp = a.uploadedAt.localeCompare(b.uploadedAt);
        else if (sortKey === 'total') cmp = a.total - b.total;
        else if (sortKey === 'poNumber') cmp = a.poNumber.localeCompare(b.poNumber);
        return sortDir === 'asc' ? cmp : -cmp;
      });
  }, [search, statusFilter, sortKey, sortDir]);

  const totalPages = Math.max(1, Math.ceil(filtered.length / PAGE_SIZE));
  const paginated = filtered.slice((page - 1) * PAGE_SIZE, page * PAGE_SIZE);

  const handleSort = (key: typeof sortKey) => {
    if (sortKey === key) setSortDir((d) => (d === 'asc' ? 'desc' : 'asc'));
    else { setSortKey(key); setSortDir('desc'); }
    setPage(1);
  };

  const sortIcon = (key: typeof sortKey) => {
    if (sortKey !== key) return <span className={styles.sortNeutral}>↕</span>;
    return <span className={styles.sortActive}>{sortDir === 'asc' ? '↑' : '↓'}</span>;
  };

  return (
    <div className={styles.page}>
      {/* Header */}
      <div className={styles.pageHeader}>
        <div>
          <h1 className={styles.heading}>Purchase Orders</h1>
          <p className={styles.subheading}>
            Your complete library of uploaded and processed purchase orders.
          </p>
        </div>
        <div className={styles.countBadge}>{MOCK_POS.length} POs</div>
      </div>

      {/* Controls */}
      <div className={styles.controls}>
        <div className={styles.searchWrap}>
          <span className={styles.searchIcon}>🔍</span>
          <input
            type="text"
            className={styles.searchInput}
            placeholder="Search by PO number or supplier…"
            value={search}
            onChange={(e) => { setSearch(e.target.value); setPage(1); }}
          />
          {search && (
            <button className={styles.clearBtn} onClick={() => { setSearch(''); setPage(1); }}>✕</button>
          )}
        </div>

        <div className={styles.filterTabs}>
          {STATUS_OPTIONS.map((s) => (
            <button
              key={s}
              className={`${styles.filterTab} ${statusFilter === s ? styles.filterTabActive : ''}`}
              onClick={() => { setStatusFilter(s); setPage(1); }}
            >
              {s}
            </button>
          ))}
        </div>
      </div>

      {/* Results info */}
      {(search || statusFilter !== 'All') && (
        <p className={styles.resultsInfo}>
          Showing <strong>{filtered.length}</strong> of {MOCK_POS.length} purchase orders
        </p>
      )}

      {/* Table */}
      <div className={styles.tableCard}>
        {paginated.length === 0 ? (
          <div className={styles.empty}>
            <span className={styles.emptyIcon}>📭</span>
            <p className={styles.emptyText}>No purchase orders match your filters.</p>
            <button className={styles.clearFiltersBtn} onClick={() => { setSearch(''); setStatusFilter('All'); }}>
              Clear filters
            </button>
          </div>
        ) : (
          <>
            <div className={styles.tableWrapper}>
              <table className={styles.table}>
                <thead>
                  <tr>
                    <th className={styles.th}>
                      <button className={styles.sortBtn} onClick={() => handleSort('poNumber')}>
                        PO Number {sortIcon('poNumber')}
                      </button>
                    </th>
                    <th className={styles.th}>Supplier</th>
                    <th className={styles.th}>
                      <button className={styles.sortBtn} onClick={() => handleSort('uploadedAt')}>
                        Order Date {sortIcon('uploadedAt')}
                      </button>
                    </th>
                    <th className={`${styles.th} ${styles.thRight}`}>
                      <button className={styles.sortBtn} onClick={() => handleSort('total')}>
                        Total {sortIcon('total')}
                      </button>
                    </th>
                    <th className={styles.th}>Status</th>
                    <th className={styles.th} />
                  </tr>
                </thead>
                <tbody>
                  {paginated.map((po) => (
                    <tr
                      key={po.poNumber}
                      className={styles.tr}
                      onClick={() => navigate(`/dashboard/po/${po.poNumber}`, { state: { from: 'po-list' } })}
                    >
                      <td className={styles.td}>
                        <span className={styles.poNumber}>{po.poNumber}</span>
                      </td>
                      <td className={styles.td}>
                        <span className={styles.supplier}>{po.supplier}</span>
                      </td>
                      <td className={styles.td}>
                        <span className={styles.date}>{po.orderDate}</span>
                      </td>
                      <td className={`${styles.td} ${styles.tdRight}`}>
                        <span className={styles.total}>{fmt(po.total)}</span>
                      </td>
                      <td className={styles.td}>
                        <StatusBadge status={po.status} />
                      </td>
                      <td className={`${styles.td} ${styles.tdAction}`}>
                        <span className={styles.viewArrow}>→</span>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            {/* Pagination */}
            {totalPages > 1 && (
              <div className={styles.pagination}>
                <span className={styles.paginationInfo}>
                  Page {page} of {totalPages}
                </span>
                <div className={styles.paginationBtns}>
                  <button
                    className={styles.pageBtn}
                    disabled={page === 1}
                    onClick={() => setPage((p) => p - 1)}
                  >
                    ← Prev
                  </button>
                  {Array.from({ length: totalPages }, (_, i) => i + 1).map((p) => (
                    <button
                      key={p}
                      className={`${styles.pageBtn} ${p === page ? styles.pageBtnActive : ''}`}
                      onClick={() => setPage(p)}
                    >
                      {p}
                    </button>
                  ))}
                  <button
                    className={styles.pageBtn}
                    disabled={page === totalPages}
                    onClick={() => setPage((p) => p + 1)}
                  >
                    Next →
                  </button>
                </div>
              </div>
            )}
          </>
        )}
      </div>
    </div>
  );
}


