import { useState, useRef } from 'react';
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
  /** Pre-selected file for retry (may be undefined if user revisited the page) */
  retryFile?: File;
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

type UploadState = 'idle' | 'uploading' | 'done_failed' | 'done_failed_retryable' | 'done_duplicate' | 'error';

export default function UploadModal({ open, onClose, onSuccess, retryDocumentId, retryFile }: Props) {
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [file, setFile] = useState<File | null>(retryFile ?? null);
  const [dragging, setDragging] = useState(false);
  const [error, setError] = useState('');
  const [uploadState, setUploadState] = useState<UploadState>('idle');
  const [failedResult, setFailedResult] = useState<{
    documentId: number;
    fileName: string;
    extractedText: string | null;
    retryable: boolean;
    errorMessage: string | null;
  } | null>(null);
  const [duplicateResult, setDuplicateResult] = useState<{ fileName: string; poId: number } | null>(null);

  if (!open) return null;

  const isRetryMode = retryDocumentId != null;
  const noFileForRetry = isRetryMode && !retryFile;

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
          setFailedResult({
            documentId: result.id,
            fileName: result.fileName,
            extractedText: result.extractedText,
            retryable: true,
            errorMessage: result.errorMessage,
          });
          setUploadState('done_failed_retryable');
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
    // Reset to idle with the same file so user can re-submit
    setUploadState('idle');
    setError('');
    // Keep the file in state so user can immediately retry
  };

  const handleClose = () => {
    setFile(null);
    setError('');
    setUploadState('idle');
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
      <div className={styles.modal}>
        {/* Header */}
        <div className={styles.header}>
          <div className={styles.headerLeft}>
            <div className={styles.headerIcon}>📤</div>
            <div>
              <h2 className={styles.title}>{isRetryMode ? 'Retry Processing' : 'Upload Purchase Order'}</h2>
              <p className={styles.subtitle}>Supported formats: PDF, DOCX · Max 10 MB · Max 100 pages</p>
            </div>
          </div>
          <button className={styles.closeBtn} onClick={handleClose} aria-label="Close">✕</button>
        </div>

        {/* No file available for retry */}
        {noFileForRetry ? (
          <div style={{ padding: '1.25rem 1rem 1.5rem' }}>
            <div style={{ display: 'flex', alignItems: 'flex-start', gap: '0.75rem', marginBottom: '1.25rem',
                          background: '#fffbeb', border: '1px solid #fcd34d', borderRadius: '8px', padding: '0.85rem 1rem' }}>
              <span style={{ fontSize: '1.5rem', lineHeight: 1 }}>⚠️</span>
              <div>
                <p style={{ fontWeight: 700, margin: '0 0 0.2rem', color: '#92400e' }}>Document No Longer Available</p>
                <p style={{ fontSize: '0.82rem', color: '#78350f', margin: 0 }}>
                  This document is no longer available for retry. Please upload the PDF again.
                </p>
              </div>
            </div>
            <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
              <button className={styles.cancelBtn} onClick={handleClose}>Close</button>
            </div>
          </div>
        ) : /* DUPLICATE state */
        uploadState === 'done_duplicate' && duplicateResult ? (
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
          <div style={{ padding: '1.25rem 1rem 1.5rem' }}>
            <div style={{ display: 'flex', alignItems: 'flex-start', gap: '0.75rem', marginBottom: '1.25rem',
                          background: '#fef2f2', border: '1px solid #fca5a5', borderRadius: '8px', padding: '0.85rem 1rem' }}>
              <span style={{ fontSize: '1.5rem', lineHeight: 1 }}>❌</span>
              <div>
                <p style={{ fontWeight: 700, margin: '0 0 0.2rem', color: '#dc2626' }}>Processing Failed</p>
                <p style={{ fontSize: '0.82rem', color: '#7f1d1d', margin: 0 }}>
                  {failedResult.errorMessage ??
                    `AI could not extract structured data from ${failedResult.fileName}. The document may be a scanned image or an unsupported format.`}
                </p>
              </div>
            </div>
            <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
              <button className={styles.processBtn} onClick={handleClose}>Close</button>
            </div>
          </div>
        ) : /* FAILED (retryable) state */
        uploadState === 'done_failed_retryable' && failedResult ? (
          <div style={{ padding: '1.25rem 1rem 1.5rem' }}>
            <div style={{ display: 'flex', alignItems: 'flex-start', gap: '0.75rem', marginBottom: '1.25rem',
                          background: '#fff7ed', border: '1px solid #fdba74', borderRadius: '8px', padding: '0.85rem 1rem' }}>
              <span style={{ fontSize: '1.5rem', lineHeight: 1 }}>⚠️</span>
              <div>
                <p style={{ fontWeight: 700, margin: '0 0 0.2rem', color: '#c2410c' }}>Temporary Processing Issue</p>
                <p style={{ fontSize: '0.82rem', color: '#7c2d12', margin: 0 }}>
                  {failedResult.errorMessage ??
                    `Processing of ${failedResult.fileName} failed due to a temporary issue. You can retry.`}
                </p>
              </div>
            </div>
            <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '0.5rem' }}>
              <button className={styles.cancelBtn} onClick={handleClose}>Close</button>
              <button className={styles.processBtn} onClick={handleRetry}>
                Retry →
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
