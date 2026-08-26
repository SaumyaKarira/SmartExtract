import { createContext, useContext, useState, useEffect, useCallback } from 'react';
import type { ReactNode } from 'react';
import { useNavigate } from 'react-router-dom';
import { api, SESSION_EXPIRED_EVENT } from '../api/client';

interface User {
  email: string;
  name: string;
}

interface AuthContextType {
  user: User | null;
  login: (email: string, password: string) => Promise<void>;
  register: (name: string, email: string, password: string) => Promise<void>;
  logout: () => void;
}

const AuthContext = createContext<AuthContextType | null>(null);

const TOKEN_KEY = 'se_token';
const USER_KEY = 'se_user';

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(() => {
    const stored = localStorage.getItem(USER_KEY);
    return stored ? JSON.parse(stored) : null;
  });
  const navigate = useNavigate();

  const persist = (u: User, token: string) => {
    localStorage.setItem(TOKEN_KEY, token);
    localStorage.setItem(USER_KEY, JSON.stringify(u));
    setUser(u);
  };

  const logout = useCallback(() => {
    setUser(null);
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(USER_KEY);
  }, []);

  // Listen for session-expired events dispatched by the API client on 401
  useEffect(() => {
    const handleSessionExpired = () => {
      logout();
      navigate('/login', {
        replace: true,
        state: { sessionExpired: true },
      });
    };
    window.addEventListener(SESSION_EXPIRED_EVENT, handleSessionExpired);
    return () => window.removeEventListener(SESSION_EXPIRED_EVENT, handleSessionExpired);
  }, [logout, navigate]);

  const login = async (email: string, password: string) => {
    const { token } = await api.post<{ token: string }>('/api/auth/login', { email, password });
    // Name is not in login response — reuse stored name if present, else fall back to email prefix
    const stored = localStorage.getItem(USER_KEY);
    const name = stored ? (JSON.parse(stored) as User).name : email.split('@')[0];
    persist({ email, name }, token);
  };

  const register = async (name: string, email: string, password: string) => {
    const result = await api.post<{ id: number; name: string; email: string; token: string }>(
      '/api/auth/register',
      { name, email, password }
    );
    persist({ email: result.email, name: result.name }, result.token);
  };

  return (
    <AuthContext.Provider value={{ user, login, register, logout }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used inside AuthProvider');
  return ctx;
}
