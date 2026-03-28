/**
 * Callback page — procesa el redirect de Keycloak.
 *
 * PROBLEMA DE STRICTMODE:
 * React 18 StrictMode monta → desmonta → monta el componente en desarrollo.
 * Los useRef se recrean en cada mount. Por eso usamos una variable de módulo
 * (fuera del componente) para garantizar que el exchange se hace una sola vez.
 *
 * En producción (sin StrictMode) esto no es necesario, pero es buena práctica.
 */

import { useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';

// Variable de módulo — persiste entre remounts de StrictMode
let callbackProcessed = false;

export default function CallbackPage() {
  const { handleCallback } = useAuth();
  const navigate           = useNavigate();

  useEffect(() => {
    // Resetear al desmontar (permite re-uso si el usuario vuelve a esta ruta)
    return () => { callbackProcessed = false; };
  }, []);

  useEffect(() => {
    if (callbackProcessed) return;
    callbackProcessed = true;

    const params = new URLSearchParams(window.location.search);
    const code   = params.get('code');
    const state  = params.get('state');
    const error  = params.get('error');

    // Debug en desarrollo
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
      <p>Iniciando sesión...</p>
    </div>
  );
}
