import { useState, useRef } from 'react';
import type { DragEvent, ChangeEvent } from 'react';
import styles from './UploadModal.module.css';
import { api } from '../api/client';

interface Props {
  open: boolean;
  onClose: () => void;
  /** Called when upload + AI extraction is done. poId is null on FAILED status. */
  onSuccess?: (poId: number | null, status: string, extractedText: string | null, fileName: string) => void;
}

interface UploadApiResponse {
  id: number;
  purchaseOrderId: number | null;
  fileName: string;
  status: string;
  extractedText: string | null;
  duplicate: boolean;
}

type UploadState = 'idle' | 'uploading' | 'done_failed' | 'done_duplicate' | 'error';

export default function UploadModal({ open, onClose, onSuccess }: Props) {
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [file, setFile] = useState<File | null>(null);
  const [dragging, setDragging] = useState(false);
  const [error, setError] = useState('');
  const [uploadState, setUploadState] = useState<UploadState>('idle');
  const [failedResult, setFailedResult] = useState<{ fileName: string; extractedText: string | null } | null>(null);
  const [duplicateResult, setDuplicateResult] = useState<{ fileName: string; poId: number } | null>(null);

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
      const result = await api.postForm<UploadApiResponse>('/api/documents/upload', formData);

      if (result.duplicate && result.purchaseOrderId) {
        setDuplicateResult({ fileName: result.fileName, poId: result.purchaseOrderId });
        setUploadState('done_duplicate');
      } else if (result.status === 'FAILED' || !result.purchaseOrderId) {
        // Show FAILED state in modal
        setFailedResult({ fileName: result.fileName, extractedText: result.extractedText });
        setUploadState('done_failed');
      } else {
        // Navigate away — let parent handle it
        if (onSuccess) {
          onSuccess(result.purchaseOrderId, result.status, result.extractedText, result.fileName);
        }
      }
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : 'Upload failed.');
      setUploadState('error');
    }
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
              <h2 className={styles.title}>Upload Purchase Order</h2>
              <p className={styles.subtitle}>Supported format: PDF  Max 20 MB</p>
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
        ) : /* FAILED state */
        uploadState === 'done_failed' && failedResult ? (
          <div style={{ padding: '1.25rem 1rem 1.5rem' }}>
            <div style={{ display: 'flex', alignItems: 'flex-start', gap: '0.75rem', marginBottom: '1.25rem',
                          background: '#fef2f2', border: '1px solid #fca5a5', borderRadius: '8px', padding: '0.85rem 1rem' }}>
              <span style={{ fontSize: '1.5rem', lineHeight: 1 }}>❌</span>
              <div>
                <p style={{ fontWeight: 700, margin: '0 0 0.2rem', color: '#dc2626' }}>Extraction Failed</p>
                <p style={{ fontSize: '0.82rem', color: '#7f1d1d', margin: 0 }}>
                  AI could not extract structured data from <strong>{failedResult.fileName}</strong>.
                  The document may be a scanned image or an unrecognised format.
                </p>
              </div>
            </div>
            <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
              <button className={styles.processBtn} onClick={handleClose}>Close</button>
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
