import { useState } from 'react';
import { api } from '../api/client';
import styles from './DeleteDocumentModal.module.css';

interface Props {
  documentId: number;
  documentName: string;
  onCancel: () => void;
  onDeleted: () => void;
}

export default function DeleteDocumentModal({ documentId, documentName, onCancel, onDeleted }: Props) {
  const [deleting, setDeleting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleDelete = async () => {
    setDeleting(true);
    setError(null);
    try {
      await api.del(`/api/documents/${documentId}`);
      onDeleted();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Deletion failed. Please try again.');
      setDeleting(false);
    }
  };

  return (
    <div className={styles.backdrop} role="dialog" aria-modal="true" aria-labelledby="delete-dialog-title">
      <div className={styles.dialog}>
        <div className={styles.iconWrap}>
          <span className={styles.trashIcon} aria-hidden="true">🗑️</span>
        </div>
        <h2 id="delete-dialog-title" className={styles.title}>Delete Document?</h2>
        <p className={styles.body}>
          You are about to permanently delete{' '}
          <strong className={styles.docName}>{documentName}</strong> and all associated
          purchase order data. This action cannot be undone.
        </p>

        {error && (
          <div className={styles.errorBanner} role="alert">
            <span className={styles.errorIcon}>⚠</span>
            {error}
          </div>
        )}

        <div className={styles.actions}>
          <button
            className={styles.cancelBtn}
            onClick={onCancel}
            disabled={deleting}
          >
            Cancel
          </button>
          <button
            className={styles.deleteBtn}
            onClick={handleDelete}
            disabled={deleting}
          >
            {deleting ? (
              <><span className={styles.spinner} aria-hidden="true" /> Deleting…</>
            ) : (
              'Delete permanently'
            )}
          </button>
        </div>
      </div>
    </div>
  );
}

