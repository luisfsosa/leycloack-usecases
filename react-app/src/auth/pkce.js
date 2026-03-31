/**
 * PKCE utilities — everything runs in the browser, no external libraries.
 *
 * CONCEPT: PKCE (Proof Key for Code Exchange)
 * The browser generates a verifier/challenge pair BEFORE going to Keycloak.
 * Keycloak stores the challenge. When exchanging the code, the browser
 * sends the verifier. Keycloak verifies: SHA256(verifier) === challenge.
 * If someone intercepted the code, they don't have the verifier → can't use it.
 *
 * Web Crypto API: native in all modern browsers, no libraries needed.
 */

/** Converts an ArrayBuffer to a base64url string (no padding) */
function base64urlEncode(buffer) {
  return btoa(String.fromCharCode(...new Uint8Array(buffer)))
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=/g, '');
}

/** Generates a random code_verifier (43-128 chars, URL-safe) */
export function generateCodeVerifier() {
  const array = new Uint8Array(32); // 32 bytes → 43 chars base64url
  crypto.getRandomValues(array);
  return base64urlEncode(array);
}

/**
 * Generates the code_challenge = BASE64URL(SHA256(verifier))
 * Web Crypto API is async → uses SubtleCrypto.digest()
 *
 * INTERVIEW: "Why S256 and not plain?"
 * → plain sends the verifier in plain text (insecure). S256 sends the
 *   SHA256 hash of the verifier. Even if the challenge is intercepted,
 *   the verifier cannot be derived (SHA256 is one-way).
 */
export async function generateCodeChallenge(verifier) {
  const encoded = new TextEncoder().encode(verifier);
  const digest = await crypto.subtle.digest('SHA-256', encoded);
  return base64urlEncode(digest);
}

/** Generates a random state value for CSRF protection */
export function generateState() {
  const array = new Uint8Array(16);
  crypto.getRandomValues(array);
  return base64urlEncode(array);
}
