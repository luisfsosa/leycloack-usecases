/**
 * Callback page — processes the redirect from Keycloak.
 *
 * STRICT MODE ISSUE:
 * React 18 StrictMode mounts → unmounts → mounts the component in development.
 * useRefs are recreated on each mount. That is why we use a module-level variable
 * (outside the component) to guarantee the exchange happens only once.
 *
 * In production (without StrictMode) this is not needed, but it is good practice.
 */

import { useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';

// Module-level variable — persists across StrictMode remounts
let callbackProcessed = false;

export default function CallbackPage() {
  const { handleCallback } = useAuth();
  const navigate           = useNavigate();

  useEffect(() => {
    // Reset on unmount (allows reuse if the user navigates back to this route)
    return () => { callbackProcessed = false; };
  }, []);

  useEffect(() => {
    if (callbackProcessed) return;
    callbackProcessed = true;

    const params = new URLSearchParams(window.location.search);
    const code   = params.get('code');
    const state  = params.get('state');
    const error  = params.get('error');

    // Debug in development
    if (import.meta.env.DEV) {
      console.group('OAuth2 Callback Debug');
      console.log('code        :', code?.substring(0, 20) + '...');
      console.log('state (URL) :', state);
      console.log('state (storage):', sessionStorage.getItem('pkce_state'));
      console.log('verifier    :', sessionStorage.getItem('pkce_verifier')?.substring(0, 20) + '...');
      console.groupEnd();
    }

    if (error) {
      navigate('/?error=' + encodeURIComponent(params.get('error_description') || error));
      return;
    }

    if (!code) {
      navigate('/');
      return;
    }

    handleCallback(code, state)
      .then(() => navigate('/dashboard'))
      .catch(err => {
        console.error('Callback failed:', err.message);
        navigate('/?error=' + encodeURIComponent(err.message));
      });
  }, [handleCallback, navigate]);

  return (
    <div style={{ textAlign: 'center', marginTop: '20%', fontFamily: 'monospace' }}>
      <p>Signing in...</p>
    </div>
  );
}
