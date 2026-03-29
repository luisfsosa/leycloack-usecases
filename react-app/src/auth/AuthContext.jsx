/**
 * AuthContext — estado global de autenticación.
 *
 * CONCEPTO: dónde viven los tokens
 *   access_token  → useRef (memoria JS, no re-renderiza, no persiste)
 *   refresh_token → useRef (idem)
 *   id_token      → useRef (para logout)
 *   user          → useState (datos del usuario para mostrar en UI)
 *
 * ¿Por qué useRef y no useState para los tokens?
 * → useState guarda en el estado de React (visible en React DevTools).
 *   useRef guarda en un objeto mutable en memoria, menos exposición.
 *   Los tokens no necesitan causar re-renders — solo el objeto 'user' lo necesita.
 *
 * ENTREVISTA: "¿Dónde guardas el access token en una SPA?"
 * → En memoria (variable/ref de JS). Nunca en localStorage ni sessionStorage.
 *   Muere con el page refresh → se renueva con refresh_token automáticamente.
 */

import { createContext, useContext, useRef, useState, useEffect, useCallback } from 'react';
import { generateCodeVerifier, generateCodeChallenge, generateState } from './pkce';
import { startLogin, exchangeCode, refreshAccessToken, decodeJwt, logout as kcLogout } from './keycloak';

const AuthContext = createContext(null);

export function AuthProvider({ children }) {
  const accessTokenRef  = useRef(null);
  const refreshTokenRef = useRef(null);
  const idTokenRef      = useRef(null);
  const refreshTimerRef = useRef(null);

  const [user, setUser]       = useState(null);   // { username, email, roles, sub }
  const [loading, setLoading] = useState(true);   // true mientras verificamos sesión

  /** Guarda tokens en refs y extrae datos del usuario del JWT */
  const storeTokens = useCallback(({ access_token, refresh_token, id_token }) => {
    accessTokenRef.current  = access_token;
    refreshTokenRef.current = refresh_token;
    idTokenRef.current      = id_token;

    const payload = decodeJwt(access_token);
    if (payload) {
      setUser({
        sub:      payload.sub,
        username: payload.preferred_username,
        email:    payload.email,
        roles:    payload.realm_access?.roles ?? [],
        exp:      payload.exp,
      });

      // Programar auto-refresh 30 segundos antes de que expire
      scheduleRefresh(payload.exp);
    }
  }, []);

  /** Auto-refresh: renueva el access token antes de que expire */
  const scheduleRefresh = useCallback((exp) => {
    if (refreshTimerRef.current) clearTimeout(refreshTimerRef.current);

    const now        = Math.floor(Date.now() / 1000);
    const msToExpiry = (exp - now - 30) * 1000; // 30s de margen

    if (msToExpiry <= 0) {
      doRefresh();
      return;
    }

    refreshTimerRef.current = setTimeout(doRefresh, msToExpiry);
  }, []);

  const doRefresh = useCallback(async () => {
    const rt = refreshTokenRef.current;
    if (!rt) return;

    try {
      const tokens = await refreshAccessToken(rt);
      storeTokens(tokens);
    } catch {
      // Refresh token expirado → logout
      clearAuth();
    }
  }, [storeTokens]);

  /** Limpia todo el estado de auth */
  const clearAuth = useCallback(() => {
    accessTokenRef.current  = null;
    refreshTokenRef.current = null;
    idTokenRef.current      = null;
    setUser(null);
    if (refreshTimerRef.current) clearTimeout(refreshTimerRef.current);
    sessionStorage.removeItem('pkce_verifier');
    sessionStorage.removeItem('pkce_state');
  }, []);

  /**
   * Starts the PKCE login flow and redirects to Keycloak.
   * idpHint:   kc_idp_hint — skip Keycloak login, go directly to the IdP.
   * loginHint: login_hint  — pre-fill the email/username field in Keycloak
   *            (and in the external IdP when kc_idp_hint is set).
   */
  const login = useCallback(async (idpHint = null, loginHint = null) => {
    const verifier   = generateCodeVerifier();
    const challenge  = await generateCodeChallenge(verifier);
    const state      = generateState();

    sessionStorage.setItem('pkce_verifier', verifier);
    sessionStorage.setItem('pkce_state', state);

    await startLogin(challenge, state, idpHint, loginHint);
  }, []);

  /**
   * Procesa el callback de Keycloak (/callback?code=XXX&state=YYY)
   * Llamado desde el componente <CallbackPage>
   */
  const handleCallback = useCallback(async (code, returnedState) => {
    const verifier      = sessionStorage.getItem('pkce_verifier');
    const expectedState = sessionStorage.getItem('pkce_state');

    // Validar state anti-CSRF ANTES de limpiar
    if (!expectedState || returnedState !== expectedState) {
      throw new Error(
        `State mismatch — posible ataque CSRF.\n` +
        `URL state: "${returnedState}"\n` +
        `Stored state: "${expectedState}"`
      );
    }

    // Limpiar sessionStorage solo si el state es válido (one-time use)
    sessionStorage.removeItem('pkce_verifier');
    sessionStorage.removeItem('pkce_state');

    if (!verifier) {
      throw new Error('No se encontró el code verifier');
    }

    const tokens = await exchangeCode(code, verifier);
    storeTokens(tokens);
    return tokens;
  }, [storeTokens]);

  /** Logout completo: limpia estado local + invalida sesión en Keycloak */
  const logout = useCallback(() => {
    const idToken = idTokenRef.current;
    clearAuth();
    kcLogout(idToken);
  }, [clearAuth]);

  /** Expone el access token para llamadas a APIs */
  const getAccessToken = useCallback(() => accessTokenRef.current, []);

  // Al montar: verificar si hay un page refresh y si el refresh token sigue vivo
  // (En esta implementación simple no persistimos nada → siempre empieza sin sesión)
  useEffect(() => {
    setLoading(false);
  }, []);

  return (
    <AuthContext.Provider value={{
      user,
      loading,
      isAuthenticated: !!user,
      login,
      logout,
      handleCallback,
      getAccessToken,
    }}>
      {children}
    </AuthContext.Provider>
  );
}

export const useAuth = () => {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth debe usarse dentro de AuthProvider');
  return ctx;
};
