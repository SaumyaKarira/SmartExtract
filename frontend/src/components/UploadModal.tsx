import { useState, useRef, useCallback } from 'react';
import type { DragEvent, ChangeEvent } from 'react';
import styles from './UploadModal.module.css';
import { api } from '../api/client';

interface Props {
  open: boolean;
  onClose: () => void;
  /** Called when upload + AI extraction is done. poId is null on FAILED status. */
  onSuccess?: (poId: number | null, status: string, extractedText: string | null, fileName: string) => void;
  /** If provided, the modal opens directly in retry mode for this document */
  retryDocumentId?: number;
}

interface UploadApiResponse {
  id: number;
  purchaseOrderId: number | null;
  fileName: string;
  status: string;
  extractedText: string | null;
  duplicate: boolean;
  retryable: boolean;
  errorMessage: string | null;
}

type UploadState = 'idle' | 'uploading' | 'done_failed' | 'done_failed_retryable' | 'done_ai_degraded' | 'done_duplicate' | 'error';

export default function UploadModal({ open, onClose, onSuccess, retryDocumentId }: Props) {
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [file, setFile] = useState<File | null>(null);
  const [dragging, setDragging] = useState(false);
  const [error, setError] = useState('');
  const [uploadState, setUploadState] = useState<UploadState>('idle');
  const [retryAttempted, setRetryAttempted] = useState(false);
  const [failedResult, setFailedResult] = useState<{
    documentId: number;
    fileName: string;
    extractedText: string | null;
    retryable: boolean;
    errorMessage: string | null;
  } | null>(null);
  const [duplicateResult, setDuplicateResult] = useState<{ fileName: string; poId: number } | null>(null);

  const [copied, setCopied] = useState(false);

  const handleCopy = useCallback(() => {
    if (failedResult?.extractedText) {
      navigator.clipboard.writeText(failedResult.extractedText).then(() => {
        setCopied(true);
        setTimeout(() => setCopied(false), 2000);
      });
    }
  }, [failedResult]);

  if (!open) return null;

  const isRetryMode = retryDocumentId != null;

  const ACCEPTED_TYPES = ['application/pdf', 'application/vnd.openxmlformats-officedocument.wordprocessingml.document'];
  const ACCEPTED_EXTENSIONS = ['.pdf', '.docx'];

  const validate = (f: File) => {
    const nameLower = f.name.toLowerCase();
    const typeOk = ACCEPTED_TYPES.includes(f.type) || ACCEPTED_EXTENSIONS.some(ext => nameLower.endsWith(ext));
    if (!typeOk) {
      setError('Only PDF and DOCX files are accepted.');
      return false;
    }
    if (f.size > 10 * 1024 * 1024) {
      setError('File is too large. The maximum allowed size is 10 MB.');
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

      let result: UploadApiResponse;
      if (isRetryMode && retryDocumentId != null) {
        result = await api.postForm<UploadApiResponse>(`/api/documents/${retryDocumentId}/retry`, formData);
      } else {
        result = await api.postForm<UploadApiResponse>('/api/documents/upload', formData);
      }

      if (result.duplicate && result.purchaseOrderId) {
        setDuplicateResult({ fileName: result.fileName, poId: result.purchaseOrderId });
        setUploadState('done_duplicate');
      } else if (result.status === 'FAILED' || !result.purchaseOrderId) {
        if (result.retryable) {
          const failData = {
            documentId: result.id,
            fileName: result.fileName,
            extractedText: result.extractedText,
            retryable: true,
            errorMessage: result.errorMessage,
          };
          setFailedResult(failData);
          // If user already retried once and AI still fails, show degraded view with raw text
          if (retryAttempted && result.extractedText) {
            setUploadState('done_ai_degraded');
          } else {
            setUploadState('done_failed_retryable');
          }
        } else {
          setFailedResult({
            documentId: result.id,
            fileName: result.fileName,
            extractedText: result.extractedText,
            retryable: false,
            errorMessage: result.errorMessage,
          });
          setUploadState('done_failed');
        }
      } else {
        if (onSuccess) {
          onSuccess(result.purchaseOrderId, result.status, result.extractedText, result.fileName);
        }
      }
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : 'Upload failed. Please try again.');
      setUploadState('error');
    }
  };

  const handleRetry = () => {
    if (!failedResult) return;
    setRetryAttempted(true);
    setUploadState('idle');
    setError('');
  };

  const handleClose = () => {
    setFile(null);
    setError('');
    setUploadState('idle');
    setRetryAttempted(false);
    setFailedResult(null);
    setDuplicateResult(null);
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
      <div className={styles.modal} style={uploadState === 'done_ai_degraded' ? { maxWidth: '680px' } : undefined}>
        {/* Header */}
        <div className={styles.header}>
          <div className={styles.headerLeft}>
            <div className={styles.headerIcon}>📤</div>
            <div>
              <h2 className={styles.title}>
                {uploadState === 'done_ai_degraded' ? 'AI Service Unavailable' : isRetryMode ? 'Retry Processing' : 'Upload Purchase Order'}
              </h2>
              <p className={styles.subtitle}>
                {uploadState === 'done_ai_degraded'
                  ? 'Raw document text extracted successfully'
                  : 'Supported formats: PDF, DOCX · Max 10 MB · Max 100 pages'}
              </p>
            </div>
          </div>
          <button className={styles.closeBtn} onClick={handleClose} aria-label="Close">✕</button>
        </div>

        {/* DUPLICATE state */}
        {uploadState === 'done_duplicate' && duplicateResult ? (
          <div style={{ padding: '1.25rem 1rem 1.5rem' }}>
            <div style={{ display: 'flex', alignItems: 'flex-start', gap: '0.75rem', marginBottom: '1.25rem',
                          background: '#eff6ff', border: '1px solid #93c5fd', borderRadius: '8px', padding: '0.85rem 1rem' }}>
              <span style={{ fontSize: '1.5rem', lineHeight: 1 }}>📄</span>
              <div>
                <p style={{ fontWeight: 700, margin: '0 0 0.2rem', color: '#1d4ed8' }}>Document Already Uploaded</p>
                <p style={{ fontSize: '0.82rem', color: '#1e3a8a', margin: 0 }}>
                  <strong>{duplicateResult.fileName}</strong> has already been uploaded and processed.
                </p>
              </div>
            </div>
            <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '0.5rem' }}>
              <button className={styles.cancelBtn} onClick={handleClose}>Close</button>
              <button className={styles.processBtn} onClick={() => {
                handleClose();
                if (onSuccess) onSuccess(duplicateResult.poId, 'COMPLETED', null, duplicateResult.fileName);
              }}>
                View Existing Purchase Order →
              </button>
            </div>
          </div>
        ) : /* FAILED (permanent) state */
        uploadState === 'done_failed' && failedResult ? (
          <div className={styles.resultBody}>
            <div className={`${styles.resultBanner} ${styles.resultBannerFailed}`}>
              <span className={styles.resultBannerIcon}>
                <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="#dc2626" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                  <circle cx="12" cy="12" r="10"/>
                  <line x1="15" y1="9" x2="9" y2="15"/>
                  <line x1="9" y1="9" x2="15" y2="15"/>
                </svg>
              </span>
              <div className={styles.resultBannerContent}>
                <p className={`${styles.resultBannerTitle} ${styles.resultBannerTitleFailed}`}>Extraction Failed</p>
                <p className={`${styles.resultBannerMsg} ${styles.resultBannerMsgFailed}`}>
                  {failedResult.errorMessage ??
                    `SmartExtract could not extract structured data from "${failedResult.fileName}". The document may be a scanned image, password-protected, or an unsupported format.`}
                </p>
              </div>
            </div>
            <div className={styles.resultFooter}>
              <button className={styles.processBtn} onClick={handleClose}>Close</button>
            </div>
          </div>
        ) : /* FAILED (retryable) state */
        uploadState === 'done_failed_retryable' && failedResult ? (
          <div className={styles.resultBody}>
            <div className={`${styles.resultBanner} ${styles.resultBannerRetryable}`}>
              <span className={styles.resultBannerIcon}>
                <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="#b45309" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                  <path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/>
                  <line x1="12" y1="9" x2="12" y2="13"/>
                  <line x1="12" y1="17" x2="12.01" y2="17"/>
                </svg>
              </span>
              <div className={styles.resultBannerContent}>
                <p className={`${styles.resultBannerTitle} ${styles.resultBannerTitleRetryable}`}>Temporary Processing Issue</p>
                <p className={`${styles.resultBannerMsg} ${styles.resultBannerMsgRetryable}`}>
                  {failedResult.errorMessage ??
                    `Processing of "${failedResult.fileName}" failed due to a temporary service issue. Please try again.`}
                </p>
              </div>
            </div>
            <div className={styles.resultFooter}>
              <button className={styles.cancelBtn} onClick={handleClose}>Close</button>
              <button className={styles.processBtn} onClick={handleRetry}>Retry</button>
            </div>
          </div>
        ) : /* AI DEGRADED state — retry also failed, show raw extracted text */
        uploadState === 'done_ai_degraded' && failedResult ? (
          <div>
            {/* Status notice */}
            <div style={{
              margin: '0',
              padding: '14px 24px',
              background: '#fffbeb',
              borderBottom: '1px solid #fde68a',
              display: 'flex',
              alignItems: 'flex-start',
              gap: '12px',
            }}>
              <svg style={{ flexShrink: 0, marginTop: '1px' }} width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#d97706" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                <path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/>
                <line x1="12" y1="9" x2="12" y2="13"/>
                <line x1="12" y1="17" x2="12.01" y2="17"/>
              </svg>
              <div>
                <p style={{ margin: '0 0 2px', fontSize: '13px', fontWeight: 700, color: '#92400e' }}>
                  AI extraction is currently unavailable
                </p>
                <p style={{ margin: 0, fontSize: '12px', color: '#b45309', lineHeight: '1.5' }}>
                  The service failed after retrying. We've extracted the raw text from your document below so you can review it manually. The document has been recorded as failed and you can retry later from the Purchase Orders page.
                </p>
              </div>
            </div>

            {/* Raw text panel */}
            <div style={{ padding: '16px 24px 0' }}>
              {/* Panel header with file info + copy button */}
              <div style={{
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'space-between',
                marginBottom: '8px',
              }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: '7px' }}>
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#64748b" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                    <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/>
                    <polyline points="14 2 14 8 20 8"/>
                  </svg>
                  <span style={{ fontSize: '12px', fontWeight: 600, color: '#475569' }}>
                    {failedResult.fileName}
                  </span>
                  <span style={{
                    fontSize: '11px',
                    color: '#94a3b8',
                    background: '#f1f5f9',
                    border: '1px solid #e2e8f0',
                    borderRadius: '4px',
                    padding: '1px 6px',
                    fontWeight: 500,
                  }}>
                    {failedResult.extractedText
                      ? `${failedResult.extractedText.split('\n').length} lines`
                      : ''}
                  </span>
                </div>
                <button
                  onClick={handleCopy}
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    gap: '5px',
                    padding: '4px 10px',
                    fontSize: '12px',
                    fontWeight: 600,
                    color: copied ? '#16a34a' : '#475569',
                    background: copied ? '#f0fdf4' : '#f8fafc',
                    border: `1px solid ${copied ? '#86efac' : '#e2e8f0'}`,
                    borderRadius: '6px',
                    cursor: 'pointer',
                    transition: 'all 0.15s',
                  }}
                >
                  {copied ? (
                    <>
                      <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="#16a34a" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
                        <polyline points="20 6 9 17 4 12"/>
                      </svg>
                      Copied!
                    </>
                  ) : (
                    <>
                      <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                        <rect x="9" y="9" width="13" height="13" rx="2" ry="2"/>
                        <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/>
                      </svg>
                      Copy text
                    </>
                  )}
                </button>
              </div>

              {/* Scrollable text area */}
              <div style={{
                border: '1px solid #e2e8f0',
                borderRadius: '8px',
                overflow: 'hidden',
                background: '#f8fafc',
              }}>
                <pre style={{
                  margin: 0,
                  padding: '14px 16px',
                  fontSize: '12px',
                  lineHeight: '1.75',
                  color: '#334155',
                  whiteSpace: 'pre-wrap',
                  wordBreak: 'break-word',
                  maxHeight: '280px',
                  overflowY: 'auto',
                  fontFamily: "'SF Mono', 'Fira Code', 'Cascadia Code', ui-monospace, monospace",
                }}>
                  {failedResult.extractedText}
                </pre>
              </div>

              {/* Helper tip */}
              <p style={{ margin: '10px 0 0', fontSize: '11.5px', color: '#94a3b8', lineHeight: '1.5' }}>
                💡 Tip: Copy this text and paste it into your preferred tool, or retry the upload later when the AI service recovers.
              </p>
            </div>

            {/* Footer */}
            <div style={{
              display: 'flex',
              justifyContent: 'flex-end',
              gap: '10px',
              padding: '16px 24px 20px',
            }}>
              <button className={styles.cancelBtn} onClick={handleClose}>
                Close
              </button>
              <button className={styles.processBtn} onClick={handleCopy} style={copied ? { background: '#16a34a' } : undefined}>
                {copied ? '✓ Copied!' : 'Copy Extracted Text'}
              </button>
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
                accept=".pdf,.docx,application/pdf,application/vnd.openxmlformats-officedocument.wordprocessingml.document"
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
                    {dragging ? 'Drop it here!' : 'Drag & drop your PDF or DOCX here'}
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
              <span className={styles.formatChip}>DOCX</span>
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
                    <span>{isRetryMode ? 'Retry Processing' : 'Process Document'}</span>
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
