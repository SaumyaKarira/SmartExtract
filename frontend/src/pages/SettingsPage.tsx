import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { api } from '../api/client';
import styles from './SettingsPage.module.css';

type Section = 'profile' | 'account';

function SectionNav({ active, onChange }: { active: Section; onChange: (s: Section) => void }) {
  const items: { id: Section; label: string; icon: string }[] = [
    { id: 'profile', label: 'Profile', icon: '👤' },
    { id: 'account', label: 'Account', icon: '🔐' },
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

function Toast({ show, message, success }: { show: boolean; message: string; success: boolean }) {
  return (
    <div className={`${styles.toast} ${show ? styles.toastVisible : ''}`}
         style={{ background: success ? '#166534' : '#991b1b' }}>
      {success ? '✓' : '⚠'} {message}
    </div>
  );
}

export default function SettingsPage() {
  const { user, logout, updateUser } = useAuth();
  const navigate = useNavigate();
  const [section, setSection] = useState<Section>('profile');

  // Toast
  const [toast, setToast] = useState<{ show: boolean; message: string; success: boolean }>({
    show: false, message: '', success: true,
  });
  const showToast = (message: string, success = true) => {
    setToast({ show: true, message, success });
    setTimeout(() => setToast(t => ({ ...t, show: false })), 2800);
  };

  // Profile state
  const [name, setName] = useState(user?.name ?? '');
  const [editingProfile, setEditingProfile] = useState(false);
  const [profileLoading, setProfileLoading] = useState(false);
  const [profileError, setProfileError] = useState('');

  // Password state
  const [showPassForm, setShowPassForm] = useState(false);
  const [currentPass, setCurrentPass] = useState('');
  const [newPass, setNewPass] = useState('');
  const [confirmPass, setConfirmPass] = useState('');
  const [passError, setPassError] = useState('');
  const [passLoading, setPassLoading] = useState(false);
  const [showCurrent, setShowCurrent] = useState(false);
  const [showNew, setShowNew] = useState(false);

  const initials =
    (user?.name ?? '')
    .split(' ')
    .map((n) => n[0])
    .join('')
    .toUpperCase()
    .slice(0, 2) || 'U';

  // Password state

  const handleSaveProfile = async () => {
    setProfileError('');
    if (!name.trim()) { setProfileError('Name cannot be empty.'); return; }
    if (name.trim().length > 100) { setProfileError('Name must be 100 characters or fewer.'); return; }
    setProfileLoading(true);
    try {
      const result = await api.patch<{ id: number; name: string; email: string }>(
        '/api/user/profile', { name: name.trim() });
      updateUser({ name: result.name });
      setEditingProfile(false);
      showToast('Profile updated successfully.');
    } catch (err: unknown) {
      setProfileError(err instanceof Error ? err.message : 'Failed to update profile.');
    } finally {
      setProfileLoading(false);
    }
  };

  const handleChangePassword = async () => {
    setPassError('');
    if (!currentPass) { setPassError('Current password is required.'); return; }
    if (newPass.length < 6) { setPassError('New password must be at least 6 characters.'); return; }
    if (newPass !== confirmPass) { setPassError('Passwords do not match.'); return; }
    setPassLoading(true);
    try {
      await api.post('/api/user/change-password', { currentPassword: currentPass, newPassword: newPass });
      setCurrentPass(''); setNewPass(''); setConfirmPass('');
      setShowPassForm(false);
      showToast('Password changed successfully.');
    } catch (err: unknown) {
      setPassError(err instanceof Error ? err.message : 'Failed to change password.');
    } finally {
      setPassLoading(false);
    }
  };

  const handleLogout = () => {
    logout();
    navigate('/login', { replace: true });
  };

  return (
    <div className={styles.page}>
      <Toast show={toast.show} message={toast.message} success={toast.success} />

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
                  <button className={styles.primaryBtn} onClick={() => { setEditingProfile(true); setProfileError(''); }}>
                    Edit Profile
                  </button>
                </div>
              ) : (
                <div className={styles.fieldGroup}>
                  {profileError && (
                    <div className={styles.inlineError}>⚠ {profileError}</div>
                  )}
                  <div className={styles.formField}>
                    <label className={styles.label}>Full name</label>
                    <input
                      className={`${styles.input} ${profileError ? styles.inputError : ''}`}
                      value={name}
                      onChange={(e) => { setName(e.target.value); setProfileError(''); }}
                      placeholder="Your full name"
                      disabled={profileLoading}
                    />
                  </div>
                  <div className={styles.formField}>
                    <label className={styles.label}>Email address</label>
                    <input
                      className={`${styles.input} ${styles.inputDisabled}`}
                      value={user?.email}
                      disabled
                    />
                    <p className={styles.fieldHint}>Email address cannot be changed.</p>
                  </div>
                  <div className={styles.btnRow}>
                    <button className={styles.ghostBtn} disabled={profileLoading}
                      onClick={() => { setEditingProfile(false); setName(user?.name ?? ''); setProfileError(''); }}>
                      Cancel
                    </button>
                    <button className={styles.primaryBtn} onClick={handleSaveProfile} disabled={profileLoading}>
                      {profileLoading ? 'Saving…' : 'Save Changes'}
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

              <div className={styles.accountSection}>
                <div className={styles.accountSectionHeader}>
                  <div>
                    <p className={styles.accountSectionTitle}>Password</p>
                    <p className={styles.accountSectionDesc}>Update your login password.</p>
                  </div>
                  {!showPassForm && (
                    <button className={styles.outlineBtn} onClick={() => { setShowPassForm(true); setPassError(''); }}>
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
                          onChange={(e) => { setCurrentPass(e.target.value); setPassError(''); }}
                          placeholder="••••••••"
                          disabled={passLoading}
                        />
                        <button type="button" className={styles.passToggle}
                          onClick={() => setShowCurrent(v => !v)}
                          aria-label={showCurrent ? 'Hide password' : 'Show password'}>
                          {showCurrent
                            ? <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94"/><path d="M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19"/><line x1="1" y1="1" x2="23" y2="23"/></svg>
                            : <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/></svg>
                          }
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
                          onChange={(e) => { setNewPass(e.target.value); setPassError(''); }}
                          placeholder="Min. 6 characters"
                          disabled={passLoading}
                        />
                        <button type="button" className={styles.passToggle}
                          onClick={() => setShowNew(v => !v)}
                          aria-label={showNew ? 'Hide password' : 'Show password'}>
                          {showNew
                            ? <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94"/><path d="M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19"/><line x1="1" y1="1" x2="23" y2="23"/></svg>
                            : <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/></svg>
                          }
                        </button>
                      </div>
                    </div>
                    <div className={styles.formField}>
                      <label className={styles.label}>Confirm new password</label>
                      <input
                        className={styles.input}
                        type="password"
                        value={confirmPass}
                        onChange={(e) => { setConfirmPass(e.target.value); setPassError(''); }}
                        placeholder="Re-enter new password"
                        disabled={passLoading}
                      />
                    </div>
                    <div className={styles.btnRow}>
                      <button className={styles.ghostBtn} disabled={passLoading}
                        onClick={() => { setShowPassForm(false); setPassError(''); setCurrentPass(''); setNewPass(''); setConfirmPass(''); }}>
                        Cancel
                      </button>
                      <button className={styles.primaryBtn} onClick={handleChangePassword} disabled={passLoading}>
                        {passLoading ? 'Updating…' : 'Update Password'}
                      </button>
                    </div>
                  </div>
                )}
              </div>

              <div className={styles.divider} />

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

        </div>
      </div>
    </div>
  );
}
