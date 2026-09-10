#!/usr/bin/env python3
"""
Bridge Stdio -> HTTP MCP Server con autenticación Keycloak.
Permite a Antigravity conectarse a un servidor MCP securizado con OAuth2/JWT
renovando tokens automáticamente y garantizando framing JSON-RPC 2.0 estricto.
"""
import sys
import os
import json
import time
import urllib.request
import urllib.error
import urllib.parse

KEYCLOAK_URL = os.environ.get("KEYCLOAK_URL", "http://localhost:9091")
REALM = os.environ.get("KEYCLOAK_REALM", "videoclub")
CLIENT_ID = os.environ.get("KEYCLOAK_CLIENT_ID", "videoclub-mcp")
USERNAME = os.environ.get("KEYCLOAK_USER", "usuarioadmin")
PASSWORD = os.environ.get("KEYCLOAK_PASSWORD", "usuarioadmin")
MCP_URL = os.environ.get("MCP_URL", "http://localhost:8080/mcp")

LOG_FILE = "/tmp/mcp-bridge.log"

current_token = None
token_expiry = 0


def log_debug(msg):
    try:
        with open(LOG_FILE, "a", encoding="utf-8") as f:
            f.write(f"[{time.strftime('%Y-%m-%d %H:%M:%S')}] {msg}\n")
    except Exception:
        pass


def get_access_token():
    global current_token, token_expiry
    now = time.time()
    if current_token and now < (token_expiry - 30):
        return current_token

    token_endpoint = f"{KEYCLOAK_URL}/realms/{REALM}/protocol/openid-connect/token"
    payload = urllib.parse.urlencode({
        "client_id": CLIENT_ID,
        "username": USERNAME,
        "password": PASSWORD,
        "grant_type": "password"
    }).encode("utf-8")

    req = urllib.request.Request(token_endpoint, data=payload, method="POST")
    req.add_header("Content-Type", "application/x-www-form-urlencoded")

    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            current_token = data["access_token"]
            expires_in = data.get("expires_in", 1800)
            token_expiry = now + expires_in
            log_debug(f"Obtained new token, expires in {expires_in}s")
            return current_token
    except Exception as e:
        log_debug(f"Error getting token from Keycloak: {e}")
        sys.stderr.write(f"[mcp-bridge] Error obteniendo token de Keycloak: {e}\n")
        sys.stderr.flush()
        raise


def forward_to_mcp(body_bytes, req_id):
    token = get_access_token()
    req = urllib.request.Request(MCP_URL, data=body_bytes, method="POST")
    req.add_header("Authorization", f"Bearer {token}")
    req.add_header("Accept", "application/json, text/event-stream")
    req.add_header("Content-Type", "application/json")

    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            data = resp.read()
            log_debug(f"HTTP {resp.status}: {data.decode('utf-8', errors='replace')}")
            return data
    except urllib.error.HTTPError as e:
        err_body = e.read()
        log_debug(f"HTTP Error {e.code}: {err_body.decode('utf-8', errors='replace')}")
        # Si fue 401, reintentar una vez con token fresco
        if e.code == 401:
            global current_token
            current_token = None
            token = get_access_token()
            req = urllib.request.Request(MCP_URL, data=body_bytes, method="POST")
            req.add_header("Authorization", f"Bearer {token}")
            req.add_header("Accept", "application/json, text/event-stream")
            req.add_header("Content-Type", "application/json")
            try:
                with urllib.request.urlopen(req, timeout=30) as resp2:
                    data = resp2.read()
                    log_debug(f"Retry HTTP {resp2.status}: {data.decode('utf-8', errors='replace')}")
                    return data
            except urllib.error.HTTPError as e2:
                err_body = e2.read()

        # Asegurar respuesta con formato JSON-RPC 2.0
        try:
            parsed = json.loads(err_body.decode("utf-8"))
            if "jsonrpc" in parsed:
                return err_body
            msg = parsed.get("message") or parsed.get("error") or str(parsed)
        except Exception:
            msg = err_body.decode("utf-8", errors="replace") or f"HTTP {e.code}"

        rpc_err = {
            "jsonrpc": "2.0",
            "id": req_id,
            "error": {
                "code": -32603,
                "message": msg
            }
        }
        return json.dumps(rpc_err).encode("utf-8")


def main():
    log_debug("Bridge started")
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        log_debug(f"STDIN IN: {line}")
        try:
            req_json = json.loads(line)
            req_id = req_json.get("id")
            method = req_json.get("method")
            is_notification = "id" not in req_json

            # Antigravity envía 'server/discover' como prueba inicial de capacidades.
            # Spring AI no soporta este método y lanzaría 500; respondemos Method Not Found estándar.
            if method == "server/discover":
                log_debug("Handled server/discover with Method Not Found (-32601)")
                resp = {
                    "jsonrpc": "2.0",
                    "id": req_id,
                    "error": {
                        "code": -32601,
                        "message": "Method not found: server/discover"
                    }
                }
                out = json.dumps(resp)
                log_debug(f"STDOUT OUT: {out}")
                sys.stdout.write(out + "\n")
                sys.stdout.flush()
                continue

            resp_bytes = forward_to_mcp(line.encode("utf-8"), req_id)
            if resp_bytes and not is_notification:
                resp_str = resp_bytes.decode("utf-8").strip()
                if resp_str:
                    # Validar que tenga el tag jsonrpc 2.0 requerido
                    try:
                        parsed = json.loads(resp_str)
                        if "jsonrpc" not in parsed:
                            parsed["jsonrpc"] = "2.0"
                            if "id" not in parsed:
                                parsed["id"] = req_id
                            resp_str = json.dumps(parsed)
                    except Exception:
                        pass
                    log_debug(f"STDOUT OUT: {resp_str}")
                    sys.stdout.write(resp_str + "\n")
                    sys.stdout.flush()
        except Exception as e:
            log_debug(f"Exception: {e}")
            sys.stderr.write(f"[mcp-bridge] Error procesando request: {e}\n")
            sys.stderr.flush()


if __name__ == "__main__":
    main()
