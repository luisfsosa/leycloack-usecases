/**
 * UC6 — AcceptInvitePage
 *
 * Flow:
 * 1. Reads ?token=... from the URL (the link the supplier received by email)
 * 2. Calls GET /api/invitations/{token} to fetch details
 * 3. Shows: "Toyota invited you to join Altana as a Tier 2 Supplier"
 * 4. Two buttons:
 *    - "Create account" → redirects to Keycloak registration with email pre-filled
 *    - "I already have an account" → normal PKCE flow
 *
 * On returning from callback (isAuthenticated === true):
 * - If pendingInviteToken is in sessionStorage → complete onboarding
 * - Clear sessionStorage and redirect to /supplier/dashboard
 *
 * LEARNING: sessionStorage as a "handoff" mechanism between pages.
 * We save the invite token BEFORE leaving to the Keycloak callback,
 * and consume it ON RETURN. Like leaving a note for yourself.
 *
 * INTERVIEW: "How do you maintain context across an OAuth2 redirect?"
 * → The 'state' parameter can encode context (base64 of a JSON object).
 *   Alternative: sessionStorage (same tab only, cleared on close).
 *   NEVER localStorage for sensitive data — it persists indefinitely.
 */

import { useEffect, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { API_URL as API_BASE } from '../config';

export default function AcceptInvitePage() {
  const [searchParams]  = useSearchParams();
  const { isAuthenticated, login, register, getAccessToken } = useAuth();
  const navigate        = useNavigate();

  const [invitation, setInvitation]   = useState(null);   // invitation details
  const [error, setError]             = useState(null);
  const [completing, setCompleting]   = useState(false);  // onboarding in progress

  const token = searchParams.get('token');

  // ── Step 1: load invitation details ──────────────────────────────────────
  useEffect(() => {
    if (!token) {
      setError('No invitation token found in the URL.');
      return;
    }

    fetch(`${API_BASE}/invitations/${token}`)
      .then(res => {
        if (!res.ok) return res.json().then(e => { throw new Error(e.detail); });
        return res.json();
      })
      .then(setInvitation)
      .catch(err => setError(err.message));
  }, [token]);

  // ── Step 2: on returning from callback, complete onboarding ──────────────
  useEffect(() => {
    if (!isAuthenticated) return;

    const pendingToken = sessionStorage.getItem('pendingInviteToken');
    if (!pendingToken) return;

    setCompleting(true);

    const accessToken = getAccessToken();
    fetch(`${API_BASE}/invitations/${pendingToken}/complete`, {
      method:  'POST',
      headers: { Authorization: `Bearer ${accessToken}` },
    })
      .then(res => {
        if (!res.ok) return res.json().then(e => { throw new Error(e.detail); });
        return res.json();
      })
      .then(() => {
        sessionStorage.removeItem('pendingInviteToken');
        navigate('/supplier/dashboard');
      })
      .catch(err => {
        setError(`Error completing onboarding: ${err.message}`);
        setCompleting(false);
      });
  }, [isAuthenticated, getAccessToken, navigate]);

  // ── Button handlers ───────────────────────────────────────────────────────

  function handleCreateAccount() {
    sessionStorage.setItem('pendingInviteToken', token);
    register(invitation?.invited_email ?? null);
  }

  function handleLogin() {
    sessionStorage.setItem('pendingInviteToken', token);
    login(null, invitation?.invited_email ?? null);
  }

  // ── Render ────────────────────────────────────────────────────────────────

  if (completing) {
    return (
      <div style={styles.container}>
        <p style={styles.subtitle}>Completing onboarding...</p>
      </div>
    );
  }

  if (error) {
    return (
      <div style={styles.container}>
        <h2 style={{ color: '#e74c3c' }}>Invalid invitation</h2>
        <p style={styles.subtitle}>{error}</p>
      </div>
    );
  }

  if (!invitation) {
    return (
      <div style={styles.container}>
        <p style={styles.subtitle}>Loading invitation...</p>
      </div>
    );
  }

  const expiresDate = new Date(invitation.expires_at * 1000).toLocaleDateString('en-US');

  return (
    <div style={styles.container}>
      <div style={styles.card}>
        <div style={styles.badge}>Supplier invitation</div>

        <h1 style={styles.title}>
          {invitation.tenant_id.toUpperCase()} invited you to Altana
        </h1>

        <p style={styles.subtitle}>
          Join as a <strong>Tier {invitation.supplier_tier} Supplier</strong> on the
          supply chain platform of {invitation.tenant_id}.
        </p>

        <div style={styles.infoBox}>
          <InfoRow label="To"              value={invitation.invited_email} />
          <InfoRow label="Invited by"      value={invitation.invited_by} />
          <InfoRow label="Company"         value={invitation.tenant_id.toUpperCase()} />
          <InfoRow label="Supplier tier"   value={`Tier ${invitation.supplier_tier}`} />
          <InfoRow label="Expires"         value={expiresDate} />
        </div>

        <div style={styles.actions}>
          <button style={styles.primaryBtn} onClick={handleCreateAccount}>
            Create new account
          </button>
          <button style={styles.secondaryBtn} onClick={handleLogin}>
            I already have an account — Sign in
          </button>
        </div>

        <p style={styles.footer}>
          By creating an account you accept Altana's terms of use.
          Your email will be verified by Keycloak.
        </p>
      </div>
    </div>
  );
}

function InfoRow({ label, value }) {
  return (
    <div style={{ display: 'flex', justifyContent: 'space-between', padding: '6px 0', borderBottom: '1px solid #eee' }}>
      <span style={{ color: '#666', fontSize: '14px' }}>{label}</span>
      <span style={{ fontWeight: 500, fontSize: '14px' }}>{value}</span>
    </div>
  );
}

const styles = {
  container: {
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    minHeight: '100vh',
    background: '#f0f4f8',
    padding: '20px',
  },
  card: {
    background: '#fff',
    borderRadius: '12px',
    padding: '40px',
    maxWidth: '480px',
    width: '100%',
    boxShadow: '0 4px 24px rgba(0,0,0,0.08)',
  },
  badge: {
    display: 'inline-block',
    background: '#e8f4fd',
    color: '#2980b9',
    fontSize: '12px',
    fontWeight: 600,
    padding: '4px 10px',
    borderRadius: '20px',
    marginBottom: '16px',
    textTransform: 'uppercase',
    letterSpacing: '0.5px',
  },
  title: {
    fontSize: '22px',
    fontWeight: 700,
    marginBottom: '10px',
    color: '#1a1a2e',
  },
  subtitle: {
    color: '#555',
    fontSize: '15px',
    marginBottom: '24px',
    lineHeight: 1.5,
  },
  infoBox: {
    background: '#f8f9fa',
    borderRadius: '8px',
    padding: '16px',
    marginBottom: '28px',
  },
  actions: {
    display: 'flex',
    flexDirection: 'column',
    gap: '12px',
    marginBottom: '20px',
  },
  primaryBtn: {
    background: '#2980b9',
    color: '#fff',
    border: 'none',
    borderRadius: '8px',
    padding: '14px',
    fontSize: '15px',
    fontWeight: 600,
    cursor: 'pointer',
  },
  secondaryBtn: {
    background: 'transparent',
    color: '#2980b9',
    border: '2px solid #2980b9',
    borderRadius: '8px',
    padding: '12px',
    fontSize: '15px',
    fontWeight: 600,
    cursor: 'pointer',
  },
  footer: {
    color: '#999',
    fontSize: '12px',
    textAlign: 'center',
    lineHeight: 1.4,
  },
};
