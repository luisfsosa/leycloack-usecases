/**
 * UC6 — SupplierDashboardPage
 *
 * Supplier portal: purchase orders + document uploads.
 * Requires authentication (protected by ProtectedRoute in App.jsx).
 */

import { useEffect, useState } from 'react';
import { useAuth } from '../auth/AuthContext';
import { API_URL as API_BASE } from '../config';

export default function SupplierDashboardPage() {
  const { user, getAccessToken, logout } = useAuth();
  const [dashboard, setDashboard] = useState(null);
  const [error, setError]         = useState(null);
  const [uploading, setUploading] = useState(false);
  const [uploadResult, setUploadResult] = useState(null);

  useEffect(() => {
    const token = getAccessToken();
    fetch(`${API_BASE}/supplier/dashboard`, {
      headers: { Authorization: `Bearer ${token}` },
    })
      .then(res => {
        if (!res.ok) return res.json().then(e => { throw new Error(e.detail); });
        return res.json();
      })
      .then(setDashboard)
      .catch(err => setError(err.message));
  }, [getAccessToken]);

  async function handleUpload() {
    setUploading(true);
    const token = getAccessToken();
    try {
      const res = await fetch(`${API_BASE}/supplier/upload`, {
        method:  'POST',
        headers: {
          Authorization: `Bearer ${token}`,
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          document_type:   'certificate_of_origin',
          filename:        'cert-origin-2025.pdf',
          content_preview: 'Certificate of origin — Brake parts batch 2025-Q2',
        }),
      });
      const data = await res.json();
      setUploadResult(data);
    } catch (err) {
      setError(err.message);
    } finally {
      setUploading(false);
    }
  }

  if (error) return (
    <div style={styles.container}>
      <p style={{ color: 'red' }}>Error: {error}</p>
    </div>
  );

  if (!dashboard) return (
    <div style={styles.container}>
      <p>Loading portal...</p>
    </div>
  );

  return (
    <div style={styles.container}>
      <div style={styles.header}>
        <div>
          <h1 style={styles.title}>{dashboard.welcome}</h1>
          <p style={styles.subtitle}>Welcome, <strong>{dashboard.supplier}</strong></p>
        </div>
        <button style={styles.logoutBtn} onClick={logout}>Sign out</button>
      </div>

      <div style={styles.grid}>
        {/* Purchase orders */}
        <div style={styles.card}>
          <h2 style={styles.cardTitle}>Purchase Orders</h2>
          <table style={styles.table}>
            <thead>
              <tr>
                {['Number', 'Item', 'Qty', 'Status'].map(h => (
                  <th key={h} style={styles.th}>{h}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {dashboard.purchase_orders.map(o => (
                <tr key={o.po_number}>
                  <td style={styles.td}>{o.po_number}</td>
                  <td style={styles.td}>{o.item}</td>
                  <td style={styles.td}>{o.qty}</td>
                  <td style={styles.td}>
                    <span style={{ ...styles.statusBadge, ...statusColor(o.status) }}>
                      {o.status}
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        {/* Certifications */}
        <div style={styles.card}>
          <h2 style={styles.cardTitle}>Certifications</h2>
          {dashboard.certifications.map(c => (
            <div key={c.cert_id} style={styles.certRow}>
              <span style={{ fontWeight: 600 }}>{c.cert_id}</span>
              <span style={{ color: '#666', fontSize: '13px' }}>Valid until {c.valid_until}</span>
              <span style={{ ...styles.statusBadge, ...statusColor(c.status) }}>{c.status}</span>
            </div>
          ))}

          <div style={{ marginTop: '20px' }}>
            <h3 style={{ fontSize: '15px', marginBottom: '10px' }}>Upload document</h3>
            <button
              style={styles.uploadBtn}
              onClick={handleUpload}
              disabled={uploading}
            >
              {uploading ? 'Uploading...' : 'Upload certificate of origin'}
            </button>
            {uploadResult && (
              <p style={{ color: 'green', fontSize: '13px', marginTop: '8px' }}>
                ✓ {uploadResult.message}
              </p>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}

function statusColor(status) {
  const map = {
    pending:        { background: '#fff3cd', color: '#856404' },
    shipped:        { background: '#cce5ff', color: '#004085' },
    delivered:      { background: '#d4edda', color: '#155724' },
    active:         { background: '#d4edda', color: '#155724' },
    expiring_soon:  { background: '#fff3cd', color: '#856404' },
  };
  return map[status] || { background: '#eee', color: '#333' };
}

const styles = {
  container: { maxWidth: '900px', margin: '0 auto', padding: '32px 20px' },
  header: { display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: '32px' },
  title: { fontSize: '24px', fontWeight: 700, marginBottom: '4px' },
  subtitle: { color: '#666', fontSize: '14px' },
  logoutBtn: { background: 'transparent', border: '1px solid #ccc', borderRadius: '6px', padding: '8px 16px', cursor: 'pointer', fontSize: '14px' },
  grid: { display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '20px' },
  card: { background: '#fff', borderRadius: '10px', padding: '24px', boxShadow: '0 2px 12px rgba(0,0,0,0.06)' },
  cardTitle: { fontSize: '16px', fontWeight: 600, marginBottom: '16px' },
  table: { width: '100%', borderCollapse: 'collapse', fontSize: '13px' },
  th: { textAlign: 'left', padding: '8px', borderBottom: '2px solid #eee', fontWeight: 600 },
  td: { padding: '8px', borderBottom: '1px solid #f0f0f0' },
  statusBadge: { fontSize: '12px', padding: '2px 8px', borderRadius: '12px', fontWeight: 500 },
  certRow: { display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '10px 0', borderBottom: '1px solid #eee' },
  uploadBtn: { background: '#2980b9', color: '#fff', border: 'none', borderRadius: '6px', padding: '10px 18px', cursor: 'pointer', fontSize: '14px' },
};
