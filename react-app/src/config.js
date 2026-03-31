/**
 * Central URL configuration.
 * Change here to move the entire project to a different host/port.
 *
 * In production: replace with Vite environment variables:
 *   VITE_API_URL=https://api.altana.com
 *   VITE_KEYCLOAK_URL=https://auth.altana.com
 */

export const KEYCLOAK_URL = import.meta.env.VITE_KEYCLOAK_URL ?? 'http://localhost:8080';
export const API_URL      = import.meta.env.VITE_API_URL      ?? 'http://localhost:8081';
