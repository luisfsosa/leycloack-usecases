import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { AuthProvider, useAuth } from './auth/AuthContext';
import HomePage              from './pages/HomePage';
import CallbackPage          from './pages/CallbackPage';
import DashboardPage         from './pages/DashboardPage';
import AcceptInvitePage      from './pages/AcceptInvitePage';
import SupplierDashboardPage from './pages/SupplierDashboardPage';

/**
 * ProtectedRoute — redirects to / if the user is not authenticated.
 *
 * CONCEPT: Route Guard in React
 * Checks for an active session before rendering the component.
 */
function ProtectedRoute({ children }) {
  const { isAuthenticated, loading } = useAuth();
  if (loading) return <p style={{ textAlign: 'center', marginTop: '20%' }}>Loading...</p>;
  if (!isAuthenticated) return <Navigate to="/" replace />;
  return children;
}

export default function App() {
  return (
    <BrowserRouter>
      <AuthProvider>
        <Routes>
          <Route path="/"          element={<HomePage />} />
          <Route path="/callback"  element={<CallbackPage />} />
          <Route path="/dashboard" element={
            <ProtectedRoute><DashboardPage /></ProtectedRoute>
          } />
          <Route path="/accept-invite" element={<AcceptInvitePage />} />
          <Route path="/supplier/dashboard" element={
            <ProtectedRoute><SupplierDashboardPage /></ProtectedRoute>
          } />
        </Routes>
      </AuthProvider>
    </BrowserRouter>
  );
}
