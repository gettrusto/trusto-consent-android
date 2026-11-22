#!/usr/bin/env python3
"""Coletor falso para o app de teste, na máquina de desenvolvimento.

Responde `GET /v1/app/config` e `POST /v1/app/records` no formato do coletor, para exercitar
o fluxo inteiro sem chave de verdade. O emulador o enxerga em http://10.0.2.2:8787.

    python3 -I sample/tools/mock-collector.py [porta]

Pare o processo para ver a fila do SDK crescer; suba de novo e toque em "Enviar agora".
Nunca use em aparelho fora da sua rede: é http, sem autenticação.
"""
import json
import sys
import uuid
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

CONFIG = {
    "version": 3,
    "bannerPosition": "bottom",
    "bannerStyle": {"primaryColor": "#B45309", "textColor": "#292524", "backgroundColor": "#FAF7F2", "borderRadius": 8},
    "privacyPolicyUrl": "https://example.com/privacidade",
    "categories": [
        {"key": "necessary", "name": "Necessários", "description": "Essenciais para o app funcionar.", "required": True, "defaultEnabled": True, "cookies": []},
        {"key": "analytics", "name": "Estatísticas", "description": "Medem o uso do app, de forma agregada.", "required": False, "defaultEnabled": False, "cookies": []},
        {"key": "marketing", "name": "Marketing", "description": "Anúncios e campanhas personalizadas.", "required": False, "defaultEnabled": False, "cookies": []},
    ],
    "consentExpiryDays": 180,
    "blockBeforeConsent": True,
    "holderUrl": None,
}


class Handler(BaseHTTPRequestHandler):
    def _send(self, status, body):
        data = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _authorized(self):
        auth = self.headers.get("Authorization", "")
        return auth.startswith("Bearer trusto_pk_test_")

    def do_GET(self):
        if not self.path.startswith("/v1/app/config"):
            return self._send(404, {"error": "not_found"})
        if not self._authorized():
            return self._send(401, {"error": "invalid_app_key"})
        self._send(200, CONFIG)

    def do_POST(self):
        if self.path != "/v1/app/records":
            return self._send(404, {"error": "not_found"})
        if not self._authorized():
            return self._send(401, {"error": "invalid_app_key"})
        length = int(self.headers.get("Content-Length", "0"))
        body = json.loads(self.rfile.read(length) or b"{}")
        if body.get("configVersion") != CONFIG["version"]:
            return self._send(409, {"error": "config_outdated", "version": CONFIG["version"]})
        print("registro:", json.dumps({k: v for k, v in body.items() if k != "visitorId"}), flush=True)
        expires = datetime.now(timezone.utc) + timedelta(days=CONFIG["consentExpiryDays"])
        self._send(201, {"consentId": str(uuid.uuid4()), "expiresAt": expires.strftime("%Y-%m-%dT%H:%M:%S.000Z")})

    def log_message(self, fmt, *args):
        print("%s %s" % (self.command, self.path), flush=True)


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8787
    print(f"coletor falso em http://127.0.0.1:{port}", flush=True)
    ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()
