import { useState, useEffect } from 'react';
import { useNavigate, useLocation } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import UploadModal from '../components/UploadModal';
import styles from './DashboardPage.module.css';

export default function DashboardPage() {
  const { user } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [uploadOpen, setUploadOpen] = useState(
    location.state?.openUpload === true
  );

  const firstName = user?.name.split(' ')[0] ?? 'there';

  // Clear the openUpload state so browser back doesn't re-open it
  useEffect(() => {
    if (location.state?.openUpload) {
      window.history.replaceState({}, '');
    }
  }, []);

  const handleUploadSuccess = (
    poId: number | null,
    _status: string,
    extractedText: string | null,
    _fileName: string
  ) => {
    setUploadOpen(false);
    if (poId) {
      navigate(`/dashboard/po/${poId}`, { state: { extractedText, fromUpload: true } });
    }
  };

  return (
    <>
      <div className={styles.welcomeRow}>
        <div>
          <h1 className={styles.welcomeHeading}>Good morning, {firstName}! 👋</h1>
          <p className={styles.welcomeSub}>Here's your SmartExtract workspace.</p>
        </div>
      </div>

      {/* Stats row */}
      <div className={styles.statsGrid}>
        {[
          { label: 'Total POs', value: '0', icon: '📄', color: '#dbeafe', iconColor: '#2563eb' },
          { label: 'Processed', value: '0', icon: '✅', color: '#dcfce7', iconColor: '#16a34a' },
          { label: 'Pending', value: '0', icon: '⏳', color: '#fef9c3', iconColor: '#ca8a04' },
          { label: 'Errors', value: '0', icon: '⚠', color: '#fee2e2', iconColor: '#dc2626' },
        ].map((stat) => (
          <div key={stat.label} className={styles.statCard}>
            <div className={styles.statIcon} style={{ background: stat.color, color: stat.iconColor }}>
              {stat.icon}
            </div>
            <div>
              <div className={styles.statValue}>{stat.value}</div>
              <div className={styles.statLabel}>{stat.label}</div>
            </div>
          </div>
        ))}
      </div>

      {/* Empty state */}
      <div className={styles.emptyCard}>
        <div className={styles.emptyIllustration}>
          <div className={styles.emptyIconBg}>
            <span className={styles.emptyIcon}>📂</span>
          </div>
        </div>
        <h2 className={styles.emptyHeading}>No Purchase Orders yet</h2>
        <p className={styles.emptyText}>
          Upload your first Purchase Order and let SmartExtract extract structured data from it automatically using AI.
        </p>
        <button className={styles.uploadBtn} onClick={() => setUploadOpen(true)}>
          <span className={styles.uploadBtnIcon}>+</span>
          Upload Purchase Order
        </button>
      </div>

      {/* Feature cards */}
      <div className={styles.featureGrid}>
        {[
          { icon: '🤖', title: 'AI Extraction', desc: 'Automatically extract line items, totals, dates, vendor info, and more from any PO format.' },
          { icon: '🔍', title: 'Smart Search', desc: 'Search across all your Purchase Orders using natural language queries.' },
          { icon: '📊', title: 'Data Export', desc: 'Export structured data as CSV, JSON, or directly into your ERP system.' },
        ].map((f) => (
          <div key={f.title} className={styles.featureCard}>
            <div className={styles.featureIconWrap}>{f.icon}</div>
            <h3 className={styles.featureTitle}>{f.title}</h3>
            <p className={styles.featureDesc}>{f.desc}</p>
          </div>
        ))}
      </div>

      <UploadModal open={uploadOpen} onClose={() => setUploadOpen(false)} onSuccess={handleUploadSuccess} />
    </>
  );
}
