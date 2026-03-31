/**
 * AuthContext — global authentication state.
 *
 * CONCEPT: where tokens live
 *   access_token  → useRef (JS memory, no re-render, not persisted)
 *   refresh_token → useRef (same)
 *   id_token      → useRef (for logout)
 *   user          → useState (user data to display in the UI)
 *
 * Why useRef and not useState for tokens?
 * → useState stores in React state (visible in React DevTools).
 *   useRef stores in a mutable object in memory, less exposure.
 *   Tokens don't need to cause re-renders — only the 'user' object does.
 *
 * INTERVIEW: "Where do you store the access token in a SPA?"
 * → In memory (JS variable/ref). Never in localStorage or sessionStorage.
 *   Dies on page refresh → renewed automatically with refresh_token.
 */

import { createContext, useContext, useRef, useState, useEffect, useCallback } from 'react';
import { generateCodeVerifier, generateCodeChallenge, generateState } from './pkce';
import { startLogin, startRegistration, exchangeCode, refreshAccessToken, decodeJwt, logout as kcLogout } from './keycloak';

const AuthContext = createContext(null);

export function AuthProvider({ children }) {
  const accessTokenRef  = useRef(null);
  const refreshTokenRef = useRef(null);
  const idTokenRef      = useRef(null);
  const refreshTimerRef = useRef(null);

  const [user, setUser]       = useState(null);   // { username, email, roles, sub }
  const [loading, setLoading] = useState(true);   // true while verifying session

  /** Stores tokens in refs and extracts user data from the JWT */
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

      // Schedule auto-refresh 30 seconds before expiry
      scheduleRefresh(payload.exp);
    }
  }, []);

  /** Auto-refresh: renews the access token before it expires */
  const scheduleRefresh = useCallback((exp) => {
    if (refreshTimerRef.current) clearTimeout(refreshTimerRef.current);

    const now        = Math.floor(Date.now() / 1000);
    const msToExpiry = (exp - now - 30) * 1000; // 30s margin

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
      // Refresh token expired → logout
      clearAuth();
    }
  }, [storeTokens]);

  /** Clears all auth state */
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
   * UC6 — Starts the REGISTRATION flow in Keycloak.
   * Same as login but redirects to the registration form.
   * loginHint pre-fills the invited supplier's email.
   */
  const register = useCallback(async (loginHint = null) => {
    const verifier  = generateCodeVerifier();
    const challenge = await generateCodeChallenge(verifier);
    const state     = generateState();

    sessionStorage.setItem('pkce_verifier', verifier);
    sessionStorage.setItem('pkce_state', state);

    await startRegistration(challenge, state, loginHint);
  }, []);

  /**
   * Processes the Keycloak callback (/callback?code=XXX&state=YYY)
   * Called from the <CallbackPage> component
   */
  const handleCallback = useCallback(async (code, returnedState) => {
    const verifier      = sessionStorage.getItem('pkce_verifier');
    const expectedState = sessionStorage.getItem('pkce_state');

    // Validate anti-CSRF state BEFORE clearing
    if (!expectedState || returnedState !== expectedState) {
      throw new Error(
        `State mismatch — possible CSRF attack.\n` +
        `URL state: "${returnedState}"\n` +
        `Stored state: "${expectedState}"`
      );
    }

    // Clear sessionStorage only if state is valid (one-time use)
    sessionStorage.removeItem('pkce_verifier');
    sessionStorage.removeItem('pkce_state');

    if (!verifier) {
      throw new Error('Code verifier not found');
    }

    const tokens = await exchangeCode(code, verifier);
    storeTokens(tokens);
    return tokens;
  }, [storeTokens]);

  /** Full logout: clears local state + invalidates session in Keycloak */
  const logout = useCallback(() => {
    const idToken = idTokenRef.current;
    clearAuth();
    kcLogout(idToken);
  }, [clearAuth]);

  /** Exposes the access token for API calls */
  const getAccessToken = useCallback(() => accessTokenRef.current, []);

  // On mount: check for page refresh and whether the refresh token is still alive
  // (In this simple implementation nothing is persisted → always starts without a session)
  useEffect(() => {
    setLoading(false);
  }, []);

  return (
    <AuthContext.Provider value={{
      user,
      loading,
      isAuthenticated: !!user,
      login,
      register,
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
  if (!ctx) throw new Error('useAuth must be used inside AuthProvider');
  return ctx;
};
