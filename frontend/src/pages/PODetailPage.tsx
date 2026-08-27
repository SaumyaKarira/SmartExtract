import { useState, useEffect } from 'react';
import { useParams, useLocation, useNavigate } from 'react-router-dom';
import { api } from '../api/client';
import styles from './PODetailPage.module.css';
import type { SearchState } from './SearchPage';
import DeleteDocumentModal from '../components/DeleteDocumentModal';

interface POItem {
  id: number;
  description: string | null;
  quantity: number | null;
  unitPrice: number | null;
  totalPrice: number | null;
}

interface PurchaseOrderDetail {
  id: number;
  documentId: number;
  fileName: string;
  poNumber: string | null;
  supplier: string | null;
  orderDate: string | null;
  deliveryDate: string | null;
  paymentTerms: string | null;
  currency: string | null;
  subtotal: number | null;
  tax: number | null;
  total: number | null;
  createdAt: string;
  items: POItem[];
  status: string | null;
  validationCorrections: string | null;   // JSON string of Correction[]
  validationReviewReasons: string | null; // JSON string of string[]
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

function InfoField({ label, value }: { label: string; value: string }) {
  return (
    <div className={styles.infoField}>
      <span className={styles.infoLabel}>{label}</span>
      <span className={styles.infoValue}>{value}</span>
    </div>
  );
}

export default function PODetailPage() {
  const { poId } = useParams();
  const location = useLocation();
  const navigate = useNavigate();

  const [po, setPo] = useState<PurchaseOrderDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [showDeleteModal, setShowDeleteModal] = useState(false);

  // Source text passed via navigation state (from upload flow)
  const extractedText: string | null = location.state?.extractedText ?? null;
  const fromUpload: boolean = location.state?.fromUpload === true;
  const fromSearch: boolean = location.state?.fromSearch === true;
  const searchState: SearchState | null = location.state?.searchState ?? null;

  useEffect(() => {
    api.get<PurchaseOrderDetail>(`/api/purchase-orders/${poId}`)
      .then(data => { setPo(data); setLoading(false); })
      .catch(err => { setError(err instanceof Error ? err.message : 'Failed to load purchase order.'); setLoading(false); });
  }, [poId]);

  // Parse validation metadata
  interface Correction { field: string; originalValue: number | null; correctedValue: number; reason: string; }
  const corrections: Correction[] = (() => {
    try { return po?.validationCorrections ? JSON.parse(po.validationCorrections) : []; }
    catch { return []; }
  })();
  const reviewReasons: string[] = (() => {
    try { return po?.validationReviewReasons ? JSON.parse(po.validationReviewReasons) : []; }
    catch { return []; }
  })();

  const isCompletedWithCorrections = po?.status === 'COMPLETED_WITH_CORRECTIONS';
  const isNeedsReview = po?.status === 'NEEDS_REVIEW';

  // ── Loading ──────────────────────────────────────────────────────────────
  if (loading) return (
    <div className={styles.page}>
      <div className={styles.loadingBox}>
        <div className={styles.spinner} />
        <p className={styles.loadingText}>Loading purchase order…</p>
      </div>
    </div>
  );

  // ── Error ────────────────────────────────────────────────────────────────
  if (error || !po) return (
    <div className={styles.page}>
      <div className={styles.errorBox}>
        <span className={styles.errorIcon}>⚠️</span>
        <h2 className={styles.errorTitle}>Unable to load purchase order</h2>
        <p className={styles.errorMsg}>{error || 'Purchase order not found.'}</p>
      </div>
    </div>
  );

  return (
    <div className={styles.page}>

      {/* ── Back to Search Results ────────────────────────────────────────── */}
      {fromSearch && (
        <button
          className={styles.backToSearch}
          onClick={() => navigate('/dashboard/search', { state: searchState ?? undefined })}
        >
          ← Back to Search Results
        </button>
      )}

      {/* ── Page Header ─────────────────────────────────────────────────── */}
      <div className={styles.pageHeader}>
        <div className={styles.pageHeaderLeft}>
          <p className={styles.pageTitle}>Purchase Order Details</p>
          <h1 className={styles.poHeading}>{po.poNumber ?? `PO #${po.id}`}</h1>
          <p className={styles.vendorName}>{po.supplier ?? 'Unknown Vendor'}</p>
        </div>
        <div className={styles.pageHeaderRight}>
          {isNeedsReview ? (
            <span className={`${styles.statusBadge} ${styles.statusBadgeOrange}`}>
              ⚠ Needs Review
            </span>
          ) : isCompletedWithCorrections ? (
            <span className={`${styles.statusBadge} ${styles.statusBadgeAmber}`}>
              ✎ Corrected
            </span>
          ) : (
            <span className={`${styles.statusBadge} ${styles.statusBadgeGreen}`}>
              ✓ Completed
            </span>
          )}
          <button
            className={styles.deleteDocBtn}
            onClick={() => setShowDeleteModal(true)}
            aria-label="Delete document"
            title="Delete document"
          >
            🗑 Delete
          </button>
        </div>
      </div>

      {showDeleteModal && po && (
        <DeleteDocumentModal
          documentId={po.documentId}
          documentName={po.fileName ?? `Document #${po.documentId}`}
          onCancel={() => setShowDeleteModal(false)}
          onDeleted={() => navigate('/dashboard/purchase-orders', { replace: true })}
        />
      )}

      {/* ── COMPLETED_WITH_CORRECTIONS banner ────────────────────────────── */}
      {isCompletedWithCorrections && corrections.length > 0 && (
        <div className={styles.correctionsBanner}>
          <span className={styles.correctionsBannerIcon}>✎</span>
          <div className={styles.correctionsBannerBody}>
            <p className={styles.correctionsBannerTitle}>Calculation Corrections Applied</p>
            <p className={styles.correctionsBannerText}>
              SmartExtract detected and automatically corrected {corrections.length} calculation{corrections.length !== 1 ? 's' : ''}.
              Original extracted values are shown below for reference.
            </p>
            <ul className={styles.correctionsList}>
              {corrections.map((c, i) => (
                <li key={i} className={styles.correctionsListItem}>
                  <strong>{c.field}:</strong>{' '}
                  {c.originalValue != null && (
                    <span className={styles.correctionOriginal}>
                      {c.originalValue.toLocaleString('en-IN', { minimumFractionDigits: 2 })}
                    </span>
                  )}
                  <span className={styles.correctionNew}>
                    → {c.correctedValue.toLocaleString('en-IN', { minimumFractionDigits: 2 })}
                  </span>
                  — {c.reason}
                </li>
              ))}
            </ul>
          </div>
        </div>
      )}

      {/* ── NEEDS_REVIEW banner ──────────────────────────────────────────── */}
      {isNeedsReview && reviewReasons.length > 0 && (
        <div className={styles.reviewBanner}>
          <span className={styles.reviewBannerIcon}>⚠️</span>
          <div className={styles.reviewBannerBody}>
            <p className={styles.reviewBannerTitle}>Review Required</p>
            <p className={styles.reviewBannerText}>
              Some fields could not be reliably extracted. Please review the data below before use.
            </p>
            <ul className={styles.reviewList}>
              {reviewReasons.map((r, i) => (
                <li key={i} className={styles.reviewListItem}>{r}</li>
              ))}
            </ul>
          </div>
        </div>
      )}

      {/* ── AI Extracted Details ────────────────────────────────────────── */}
      <div className={styles.card}>
        <div className={styles.cardHeader}>
          <div className={styles.cardHeaderLeft}>
            <span className={styles.cardIcon}>🤖</span>
            <h2 className={styles.cardTitle}>AI Extracted Details</h2>
          </div>
          {fromUpload && (
            <span className={styles.successPill}>✓ AI extraction completed</span>
          )}
        </div>

        <div className={styles.infoGrid}>
          <InfoField label="PO Number" value={po.poNumber ?? '—'} />
          <InfoField label="Vendor" value={po.supplier ?? '—'} />
          <InfoField label="PO Date" value={fmtDate(po.orderDate)} />
          <InfoField label="Payment Terms" value={po.paymentTerms ?? '—'} />
          <InfoField label="Total Amount" value={fmtCurrency(po.total)} />
          {po.deliveryDate && <InfoField label="Delivery Date" value={fmtDate(po.deliveryDate)} />}
          {po.currency && <InfoField label="Currency" value={po.currency} />}
        </div>
      </div>

      {/* ── Line Items ──────────────────────────────────────────────────── */}
      <div className={styles.card}>
        <div className={styles.cardHeader}>
          <div className={styles.cardHeaderLeft}>
            <span className={styles.cardIcon}>📋</span>
            <h2 className={styles.cardTitle}>Line Items</h2>
          </div>
          <span className={styles.itemCount}>{po.items.length} item{po.items.length !== 1 ? 's' : ''}</span>
        </div>

        {po.items.length === 0 ? (
          <p className={styles.noItems}>No line items were extracted from this document.</p>
        ) : (
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
                {po.items.map((item, i) => (
                  <tr key={item.id} className={styles.tr}>
                    <td className={`${styles.td} ${styles.tdIndex}`}>{i + 1}</td>
                    <td className={`${styles.td} ${styles.tdDesc}`}>{item.description ?? '—'}</td>
                    <td className={`${styles.td} ${styles.tdNum}`}>{item.quantity ?? '—'}</td>
                    <td className={`${styles.td} ${styles.tdNum}`}>{fmtCurrency(item.unitPrice)}</td>
                    <td className={`${styles.td} ${styles.tdNum} ${styles.tdTotal}`}>{fmtCurrency(item.totalPrice)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        {/* Order Summary */}
        {(po.subtotal != null || po.tax != null || po.total != null) && (
          <div className={styles.summarySection}>
            <h3 className={styles.summaryTitle}>Order Summary</h3>
            <div className={styles.summaryRows}>
              {po.subtotal != null && (
                <div className={styles.summaryRow}>
                  <span className={styles.summaryLabel}>Subtotal</span>
                  <span className={styles.summaryValue}>{fmtCurrency(po.subtotal)}</span>
                </div>
              )}
              {po.tax != null && (
                <div className={styles.summaryRow}>
                  <span className={styles.summaryLabel}>Tax</span>
                  <span className={styles.summaryValue}>{fmtCurrency(po.tax)}</span>
                </div>
              )}
              {po.total != null && (
                <div className={`${styles.summaryRow} ${styles.summaryGrandTotal}`}>
                  <span className={styles.summaryGrandLabel}>Total</span>
                  <span className={styles.summaryGrandValue}>{fmtCurrency(po.total)}</span>
                </div>
              )}
            </div>
          </div>
        )}
      </div>

      {/* ── Source Text ─────────────────────────────────────────────────── */}
      {extractedText && (
        <div className={styles.card}>
          <details>
            <summary className={styles.sourceTextSummary}>
              <span className={styles.sourceTextIcon}>📄</span>
              View Source Text
              <span className={styles.sourceTextChevron}>›</span>
            </summary>
            <pre className={styles.sourceTextPre}>{extractedText}</pre>
          </details>
        </div>
      )}


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
