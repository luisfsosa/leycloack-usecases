/**
 * Pattern 2 — Email Domain Discovery
 *
 * The user types their corporate email. React extracts the domain and maps
 * it to a Keycloak IDP alias (kc_idp_hint). If the domain is not recognized,
 * the user lands on the standard Keycloak login screen.
 *
 * CONCEPT: kc_idp_hint
 *   Adding kc_idp_hint=<alias> to the authorization URL tells Keycloak to skip
 *   its login screen and redirect immediately to the specified Identity Provider.
 *   The user never sees the "Login with Toyota / Login with Ford" selector —
 *   they go straight to their corporate SSO.
 *
 * INTERVIEW: "How does your React app know which Identity Provider to use?"
 *   → Email domain discovery: we extract the domain from the email, look it up
 *     in a local map, and add kc_idp_hint to the PKCE authorization URL.
 *     No server round-trip needed — the mapping is a static config in the SPA.
 *     Unknown domains fall back to the generic Keycloak login page.
 */

import { useState, useEffect } from 'react';
import { useAuth } from '../auth/AuthContext';
import { useNavigate } from 'react-router-dom';

/**
 * Domain → Keycloak IDP alias map.
 * In production this could be fetched from an API endpoint that returns
 * the current tenant configuration, avoiding a frontend redeploy when
 * new clients are onboarded.
 */
const DOMAIN_IDP_MAP = {
  'toyota.com':  'toyota-corp',
  // 'ford.com': 'ford-corp',   ← add new B2B clients here
  // 'bmw.com':  'bmw-corp',
};

/** Returns the IDP alias for the given email domain, or null if not found. */
function getIdpHint(email) {
  const domain = email.split('@')[1]?.toLowerCase().trim();
  return DOMAIN_IDP_MAP[domain] ?? null;
}

export default function HomePage() {
  const { login, isAuthenticated, loading } = useAuth();
  const navigate = useNavigate();
  const [email, setEmail] = useState('');

  useEffect(() => {
    if (!loading && isAuthenticated) navigate('/dashboard');
  }, [isAuthenticated, loading]);

  const params = new URLSearchParams(window.location.search);
  const error  = params.get('error');

  function handleSubmit(e) {
    e.preventDefault();
    const hint = getIdpHint(email);
    // idpHint  = 'toyota-corp' → skip Keycloak, go directly to Toyota SSO
    // idpHint  = null          → show Keycloak login page
    // loginHint = email        → pre-fill the username/email field everywhere
    login(hint, email);
  }

  const hint = getIdpHint(email);

  return (
    <div style={{ textAlign: 'center', marginTop: '15%', fontFamily: 'monospace' }}>
      <h1>Altana Supply Chain</h1>
      <p style={{ color: '#555' }}>Protected by Keycloak + PKCE</p>

      {error && (
        <p style={{ color: 'red' }}>Error: {decodeURIComponent(error)}</p>
      )}

      <form onSubmit={handleSubmit} style={{ marginTop: '1.5rem' }}>
        <div>
          <label style={{ display: 'block', marginBottom: '6px', color: '#333' }}>
            Your corporate email:
          </label>
          <input
            type="email"
            placeholder="you@company.com"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            disabled={loading}
            autoFocus
            style={{
              padding: '10px 14px',
              fontSize: '1rem',
              width: '280px',
              border: '1px solid #ccc',
              borderRadius: '4px',
            }}
          />
        </div>

        {/* Show which IDP will be used — useful for learning/debugging */}
        {email.includes('@') && (
          <p style={{ fontSize: '0.8em', color: hint ? '#1a7f37' : '#888', margin: '6px 0' }}>
            {hint
              ? `→ kc_idp_hint=${hint}  (direct SSO)`
              : '→ no domain match — Keycloak login'}
          </p>
        )}

        <button
          type="submit"
          disabled={loading || !email}
          style={{
            padding: '10px 28px',
            fontSize: '1rem',
            marginTop: '10px',
            cursor: loading || !email ? 'not-allowed' : 'pointer',
            backgroundColor: '#0066cc',
            color: 'white',
            border: 'none',
            borderRadius: '4px',
          }}
        >
          Continue →
        </button>
      </form>

      <p style={{ fontSize: '0.72em', color: '#aaa', marginTop: '2.5rem' }}>
        Authorization Code + PKCE (S256) · Token stored in memory only, never in localStorage.
      </p>
    </div>
  );
}
