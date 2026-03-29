/**
 * Configuración central de URLs.
 * Cambiar aquí para mover todo el proyecto a otro host/puerto.
 *
 * En producción: reemplazar con variables de entorno de Vite:
 *   VITE_API_URL=https://api.altana.com
 *   VITE_KEYCLOAK_URL=https://auth.altana.com
 */

export const KEYCLOAK_URL = import.meta.env.VITE_KEYCLOAK_URL ?? 'http://localhost:8080';
export const API_URL      = import.meta.env.VITE_API_URL      ?? 'http://localhost:8081';
