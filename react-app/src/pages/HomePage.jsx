import { useAuth } from '../auth/AuthContext';
import { useNavigate } from 'react-router-dom';
import { useEffect } from 'react';

export default function HomePage() {
  const { login, isAuthenticated, loading } = useAuth();
  const navigate = useNavigate();

  // Si ya está autenticado, ir directo al dashboard
  useEffect(() => {
    if (!loading && isAuthenticated) navigate('/dashboard');
  }, [isAuthenticated, loading]);

  const params = new URLSearchParams(window.location.search);
  const error  = params.get('error');

  return (
    <div style={{ textAlign: 'center', marginTop: '20%', fontFamily: 'monospace' }}>
      <h1>Altana Supply Chain</h1>
      <p>Plataforma protegida con Keycloak + PKCE</p>

      {error && (
        <p style={{ color: 'red' }}>Error: {decodeURIComponent(error)}</p>
      )}

      <button
        onClick={login}
        disabled={loading}
        style={{ padding: '12px 24px', fontSize: '1rem', marginTop: '1rem' }}
      >
        Login con Keycloak
      </button>

      <p style={{ fontSize: '0.75em', color: '#888', marginTop: '2rem' }}>
        El flujo usa Authorization Code + PKCE.<br/>
        El access token se guarda solo en memoria JS.
      </p>
    </div>
  );
}
