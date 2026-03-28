#!/usr/bin/env python3
"""
Servidor HTTP mínimo en puerto 3000 que captura el callback OAuth2.
Extrae code y state del query string y los muestra en consola.

Uso:
  1. Ejecutar este script: python scripts/capture_callback.py
  2. En otro terminal: python scripts/generate_b2b_url.py
  3. Copiar el URL y abrirlo en el browser
  4. Completar el login → este servidor captura el code automáticamente
"""

from http.server import HTTPServer, BaseHTTPRequestHandler
import urllib.parse


class CallbackHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)

        if parsed.path != "/callback":
            self.send_response(404)
            self.end_headers()
            return

        params = urllib.parse.parse_qs(parsed.query)

        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.end_headers()

        code  = params.get("code",  [""])[0]
        state = params.get("state", [""])[0]
        error = params.get("error", [""])[0]

        if error:
            body = f"<h1>Error OAuth2</h1><pre>{params}</pre>"
            print(f"\n[ERROR] {params}")
        else:
            body = f"""
            <h1>Callback capturado</h1>
            <p><b>code:</b> <code>{code}</code></p>
            <p><b>state:</b> <code>{state}</code></p>
            <p>Cierra esta pestaña y vuelve al terminal.</p>
            """
            print("\n" + "=" * 70)
            print("CALLBACK CAPTURADO")
            print(f"  code  : {code}")
            print(f"  state : {state}")
            print("=" * 70)
            print("\nAhora ejecuta el curl de exchange que generó generate_b2b_url.py")
            print("Reemplaza $CODE con el code de arriba.\n")

        self.wfile.write(body.encode())

    def log_message(self, format, *args):
        pass  # silenciar logs HTTP normales


print("Servidor de captura escuchando en http://localhost:3000")
print("Esperando callback de Keycloak...\n")
HTTPServer(("localhost", 3000), CallbackHandler).serve_forever()
