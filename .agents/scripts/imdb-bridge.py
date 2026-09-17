#!/usr/bin/env python3
"""
Bridge Stdio para mcp-imdb en Antigravity / Gemini.
Intercepta y responde 'server/discover' (método de prueba propio de Antigravity
que el SDK oficial de Python MCP no reconoce y hace fallar la validación de Pydantic),
y reenvía el resto del tráfico JSON-RPC al servidor stdio en Docker.
"""
import sys
import json
import subprocess
import threading

LOG_FILE = "/tmp/mcp-imdb-bridge.log"


def log_debug(msg):
    try:
        with open(LOG_FILE, "a", encoding="utf-8") as f:
            f.write(f"{msg}\n")
    except Exception:
        pass


def get_docker_cmd():
    # Si el contenedor del compose ya está corriendo, usamos 'docker exec' (arranque instantáneo)
    check = subprocess.run(
        ["docker", "ps", "-q", "-f", "name=videoclub-mcp-imdb-1"],
        capture_output=True,
        text=True
    )
    if check.stdout.strip():
        return [
            "docker", "exec", "-i", "videoclub-mcp-imdb-1",
            "/app/.venv/bin/python", "-c",
            "import asyncio; from mcp_imdb.server import main; asyncio.run(main())"
        ]
    return [
        "docker", "run", "-i", "--rm", "ghcr.io/juanmandev/mcp-imdb:pr-4",
        "/app/.venv/bin/python", "-c",
        "import asyncio; from mcp_imdb.server import main; asyncio.run(main())"
    ]


def main():
    log_debug("Starting imdb bridge...")
    cmd = get_docker_cmd()
    log_debug(f"Spawning: {' '.join(cmd)}")

    proc = subprocess.Popen(
        cmd,
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=sys.stderr,
        text=True,
        bufsize=1
    )

    def forward_stdout():
        for line in proc.stdout:
            log_debug(f"FROM MCP: {line.strip()}")
            sys.stdout.write(line)
            sys.stdout.flush()

    t = threading.Thread(target=forward_stdout, daemon=True)
    t.start()

    for line in sys.stdin:
        line_clean = line.strip()
        if not line_clean:
            continue
        log_debug(f"FROM CLIENT: {line_clean}")
        try:
            req = json.loads(line_clean)
            if isinstance(req, dict) and req.get("method") == "server/discover":
                req_id = req.get("id")
                err_resp = {
                    "jsonrpc": "2.0",
                    "id": req_id,
                    "error": {
                        "code": -32601,
                        "message": "Method not found: server/discover"
                    }
                }
                out = json.dumps(err_resp)
                log_debug(f"INTERCEPTED server/discover -> {out}")
                sys.stdout.write(out + "\n")
                sys.stdout.flush()
                continue
        except Exception as e:
            log_debug(f"Error parsing line: {e}")

        if proc.poll() is None:
            try:
                proc.stdin.write(line)
                proc.stdin.flush()
            except BrokenPipeError:
                break
        else:
            log_debug("Subprocess terminated unexpectedly")
            break

    try:
        proc.stdin.close()
    except Exception:
        pass
    try:
        proc.wait(timeout=3)
    except Exception:
        proc.terminate()


if __name__ == "__main__":
    main()
