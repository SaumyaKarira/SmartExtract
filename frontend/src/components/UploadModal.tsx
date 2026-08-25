import { useState, useRef } from 'react';
import type { DragEvent, ChangeEvent } from 'react';
import styles from './UploadModal.module.css';
import { api } from '../api/client';

interface Props {
  open: boolean;
  onClose: () => void;
}

interface LineItem {
  description: string;
  quantity: number;
  unitPrice: number;
  totalPrice: number;
}

interface ExtractedPO {
  poNumber: string | null;
  vendorName: string | null;
  poDate: string | null;
  paymentTerms: string | null;
  totalAmount: number | null;
  items: LineItem[];
}

interface UploadResult {
  fileName: string;
  status: string;
  extractedText: string | null;
  extractedPurchaseOrder: ExtractedPO | null;
}

type UploadState = 'idle' | 'uploading' | 'success' | 'error';

export default function UploadModal({ open, onClose }: Props) {
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [file, setFile] = useState<File | null>(null);
  const [dragging, setDragging] = useState(false);
  const [error, setError] = useState('');
  const [uploadState, setUploadState] = useState<UploadState>('idle');
  const [uploadResult, setUploadResult] = useState<UploadResult | null>(null);

  if (!open) return null;

  const validate = (f: File) => {
    if (f.type !== 'application/pdf' && !f.name.toLowerCase().endsWith('.pdf')) {
      setError('Only PDF files are accepted.');
      return false;
    }
    if (f.size > 20 * 1024 * 1024) {
      setError('File too large. Maximum size is 20 MB.');
      return false;
    }
    setError('');
    return true;
  };

  const handleFile = (f: File) => { if (validate(f)) setFile(f); };

  const onDrop = (e: DragEvent) => {
    e.preventDefault();
    setDragging(false);
    const f = e.dataTransfer.files[0];
    if (f) handleFile(f);
  };

  const onInputChange = (e: ChangeEvent<HTMLInputElement>) => {
    const f = e.target.files?.[0];
    if (f) handleFile(f);
  };

  const handleProcess = async () => {
    if (!file) return;
    setUploadState('uploading');
    setError('');
    try {
      const formData = new FormData();
      formData.append('file', file);
      const result = await api.postForm<{
        fileName: string;
        status: string;
        extractedText: string | null;
        extractedPurchaseOrder: ExtractedPO | null;
      }>('/api/documents/upload', formData);
      setUploadResult(result);
      setUploadState('success');
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : 'Upload failed.');
      setUploadState('error');
    }
  };

  const handleClose = () => {
    setFile(null);
    setError('');
    setUploadState('idle');
    setUploadResult(null);
    onClose();
  };

  const handleBackdropClick = (e: React.MouseEvent<HTMLDivElement>) => {
    if (e.target === e.currentTarget) handleClose();
  };

  const formatSize = (bytes: number) =>
    bytes < 1024 * 1024
      ? `${(bytes / 1024).toFixed(1)} KB`
      : `${(bytes / (1024 * 1024)).toFixed(1)} MB`;

  return (
    <div className={styles.backdrop} onClick={handleBackdropClick} role="dialog" aria-modal>
      <div className={styles.modal}>
        {/* Header */}
        <div className={styles.header}>
          <div className={styles.headerLeft}>
            <div className={styles.headerIcon}>📤</div>
            <div>
              <h2 className={styles.title}>Upload Purchase Order</h2>
              <p className={styles.subtitle}>Supported format: PDF  Max 20 MB</p>
            </div>
          </div>
          <button className={styles.closeBtn} onClick={handleClose} aria-label="Close">✕</button>
        </div>

        {/* Success / Failed state */}
        {uploadState === 'success' && uploadResult ? (
          <div style={{ padding: '1.25rem 1rem 1.5rem', overflowY: 'auto', maxHeight: '70vh' }}>

            {/* Status banner */}
            {uploadResult.status === 'FAILED' ? (
              /* ── FAILED ── */
              <div style={{ display: 'flex', alignItems: 'flex-start', gap: '0.75rem', marginBottom: '1.25rem',
                            background: '#fef2f2', border: '1px solid #fca5a5', borderRadius: '8px', padding: '0.85rem 1rem' }}>
                <span style={{ fontSize: '1.5rem', lineHeight: 1 }}>❌</span>
                <div>
                  <p style={{ fontWeight: 700, margin: '0 0 0.2rem', color: '#dc2626' }}>Extraction Failed</p>
                  <p style={{ fontSize: '0.82rem', color: '#7f1d1d', margin: 0 }}>
                    AI could not extract structured data from <strong>{uploadResult.fileName}</strong>.
                    The document may be a scanned image or an unrecognised format.
                  </p>
                </div>
              </div>
            ) : (
              /* ── COMPLETED (PROCESSED) ── */
              <>
                {/* Success header */}
                <div style={{ display: 'flex', alignItems: 'center', gap: '0.75rem', marginBottom: '0.85rem' }}>
                  <span style={{ fontSize: '1.75rem' }}>✅</span>
                  <div>
                    <p style={{ fontWeight: 600, margin: 0 }}>{uploadResult.fileName}</p>
                    <p style={{ color: '#16a34a', fontSize: '0.8rem', margin: 0, fontWeight: 600 }}>
                      AI extraction completed
                    </p>
                  </div>
                </div>

                {uploadResult.extractedPurchaseOrder ? (() => {
                  const po = uploadResult.extractedPurchaseOrder!;

                  const formatCurrency = (val: number | null) =>
                    val != null
                      ? `₹ ${val.toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
                      : null;

                  const field = (label: string, value: string | null) => value != null ? (
                    <div style={{ display: 'flex', gap: '0.5rem', marginBottom: '0.35rem', fontSize: '0.85rem' }}>
                      <span style={{ color: '#6b7280', minWidth: '120px', flexShrink: 0 }}>{label}</span>
                      <span style={{ fontWeight: 500, color: '#111827' }}>{value}</span>
                    </div>
                  ) : null;

                  return (
                    <div>
                      <p style={{ fontWeight: 600, fontSize: '0.85rem', color: '#374151', marginBottom: '0.6rem' }}>
                        AI Extracted Details
                      </p>
                      <div style={{ background: '#f9fafb', border: '1px solid #e5e7eb', borderRadius: '8px', padding: '0.85rem 1rem', marginBottom: '1rem' }}>
                        {field('PO Number', po.poNumber)}
                        {field('Vendor', po.vendorName)}
                        {field('Date', po.poDate)}
                        {field('Payment Terms', po.paymentTerms)}
                        {field('Total Amount', formatCurrency(po.totalAmount))}
                      </div>

                      {po.items && po.items.length > 0 && (
                        <>
                          <p style={{ fontWeight: 600, fontSize: '0.85rem', color: '#374151', marginBottom: '0.5rem' }}>
                            Line Items ({po.items.length})
                          </p>
                          <div style={{ border: '1px solid #e5e7eb', borderRadius: '8px', overflow: 'hidden', marginBottom: '1rem' }}>
                            <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '0.78rem' }}>
                              <thead>
                                <tr style={{ background: '#f3f4f6' }}>
                                  {['Description', 'Qty', 'Unit Price', 'Total'].map(h => (
                                    <th key={h} style={{ padding: '0.5rem 0.75rem', textAlign: 'left', fontWeight: 600, color: '#374151', borderBottom: '1px solid #e5e7eb' }}>{h}</th>
                                  ))}
                                </tr>
                              </thead>
                              <tbody>
                                {po.items.map((item, i) => (
                                  <tr key={i} style={{ borderBottom: i < po.items.length - 1 ? '1px solid #f3f4f6' : 'none' }}>
                                    <td style={{ padding: '0.5rem 0.75rem', color: '#111827' }}>{item.description}</td>
                                    <td style={{ padding: '0.5rem 0.75rem', color: '#374151' }}>{item.quantity}</td>
                                    <td style={{ padding: '0.5rem 0.75rem', color: '#374151' }}>
                                      {item.unitPrice != null ? `₹ ${item.unitPrice.toLocaleString('en-IN', { minimumFractionDigits: 2 })}` : '—'}
                                    </td>
                                    <td style={{ padding: '0.5rem 0.75rem', color: '#111827', fontWeight: 500 }}>
                                      {item.totalPrice != null ? `₹ ${item.totalPrice.toLocaleString('en-IN', { minimumFractionDigits: 2 })}` : '—'}
                                    </td>
                                  </tr>
                                ))}
                              </tbody>
                            </table>
                          </div>
                        </>
                      )}

                      {/* Raw source text (collapsed) */}
                      <details style={{ fontSize: '0.78rem', color: '#6b7280' }}>
                        <summary style={{ cursor: 'pointer', userSelect: 'none', marginBottom: '0.4rem', color: '#2563eb', fontWeight: 500 }}>
                          View source text
                        </summary>
                        <pre style={{
                          background: '#f9fafb', border: '1px solid #e5e7eb', borderRadius: '6px',
                          padding: '0.6rem', maxHeight: '180px', overflowY: 'auto',
                          whiteSpace: 'pre-wrap', wordBreak: 'break-word', color: '#374151', margin: 0,
                        }}>{uploadResult.extractedText}</pre>
                      </details>
                    </div>
                  );
                })() : (
                  // Fallback: no PO extracted
                  <>
                    <p style={{ fontWeight: 600, marginBottom: '0.4rem', fontSize: '0.85rem', color: '#374151' }}>Extracted Text</p>
                    <pre style={{
                      background: '#f9fafb', border: '1px solid #e5e7eb', borderRadius: '6px',
                      padding: '0.75rem', fontSize: '0.78rem', lineHeight: '1.6',
                      maxHeight: '260px', overflowY: 'auto', whiteSpace: 'pre-wrap',
                      wordBreak: 'break-word', color: '#111827', margin: 0,
                    }}>{uploadResult.extractedText}</pre>
                  </>
                )}
              </>
            )}

            <div style={{ display: 'flex', justifyContent: 'flex-end', marginTop: '1rem' }}>
              <button className={styles.processBtn} onClick={handleClose}>Done</button>
            </div>
          </div>
        ) : (
          <>
            {/* Drop zone */}
            <div
              className={`${styles.dropZone} ${dragging ? styles.dragging : ''} ${file ? styles.hasFile : ''}`}
              onDragOver={(e) => { e.preventDefault(); setDragging(true); }}
              onDragLeave={() => setDragging(false)}
              onDrop={onDrop}
              onClick={() => !file && fileInputRef.current?.click()}
            >
              <input
                ref={fileInputRef}
                type="file"
                accept=".pdf,application/pdf"
                className={styles.fileInput}
                onChange={onInputChange}
              />

              {file ? (
                <div className={styles.filePreview}>
                  <div className={styles.fileIconWrap}>📄</div>
                  <div className={styles.fileInfo}>
                    <span className={styles.fileName}>{file.name}</span>
                    <span className={styles.fileSize}>{formatSize(file.size)}</span>
                  </div>
                  <button
                    className={styles.removeBtn}
                    onClick={(e) => { e.stopPropagation(); setFile(null); setError(''); setUploadState('idle'); }}
                    aria-label="Remove file"
                    disabled={uploadState === 'uploading'}
                  >
                    ✕
                  </button>
                </div>
              ) : (
                <div className={styles.dropContent}>
                  <div className={styles.dropIconBg}>
                    <span className={styles.dropIcon}>☁</span>
                  </div>
                  <p className={styles.dropPrimary}>
                    {dragging ? 'Drop it here!' : 'Drag & drop your PDF here'}
                  </p>
                  <p className={styles.dropSecondary}>or</p>
                  <button
                    className={styles.chooseBtn}
                    type="button"
                    onClick={(e) => { e.stopPropagation(); fileInputRef.current?.click(); }}
                  >
                    Choose File
                  </button>
                </div>
              )}
            </div>

            {error && <p className={styles.error}>⚠ {error}</p>}

            <div className={styles.formats}>
              <span className={styles.formatChip}>PDF</span>
            </div>

            <div className={styles.footer}>
              <button className={styles.cancelBtn} onClick={handleClose} disabled={uploadState === 'uploading'}>
                Cancel
              </button>
              <button
                className={styles.processBtn}
                disabled={!file || uploadState === 'uploading'}
                onClick={handleProcess}
              >
                {uploadState === 'uploading' ? (
                  <span>Uploading…</span>
                ) : (
                  <>
                    <span>Process Document</span>
                    <span className={styles.processBtnArrow}>→</span>
                  </>
                )}
              </button>
            </div>
          </>
        )}
      </div>
    </div>
  );
}
