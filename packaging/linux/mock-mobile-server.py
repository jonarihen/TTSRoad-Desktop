#!/usr/bin/env python3
"""Tiny stdlib-only capabilities server for the installed-package smoke test."""

from __future__ import annotations

import json
import pathlib
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


if len(sys.argv) != 3:
    raise SystemExit(f"usage: {sys.argv[0]} <ready-file> <request-log>")

ready_file = pathlib.Path(sys.argv[1])
request_log = pathlib.Path(sys.argv[2])


class Handler(BaseHTTPRequestHandler):
    def do_GET(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
        request_log.parent.mkdir(parents=True, exist_ok=True)
        with request_log.open("a", encoding="utf-8") as stream:
            stream.write(f"{self.command} {self.path}\n")
        if self.path != "/api/mobile/capabilities":
            self.send_error(404)
            return

        body = json.dumps(
            {
                "api_version": 1,
                "server": {
                    "name": "TTSRoad package smoke server",
                    "version": "1.0.0-test",
                    "base_url": f"http://127.0.0.1:{self.server.server_port}",
                },
                "capabilities": {"readalong": True},
                "limits": {},
            }
        ).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, _format: str, *args: object) -> None:
        return


server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
ready_file.parent.mkdir(parents=True, exist_ok=True)
ready_file.write_text(f"http://127.0.0.1:{server.server_port}/", encoding="utf-8")
server.serve_forever()
