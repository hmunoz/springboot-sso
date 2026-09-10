#!/usr/bin/env python3
"""
Bridge Stdio -> HTTP MCP Server con autenticación Keycloak.
Permite a Antigravity conectarse a un servidor MCP securizado con OAuth2/JWT
renovando tokens automáticamente.
"""
import sys
import os
import json
import time
import urllib.request
import urllib.error

KEYCLOAK_URL = os.environ.get("KEYCLOAK_URL", "http://localhost:9091")
REALM = os.environ.get("KEYCLOAK_REALM", "videoclub")
CLIENT_ID = os.environ.get("KEYCLOAK_CLIENT_ID", "videoclub-mcp")
USERNAME = os.environ.get("KEYCLOAK_USER", "usuarioadmin")
PASSWORD = os.environ.get("KEYCLOAK_PASSWORD", "usuarioadmin")
MCP_URL = os.environ.get("MCP_URL", "http://localhost:8080/mcp")

current_token = None
token_expiry = 0


LOG_FILE = "/tmp/mcp-bridge.log"

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


def forward_to_mcp(body_bytes):
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
            with urllib.request.urlopen(req, timeout=30) as resp2:
                data = resp2.read()
                log_debug(f"Retry HTTP {resp2.status}: {data.decode('utf-8', errors='replace')}")
                return data
        return err_body


def main():
    import urllib.parse
    log_debug("Bridge started")
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        log_debug(f"STDIN IN: {line}")
        try:
            req_json = json.loads(line)
            is_notification = "id" not in req_json

            resp_bytes = forward_to_mcp(line.encode("utf-8"))
            if resp_bytes and not is_notification:
                resp_str = resp_bytes.decode("utf-8")
                log_debug(f"STDOUT OUT: {resp_str}")
                sys.stdout.write(resp_str + "\n")
                sys.stdout.flush()
        except Exception as e:
            log_debug(f"Exception: {e}")
            sys.stderr.write(f"[mcp-bridge] Error procesando request: {e}\n")
            sys.stderr.flush()


if __name__ == "__main__":
    main()
