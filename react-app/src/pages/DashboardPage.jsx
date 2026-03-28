/**
 * Dashboard protegido — solo usuarios autenticados.
 * Muestra datos del JWT y llama a la FastAPI.
 */

import { useState } from 'react';
import { useAuth } from '../auth/AuthContext';

const API_URL = 'http://localhost:8002';

export default function DashboardPage() {
  const { user, logout, getAccessToken } = useAuth();
  const [apiResponse, setApiResponse]    = useState(null);
  const [apiError, setApiError]          = useState(null);
  const [loading, setLoading]            = useState(false);

  /** Llama a la FastAPI con el access token en el header */
  async function callApi(endpoint) {
    setLoading(true);
    setApiError(null);
    setApiResponse(null);

    const token = getAccessToken();

    try {
      const res = await fetch(`${API_URL}${endpoint}`, {
        headers: { Authorization: `Bearer ${token}` },
      });

      const data = await res.json();

      if (!res.ok) {
        setApiError(`${res.status}: ${data.detail}`);
      } else {
        setApiResponse(data);
      }
    } catch (err) {
      setApiError(err.message);
    } finally {
      setLoading(false);
    }
  }

  async function callDelete() {
    setLoading(true);
    setApiError(null);
    setApiResponse(null);

    const token = getAccessToken();

    try {
      const res = await fetch(`${API_URL}/supply-chain/suppliers/1`, {
        method: 'DELETE',
        headers: { Authorization: `Bearer ${token}` },
      });
      const data = await res.json();
      if (!res.ok) setApiError(`${res.status}: ${data.detail}`);
      else setApiResponse(data);
    } catch (err) {
      setApiError(err.message);
    } finally {
      setLoading(false);
    }
  }

  const expDate = user?.exp
    ? new Date(user.exp * 1000).toUTCString()
    : null;

  return (
    <div style={{ fontFamily: 'monospace', padding: '2rem', maxWidth: '900px', margin: '0 auto' }}>

      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <h1>Altana Supply Chain</h1>
        <button onClick={logout} style={{ padding: '8px 16px' }}>Logout</button>
      </div>

      {/* Datos del token */}
      <section style={{ background: '#f0f0f0', padding: '1rem', borderRadius: '8px', marginBottom: '1rem' }}>
        <h2>Token Claims (del JWT en memoria)</h2>
        <table>
          <tbody>
            <tr><td><b>username</b></td><td>{user?.username}</td></tr>
            <tr><td><b>email</b></td><td>{user?.email}</td></tr>
            <tr><td><b>sub</b></td><td style={{ fontSize: '0.8em' }}>{user?.sub}</td></tr>
            <tr><td><b>roles</b></td><td>{user?.roles?.join(', ')}</td></tr>
            <tr><td><b>exp (UTC)</b></td><td>{expDate}</td></tr>
          </tbody>
        </table>
        <p style={{ fontSize: '0.8em', color: '#666' }}>
          El access token vive solo en memoria JS (useRef). No está en localStorage ni en DevTools → Storage.
        </p>
      </section>

      {/* Llamadas a la API */}
      <section>
        <h2>Llamadas a FastAPI (puerto 8002)</h2>
        <div style={{ display: 'flex', gap: '8px', flexWrap: 'wrap', marginBottom: '1rem' }}>
          <button onClick={() => callApi('/supply-chain/me')}>
            GET /me
          </button>
          <button onClick={() => callApi('/supply-chain/suppliers')}>
            GET /suppliers (cualquier user)
          </button>
          <button onClick={() => callApi('/supply-chain/shipments')}>
            GET /shipments (ANALYST/ADMIN)
          </button>
          <button onClick={callDelete} style={{ background: '#ffcccc' }}>
            DELETE /suppliers/1 (solo ADMIN)
          </button>
        </div>

        {loading && <p>Llamando a la API...</p>}

        {apiError && (
          <div style={{ background: '#ffeeee', padding: '1rem', borderRadius: '4px', color: 'red' }}>
            <b>Error:</b> {apiError}
          </div>
        )}

        {apiResponse && (
          <pre style={{ background: '#eeffee', padding: '1rem', borderRadius: '4px', overflow: 'auto' }}>
            {JSON.stringify(apiResponse, null, 2)}
          </pre>
        )}
      </section>
    </div>
  );
}
