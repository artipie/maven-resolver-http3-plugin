#!/usr/bin/env python3
"""
Very simple HTTP file server in python
Usage::
    ./server.py [<port>]
"""
from http.server import BaseHTTPRequestHandler, HTTPServer
import mimetypes
import os
import pathlib

cwd = os.path.normcase(os.getcwd())

class S(BaseHTTPRequestHandler):
    def __init__(self, *args, directory=None, **kwargs):
        self.protocol_version = 'HTTP/1.1'
        super().__init__(*args, **kwargs)

    def _send_response(self, code = 200, msg='Done', content_type='text/plain'):
        body = msg.encode('utf-8')
        self.send_response(code)
        self.send_header('Content-type', content_type)
        self.send_header('Content-Length', len(body))
        self.end_headers()
        self.wfile.write(body)

    def req_path(self):
        fname = self.path[1:] # Strip leading slash
        path = os.path.normcase(os.path.dirname(os.path.realpath(fname)))
        if os.path.commonpath((path, cwd)) == cwd:
            return fname
        raise Exception("Access denied")

    def do_GET(self):
        try:
            with open(self.req_path(), "rb") as src:
                self._send_response(200, src.read(), mimetypes.guess_type(self.req_path()))
        except:
            self._send_response(404, '404 Not Found\r\n')

    def do_PUT(self):
        try:
            path = self.req_path()
            pathlib.Path(path).parent.mkdir(parents=True, exist_ok=True)
            with open(path, "wb") as dst:
                content_length = int(self.headers['Content-Length'])
                dst.write(self.rfile.read(content_length))
            self._send_response(200, 'Done\r\n')
        except Exception as ex:
            print(ex)
            self._send_response(500, '500 Access Denied\r\n')

    def do_POST(self):
        return self.do_PUT()

def run(port=8000):
    server_address = ('', port)
    httpd = HTTPServer(server_address, S)
    print(f'Listening on port {port}')
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass
    httpd.server_close()

if __name__ == '__main__':
    from sys import argv

    if len(argv) == 2:
        run(port=int(argv[1]))
    else:
        run()