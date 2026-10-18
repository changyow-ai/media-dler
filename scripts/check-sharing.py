#!/usr/bin/env python3
"""Exercise running servers over real sockets. pip install paramiko; no Android mocks.

Host: start :core:sharingSmokeServer -PshareRoot=... and pass its printed ports.
Device: use --host <device LAN IPv4> and the ports shown in settings, with --ssdp.
The selected device folder must already contain matching fixtures; --prepare writes local files.
"""
import argparse
import base64
import hashlib
import http.server
import pathlib
import socket
import threading
import time
import queue
import shutil
import subprocess
import urllib.error
import urllib.request
import urllib.parse
import xml.etree.ElementTree as ET

import paramiko

parser = argparse.ArgumentParser()
parser.add_argument("--root", required=True, type=pathlib.Path)
parser.add_argument("--prepare", action="store_true")
parser.add_argument("--http", type=int)
parser.add_argument("--host", default="127.0.0.1")
parser.add_argument("--sftp", type=int, default=22222)
parser.add_argument("--password", default="smoke-test-password")
parser.add_argument("--ssdp", action="store_true")
parser.add_argument("--device-id", default="d0930f35-a0c2-4b43-9874-5bd6b189749a")
parser.add_argument("--callback-host", help="Local address the server can reach for event callbacks")
parser.add_argument("--playback", action="store_true", help="Generate/decode a small H.264 video using ffmpeg")
args = parser.parse_args()
args.root.mkdir(parents=True, exist_ok=True)
small = args.root / "中文資料夾" / "測試 & 空白.mp4"
large = args.root / "large.mp4"
if args.prepare:
    small.parent.mkdir(exist_ok=True)
    small.write_bytes(bytes(range(256)) * 32768)
    with large.open("wb") as f:
        f.seek(4 * 1024**3 + 37)
        f.write(b"AFTER_FOUR_GIB")
    (args.root / "media-dler-incomplete.partial.mp4").write_bytes(b"not complete")
    if args.playback:
        assert shutil.which("ffmpeg"), "--playback requires ffmpeg"
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", "testsrc=size=160x90:rate=24",
                        "-t", "2", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-movflags", "+faststart",
                        str(args.root / "playback.mp4")], check=True)
    print("Fixtures ready", flush=True)
    if not args.http:
        raise SystemExit(0)
assert args.http, "--http is required for network checks"
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
base = f"http://{args.host}:{args.http}"
if not args.callback_host:
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as route:
        route.connect((args.host, args.http))
        args.callback_host = route.getsockname()[0]
checks = []


def passed(name):
    checks.append(name)
    print("PASS", name, flush=True)


def request(path, method="GET", data=None, headers=None):
    req = urllib.request.Request(base + path, method=method, data=data,
                                 headers=headers or {})
    try:
        with opener.open(req, timeout=15) as r:
            return r.status, r.headers, r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.headers, e.read()


if args.ssdp:
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind((args.callback_host, 0))
    sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_IF, socket.inet_aton(args.callback_host))
    sock.sendto(b'M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: "ssdp:discover"\r\nMX: 1\r\nST: urn:schemas-upnp-org:device:MediaServer:1\r\n\r\n', ("239.255.255.250", 1900))
    deadline = time.monotonic() + 5
    try:
        while True:
            sock.settimeout(max(0.001, deadline - time.monotonic()))
            answer = sock.recv(8192)
            locations = [line.split(":", 1)[1].strip() for line in answer.decode().splitlines() if line.lower().startswith("location:")]
            if locations:
                location = urllib.parse.urlparse(locations[0])
                if location.hostname == args.host and location.port == args.http:
                    assert b"200 OK" in answer, answer
                    break
            assert time.monotonic() < deadline, "No discovery response from the target server"
    finally:
        sock.close()
    passed("SSDP multicast M-SEARCH response")

desc_path = f"/dev/{args.device_id}/desc"
# Generic jUPnP descriptor URL can be discovered from SSDP on a device.
if args.ssdp:
    location = next(line.split(":", 1)[1].strip() for line in answer.decode().splitlines() if line.lower().startswith("location:"))
    desc_path = urllib.parse.urlparse(location).path
status, _, xml = request(desc_path)
assert status == 200, (status, xml)
device = ET.fromstring(xml)
ns = {"u": "urn:schemas-upnp-org:device-1-0"}
services = device.findall(".//u:service", ns)
directory = next(s for s in services if "ContentDirectory" in s.findtext("u:serviceType", namespaces=ns))
control = directory.findtext("u:controlURL", namespaces=ns)
event = directory.findtext("u:eventSubURL", namespaces=ns)
passed("UPnP device and ContentDirectory descriptor")
assert request(control, "POST", b'<!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/hostname">]><x>&e;</x>',
               {"Content-Type": "text/xml"})[0] == 400
assert request(control, "POST", b"x" * 65537, {"Content-Type": "text/xml"})[0] == 400
escape = base64.urlsafe_b64encode(b"/../../etc/passwd").decode().rstrip("=")
assert request("/media/" + escape)[0] == 403
passed("HTTP rejects XML entities, oversized control bodies and share escape")

events = queue.Queue()


class EventReceiver(http.server.BaseHTTPRequestHandler):
    def do_NOTIFY(self):
        events.put(self.rfile.read(int(self.headers["Content-Length"])))
        self.send_response(200)
        self.end_headers()

    def log_message(self, *args):
        pass


callback = http.server.ThreadingHTTPServer(("0.0.0.0", 0), EventReceiver)
threading.Thread(target=callback.serve_forever, daemon=True).start()
try:
    status, headers, _ = request(event, "SUBSCRIBE", headers={
        "CALLBACK": f"<http://{args.callback_host}:{callback.server_port}/event>",
        "NT": "upnp:event", "TIMEOUT": "Second-300"})
    assert status == 200 and headers["SID"], status
    assert b"SystemUpdateID" in events.get(timeout=15)
    sid = headers["SID"]
    assert request(event, "SUBSCRIBE", headers={"SID": sid, "TIMEOUT": "Second-300"})[0] == 200
    assert request(event, "UNSUBSCRIBE", headers={"SID": sid})[0] == 200
    passed("UPnP event subscribe, initial callback, renew and unsubscribe")
finally:
    callback.shutdown()
    callback.server_close()


def browse(oid, start=0, count=0):
    body = f'''<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:Browse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1"><ObjectID>{oid}</ObjectID><BrowseFlag>BrowseDirectChildren</BrowseFlag><Filter>*</Filter><StartingIndex>{start}</StartingIndex><RequestedCount>{count}</RequestedCount><SortCriteria>+dc:title</SortCriteria></u:Browse></s:Body></s:Envelope>'''
    status, _, payload = request(control, "POST", body.encode(), {"Content-Type": 'text/xml; charset="utf-8"', "SOAPACTION": '"urn:schemas-upnp-org:service:ContentDirectory:1#Browse"'})
    assert status == 200, payload
    envelope = ET.fromstring(payload)
    result = next(e.text for e in envelope.iter() if e.tag == "Result")
    return ET.fromstring(result), envelope


root, _ = browse("0")
assert "partial" not in ET.tostring(root, encoding="unicode")
child = next(e for e in root if e.findtext("{http://purl.org/dc/elements/1.1/}title") == "中文資料夾")
items, _ = browse(child.attrib["id"])
resource = next(e for e in items.iter() if e.tag.endswith("}res"))
media_path = urllib.parse.urlparse(resource.text).path
assert any(e.text == "測試 & 空白.mp4" for e in items.iter())
passed("UPnP directory browse and escaped Unicode metadata")
paged, envelope = browse("0", 0, 1)
assert len(paged) == 1
assert int(next(e.text for e in envelope.iter() if e.tag == "TotalMatches")) >= 2
passed("UPnP pagination")

expected = small.read_bytes()
status, headers, actual = request(media_path)
assert status == 200 and hashlib.sha256(actual).digest() == hashlib.sha256(expected).digest()
passed("HTTP complete transfer hash")
status, headers, body = request(media_path, "HEAD")
assert status == 200 and int(headers["Content-Length"]) == len(expected) and not body
for span, start, end in [("bytes=131072-262143", 131072, 262144), ("bytes=-257", len(expected)-257, len(expected)), ("bytes=8388600-", 8388600, len(expected))]:
    status, _, actual = request(media_path, headers={"Range": span})
    assert status == 206 and actual == expected[start:end], span
for span in ["bytes=999999999999-", "bytes=-0", "bytes=20-10", "bytes=999999999999999999999-"]:
    assert request(media_path, headers={"Range": span})[0] == 416, span
passed("HTTP HEAD, seeks, suffixes and invalid/overflow ranges")
large_id = base64.urlsafe_b64encode(b"/large.mp4").decode().rstrip("=")
status, _, actual = request("/media/" + large_id, headers={"Range": f"bytes={4*1024**3+37}-"})
assert status == 206 and actual == b"AFTER_FOUR_GIB"
passed("HTTP seek beyond 4 GiB")
if args.playback:
    resource_id = base64.urlsafe_b64encode(b"/playback.mp4").decode().rstrip("=")
    subprocess.run(["ffmpeg", "-v", "error", "-ss", "0.5",
                    "-i", base + "/media/" + resource_id, "-t", "1", "-f", "null", "-"],
                   check=True, timeout=30)
    passed("Independent ffmpeg client streams, seeks and decodes H.264")

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())  # Dedicated test fixture only.
try:
    ssh.connect(args.host, args.sftp, "media", "wrong-password", look_for_keys=False, allow_agent=False, timeout=10, auth_timeout=150)
    raise AssertionError("Wrong password accepted")
except paramiko.AuthenticationException:
    passed("SFTP rejects wrong password")
ssh.connect(args.host, args.sftp, "media", args.password, look_for_keys=False, allow_agent=False, timeout=10, auth_timeout=150)
sftp = ssh.open_sftp()
assert "中文資料夾" in sftp.listdir("/")
assert not any("partial" in n for n in sftp.listdir("/"))
with sftp.open("/中文資料夾/測試 & 空白.mp4", "rb") as f:
    assert hashlib.sha256(f.read()).digest() == hashlib.sha256(expected).digest()
    f.seek(131072)
    assert f.read(131072) == expected[131072:262144]
with sftp.open("/large.mp4", "rb") as f:
    f.seek(4*1024**3+37)
    assert f.read() == b"AFTER_FOUR_GIB"
passed("SFTP Unicode listing, complete transfer hash and 64-bit seek")
for operation in [lambda: sftp.open("/new.txt", "wb"), lambda: sftp.remove("/large.mp4"), lambda: sftp.mkdir("/new"), lambda: sftp.rename("/large.mp4", "/moved.mp4"), lambda: sftp.chmod("/large.mp4", 0o777), lambda: sftp.open("/../../etc/passwd", "rb")]:
    try:
        operation()
        raise AssertionError("Forbidden operation succeeded")
    except (OSError, IOError):
        pass
passed("SFTP rejects writes, delete, mkdir, rename, chmod and escaping the share")
try:
    ssh.exec_command("id", timeout=5)
    raise AssertionError("Shell command accepted")
except paramiko.SSHException:
    passed("SFTP does not expose shell commands")
sftp.close()
ssh.close()
print(f"{len(checks)} real-protocol checks passed", flush=True)
