import { useState, useRef, useEffect } from 'react';
import { NavLink, Outlet, useNavigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import styles from './AppLayout.module.css';

const NAV_ITEMS = [
  { icon: '⊞', label: 'Dashboard', to: '/dashboard' },
  { icon: '📄', label: 'Purchase Orders', to: '/dashboard/purchase-orders' },
  { icon: '🔍', label: 'Search', to: '/dashboard/search' },
  { icon: '⚙', label: 'Settings', to: '/dashboard/settings' },
];

function UserMenu({ collapsed }: { collapsed: boolean }) {
  const { user, logout } = useAuth();
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  const initials =
    user?.name
      .split(' ')
      .map((n) => n[0])
      .join('')
      .toUpperCase()
      .slice(0, 2) ?? 'U';

  // Close on outside click
  useEffect(() => {
    const handler = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, []);

  const handleLogout = () => {
    logout();
    navigate('/login', { replace: true });
  };

  return (
    <div className={styles.userMenuWrap} ref={ref}>
      <button
        className={styles.userArea}
        onClick={() => setOpen((v) => !v)}
        title={collapsed ? user?.name : undefined}
        aria-label="User menu"
        aria-expanded={open}
      >
        <div className={styles.avatar}>{initials}</div>
        {!collapsed && (
          <>
            <div className={styles.userInfo}>
              <span className={styles.userName}>{user?.name}</span>
              <span className={styles.userEmail}>{user?.email}</span>
            </div>
            <span className={styles.chevron}>{open ? '▲' : '▼'}</span>
          </>
        )}
      </button>

      {open && (
        <div className={`${styles.profileMenu} ${collapsed ? styles.profileMenuCollapsed : ''}`}>
          <div className={styles.profileMenuHeader}>
            <div className={styles.profileMenuAvatar}>{initials}</div>
            <div className={styles.profileMenuInfo}>
              <span className={styles.profileMenuName}>{user?.name}</span>
              <span className={styles.profileMenuEmail}>{user?.email}</span>
            </div>
          </div>
          <div className={styles.profileMenuDivider} />
          <button
            className={`${styles.profileMenuItem} ${styles.profileMenuLogout}`}
            onClick={handleLogout}
          >
            <span className={styles.profileMenuIcon}>⏻</span>
            Sign out
          </button>
        </div>
      )}
    </div>
  );
}

function Sidebar({ collapsed, onCollapse }: { collapsed: boolean; onCollapse: () => void }) {
  return (
    <aside className={`${styles.sidebar} ${collapsed ? styles.collapsed : ''}`}>
      <div className={styles.brand}>
        <div className={styles.logoMark}>SE</div>
        {!collapsed && <span className={styles.brandName}>SmartExtract</span>}
        <button className={styles.collapseBtn} onClick={onCollapse} aria-label="Toggle sidebar">
          {collapsed ? '→' : '←'}
        </button>
      </div>

      <nav className={styles.nav}>
        {NAV_ITEMS.map((item) => (
          <NavLink
            key={item.to}
            to={item.to}
            end
            className={({ isActive }) =>
              `${styles.navItem} ${isActive ? styles.navItemActive : ''}`
            }
            title={collapsed ? item.label : undefined}
          >
            <span className={styles.navIcon}>{item.icon}</span>
            {!collapsed && <span className={styles.navLabel}>{item.label}</span>}
          </NavLink>
        ))}
      </nav>

      <UserMenu collapsed={collapsed} />
    </aside>
  );
}

export default function AppLayout() {
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false);
  const [mobileOpen, setMobileOpen] = useState(false);

  return (
    <div className={styles.layout}>
      {mobileOpen && (
        <div className={styles.overlay} onClick={() => setMobileOpen(false)} />
      )}

      <div className={`${styles.sidebarWrapper} ${mobileOpen ? styles.mobileOpen : ''}`}>
        <Sidebar
          collapsed={sidebarCollapsed}
          onCollapse={() => setSidebarCollapsed((v) => !v)}
        />
      </div>

      <main className={styles.main}>
        <header className={styles.topbar}>
          <button
            className={styles.mobileMenuBtn}
            onClick={() => setMobileOpen(true)}
            aria-label="Open menu"
          >
            ☰
          </button>
          <div className={styles.topbarRight}>
            <span className={styles.topbarTag}>Beta</span>
          </div>
        </header>

        <div className={styles.pageContent}>
          <Outlet />
        </div>
      </main>
    </div>
  );
}

