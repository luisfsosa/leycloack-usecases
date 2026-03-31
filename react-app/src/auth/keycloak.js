/**
 * OAuth2/OIDC client for Keycloak.
 * Manual implementation of the Authorization Code + PKCE flow.
 *
 * CONCEPT: Discovery Document
 * Instead of hardcoding URLs, we could fetch:
 *   /realms/altana-dev/.well-known/openid-configuration
 * and obtain all endpoints automatically.
 * For educational clarity, we define them explicitly here.
 */

import { KEYCLOAK_URL } from '../config';

const REALM          = 'altana-dev';
const CLIENT_ID      = 'altana-web';
const REDIRECT_URI   = 'http://localhost:5173/callback'; // Vite port

const BASE = `${KEYCLOAK_URL}/realms/${REALM}/protocol/openid-connect`;

export const endpoints = {
  auth:     `${BASE}/auth`,
  token:    `${BASE}/token`,
  userinfo: `${BASE}/userinfo`,
  logout:   `${BASE}/logout`,
  jwks:     `${BASE}/certs`,
};

/**
 * STEP 1 — Build the authorization URL and redirect the browser.
 *
 * Saves verifier and state in sessionStorage BEFORE redirecting.
 * sessionStorage survives navigation but is cleared when the tab is closed.
 *
 * INTERVIEW: "Why sessionStorage for the verifier and not localStorage?"
 * → The verifier is temporary (used only once to exchange the code).
 *   sessionStorage is cleared when the tab is closed, smaller attack surface.
 *   If we used localStorage, the verifier could persist indefinitely.
 */
/**
 * idpHint (optional): adds kc_idp_hint=<alias> to skip the Keycloak login
 * screen and go directly to the specified Identity Provider.
 *
 * loginHint (optional): adds login_hint=<email> so Keycloak (and the external
 * IdP when kc_idp_hint is set) pre-fills the username/email field.
 * The user typed the email once in React — they should not have to type it again.
 *
 * INTERVIEW: "How do you avoid asking the user to type their email twice?"
 * → Pass login_hint=<email> in the authorization URL. Keycloak pre-fills its
 *   own login form, and automatically forwards the hint to the external IdP
 *   when kc_idp_hint is also set (OIDC standard: login_hint is forwarded).
 */
export async function startLogin(codeChallenge, state, idpHint = null, loginHint = null) {
  const params = new URLSearchParams({
    client_id:             CLIENT_ID,
    response_type:         'code',
    scope:                 'openid profile email',
    redirect_uri:          REDIRECT_URI,
    code_challenge:        codeChallenge,
    code_challenge_method: 'S256',
    state,
  });

  if (idpHint)    params.set('kc_idp_hint', idpHint);
  if (loginHint)  params.set('login_hint',  loginHint);

  window.location.href = `${endpoints.auth}?${params}`;
}

/**
 * UC6 — Redirect to the Keycloak REGISTRATION form.
 *
 * Keycloak exposes a registrations endpoint:
 *   /realms/{realm}/protocol/openid-connect/registrations
 *
 * It is equivalent to the auth endpoint but with action=register implicit.
 * loginHint pre-fills the email in the registration form.
 *
 * INTERVIEW: "How do you send the user to registration instead of login?"
 * → Keycloak has the /registrations endpoint that starts the PKCE flow
 *   but shows the registration form instead of the login form.
 *   After registration, the callback is identical to a normal login.
 */
export async function startRegistration(codeChallenge, state, loginHint = null) {
  const registrationEndpoint = `${KEYCLOAK_URL}/realms/${REALM}/protocol/openid-connect/registrations`;

  const params = new URLSearchParams({
    client_id:             CLIENT_ID,
    response_type:         'code',
    scope:                 'openid profile email',
    redirect_uri:          REDIRECT_URI,
    code_challenge:        codeChallenge,
    code_challenge_method: 'S256',
    state,
  });

  if (loginHint) params.set('login_hint', loginHint);

  window.location.href = `${registrationEndpoint}?${params}`;
}

/**
 * STEP 2 — Exchange the code for tokens (back-channel from the browser).
 *
 * In a pure SPA, this POST is made by the browser directly to Keycloak.
 * In a BFF pattern, the server would do it.
 *
 * INTERVIEW: "Is it safe to do the exchange from the browser?"
 * → With PKCE yes, for public clients. The code_verifier ensures that
 *   only the party that initiated the flow can exchange the code.
 *   Without PKCE it would be insecure because an attacker who intercepts
 *   the code could exchange it.
 */
export async function exchangeCode(code, codeVerifier) {
  const response = await fetch(endpoints.token, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type:    'authorization_code',
      client_id:     CLIENT_ID,
      code,
      redirect_uri:  REDIRECT_URI,
      code_verifier: codeVerifier,
    }),
  });

  if (!response.ok) {
    const error = await response.json();
    throw new Error(error.error_description || 'Failed to exchange code');
  }

  return response.json();
  // Returns: { access_token, refresh_token, id_token, expires_in, ... }
}

/**
 * STEP 3 — Renew access token with refresh token.
 * Called automatically before the access token expires.
 */
export async function refreshAccessToken(refreshToken) {
  const response = await fetch(endpoints.token, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type:    'refresh_token',
      client_id:     CLIENT_ID,
      refresh_token: refreshToken,
    }),
  });

  if (!response.ok) {
    throw new Error('Refresh token expired — re-login required');
  }

  return response.json();
}

/** Decodes the payload of a JWT without verifying the signature (client-side only) */
export function decodeJwt(token) {
  try {
    const payload = token.split('.')[1];
    // atob decodes base64 → we need base64url → base64
    const base64 = payload.replace(/-/g, '+').replace(/_/g, '/');
    return JSON.parse(atob(base64));
  } catch {
    return null;
  }
}

/**
 * STEP 4 — Logout
 * Keycloak has a logout endpoint that invalidates the SSO session.
 * Without calling this endpoint, even if you delete the tokens in the browser,
 * the session in Keycloak remains active → the user could obtain
 * new tokens without re-authenticating.
 *
 * INTERVIEW: "How do you do logout correctly in Keycloak?"
 * → Call /logout with id_token_hint + post_logout_redirect_uri.
 *   Without id_token_hint Keycloak may ask the user for confirmation.
 */
export function logout(idToken) {
  const params = new URLSearchParams({
    client_id:                CLIENT_ID,
    post_logout_redirect_uri: 'http://localhost:5173',
    id_token_hint:            idToken,
  });
  window.location.href = `${endpoints.logout}?${params}`;
}
