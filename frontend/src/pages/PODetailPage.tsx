import { useNavigate, useParams, useLocation } from 'react-router-dom';
import { MOCK_POS } from '../data/mockPOs';
import styles from './PODetailPage.module.css';

const fmt = (n: number) =>
  n.toLocaleString('en-US', { style: 'currency', currency: 'USD' });

export default function PODetailPage() {
  const navigate = useNavigate();
  const { poId } = useParams();
  const location = useLocation();

  // Find PO from mock data by ID; fall back to first PO (for processing flow)
  const po = MOCK_POS.find((p) => p.poNumber === poId) ?? MOCK_POS[0];

  // Determine where "back" goes depending on how we arrived
  const fromPOList = location.state?.from === 'po-list';
  const handleBack = () =>
    fromPOList ? navigate('/dashboard/purchase-orders') : navigate('/dashboard');

  const statusClass =
    po.status === 'Processed' ? styles.statusBadgeGreen
    : po.status === 'Processing' ? styles.statusBadgeBlue
    : styles.statusBadgeRed;

  return (
    <div className={styles.page}>
      {/* Page header */}
      <div className={styles.pageHeader}>
        <button className={styles.backBtn} onClick={handleBack}>
          ← {fromPOList ? 'Back to Purchase Orders' : 'Back to Dashboard'}
        </button>
        <div className={styles.pageHeaderRight}>
          <span className={`${styles.statusBadge} ${statusClass}`}>
            {po.status === 'Processed' && '✓ '}
            {po.status === 'Processing' && '⟳ '}
            {po.status === 'Error' && '✕ '}
            {po.status}
          </span>
          <button className={styles.viewOriginalBtn} disabled title="Coming soon">
            🔗 View Original Document
          </button>
        </div>
      </div>

      {/* Title row */}
      <div className={styles.titleRow}>
        <div>
          <h1 className={styles.poNumber}>{po.poNumber}</h1>
          <p className={styles.supplier}>{po.supplier}</p>
        </div>
        <div className={styles.totalPill}>{fmt(po.total)}</div>
      </div>

      {/* Meta grid */}
      <div className={styles.metaGrid}>
        {[
          { label: 'Supplier', value: po.supplier },
          { label: 'Supplier Address', value: po.supplierAddress },
          { label: 'Order Date', value: po.orderDate },
          { label: 'Delivery Date', value: po.deliveryDate },
          { label: 'Payment Terms', value: po.paymentTerms },
          { label: 'Currency', value: po.currency },
        ].map((item) => (
          <div key={item.label} className={styles.metaItem}>
            <span className={styles.metaLabel}>{item.label}</span>
            <span className={styles.metaValue}>{item.value}</span>
          </div>
        ))}
      </div>

      {/* Line items table */}
      <div className={styles.tableCard}>
        <div className={styles.tableHeader}>
          <h2 className={styles.tableTitle}>Line Items</h2>
          <span className={styles.tableCount}>{po.items.length} items</span>
        </div>
        <div className={styles.tableWrapper}>
          <table className={styles.table}>
            <thead>
              <tr>
                <th className={styles.th}>#</th>
                <th className={`${styles.th} ${styles.thDesc}`}>Description</th>
                <th className={`${styles.th} ${styles.thNum}`}>Qty</th>
                <th className={`${styles.th} ${styles.thNum}`}>Unit Price</th>
                <th className={`${styles.th} ${styles.thNum}`}>Total</th>
              </tr>
            </thead>
            <tbody>
              {po.items.map((item) => (
                <tr key={item.id} className={styles.tr}>
                  <td className={`${styles.td} ${styles.tdIndex}`}>{item.id}</td>
                  <td className={`${styles.td} ${styles.tdDesc}`}>{item.description}</td>
                  <td className={`${styles.td} ${styles.tdNum}`}>{item.qty}</td>
                  <td className={`${styles.td} ${styles.tdNum}`}>{fmt(item.unitPrice)}</td>
                  <td className={`${styles.td} ${styles.tdNum} ${styles.tdTotal}`}>{fmt(item.total)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        {/* Totals */}
        <div className={styles.totals}>
          <div className={styles.totalRow}>
            <span className={styles.totalLabel}>Subtotal</span>
            <span className={styles.totalValue}>{fmt(po.subtotal)}</span>
          </div>
          <div className={styles.totalRow}>
            <span className={styles.totalLabel}>Tax</span>
            <span className={styles.totalValue}>{fmt(po.tax)}</span>
          </div>
          <div className={styles.totalRow}>
            <span className={styles.totalLabel}>Shipping</span>
            <span className={styles.totalValue}>{fmt(po.shipping)}</span>
          </div>
          <div className={`${styles.totalRow} ${styles.grandTotal}`}>
            <span className={styles.grandTotalLabel}>Total</span>
            <span className={styles.grandTotalValue}>{fmt(po.total)}</span>
          </div>
        </div>
      </div>

      {/* Info banner */}
      <div className={styles.infoBanner}>
        <span className={styles.infoBannerIcon}>🤖</span>
        <p className={styles.infoBannerText}>
          This data was extracted automatically by SmartExtract AI. Please review for accuracy before use.
        </p>
      </div>
    </div>
  );
}
