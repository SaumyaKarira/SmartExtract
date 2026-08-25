import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import styles from './SettingsPage.module.css';

type Section = 'profile' | 'account' | 'preferences';

function SectionNav({ active, onChange }: { active: Section; onChange: (s: Section) => void }) {
  const items: { id: Section; label: string; icon: string }[] = [
    { id: 'profile', label: 'Profile', icon: '👤' },
    { id: 'account', label: 'Account', icon: '🔐' },
    { id: 'preferences', label: 'Preferences', icon: '🔔' },
  ];
  return (
    <nav className={styles.sectionNav}>
      {items.map((item) => (
        <button
          key={item.id}
          className={`${styles.sectionNavItem} ${active === item.id ? styles.sectionNavItemActive : ''}`}
          onClick={() => onChange(item.id)}
        >
          <span className={styles.sectionNavIcon}>{item.icon}</span>
          {item.label}
        </button>
      ))}
    </nav>
  );
}

function SaveToast({ show }: { show: boolean }) {
  return (
    <div className={`${styles.toast} ${show ? styles.toastVisible : ''}`}>
      ✓ Changes saved
    </div>
  );
}

export default function SettingsPage() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();
  const [section, setSection] = useState<Section>('profile');

  // Profile state
  const [name, setName] = useState(user?.name ?? '');
  const [editingProfile, setEditingProfile] = useState(false);
  const [toastVisible, setToastVisible] = useState(false);

  // Password state
  const [showPassForm, setShowPassForm] = useState(false);
  const [currentPass, setCurrentPass] = useState('');
  const [newPass, setNewPass] = useState('');
  const [confirmPass, setConfirmPass] = useState('');
  const [passError, setPassError] = useState('');
  const [showCurrent, setShowCurrent] = useState(false);
  const [showNew, setShowNew] = useState(false);

  // Preferences
  const [emailNotifs, setEmailNotifs] = useState(true);
  const [processingNotifs, setProcessingNotifs] = useState(true);

  const initials = name
    .split(' ')
    .map((n) => n[0])
    .join('')
    .toUpperCase()
    .slice(0, 2) || 'U';

  const showToast = () => {
    setToastVisible(true);
    setTimeout(() => setToastVisible(false), 2400);
  };

  const handleSaveProfile = () => {
    setEditingProfile(false);
    showToast();
  };

  const handleChangePassword = () => {
    setPassError('');
    if (!currentPass) return setPassError('Current password is required.');
    if (newPass.length < 6) return setPassError('New password must be at least 6 characters.');
    if (newPass !== confirmPass) return setPassError('Passwords do not match.');
    setCurrentPass(''); setNewPass(''); setConfirmPass('');
    setShowPassForm(false);
    showToast();
  };

  const handleLogout = () => {
    logout();
    navigate('/login', { replace: true });
  };

  const handleSavePrefs = () => showToast();

  return (
    <div className={styles.page}>
      <SaveToast show={toastVisible} />

      <div className={styles.pageHeader}>
        <h1 className={styles.heading}>Settings</h1>
        <p className={styles.subheading}>Manage your profile, account security, and preferences.</p>
      </div>

      <div className={styles.layout}>
        <SectionNav active={section} onChange={setSection} />

        <div className={styles.content}>
          {/* ── PROFILE ── */}
          {section === 'profile' && (
            <div className={styles.card}>
              <div className={styles.cardHeader}>
                <h2 className={styles.cardTitle}>Profile</h2>
                <p className={styles.cardDesc}>Your personal information visible within SmartExtract.</p>
              </div>

              <div className={styles.avatarRow}>
                <div className={styles.avatarLarge}>{initials}</div>
                <div className={styles.avatarInfo}>
                  <p className={styles.avatarName}>{name}</p>
                  <p className={styles.avatarEmail}>{user?.email}</p>
                </div>
              </div>

              <div className={styles.divider} />

              {!editingProfile ? (
                <div className={styles.fieldGroup}>
                  <div className={styles.readField}>
                    <span className={styles.readLabel}>Full name</span>
                    <span className={styles.readValue}>{name}</span>
                  </div>
                  <div className={styles.readField}>
                    <span className={styles.readLabel}>Email address</span>
                    <span className={styles.readValue}>{user?.email}</span>
                  </div>
                  <button className={styles.primaryBtn} onClick={() => setEditingProfile(true)}>
                    Edit Profile
                  </button>
                </div>
              ) : (
                <div className={styles.fieldGroup}>
                  <div className={styles.formField}>
                    <label className={styles.label}>Full name</label>
                    <input
                      className={styles.input}
                      value={name}
                      onChange={(e) => setName(e.target.value)}
                      placeholder="Your full name"
                    />
                  </div>
                  <div className={styles.formField}>
                    <label className={styles.label}>Email address</label>
                    <input
                      className={`${styles.input} ${styles.inputDisabled}`}
                      value={user?.email}
                      disabled
                    />
                    <p className={styles.fieldHint}>Email cannot be changed in demo mode.</p>
                  </div>
                  <div className={styles.btnRow}>
                    <button className={styles.ghostBtn} onClick={() => { setEditingProfile(false); setName(user?.name ?? ''); }}>
                      Cancel
                    </button>
                    <button className={styles.primaryBtn} onClick={handleSaveProfile}>
                      Save Changes
                    </button>
                  </div>
                </div>
              )}
            </div>
          )}

          {/* ── ACCOUNT ── */}
          {section === 'account' && (
            <div className={styles.card}>
              <div className={styles.cardHeader}>
                <h2 className={styles.cardTitle}>Account</h2>
                <p className={styles.cardDesc}>Manage your password and account access.</p>
              </div>

              {/* Change password */}
              <div className={styles.accountSection}>
                <div className={styles.accountSectionHeader}>
                  <div>
                    <p className={styles.accountSectionTitle}>Password</p>
                    <p className={styles.accountSectionDesc}>Update your login password.</p>
                  </div>
                  {!showPassForm && (
                    <button className={styles.outlineBtn} onClick={() => setShowPassForm(true)}>
                      Change Password
                    </button>
                  )}
                </div>

                {showPassForm && (
                  <div className={styles.passForm}>
                    {passError && <p className={styles.passError}>⚠ {passError}</p>}
                    <div className={styles.formField}>
                      <label className={styles.label}>Current password</label>
                      <div className={styles.passWrap}>
                        <input
                          className={styles.input}
                          type={showCurrent ? 'text' : 'password'}
                          value={currentPass}
                          onChange={(e) => setCurrentPass(e.target.value)}
                          placeholder="••••••••"
                        />
                        <button type="button" className={styles.passToggle} onClick={() => setShowCurrent(v => !v)}>
                          {showCurrent ? '🙈' : '👁'}
                        </button>
                      </div>
                    </div>
                    <div className={styles.formField}>
                      <label className={styles.label}>New password</label>
                      <div className={styles.passWrap}>
                        <input
                          className={styles.input}
                          type={showNew ? 'text' : 'password'}
                          value={newPass}
                          onChange={(e) => setNewPass(e.target.value)}
                          placeholder="Min. 6 characters"
                        />
                        <button type="button" className={styles.passToggle} onClick={() => setShowNew(v => !v)}>
                          {showNew ? '🙈' : '👁'}
                        </button>
                      </div>
                    </div>
                    <div className={styles.formField}>
                      <label className={styles.label}>Confirm new password</label>
                      <input
                        className={styles.input}
                        type="password"
                        value={confirmPass}
                        onChange={(e) => setConfirmPass(e.target.value)}
                        placeholder="Re-enter new password"
                      />
                    </div>
                    <div className={styles.btnRow}>
                      <button className={styles.ghostBtn} onClick={() => { setShowPassForm(false); setPassError(''); setCurrentPass(''); setNewPass(''); setConfirmPass(''); }}>
                        Cancel
                      </button>
                      <button className={styles.primaryBtn} onClick={handleChangePassword}>
                        Update Password
                      </button>
                    </div>
                  </div>
                )}
              </div>

              <div className={styles.divider} />

              {/* Logout */}
              <div className={styles.accountSection}>
                <div className={styles.accountSectionHeader}>
                  <div>
                    <p className={styles.accountSectionTitle}>Sign out</p>
                    <p className={styles.accountSectionDesc}>Log out of your SmartExtract account on this device.</p>
                  </div>
                  <button className={styles.dangerBtn} onClick={handleLogout}>
                    Sign Out
                  </button>
                </div>
              </div>
            </div>
          )}

          {/* ── PREFERENCES ── */}
          {section === 'preferences' && (
            <div className={styles.card}>
              <div className={styles.cardHeader}>
                <h2 className={styles.cardTitle}>Preferences</h2>
                <p className={styles.cardDesc}>Manage notifications and app behaviour.</p>
              </div>

              <div className={styles.prefList}>
                <div className={styles.prefItem}>
                  <div className={styles.prefText}>
                    <p className={styles.prefTitle}>Email notifications</p>
                    <p className={styles.prefDesc}>Receive a summary email when POs are processed or fail.</p>
                  </div>
                  <button
                    className={`${styles.toggle} ${emailNotifs ? styles.toggleOn : ''}`}
                    onClick={() => setEmailNotifs(v => !v)}
                    aria-label="Toggle email notifications"
                    role="switch"
                    aria-checked={emailNotifs}
                  >
                    <span className={styles.toggleThumb} />
                  </button>
                </div>

                <div className={styles.divider} />

                <div className={styles.prefItem}>
                  <div className={styles.prefText}>
                    <p className={styles.prefTitle}>Processing notifications</p>
                    <p className={styles.prefDesc}>Get an in-app alert when a document finishes AI extraction.</p>
                  </div>
                  <button
                    className={`${styles.toggle} ${processingNotifs ? styles.toggleOn : ''}`}
                    onClick={() => setProcessingNotifs(v => !v)}
                    aria-label="Toggle processing notifications"
                    role="switch"
                    aria-checked={processingNotifs}
                  >
                    <span className={styles.toggleThumb} />
                  </button>
                </div>
              </div>

              <div className={styles.divider} />
              <div className={styles.prefActions}>
                <button className={styles.primaryBtn} onClick={handleSavePrefs}>
                  Save Preferences
                </button>
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

