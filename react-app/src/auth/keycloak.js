/**
 * Cliente OAuth2/OIDC para Keycloak.
 * Implementación manual del Authorization Code + PKCE flow.
 *
 * CONCEPTO: Discovery Document
 * En lugar de hardcodear URLs, podríamos fetchear:
 *   /realms/altana-dev/.well-known/openid-configuration
 * y obtener todos los endpoints automáticamente.
 * Por claridad educativa aquí las definimos explícitamente.
 */

import { KEYCLOAK_URL } from '../config';

const REALM          = 'altana-dev';
const CLIENT_ID      = 'altana-web';
const REDIRECT_URI   = 'http://localhost:5173/callback'; // puerto de Vite

const BASE = `${KEYCLOAK_URL}/realms/${REALM}/protocol/openid-connect`;

export const endpoints = {
  auth:     `${BASE}/auth`,
  token:    `${BASE}/token`,
  userinfo: `${BASE}/userinfo`,
  logout:   `${BASE}/logout`,
  jwks:     `${BASE}/certs`,
};

/**
 * PASO 1 — Construir la URL de autorización y redirigir al browser.
 *
 * Guarda verifier y state en sessionStorage ANTES de redirigir.
 * sessionStorage sobrevive la navegación pero se limpia al cerrar la pestaña.
 *
 * ENTREVISTA: "¿Por qué sessionStorage para el verifier y no localStorage?"
 * → El verifier es temporal (solo se usa una vez para intercambiar el code).
 *   sessionStorage se limpia al cerrar la pestaña, menor superficie de ataque.
 *   Si usáramos localStorage, el verifier podría persistir indefinidamente.
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
 * UC6 — Redirigir al formulario de REGISTRO de Keycloak.
 *
 * Keycloak expone un endpoint de registrations:
 *   /realms/{realm}/protocol/openid-connect/registrations
 *
 * Es equivalente al auth endpoint pero con action=register implícito.
 * loginHint pre-rellena el email en el formulario de registro.
 *
 * ENTREVISTA: "¿Cómo envías al usuario al registro en lugar del login?"
 * → Keycloak tiene el endpoint /registrations que arranca el flujo PKCE
 *   pero muestra el formulario de registro en lugar del de login.
 *   Después del registro, el callback es idéntico al del login normal.
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
 * PASO 2 — Intercambiar el code por tokens (back-channel desde el browser).
 *
 * En una SPA pura, este POST lo hace el browser directamente a Keycloak.
 * En BFF, lo haría el servidor.
 *
 * ENTREVISTA: "¿Es seguro hacer el exchange desde el browser?"
 * → Con PKCE sí, para clientes públicos. El code_verifier garantiza que
 *   solo quien inició el flujo puede intercambiar el code.
 *   Sin PKCE sería inseguro porque un atacante que intercepte el code
 *   podría intercambiarlo.
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
    throw new Error(error.error_description || 'Error al intercambiar code');
  }

  return response.json();
  // Retorna: { access_token, refresh_token, id_token, expires_in, ... }
}

/**
 * PASO 3 — Renovar access token con refresh token.
 * Se llama automáticamente antes de que el access token expire.
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
    throw new Error('Refresh token expirado — re-login requerido');
  }

  return response.json();
}

/** Decodifica el payload de un JWT sin verificar la firma (solo en cliente) */
export function decodeJwt(token) {
  try {
    const payload = token.split('.')[1];
    // atob decodifica base64 → necesitamos base64url → base64
    const base64 = payload.replace(/-/g, '+').replace(/_/g, '/');
    return JSON.parse(atob(base64));
  } catch {
    return null;
  }
}

/**
 * PASO 4 — Logout
 * Keycloak tiene un endpoint de logout que invalida la sesión SSO.
 * Sin llamar a este endpoint, aunque borres los tokens en el browser,
 * la sesión en Keycloak sigue activa → el usuario podría obtener
 * nuevos tokens sin re-autenticarse.
 *
 * ENTREVISTA: "¿Cómo haces logout correctamente en Keycloak?"
 * → Llamar a /logout con id_token_hint + post_logout_redirect_uri.
 *   Sin id_token_hint Keycloak puede pedir confirmación al usuario.
 */
export function logout(idToken) {
  const params = new URLSearchParams({
    client_id:                CLIENT_ID,
    post_logout_redirect_uri: 'http://localhost:5173',
    id_token_hint:            idToken,
  });
  window.location.href = `${endpoints.logout}?${params}`;
}
