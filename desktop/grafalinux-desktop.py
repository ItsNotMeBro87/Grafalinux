#!/usr/bin/env python3
"""Grafalinux Desktop Server: start/stop the stats server, set port and interval, manage trusted
devices (one token each), and start it automatically. Python 3.8+, standard library only.

The settings page lives at http://127.0.0.1:8790 (this computer only). A tray icon is shown when the
optional packages `pystray` and `Pillow` are installed; without them the page is simply opened in
your browser. The monitoring itself is done by linmon-agent.py, which has to sit next to this file
(or in ../agent).
"""
import argparse
import hmac
import importlib.util
import json
import os
import secrets
import shlex
import shutil
import signal
import socket
import subprocess
import sys
import threading
import time
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

VERSION = "0.1.0"
HERE = os.path.dirname(os.path.abspath(__file__))
CONF_DIR = os.path.join(os.environ.get("XDG_CONFIG_HOME") or os.path.expanduser("~/.config"), "grafalinux")
CONF_FILE = os.path.join(CONF_DIR, "config.json")
KEY_FILE = os.path.join(CONF_DIR, "admin.key")
UNIT_NAME = "grafalinux.service"
UNIT_FILE = os.path.join(os.path.expanduser("~/.config/systemd/user"), UNIT_NAME)
XDG_FILE = os.path.join(os.environ.get("XDG_CONFIG_HOME") or os.path.expanduser("~/.config"),
                        "autostart", "grafalinux.desktop")
INTERVALS = [0.5, 1.0, 2.0, 5.0]


# --------------------------------------------------------------------------
# the agent (does the actual monitoring)
# --------------------------------------------------------------------------
def load_agent():
    for p in (os.path.join(HERE, "linmon-agent.py"), os.path.join(HERE, "..", "agent", "linmon-agent.py")):
        if os.path.isfile(p):
            spec = importlib.util.spec_from_file_location("linmon_agent", p)
            mod = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(mod)
            return mod
    sys.exit("linmon-agent.py not found next to grafalinux-desktop.py")


# --------------------------------------------------------------------------
# config (contains the tokens, so it is private to your user)
# --------------------------------------------------------------------------
class Config:
    DEFAULTS = {"enabled": True, "port": 8787, "interval": 1.0, "discovery": True,
                "admin_port": 8790, "devices": []}

    def __init__(self):
        self.lock = threading.RLock()
        self.data = json.loads(json.dumps(self.DEFAULTS))
        try:
            with open(CONF_FILE) as f:
                self.data.update(json.load(f))
        except (OSError, ValueError):
            pass

    def save(self):
        with self.lock:
            os.makedirs(CONF_DIR, mode=0o700, exist_ok=True)
            tmp = CONF_FILE + ".tmp"
            fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
            with os.fdopen(fd, "w") as f:
                json.dump(self.data, f, indent=1)
            os.replace(tmp, CONF_FILE)

    def add_device(self, name):
        d = {"id": secrets.token_hex(4), "name": name[:40], "token": secrets.token_urlsafe(18),
             "created": int(time.time()), "last_seen": 0, "last_ip": ""}
        with self.lock:
            self.data["devices"].append(d)
            self.save()
        return d


def admin_key():
    try:
        with open(KEY_FILE) as f:
            k = f.read().strip()
            if k:
                return k
    except OSError:
        pass
    os.makedirs(CONF_DIR, mode=0o700, exist_ok=True)
    k = secrets.token_urlsafe(24)
    fd = os.open(KEY_FILE, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as f:
        f.write(k)
    return k


# --------------------------------------------------------------------------
# the server (start / stop the agent in this process)
# --------------------------------------------------------------------------
class Server:
    def __init__(self, agent, cfg):
        self.agent = agent
        self.cfg = cfg
        self.lock = threading.RLock()
        self.running = False
        self.error = ""
        self.sampler = None
        self.httpd = None
        self.disc_stop = None
        self.seen = {}      # device id -> (time, ip), live only

    def start(self):
        with self.lock:
            if self.running:
                return
            self.error = ""
            c = self.cfg.data
            sampler = self.agent.Sampler(max(0.2, float(c["interval"])))
            sampler.info = sampler.build_info()
            try:
                httpd = self.agent.ThreadingHTTPServer(("0.0.0.0", int(c["port"])),
                                                       self.agent.make_handler(sampler, "", False, auth=self.auth))
            except OSError as e:
                self.error = "Cannot use port %s: %s" % (c["port"], e.strerror or e)
                raise
            sampler.start()
            threading.Thread(target=httpd.serve_forever, daemon=True, name="stats-http").start()
            self.disc_stop = threading.Event()
            if c.get("discovery", True):
                threading.Thread(target=self.agent.discovery_loop, daemon=True, name="discovery",
                                 args=(int(c["port"]), self.agent.DISCOVERY_PORT, self.disc_stop)).start()
            self.sampler, self.httpd = sampler, httpd
            self.running = True

    def stop(self):
        with self.lock:
            if not self.running:
                return
            self.running = False
            try:
                self.httpd.shutdown()
                self.httpd.server_close()
            except Exception:
                pass
            self.sampler.stop()
            self.disc_stop.set()
            self.cfg.save()

    def restart(self):
        self.stop()
        time.sleep(0.2)
        self.start()

    def auth(self, supplied, ip):
        if not self.running or not supplied:
            return False
        sb = supplied.encode()
        found = None
        for d in list(self.cfg.data["devices"]):       # compare with all, no early exit
            if hmac.compare_digest(sb, d["token"].encode()):
                found = d
        if found is None:
            return False
        now = time.time()
        self.seen[found["id"]] = (now, ip)
        if now - found.get("last_seen", 0) > 60:
            found["last_seen"], found["last_ip"] = int(now), ip
            try:
                self.cfg.save()
            except OSError:
                pass
        return True


# --------------------------------------------------------------------------
# start automatically (systemd user service, or an XDG autostart entry)
# --------------------------------------------------------------------------
def _run(cmd):
    try:
        return subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, universal_newlines=True,
                              timeout=15)
    except Exception as e:
        class R:
            returncode, stdout, stderr = 1, "", str(e)
        return R()


def have_systemd():
    return bool(shutil.which("systemctl")) and _run(["systemctl", "--user", "show-environment"]).returncode == 0


def exec_line():
    return "%s %s --background" % (shlex.quote(sys.executable), shlex.quote(os.path.abspath(__file__)))


def linger_on():
    return os.path.exists("/var/lib/systemd/linger/" + (os.environ.get("USER") or ""))


def autostart_state():
    sd = have_systemd()
    if sd:
        enabled = _run(["systemctl", "--user", "is-enabled", UNIT_NAME]).stdout.strip() == "enabled"
    else:
        enabled = os.path.exists(XDG_FILE)
    return {"method": "systemd" if sd else "xdg", "enabled": enabled, "boot": sd and linger_on(),
            "boot_possible": sd}


def set_autostart(enable, boot):
    """Returns an error message, or "" when everything worked."""
    errors = []
    if have_systemd():
        if enable:
            os.makedirs(os.path.dirname(UNIT_FILE), exist_ok=True)
            with open(UNIT_FILE, "w") as f:
                f.write("[Unit]\nDescription=Grafalinux Desktop Server (system stats for the phone app)\n"
                        "After=network-online.target\nWants=network-online.target\n\n[Service]\n"
                        "Environment=PYTHONUNBUFFERED=1\nExecStart=%s\nRestart=on-failure\nRestartSec=3\n\n"
                        "[Install]\nWantedBy=default.target\n" % exec_line())
            _run(["systemctl", "--user", "daemon-reload"])
            r = _run(["systemctl", "--user", "enable", UNIT_NAME])
            if r.returncode:
                errors.append(r.stderr.strip() or "systemctl enable failed")
        else:
            r = _run(["systemctl", "--user", "disable", UNIT_NAME])
            if r.returncode:
                errors.append(r.stderr.strip() or "systemctl disable failed")
        want_boot = enable and boot
        if want_boot != linger_on():
            r = _run(["loginctl", "enable-linger" if want_boot else "disable-linger"])
            if r.returncode:
                errors.append("Start-before-login: " + (r.stderr.strip() or "loginctl failed"))
    else:
        if enable:
            os.makedirs(os.path.dirname(XDG_FILE), exist_ok=True)
            with open(XDG_FILE, "w") as f:
                f.write("[Desktop Entry]\nType=Application\nName=Grafalinux Desktop Server\n"
                        "Exec=%s\nX-GNOME-Autostart-enabled=true\n" % exec_line())
        else:
            try:
                os.remove(XDG_FILE)
            except OSError:
                pass
    return "; ".join(errors)


# --------------------------------------------------------------------------
# settings page (127.0.0.1 only, needs the admin key)
# --------------------------------------------------------------------------
def local_ips():
    ips = []
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("10.255.255.255", 1))
        ips.append(s.getsockname()[0])
        s.close()
    except OSError:
        pass
    try:
        for ai in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = ai[4][0]
            if ip not in ips and not ip.startswith("127."):
                ips.append(ip)
    except OSError:
        pass
    return ips


class Manager:
    def __init__(self, agent, cfg):
        self.agent, self.cfg = agent, cfg
        self.server = Server(agent, cfg)
        self.key = admin_key()
        self.quit_ev = threading.Event()
        self.tray = None

    def url(self):
        return "http://127.0.0.1:%d/?key=%s" % (self.cfg.data["admin_port"], self.key)

    def state(self):
        c = self.cfg.data
        now = time.time()
        devs = []
        for d in c["devices"]:
            seen = self.server.seen.get(d["id"])
            ts, ip = (seen if seen else (d.get("last_seen", 0), d.get("last_ip", "")))
            devs.append({"id": d["id"], "name": d["name"], "token": d["token"],
                         "ago": int(now - ts) if ts else None, "ip": ip})
        return {"version": VERSION, "running": self.server.running, "error": self.server.error,
                "port": c["port"], "interval": c["interval"], "discovery": c["discovery"],
                "intervals": INTERVALS, "ips": local_ips(), "hostname": socket.gethostname(),
                "devices": devs, "autostart": autostart_state(), "discovery_port": self.agent.DISCOVERY_PORT}

    def act(self, path, body):
        c = self.cfg.data
        if path == "/api/server":
            a = body.get("action")
            try:
                if a == "start":
                    c["enabled"] = True
                    self.cfg.save()
                    self.server.start()
                elif a == "stop":
                    c["enabled"] = False
                    self.cfg.save()
                    self.server.stop()
                elif a == "restart":
                    self.server.restart()
            except OSError:
                pass
            return {}
        if path == "/api/config":
            try:
                port = int(body.get("port"))
                interval = float(body.get("interval"))
            except (TypeError, ValueError):
                return {"error": "Port and interval must be numbers."}
            if not 1024 <= port <= 65535:
                return {"error": "Port must be between 1024 and 65535."}
            if port in (c["admin_port"], self.agent.DISCOVERY_PORT):
                return {"error": "That port is used by Grafalinux itself."}
            if not 0.2 <= interval <= 60:
                return {"error": "Interval must be between 0.2 and 60 seconds."}
            c["port"], c["interval"], c["discovery"] = port, interval, bool(body.get("discovery"))
            self.cfg.save()
            if self.server.running:
                try:
                    self.server.restart()
                except OSError:
                    pass
            return {}
        if path == "/api/device":
            a = body.get("action")
            if a == "add":
                name = str(body.get("name", "")).strip()
                if not name:
                    return {"error": "Give the device a name."}
                self.cfg.add_device(name)
            elif a in ("remove", "regen"):
                for d in list(c["devices"]):
                    if d["id"] == body.get("id"):
                        if a == "remove":
                            c["devices"].remove(d)
                        else:
                            d["token"] = secrets.token_urlsafe(18)
                self.cfg.save()
            return {}
        if path == "/api/autostart":
            err = set_autostart(bool(body.get("enable")), bool(body.get("boot")))
            return {"error": err} if err else {}
        if path == "/api/quit":
            self.quit_ev.set()
            return {}
        return {"error": "unknown action"}


def make_admin_handler(mgr):
    port = mgr.cfg.data["admin_port"]
    hosts = ("127.0.0.1:%d" % port, "localhost:%d" % port)

    class H(BaseHTTPRequestHandler):
        server_version = "grafalinux-desktop/" + VERSION

        def log_message(self, *a):
            pass

        def _send(self, code, body, ctype="application/json", extra=None):
            if isinstance(body, str):
                body = body.encode()
            self.send_response(code)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            for k, v in (extra or {}).items():
                self.send_header(k, v)
            self.end_headers()
            self.wfile.write(body)

        def _cookie_ok(self):
            for part in self.headers.get("Cookie", "").split(";"):
                k, _, v = part.strip().partition("=")
                if k == "gk" and hmac.compare_digest(v.encode(), mgr.key.encode()):
                    return True
            return False

        def _safe(self):
            if self.headers.get("Host", "") not in hosts:       # blocks DNS-rebinding tricks
                self._send(403, "bad host", "text/plain")
                return False
            return True

        def do_GET(self):
            if not self._safe():
                return
            u = urlparse(self.path)
            q = parse_qs(u.query)
            if "key" in q and hmac.compare_digest(q["key"][0].encode(), mgr.key.encode()):
                return self._send(302, "", "text/plain", {
                    "Location": "/", "Set-Cookie": "gk=%s; Path=/; HttpOnly; SameSite=Strict" % mgr.key})
            if not self._cookie_ok():
                return self._send(403, "Open the settings page with the tray icon, or run: "
                                       "grafalinux-desktop --open", "text/plain")
            if u.path == "/":
                return self._send(200, PAGE, "text/html; charset=utf-8")
            if u.path == "/api/state":
                return self._send(200, json.dumps(mgr.state()))
            self._send(404, "{}")

        def do_POST(self):
            if not self._safe() or not self._cookie_ok():
                return self._send(403, "{}")
            origin = self.headers.get("Origin")
            if origin and origin.replace("http://", "") not in hosts:
                return self._send(403, "{}")
            if not self.headers.get("Content-Type", "").startswith("application/json"):
                return self._send(415, "{}")
            try:
                n = min(int(self.headers.get("Content-Length", "0")), 4096)
                body = json.loads(self.rfile.read(n) or b"{}")
                if not isinstance(body, dict):
                    raise ValueError
            except ValueError:
                return self._send(400, "{}")
            self._send(200, json.dumps(mgr.act(urlparse(self.path).path, body)))

    return H


PAGE = r"""<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Grafalinux Desktop Server</title>
<style>
:root{color-scheme:dark}
body{margin:0;background:#111214;color:#d8d9da;font:15px system-ui,sans-serif}
main{max-width:760px;margin:0 auto;padding:18px}
h1{font-size:20px;margin:6px 0 14px}h2{font-size:14px;margin:0 0 10px;color:#9aa;text-transform:uppercase;letter-spacing:.05em}
.card{background:#181b1f;border:1px solid #2c3037;border-radius:4px;padding:14px;margin-bottom:12px}
button{background:#2a2f36;color:#d8d9da;border:1px solid #3a404a;border-radius:3px;padding:6px 12px;font:inherit;cursor:pointer}
button:hover{background:#343a43}button.pri{background:#2d6a3e;border-color:#3b8a52}button.bad{color:#ff7b7b}
input,select{background:#0e1012;color:#d8d9da;border:1px solid #3a404a;border-radius:3px;padding:6px;font:inherit}
input[type=number]{width:90px}.row{display:flex;gap:10px;align-items:center;flex-wrap:wrap;margin:6px 0}
.pill{display:inline-block;padding:2px 10px;border-radius:10px;font-size:13px}.on{background:#2d6a3e}.off{background:#5a2a2a}
table{width:100%;border-collapse:collapse}td{padding:6px 4px;border-top:1px solid #2c3037;vertical-align:middle}
.mono{font-family:ui-monospace,monospace;font-size:13px}.dim{color:#8e8e99;font-size:13px}.err{color:#ff7b7b}
</style></head><body><main>
<h1>Grafalinux Desktop Server <span class="dim" id="ver"></span></h1>
<div class="card"><h2>Server</h2>
 <div class="row"><span class="pill" id="pill"></span><button id="toggle"></button><span class="err" id="srvErr"></span></div>
 <div class="dim" id="conn"></div></div>
<div class="card"><h2>Settings</h2>
 <div class="row"><label>Port <input type="number" id="port" min="1024" max="65535"></label>
 <label>Update every <select id="interval"></select></label></div>
 <div class="row"><label><input type="checkbox" id="disc"> Let the phone find this PC automatically (UDP <span id="dport"></span>)</label></div>
 <div class="row"><button class="pri" id="save">Save</button><span class="dim">Saving restarts the server.</span><span class="err" id="cfgErr"></span></div>
 <div class="dim">A shorter interval means fresher numbers but a little more CPU here and more Wi-Fi traffic and battery use on the phone. 1 second is a good default.</div></div>
<div class="card"><h2>Trusted devices</h2>
 <div class="dim">Each device has its own token. Type it into the phone app. Removing a device cuts it off immediately.</div>
 <table id="devs"></table>
 <div class="row"><input id="newName" placeholder="Device name, e.g. My phone" maxlength="40"><button class="pri" id="add">Add device</button><span class="err" id="devErr"></span></div></div>
<div class="card"><h2>Startup</h2>
 <div class="row"><label><input type="checkbox" id="auto"> Start automatically when I log in</label></div>
 <div class="row"><label><input type="checkbox" id="boot"> Also start at boot, before I log in (needs systemd)</label></div>
 <div class="row"><button id="applyAuto">Apply</button><span class="dim" id="autoInfo"></span><span class="err" id="autoErr"></span></div></div>
<div class="card"><div class="row"><button class="bad" id="quit">Quit Grafalinux Desktop Server</button>
<span class="dim">The firewall must allow the port above (TCP) and UDP 8788 for discovery.</span></div></div>
</main><script>
var S=null;
function $(i){return document.getElementById(i)}
function post(p,b){return fetch(p,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(b||{})}).then(function(r){return r.json()})}
function ago(s){if(s===null)return'never connected';if(s<10)return'just now';if(s<90)return s+' s ago';if(s<5400)return Math.round(s/60)+' min ago';if(s<172800)return Math.round(s/3600)+' h ago';return Math.round(s/86400)+' days ago'}
function el(t,txt,cls){var e=document.createElement(t);if(txt!==undefined)e.textContent=txt;if(cls)e.className=cls;return e}
function render(s,full){
 S=s;$('ver').textContent='v'+s.version;
 var p=$('pill');p.textContent=s.running?'Running':'Stopped';p.className='pill '+(s.running?'on':'off');
 $('toggle').textContent=s.running?'Stop server':'Start server';$('srvErr').textContent=s.error;
 $('conn').textContent=s.running?('Connect the phone to: '+s.ips.map(function(i){return i+':'+s.port}).join('  or  ')+'   ('+s.hostname+')'):'';
 $('dport').textContent=s.discovery_port;
 var t=$('devs');t.innerHTML='';
 s.devices.forEach(function(d){var r=el('tr'),a=el('td',d.name),b=el('td'),c=el('td',ago(d.ago)+(d.ip?' ('+d.ip+')':''),'dim'),x=el('td');
  var tok=el('span','\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022','mono'),shown=false;
  var sh=el('button','Show');sh.onclick=function(){shown=!shown;tok.textContent=shown?d.token:'\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022';sh.textContent=shown?'Hide':'Show'};
  var cp=el('button','Copy');cp.onclick=function(){navigator.clipboard.writeText(d.token);cp.textContent='Copied';setTimeout(function(){cp.textContent='Copy'},1200)};
  b.appendChild(tok);b.appendChild(document.createTextNode(' '));b.appendChild(sh);b.appendChild(document.createTextNode(' '));b.appendChild(cp);
  var rg=el('button','New token');rg.onclick=function(){if(confirm('Create a new token for "'+d.name+'"? The old one stops working.'))post('/api/device',{action:'regen',id:d.id}).then(load)};
  var rm=el('button','Remove','bad');rm.onclick=function(){if(confirm('Remove "'+d.name+'"?'))post('/api/device',{action:'remove',id:d.id}).then(load)};
  x.appendChild(rg);x.appendChild(document.createTextNode(' '));x.appendChild(rm);
  r.appendChild(a);r.appendChild(b);r.appendChild(c);r.appendChild(x);t.appendChild(r)});
 if(!s.devices.length){var r=el('tr'),c=el('td','No devices: nobody can connect. Add one below.','err');r.appendChild(c);t.appendChild(r)}
 if(full){$('port').value=s.port;$('disc').checked=s.discovery;var iv=$('interval');iv.innerHTML='';
  s.intervals.forEach(function(v){var o=el('option',v+' s');o.value=v;iv.appendChild(o)});
  if(s.intervals.indexOf(s.interval)<0){var o=el('option',s.interval+' s');o.value=s.interval;iv.appendChild(o)}
  iv.value=s.interval;
  $('auto').checked=s.autostart.enabled;$('boot').checked=s.autostart.boot;$('boot').disabled=!s.autostart.boot_possible;
  $('autoInfo').textContent=s.autostart.method==='systemd'?'Uses a systemd user service.':'Uses an autostart entry.'}}
function load(full){return fetch('/api/state').then(function(r){return r.json()}).then(function(s){render(s,full!==false&&!S||full===true)})}
$('toggle').onclick=function(){post('/api/server',{action:S.running?'stop':'start'}).then(function(){load()})};
$('save').onclick=function(){post('/api/config',{port:$('port').value,interval:$('interval').value,discovery:$('disc').checked}).then(function(r){$('cfgErr').textContent=r.error||'';load(true)})};
$('add').onclick=function(){post('/api/device',{action:'add',name:$('newName').value}).then(function(r){$('devErr').textContent=r.error||'';if(!r.error)$('newName').value='';load()})};
$('applyAuto').onclick=function(){post('/api/autostart',{enable:$('auto').checked,boot:$('boot').checked}).then(function(r){$('autoErr').textContent=r.error||'';load(true)})};
$('quit').onclick=function(){if(confirm('Quit? The phone will stop receiving data.'))post('/api/quit').then(function(){document.body.textContent='Stopped. You can close this tab.'})};
load(true);setInterval(function(){load(false)},3000);
</script></body></html>"""


# --------------------------------------------------------------------------
# tray icon (optional)
# --------------------------------------------------------------------------
def run_tray(mgr):
    """Shows a tray icon if pystray + Pillow work here. Returns False when not possible."""
    try:
        import pystray
        from PIL import Image, ImageDraw
        img = Image.new("RGBA", (64, 64), (24, 27, 31, 255))
        d = ImageDraw.Draw(img)
        for i, h in enumerate((22, 40, 30, 50)):
            d.rectangle([8 + i * 13, 58 - h, 17 + i * 13, 58], fill=(115, 191, 105, 255))

        def toggle(icon, item):
            try:
                if mgr.server.running:
                    mgr.cfg.data["enabled"] = False
                    mgr.cfg.save()
                    mgr.server.stop()
                else:
                    mgr.cfg.data["enabled"] = True
                    mgr.cfg.save()
                    mgr.server.start()
            except OSError:
                pass

        menu = pystray.Menu(
            pystray.MenuItem("Open settings", lambda i, m: webbrowser.open(mgr.url()), default=True),
            pystray.MenuItem(lambda item: "Stop server" if mgr.server.running else "Start server", toggle),
            pystray.MenuItem("Quit", lambda i, m: mgr.quit_ev.set()))
        icon = pystray.Icon("grafalinux", img, "Grafalinux Desktop Server", menu)
        mgr.tray = icon
        threading.Thread(target=lambda: (mgr.quit_ev.wait(), icon.stop()), daemon=True).start()
        icon.run()
        return True
    except Exception as e:
        print("No tray icon (%s). The settings page still works." % (e,), flush=True)
        return False


# --------------------------------------------------------------------------
def main():
    ap = argparse.ArgumentParser(description="Grafalinux Desktop Server")
    ap.add_argument("--background", action="store_true", help="no tray icon, do not open the browser")
    ap.add_argument("--open", action="store_true", help="open the settings page of the running instance")
    ap.add_argument("--no-tray", action="store_true")
    ap.add_argument("--admin-port", type=int, help="port of the settings page (default 8790)")
    args = ap.parse_args()

    cfg = Config()
    if args.admin_port:
        cfg.data["admin_port"] = args.admin_port
    mgr_key = admin_key()
    admin_port = cfg.data["admin_port"]
    url = "http://127.0.0.1:%d/?key=%s" % (admin_port, mgr_key)

    # already running? just open the page
    try:
        socket.create_connection(("127.0.0.1", admin_port), timeout=0.5).close()
        print("Grafalinux Desktop Server is already running.")
        if not args.background:
            webbrowser.open(url)
        return
    except OSError:
        if args.open:
            sys.exit("Grafalinux Desktop Server is not running.")

    agent = load_agent()
    mgr = Manager(agent, cfg)
    if not cfg.data["devices"]:
        d = cfg.add_device("My phone")
        print("First start: created device \"My phone\" with token %s" % d["token"], flush=True)
    if cfg.data.get("enabled", True):
        try:
            mgr.server.start()
        except OSError as e:
            print("Server not started: %s" % (mgr.server.error or e), flush=True)

    try:
        admin = ThreadingHTTPServer(("127.0.0.1", admin_port), make_admin_handler(mgr))
    except OSError as e:
        sys.exit("Cannot open the settings page on port %d: %s" % (admin_port, e))
    admin.daemon_threads = True
    threading.Thread(target=admin.serve_forever, daemon=True, name="admin").start()
    print("Grafalinux Desktop Server %s. Settings: %s" % (VERSION, url), flush=True)

    for sig in (signal.SIGINT, signal.SIGTERM):
        signal.signal(sig, lambda *a: mgr.quit_ev.set())

    tray_ok = False
    if not args.background and not args.no_tray:
        tray_ok = run_tray(mgr)
    if not args.background and not tray_ok:
        webbrowser.open(url)
    mgr.quit_ev.wait()
    mgr.server.stop()
    admin.shutdown()


if __name__ == "__main__":
    main()
