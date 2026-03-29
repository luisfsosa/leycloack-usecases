import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { AuthProvider, useAuth } from './auth/AuthContext';
import HomePage              from './pages/HomePage';
import CallbackPage          from './pages/CallbackPage';
import DashboardPage         from './pages/DashboardPage';
import AcceptInvitePage      from './pages/AcceptInvitePage';
import SupplierDashboardPage from './pages/SupplierDashboardPage';

/**
 * ProtectedRoute — redirige a / si el usuario no está autenticado.
 *
 * CONCEPTO: Route Guard en React
 * Verifica si hay sesión activa antes de renderizar el componente.
 */
function ProtectedRoute({ children }) {
  const { isAuthenticated, loading } = useAuth();
  if (loading) return <p style={{ textAlign: 'center', marginTop: '20%' }}>Cargando...</p>;
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
