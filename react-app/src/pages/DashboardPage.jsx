/**
 * Protected dashboard — authenticated users only.
 * Displays JWT claims and calls the FastAPI.
 */

import { useState } from 'react';
import { useAuth } from '../auth/AuthContext';
import { API_URL } from '../config';

export default function DashboardPage() {
  const { user, logout, getAccessToken } = useAuth();
  const [apiResponse, setApiResponse]    = useState(null);
  const [apiError, setApiError]          = useState(null);
  const [loading, setLoading]            = useState(false);

  /** Calls the FastAPI with the access token in the Authorization header */
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

      {/* Token data */}
      <section style={{ background: '#f0f0f0', padding: '1rem', borderRadius: '8px', marginBottom: '1rem' }}>
        <h2>Token Claims (from in-memory JWT)</h2>
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
          The access token lives only in JS memory (useRef). It is not in localStorage or DevTools → Storage.
        </p>
      </section>

      {/* API calls */}
      <section>
        <h2>FastAPI calls (port 8002)</h2>
        <div style={{ display: 'flex', gap: '8px', flexWrap: 'wrap', marginBottom: '1rem' }}>
          <button onClick={() => callApi('/supply-chain/me')}>
            GET /me
          </button>
          <button onClick={() => callApi('/supply-chain/suppliers')}>
            GET /suppliers (any user)
          </button>
          <button onClick={() => callApi('/supply-chain/shipments')}>
            GET /shipments (ANALYST/ADMIN)
          </button>
          <button onClick={callDelete} style={{ background: '#ffcccc' }}>
            DELETE /suppliers/1 (ADMIN only)
          </button>
        </div>

        {loading && <p>Calling the API...</p>}

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
