/**
 * PKCE utilities — todo ocurre en el browser, sin librerías externas.
 *
 * CONCEPTO: PKCE (Proof Key for Code Exchange)
 * El browser genera un par verifier/challenge ANTES de ir a Keycloak.
 * Keycloak guarda el challenge. Al intercambiar el code, el browser
 * envía el verifier. Keycloak verifica: SHA256(verifier) === challenge.
 * Si alguien interceptó el code, no tiene el verifier → no puede usarlo.
 *
 * Web Crypto API: nativa en todos los browsers modernos, no necesita librerías.
 */

/** Convierte ArrayBuffer a string base64url (sin padding) */
function base64urlEncode(buffer) {
  return btoa(String.fromCharCode(...new Uint8Array(buffer)))
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=/g, '');
}

/** Genera un code_verifier aleatorio (43-128 chars, URL-safe) */
export function generateCodeVerifier() {
  const array = new Uint8Array(32); // 32 bytes → 43 chars base64url
  crypto.getRandomValues(array);
  return base64urlEncode(array);
}

/**
 * Genera el code_challenge = BASE64URL(SHA256(verifier))
 * Web Crypto API es async → usa SubtleCrypto.digest()
 *
 * ENTREVISTA: "¿Por qué S256 y no plain?"
 * → plain envía el verifier en texto plano (inseguro). S256 envía el hash
 *   SHA256 del verifier. Incluso si el challenge es interceptado, no se
 *   puede derivar el verifier (SHA256 es one-way).
 */
export async function generateCodeChallenge(verifier) {
  const encoded = new TextEncoder().encode(verifier);
  const digest = await crypto.subtle.digest('SHA-256', encoded);
  return base64urlEncode(digest);
}

/** Genera un state aleatorio para protección CSRF */
export function generateState() {
  const array = new Uint8Array(16);
  crypto.getRandomValues(array);
  return base64urlEncode(array);
}
