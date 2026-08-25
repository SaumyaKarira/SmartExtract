import { useEffect, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import styles from './ProcessingPage.module.css';

const STEPS = [
  { id: 'upload', label: 'Document uploaded' },
  { id: 'read', label: 'Reading document' },
  { id: 'extract', label: 'Extracting information with AI' },
  { id: 'validate', label: 'Validating extracted data' },
  { id: 'save', label: 'Saving purchase order' },
];

// Cumulative delay (ms) at which each step becomes "done"
const STEP_DELAYS = [600, 1600, 3800, 5000, 6000];

type StepStatus = 'done' | 'active' | 'pending';

export default function ProcessingPage() {
  const location = useLocation();
  const navigate = useNavigate();
  const fileName = (location.state as { fileName?: string })?.fileName ?? 'document.pdf';

  const [currentStep, setCurrentStep] = useState(0); // index of currently-active step

  useEffect(() => {
    const timers: ReturnType<typeof setTimeout>[] = [];

    STEP_DELAYS.forEach((delay, idx) => {
      timers.push(
        setTimeout(() => {
          setCurrentStep(idx + 1); // idx+1 means step idx is done
        }, delay)
      );
    });

    // Navigate to PO detail after last step
    const navTimer = setTimeout(() => {
      navigate('/dashboard/po/PO-2026-0042', { replace: true });
    }, STEP_DELAYS[STEP_DELAYS.length - 1] + 600);

    timers.push(navTimer);
    return () => timers.forEach(clearTimeout);
  }, [navigate]);

  const getStatus = (idx: number): StepStatus => {
    if (idx < currentStep) return 'done';
    if (idx === currentStep) return 'active';
    return 'pending';
  };

  const progress = Math.min(100, Math.round((currentStep / STEPS.length) * 100));

  return (
    <div className={styles.page}>
      <div className={styles.card}>
        {/* Animated logo */}
        <div className={styles.logoWrap}>
          <div className={styles.logoPulse} />
          <div className={styles.logoMark}>SE</div>
        </div>

        <h1 className={styles.heading}>Processing your document</h1>
        <p className={styles.subheading}>
          <span className={styles.fileName}>{fileName}</span>
        </p>

        {/* Progress bar */}
        <div className={styles.progressTrack}>
          <div className={styles.progressBar} style={{ width: `${progress}%` }} />
        </div>
        <p className={styles.progressLabel}>{progress}% complete</p>

        {/* Steps */}
        <ul className={styles.steps}>
          {STEPS.map((step, idx) => {
            const status = getStatus(idx);
            return (
              <li key={step.id} className={`${styles.step} ${styles[status]}`}>
                <div className={styles.stepIndicator}>
                  {status === 'done' && <span className={styles.checkIcon}>✓</span>}
                  {status === 'active' && <span className={styles.spinnerIcon} />}
                  {status === 'pending' && <span className={styles.pendingDot} />}
                </div>
                <span className={styles.stepLabel}>{step.label}</span>
              </li>
            );
          })}
        </ul>

        <p className={styles.hint}>This usually takes under 10 seconds.</p>
      </div>
    </div>
  );
}

