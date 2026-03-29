/**
 * UC6 — AcceptInvitePage
 *
 * Flujo:
 * 1. Lee ?token=... de la URL (el link que recibió el proveedor por email)
 * 2. Llama a GET /api/invitations/{token} para obtener detalles
 * 3. Muestra: "Toyota te invitó a unirte a Altana como proveedor Tier 2"
 * 4. Dos botones:
 *    - "Crear cuenta" → redirige a Keycloak registration con email pre-llenado
 *    - "Ya tengo cuenta" → flow normal PKCE
 *
 * Al volver del callback (isAuthenticated === true):
 * - Si hay pendingInviteToken en sessionStorage → completa el onboarding
 * - Limpia sessionStorage y redirige a /supplier/dashboard
 *
 * APRENDIZAJE: sessionStorage como mecanismo de "handoff" entre páginas.
 * Guardamos el invite token ANTES de salir al callback de Keycloak,
 * y lo consumimos AL VOLVER. Es como dejar una nota para ti mismo.
 *
 * ENTREVISTA: "¿Cómo mantienes contexto a través de un redirect OAuth2?"
 * → El parámetro 'state' puede codificar contexto (base64 de un objeto JSON).
 *   Alternativa: sessionStorage (solo mismo tab, se limpia al cerrar).
 *   NUNCA localStorage para datos sensibles — persiste indefinidamente.
 */

import { useEffect, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { API_URL as API_BASE } from '../config';

export default function AcceptInvitePage() {
  const [searchParams]  = useSearchParams();
  const { isAuthenticated, login, register, getAccessToken } = useAuth();
  const navigate        = useNavigate();

  const [invitation, setInvitation]   = useState(null);   // detalles de la invitación
  const [error, setError]             = useState(null);
  const [completing, setCompleting]   = useState(false);  // onboarding en progreso

  const token = searchParams.get('token');

  // ── Paso 1: cargar detalles de la invitación ─────────────────────────────
  useEffect(() => {
    if (!token) {
      setError('No se encontró un token de invitación en la URL.');
      return;
    }

    fetch(`${API_BASE}/invitations/${token}`)
      .then(res => {
        if (!res.ok) return res.json().then(e => { throw new Error(e.detail); });
        return res.json();
      })
      .then(setInvitation)
      .catch(err => setError(err.message));
  }, [token]);

  // ── Paso 2: al volver del callback, completar el onboarding ──────────────
  useEffect(() => {
    if (!isAuthenticated) return;

    const pendingToken = sessionStorage.getItem('pendingInviteToken');
    if (!pendingToken) return;

    setCompleting(true);

    const accessToken = getAccessToken();
    fetch(`${API_BASE}/invitations/${pendingToken}/complete`, {
      method:  'POST',
      headers: { Authorization: `Bearer ${accessToken}` },
    })
      .then(res => {
        if (!res.ok) return res.json().then(e => { throw new Error(e.detail); });
        return res.json();
      })
      .then(() => {
        sessionStorage.removeItem('pendingInviteToken');
        navigate('/supplier/dashboard');
      })
      .catch(err => {
        setError(`Error completando onboarding: ${err.message}`);
        setCompleting(false);
      });
  }, [isAuthenticated, getAccessToken, navigate]);

  // ── Handlers de botones ───────────────────────────────────────────────────

  function handleCreateAccount() {
    sessionStorage.setItem('pendingInviteToken', token);
    register(invitation?.invited_email ?? null);
  }

  function handleLogin() {
    sessionStorage.setItem('pendingInviteToken', token);
    login(null, invitation?.invited_email ?? null);
  }

  // ── Render ────────────────────────────────────────────────────────────────

  if (completing) {
    return (
      <div style={styles.container}>
        <p style={styles.subtitle}>Completando onboarding...</p>
      </div>
    );
  }

  if (error) {
    return (
      <div style={styles.container}>
        <h2 style={{ color: '#e74c3c' }}>Invitación inválida</h2>
        <p style={styles.subtitle}>{error}</p>
      </div>
    );
  }

  if (!invitation) {
    return (
      <div style={styles.container}>
        <p style={styles.subtitle}>Cargando invitación...</p>
      </div>
    );
  }

  const expiresDate = new Date(invitation.expires_at * 1000).toLocaleDateString('es-ES');

  return (
    <div style={styles.container}>
      <div style={styles.card}>
        <div style={styles.badge}>Invitación de proveedor</div>

        <h1 style={styles.title}>
          {invitation.tenant_id.toUpperCase()} te invitó a Altana
        </h1>

        <p style={styles.subtitle}>
          Únete como <strong>Proveedor Tier {invitation.supplier_tier}</strong> en la
          plataforma de supply chain de {invitation.tenant_id}.
        </p>

        <div style={styles.infoBox}>
          <InfoRow label="Para" value={invitation.invited_email} />
          <InfoRow label="Invitado por" value={invitation.invited_by} />
          <InfoRow label="Empresa" value={invitation.tenant_id.toUpperCase()} />
          <InfoRow label="Tier de proveedor" value={`Tier ${invitation.supplier_tier}`} />
          <InfoRow label="Expira" value={expiresDate} />
        </div>

        <div style={styles.actions}>
          <button style={styles.primaryBtn} onClick={handleCreateAccount}>
            Crear cuenta nueva
          </button>
          <button style={styles.secondaryBtn} onClick={handleLogin}>
            Ya tengo cuenta — Iniciar sesión
          </button>
        </div>

        <p style={styles.footer}>
          Al crear una cuenta aceptas los términos de uso de Altana.
          Tu email será verificado por Keycloak.
        </p>
      </div>
    </div>
  );
}

function InfoRow({ label, value }) {
  return (
    <div style={{ display: 'flex', justifyContent: 'space-between', padding: '6px 0', borderBottom: '1px solid #eee' }}>
      <span style={{ color: '#666', fontSize: '14px' }}>{label}</span>
      <span style={{ fontWeight: 500, fontSize: '14px' }}>{value}</span>
    </div>
  );
}

const styles = {
  container: {
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    minHeight: '100vh',
    background: '#f0f4f8',
    padding: '20px',
  },
  card: {
    background: '#fff',
    borderRadius: '12px',
    padding: '40px',
    maxWidth: '480px',
    width: '100%',
    boxShadow: '0 4px 24px rgba(0,0,0,0.08)',
  },
  badge: {
    display: 'inline-block',
    background: '#e8f4fd',
    color: '#2980b9',
    fontSize: '12px',
    fontWeight: 600,
    padding: '4px 10px',
    borderRadius: '20px',
    marginBottom: '16px',
    textTransform: 'uppercase',
    letterSpacing: '0.5px',
  },
  title: {
    fontSize: '22px',
    fontWeight: 700,
    marginBottom: '10px',
    color: '#1a1a2e',
  },
  subtitle: {
    color: '#555',
    fontSize: '15px',
    marginBottom: '24px',
    lineHeight: 1.5,
  },
  infoBox: {
    background: '#f8f9fa',
    borderRadius: '8px',
    padding: '16px',
    marginBottom: '28px',
  },
  actions: {
    display: 'flex',
    flexDirection: 'column',
    gap: '12px',
    marginBottom: '20px',
  },
  primaryBtn: {
    background: '#2980b9',
    color: '#fff',
    border: 'none',
    borderRadius: '8px',
    padding: '14px',
    fontSize: '15px',
    fontWeight: 600,
    cursor: 'pointer',
  },
  secondaryBtn: {
    background: 'transparent',
    color: '#2980b9',
    border: '2px solid #2980b9',
    borderRadius: '8px',
    padding: '12px',
    fontSize: '15px',
    fontWeight: 600,
    cursor: 'pointer',
  },
  footer: {
    color: '#999',
    fontSize: '12px',
    textAlign: 'center',
    lineHeight: 1.4,
  },
};
