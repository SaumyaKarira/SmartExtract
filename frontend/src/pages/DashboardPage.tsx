import { useState, useEffect } from 'react';
import { useNavigate, useLocation } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import UploadModal from '../components/UploadModal';
import { api } from '../api/client';
import styles from './DashboardPage.module.css';

interface DashboardStats {
  total: number;
  completed: number;
  needsReview: number;
  failed: number;
}

export default function DashboardPage() {
  const { user } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [uploadOpen, setUploadOpen] = useState(
    location.state?.openUpload === true
  );
  const [stats, setStats] = useState<DashboardStats>({ total: 0, completed: 0, needsReview: 0, failed: 0 });

  const firstName = user?.name.split(' ')[0] ?? 'there';

  const fetchStats = () => {
    api.get<DashboardStats>('/api/dashboard/stats')
      .then(setStats)
      .catch(() => {});
  };

  useEffect(() => {
    if (location.state?.openUpload) {
      window.history.replaceState({}, '');
    }
    fetchStats();
  }, []);

  const handleModalClose = () => {
    setUploadOpen(false);
    fetchStats(); // always refresh stats when modal closes
  };

  const handleUploadSuccess = (
    poId: number | null,
    _status: string,
    extractedText: string | null,
    _fileName: string
  ) => {
    setUploadOpen(false);
    fetchStats();
    if (poId) {
      navigate(`/dashboard/po/${poId}`, { state: { extractedText, fromUpload: true } });
    }
  };

  const STAT_CARDS = [
    { label: 'Total POs',    value: stats.total,       icon: '📄', color: '#dbeafe', iconColor: '#2563eb' },
    { label: 'Completed',    value: stats.completed,   icon: '✅', color: '#dcfce7', iconColor: '#16a34a' },
    { label: 'Needs Review', value: stats.needsReview, icon: '⚠',  color: '#fff7ed', iconColor: '#c2410c' },
    { label: 'Failed',       value: stats.failed,      icon: '✕',  color: '#fee2e2', iconColor: '#dc2626' },
  ];

  const hasPos = stats.total > 0;

  const hour = new Date().getHours();
  const greeting = hour < 12 ? 'Good morning' : hour < 17 ? 'Good afternoon' : 'Good evening';

  return (
    <>
      {/* Welcome */}
      <div className={styles.welcomeRow}>
        <div>
          <h1 className={styles.welcomeHeading}>{greeting}, {firstName} 👋</h1>
          <p className={styles.welcomeSub}>Here's your SmartExtract workspace.</p>
        </div>
      </div>

      {/* KPI stats — always shown, informational only */}
      <div className={styles.statsGrid}>
        {STAT_CARDS.map((stat) => (
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

      {/* Empty / getting-started card — shown in both states, wording differs */}
      <div className={styles.emptyCard}>
        <div className={styles.emptyIconBg}>
          <span className={styles.emptyIcon}>{hasPos ? '📤' : '📂'}</span>
        </div>
        <h2 className={styles.emptyHeading}>
          {hasPos ? 'Upload Another Purchase Order' : 'No Purchase Orders yet'}
        </h2>
        <p className={styles.emptyText}>
          {hasPos
            ? 'Upload a PDF or DOCX and SmartExtract will extract structured data from it automatically using AI.'
            : 'Upload your first Purchase Order and let SmartExtract extract structured data from it automatically using AI.'}
        </p>
        <button className={styles.uploadBtn} onClick={() => setUploadOpen(true)}>
          <span className={styles.uploadBtnIcon}>+</span>
          Upload Purchase Order
        </button>
      </div>

      {/* Feature cards — always shown */}
      <div className={styles.featureGrid}>
        {[
          { icon: '🤖', title: 'AI Extraction', desc: 'Automatically extract line items, totals, dates, vendor info, and more from any PO format.' },
          { icon: '🔍', title: 'Smart Search',  desc: 'Search across all your Purchase Orders using natural language queries.' },
          { icon: '📊', title: 'Data Export',   desc: 'Export structured data as CSV, Excel or PDF for downstream use.' },
        ].map((f) => (
          <div key={f.title} className={styles.featureCard}>
            <div className={styles.featureIconWrap}>{f.icon}</div>
            <h3 className={styles.featureTitle}>{f.title}</h3>
            <p className={styles.featureDesc}>{f.desc}</p>
          </div>
        ))}
      </div>

      <UploadModal open={uploadOpen} onClose={handleModalClose} onSuccess={handleUploadSuccess} />
    </>
  );
}
