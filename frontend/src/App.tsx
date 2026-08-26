import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { AuthProvider } from './context/AuthContext';
import ProtectedRoute from './components/ProtectedRoute';
import PublicOnlyRoute from './components/PublicOnlyRoute';
import AppLayout from './components/AppLayout';
import LoginPage from './pages/LoginPage';
import SignupPage from './pages/SignupPage';
import DashboardPage from './pages/DashboardPage';
import PurchaseOrdersPage from './pages/PurchaseOrdersPage';
import SearchPage from './pages/SearchPage';
import SettingsPage from './pages/SettingsPage';
import ProcessingPage from './pages/ProcessingPage';
import PODetailPage from './pages/PODetailPage';

function App() {
  return (
    <BrowserRouter>
      <AuthProvider>
        <Routes>
          <Route element={<PublicOnlyRoute />}>
            <Route path="/login" element={<LoginPage />} />
            <Route path="/signup" element={<SignupPage />} />
          </Route>

          <Route element={<ProtectedRoute />}>
            <Route element={<AppLayout />}>
              <Route path="/dashboard" element={<DashboardPage />} />
              <Route path="/dashboard/purchase-orders" element={<PurchaseOrdersPage />} />
              <Route path="/dashboard/search" element={<SearchPage />} />
              <Route path="/dashboard/settings" element={<SettingsPage />} />
              <Route path="/dashboard/processing" element={<ProcessingPage />} />
              <Route path="/dashboard/po/:poId" element={<PODetailPage />} />
              {/* Catch-all inside protected: redirect unknown dashboard paths to dashboard */}
              <Route path="*" element={<Navigate to="/dashboard" replace />} />
            </Route>
          </Route>

          {/* Top-level fallback for unauthenticated unknown paths */}
          <Route path="*" element={<Navigate to="/login" replace />} />
        </Routes>
      </AuthProvider>
    </BrowserRouter>
  );
}

export default App;
