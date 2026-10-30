#!/usr/bin/env python3
"""linmon-agent: lightweight Linux system monitor agent.

Samples CPU, memory, GPU, disks, network and every hwmon sensor it can find,
and serves the latest snapshot as JSON over HTTP for a phone/app to display.

Only the Python standard library is used (Python 3.8+).

Endpoints
  GET /api/v1/info    static host info (hostname, OS, CPU model, GPUs...)
  GET /api/v1/stats   latest snapshot (updated every --interval seconds)
  GET /               tiny debug page showing the live JSON

Run `linmon-agent.py --once` to print one snapshot and exit.
"""
import argparse
import csv
import glob
import hmac
import json
import os
import platform
import re
import shutil
import socket
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

VERSION = "0.1.0"
API = 1


# --------------------------------------------------------------------------
# helpers
# --------------------------------------------------------------------------
def read(path, default=None):
    """Read a small text file; return default on any error (I/O errors included)."""
    try:
        with open(path) as f:
            return f.read().strip()
    except (OSError, ValueError):
        return default


def read_int(path):
    try:
        return int(read(path))
    except (TypeError, ValueError):
        return None


def num(s):
    try:
        return float(s)
    except (TypeError, ValueError):
        return None


def numkey(path):
    m = re.findall(r"\d+", os.path.basename(path))
    return int(m[0]) if m else 0


def round_or_none(v, n=1):
    return None if v is None else round(v, n)


class Rates:
    """Turns ever-increasing counters into per-second rates."""

    def __init__(self):
        self.prev = {}

    def rate(self, key, value, now):
        p = self.prev.get(key)
        self.prev[key] = (value, now)
        if p is None or now <= p[1] or value < p[0]:
            return None
        return (value - p[0]) / (now - p[1])


# --------------------------------------------------------------------------
# static host info
# --------------------------------------------------------------------------
def os_pretty():
    try:
        with open("/etc/os-release") as f:
            for line in f:
                if line.startswith("PRETTY_NAME="):
                    return line.split("=", 1)[1].strip().strip('"')
    except OSError:
        pass
    return platform.system()


def cpu_topology():
    """Return (model, physical_cores, threads)."""
    model, cores, threads = None, set(), 0
    try:
        with open("/proc/cpuinfo") as f:
            blocks = f.read().split("\n\n")
    except OSError:
        return None, os.cpu_count(), os.cpu_count()
    for b in blocks:
        fields = {}
        for line in b.splitlines():
            k, _, v = line.partition(":")
            fields[k.strip()] = v.strip()
        if "processor" not in fields:
            continue
        threads += 1
        model = model or fields.get("model name") or fields.get("Hardware")
        if "core id" in fields:
            cores.add((fields.get("physical id", "0"), fields["core id"]))
    return model, (len(cores) or threads), threads


# --------------------------------------------------------------------------
# CPU
# --------------------------------------------------------------------------
class Cpu:
    def __init__(self):
        self.prev = self._read_stat()

    @staticmethod
    def _read_stat():
        out = {}
        try:
            with open("/proc/stat") as f:
                for line in f:
                    if not line.startswith("cpu"):
                        continue
                    parts = line.split()
                    vals = [int(x) for x in parts[1:9]]
                    idle = vals[3] + (vals[4] if len(vals) > 4 else 0)
                    out[parts[0]] = (sum(vals), idle)
        except (OSError, ValueError):
            pass
        return out

    @staticmethod
    def _freq_mhz(i):
        for name in ("scaling_cur_freq", "cpuinfo_cur_freq"):
            v = read_int("/sys/devices/system/cpu/cpu%d/cpufreq/%s" % (i, name))
            if v:
                return round(v / 1000)
        return None

    @staticmethod
    def _cpuinfo_mhz():
        out = []
        try:
            with open("/proc/cpuinfo") as f:
                for line in f:
                    if line.startswith("cpu MHz"):
                        out.append(round(float(line.split(":")[1])))
        except (OSError, ValueError):
            pass
        return out

    def sample(self):
        cur = self._read_stat()
        usage = {}
        for name, (tot, idle) in cur.items():
            p = self.prev.get(name)
            if p and tot > p[0]:
                usage[name] = 100.0 * (1.0 - (idle - p[1]) / (tot - p[0]))
            else:
                usage[name] = None
        self.prev = cur

        idx = sorted(int(k[3:]) for k in cur if k[3:].isdigit())
        fallback = None
        cores = []
        for i in idx:
            f = self._freq_mhz(i)
            if f is None:
                if fallback is None:
                    fallback = self._cpuinfo_mhz()
                if i < len(fallback):
                    f = fallback[i]
            cores.append({"usage_pct": round_or_none(usage.get("cpu%d" % i)),
                          "freq_mhz": f})
        freqs = [c["freq_mhz"] for c in cores if c["freq_mhz"]]
        return {
            "usage_pct": round_or_none(usage.get("cpu")),
            "freq_avg_mhz": round(sum(freqs) / len(freqs)) if freqs else None,
            "freq_max_mhz": max(freqs) if freqs else None,
            "cores": cores,
        }


# --------------------------------------------------------------------------
# memory / uptime
# --------------------------------------------------------------------------
def memory():
    m = {}
    try:
        with open("/proc/meminfo") as f:
            for line in f:
                k, _, v = line.partition(":")
                m[k] = int(v.split()[0]) * 1024
    except (OSError, ValueError, IndexError):
        return {}
    total = m.get("MemTotal", 0)
    avail = m.get("MemAvailable", m.get("MemFree", 0) + m.get("Cached", 0))
    used = total - avail
    st, sf = m.get("SwapTotal", 0), m.get("SwapFree", 0)
    return {
        "total_b": total,
        "used_b": used,
        "available_b": avail,
        "used_pct": round(100.0 * used / total, 1) if total else None,
        "cached_b": m.get("Cached", 0) + m.get("Buffers", 0),
        "swap_total_b": st,
        "swap_used_b": st - sf,
    }


def uptime_s():
    try:
        return float(read("/proc/uptime").split()[0])
    except (AttributeError, ValueError, IndexError):
        return None


# --------------------------------------------------------------------------
# hwmon (temperatures, fans, PWM, voltages, power) -- fully auto-discovered
# --------------------------------------------------------------------------
KIND_BY_CHIP = {
    "k10temp": "cpu", "coretemp": "cpu", "zenpower": "cpu", "cpu_thermal": "cpu",
    "amdgpu": "gpu", "nouveau": "gpu", "radeon": "gpu", "i915": "gpu", "xe": "gpu",
    "nvme": "storage", "drivetemp": "storage",
    "acpitz": "acpi",
    "iwlwifi": "network", "iwlwifi_1": "network", "r8169": "network",
}
CPU_TEMP_PREFERENCE = ("Tctl", "Tdie", "Package id 0", "Tccd1")


def clean_temp(milli):
    if milli is None:
        return None
    c = milli / 1000.0
    if c <= -100 or c >= 125:  # sentinel / bogus values (e.g. 127 or -128)
        return None
    return round(c, 1)


class Hwmon:
    def __init__(self):
        self.chips = self._discover()

    @staticmethod
    def _discover():
        chips, seen = [], {}
        paths = sorted(glob.glob("/sys/class/hwmon/hwmon*"), key=numkey)
        for d in paths:
            name = read(os.path.join(d, "name"), os.path.basename(d))
            seen[name] = seen.get(name, 0) + 1
            cid = name if seen[name] == 1 else "%s#%d" % (name, seen[name])
            model = read(os.path.join(d, "device", "model"))  # e.g. NVMe model
            if name in ("nvme", "drivetemp") and model:
                label = "%s (%s)" % (name, model)
            else:
                label = name
            kind = KIND_BY_CHIP.get(name, "board")
            chips.append({"id": cid, "name": name, "label": label,
                          "kind": kind, "path": d})
        return chips

    def sample(self):
        out = []
        for chip in self.chips:
            d = chip["path"]
            temps, fans, pwms, volts, powers = [], [], [], [], []

            for p in sorted(glob.glob(d + "/temp*_input"), key=numkey):
                n = numkey(p)
                v = clean_temp(read_int(p))
                if v is None:
                    continue
                temps.append({"key": "temp%d" % n,
                              "label": read("%s/temp%d_label" % (d, n)) or "temp%d" % n,
                              "c": v})

            for p in sorted(glob.glob(d + "/fan*_input"), key=numkey):
                n = numkey(p)
                v = read_int(p)
                if v is None:
                    continue
                fans.append({"key": "fan%d" % n,
                             "label": read("%s/fan%d_label" % (d, n)) or "fan%d" % n,
                             "rpm": v})

            for p in sorted(glob.glob(d + "/pwm[0-9]"), key=numkey):
                n = numkey(p)
                v = read_int(p)
                if v is None:
                    continue
                pwms.append({"key": "pwm%d" % n,
                             "pct": round(v * 100.0 / 255.0),
                             "manual": read("%s/pwm%d_enable" % (d, n)) == "1"})

            for p in sorted(glob.glob(d + "/in[0-9]*_input"), key=numkey):
                n = numkey(p)
                v = read_int(p)
                if v is None:
                    continue
                volts.append({"key": "in%d" % n,
                              "label": read("%s/in%d_label" % (d, n)) or "in%d" % n,
                              "v": round(v / 1000.0, 3)})

            for p in sorted(glob.glob(d + "/power[0-9]*_average") +
                            glob.glob(d + "/power[0-9]*_input"), key=numkey):
                n = numkey(p)
                v = read_int(p)
                if v is None:
                    continue
                powers.append({"key": "power%d" % n,
                               "label": read("%s/power%d_label" % (d, n)) or "power%d" % n,
                               "w": round(v / 1e6, 2)})

            if not (temps or fans or pwms or volts or powers):
                continue
            entry = {"id": chip["id"], "name": chip["name"],
                     "label": chip["label"], "kind": chip["kind"]}
            for k, v in (("temps", temps), ("fans", fans), ("pwms", pwms),
                         ("volts", volts), ("powers", powers)):
                if v:
                    entry[k] = v
            out.append(entry)
        return out


def pick_cpu_temp(sensors):
    for chip in sensors:
        if chip["kind"] != "cpu" or "temps" not in chip:
            continue
        for want in CPU_TEMP_PREFERENCE:
            for t in chip["temps"]:
                if t["label"] == want:
                    return t["c"]
        return chip["temps"][0]["c"]
    return None


# --------------------------------------------------------------------------
# GPUs
# --------------------------------------------------------------------------
NV_QUERY = ["index", "name", "temperature.gpu", "utilization.gpu",
            "utilization.memory", "memory.used", "memory.total", "fan.speed",
            "power.draw", "power.limit", "clocks.current.graphics",
            "clocks.current.memory"]


def nvidia_gpus():
    exe = shutil.which("nvidia-smi")
    if not exe:
        return []
    try:
        res = subprocess.run(
            [exe, "--query-gpu=" + ",".join(NV_QUERY),
             "--format=csv,noheader,nounits"],
            capture_output=True, text=True, timeout=3)
    except (OSError, subprocess.TimeoutExpired):
        return []
    if res.returncode != 0:
        return []
    gpus = []
    for row in csv.reader(res.stdout.splitlines(), skipinitialspace=True):
        if len(row) < len(NV_QUERY):
            continue
        r = dict(zip(NV_QUERY, [c.strip() for c in row]))
        used, total = num(r["memory.used"]), num(r["memory.total"])
        gpus.append({
            "id": "nvidia:" + r["index"],
            "vendor": "nvidia",
            "name": r["name"],
            "temp_c": num(r["temperature.gpu"]),
            "util_pct": num(r["utilization.gpu"]),
            "mem_ctrl_util_pct": num(r["utilization.memory"]),
            "mem_used_b": None if used is None else int(used * 1048576),
            "mem_total_b": None if total is None else int(total * 1048576),
            "mem_used_pct": round(100.0 * used / total, 1) if used is not None and total else None,
            "fan_pct": num(r["fan.speed"]),
            "fan_rpm": None,
            "power_w": num(r["power.draw"]),
            "power_limit_w": num(r["power.limit"]),
            "clock_core_mhz": num(r["clocks.current.graphics"]),
            "clock_mem_mhz": num(r["clocks.current.memory"]),
        })
    return gpus


def amd_gpus():
    gpus = []
    for dev in sorted(glob.glob("/sys/class/drm/card[0-9]*/device"), key=lambda p: numkey(p.split("/")[-2])):
        if read(dev + "/vendor") != "0x1002" or not os.path.exists(dev + "/gpu_busy_percent"):
            continue
        card = dev.split("/")[-2]
        hw = sorted(glob.glob(dev + "/hwmon/hwmon*"))
        h = hw[0] if hw else None
        used = read_int(dev + "/mem_info_vram_used")
        total = read_int(dev + "/mem_info_vram_total")
        temp = clean_temp(read_int(h + "/temp1_input")) if h else None
        pwm = read_int(h + "/pwm1") if h else None
        power = read_int(h + "/power1_average") if h else None
        if power is None and h:
            power = read_int(h + "/power1_input")
        cap = read_int(h + "/power1_cap") if h else None
        sclk = read_int(h + "/freq1_input") if h else None
        mclk = read_int(h + "/freq2_input") if h else None
        gpus.append({
            "id": "amd:" + card,
            "vendor": "amd",
            "name": read(dev + "/product_name") or "AMD GPU (%s)" % card,
            "temp_c": temp,
            "util_pct": read_int(dev + "/gpu_busy_percent"),
            "mem_ctrl_util_pct": read_int(dev + "/mem_busy_percent"),
            "mem_used_b": used,
            "mem_total_b": total,
            "mem_used_pct": round(100.0 * used / total, 1) if used is not None and total else None,
            "fan_pct": round(pwm * 100.0 / 255.0) if pwm is not None else None,
            "fan_rpm": read_int(h + "/fan1_input") if h else None,
            "power_w": round_or_none(power / 1e6 if power is not None else None, 1),
            "power_limit_w": round_or_none(cap / 1e6 if cap is not None else None, 1),
            "clock_core_mhz": round(sclk / 1e6) if sclk else None,
            "clock_mem_mhz": round(mclk / 1e6) if mclk else None,
        })
    return gpus


# --------------------------------------------------------------------------
# disks and network
# --------------------------------------------------------------------------
FS_TYPES = {"ext2", "ext3", "ext4", "btrfs", "xfs", "f2fs", "vfat", "exfat",
            "ntfs", "ntfs3", "fuseblk", "zfs", "jfs", "reiserfs"}
SKIP_MOUNT_PREFIXES = ("/run", "/snap", "/var/lib/docker", "/var/lib/containers",
                       "/var/lib/flatpak")
PHYS_DISK = re.compile(r"^(nvme\d+n\d+|sd[a-z]+|vd[a-z]+|xvd[a-z]+|mmcblk\d+)$")
VIRTUAL_NET_PREFIXES = ("lo", "veth", "docker", "br-", "virbr", "vboxnet")


def _unescape_mount(s):
    return re.sub(r"\\([0-7]{3})", lambda m: chr(int(m.group(1), 8)), s)


def filesystems():
    best = {}
    try:
        with open("/proc/mounts") as f:
            lines = f.read().splitlines()
    except OSError:
        return []
    for line in lines:
        p = line.split()
        if len(p) < 3 or p[2] not in FS_TYPES:
            continue
        dev, mnt = p[0], _unescape_mount(p[1])
        if mnt.startswith(SKIP_MOUNT_PREFIXES):
            continue
        # btrfs subvolumes mount the same device many times: keep shortest path
        if dev not in best or len(mnt) < len(best[dev][0]):
            best[dev] = (mnt, p[2])
    out = []
    for dev, (mnt, fstype) in sorted(best.items(), key=lambda kv: kv[1][0]):
        try:
            st = os.statvfs(mnt)
        except OSError:
            continue
        total = st.f_blocks * st.f_frsize
        free = st.f_bavail * st.f_frsize
        used = total - st.f_bfree * st.f_frsize
        if total == 0:
            continue
        out.append({"device": dev, "mount": mnt, "fstype": fstype,
                    "total_b": total, "used_b": used,
                    "used_pct": round(100.0 * used / (used + free), 1) if used + free else None})
    return out


def disk_io(rates, now):
    out = []
    try:
        with open("/proc/diskstats") as f:
            for line in f:
                p = line.split()
                if len(p) < 10 or not PHYS_DISK.match(p[2]):
                    continue
                rd = rates.rate("dr:" + p[2], int(p[5]) * 512, now)
                wr = rates.rate("dw:" + p[2], int(p[9]) * 512, now)
                out.append({"device": p[2],
                            "read_bps": round_or_none(rd, 0),
                            "write_bps": round_or_none(wr, 0)})
    except (OSError, ValueError):
        pass
    return out


def network(rates, now):
    out = []
    try:
        with open("/proc/net/dev") as f:
            lines = f.read().splitlines()[2:]
    except OSError:
        return out
    for line in lines:
        name, _, rest = line.partition(":")
        name = name.strip()
        if name.startswith(VIRTUAL_NET_PREFIXES):
            continue
        if read("/sys/class/net/%s/operstate" % name) not in ("up", "unknown"):
            continue
        p = rest.split()
        try:
            rx, tx = int(p[0]), int(p[8])
        except (IndexError, ValueError):
            continue
        out.append({"iface": name,
                    "rx_bps": round_or_none(rates.rate("rx:" + name, rx, now), 0),
                    "tx_bps": round_or_none(rates.rate("tx:" + name, tx, now), 0),
                    "rx_total_b": rx, "tx_total_b": tx})
    return out


# --------------------------------------------------------------------------
# sampler thread
# --------------------------------------------------------------------------
class Sampler(threading.Thread):
    def __init__(self, interval):
        super().__init__(daemon=True)
        self.interval = interval
        self.cpu = Cpu()
        self.hwmon = Hwmon()
        self.rates = Rates()
        self.seq = 0
        self.lock = threading.Lock()
        self.latest = None
        self.info = None

    def collect(self):
        now = time.time()
        self.seq += 1
        sensors = self.hwmon.sample()
        cpu = self.cpu.sample()
        cpu["temp_c"] = pick_cpu_temp(sensors)
        try:
            load = list(os.getloadavg())
        except OSError:
            load = None
        return {
            "api": API,
            "seq": self.seq,
            "ts": round(now, 3),
            "interval_s": self.interval,
            "uptime_s": uptime_s(),
            "load": load,
            "cpu": cpu,
            "memory": memory(),
            "gpus": nvidia_gpus() + amd_gpus(),
            "sensors": sensors,
            "storage": {"filesystems": filesystems(),
                        "io": disk_io(self.rates, now)},
            "network": network(self.rates, now),
        }

    def build_info(self):
        model, cores, threads = cpu_topology()
        gpus = nvidia_gpus() + amd_gpus()
        return {
            "api": API,
            "agent": "linmon-agent",
            "version": VERSION,
            "hostname": socket.gethostname(),
            "os": os_pretty(),
            "kernel": platform.release(),
            "arch": platform.machine(),
            "cpu_model": model,
            "cpu_cores": cores,
            "cpu_threads": threads,
            "gpus": [{"id": g["id"], "vendor": g["vendor"], "name": g["name"]} for g in gpus],
            "sensor_chips": [{"id": c["id"], "label": c["label"], "kind": c["kind"]}
                             for c in self.hwmon.chips],
            "interval_s": self.interval,
        }

    def run(self):
        nxt = time.monotonic()
        while True:
            try:
                snap = self.collect()
                with self.lock:
                    self.latest = snap
            except Exception as e:  # keep the agent alive no matter what
                print("sampler error: %r" % (e,), flush=True)
            nxt += self.interval
            delay = nxt - time.monotonic()
            if delay < 0:
                nxt, delay = time.monotonic(), 0
            time.sleep(delay)

    def get_latest(self):
        with self.lock:
            return self.latest


# --------------------------------------------------------------------------
# HTTP server
# --------------------------------------------------------------------------
DEBUG_PAGE = b"""<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>linmon-agent</title>
<style>body{font:12px monospace;margin:8px}</style></head>
<body><b>linmon-agent live JSON</b><pre id="o">loading...</pre>
<script>
var t=new URLSearchParams(location.search).get('token');
var q=t?'?token='+encodeURIComponent(t):'';
function tick(){fetch('/api/v1/stats'+q).then(function(r){return r.json()}).then(function(j){
document.getElementById('o').textContent=JSON.stringify(j,null,1)}).catch(function(e){
document.getElementById('o').textContent=String(e)})}
setInterval(tick,1000);tick();
</script></body></html>"""


def make_handler(sampler, token, verbose):
    class Handler(BaseHTTPRequestHandler):
        server_version = "linmon-agent/" + VERSION
        protocol_version = "HTTP/1.1"   # keep-alive: the phone reuses one connection
        timeout = 20                    # drop idle connections

        def log_message(self, fmt, *args):
            if verbose:
                super().log_message(fmt, *args)

        def _send(self, code, body, ctype="application/json"):
            self.send_response(code)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(body)

        def _authorized(self, query):
            if not token:
                return True
            supplied = ""
            auth = self.headers.get("Authorization", "")
            if auth.startswith("Bearer "):
                supplied = auth[7:]
            elif "token" in query:
                supplied = query["token"][0]
            return hmac.compare_digest(supplied.encode(), token.encode())

        def do_GET(self):
            url = urlparse(self.path)
            query = parse_qs(url.query)
            if url.path in ("/", "/index.html"):
                return self._send(200, DEBUG_PAGE, "text/html; charset=utf-8")
            if url.path.startswith("/api/"):
                if not self._authorized(query):
                    return self._send(401, b'{"error":"unauthorized"}')
                if url.path == "/api/v1/stats":
                    snap = sampler.get_latest()
                    if snap is None:
                        return self._send(503, b'{"error":"warming up"}')
                    return self._send(200, json.dumps(snap, separators=(",", ":")).encode())
                if url.path == "/api/v1/info":
                    return self._send(200, json.dumps(sampler.info, separators=(",", ":")).encode())
            self._send(404, b'{"error":"not found"}')

    return Handler


# --------------------------------------------------------------------------
def main():
    ap = argparse.ArgumentParser(description="linmon-agent: Linux system monitor agent")
    ap.add_argument("--bind", default=os.environ.get("LINMON_BIND", "0.0.0.0"))
    ap.add_argument("--port", type=int, default=int(os.environ.get("LINMON_PORT", "8787")))
    ap.add_argument("--interval", type=float, default=float(os.environ.get("LINMON_INTERVAL", "1.0")),
                    help="seconds between samples (default 1.0)")
    ap.add_argument("--token", default=os.environ.get("LINMON_TOKEN", ""),
                    help="require this token (Authorization: Bearer or ?token=)")
    ap.add_argument("--once", action="store_true",
                    help="print one snapshot (plus host info) as JSON and exit")
    ap.add_argument("--verbose", action="store_true", help="log every HTTP request")
    args = ap.parse_args()

    sampler = Sampler(max(0.2, args.interval))
    sampler.info = sampler.build_info()

    if args.once:
        sampler.collect()          # prime the counters
        time.sleep(1.0)
        print(json.dumps({"info": sampler.info, "stats": sampler.collect()}, indent=1))
        return

    if not args.token:
        print("WARNING: no --token set; anyone on your network can read these stats.", flush=True)
    sampler.start()
    server = ThreadingHTTPServer((args.bind, args.port), make_handler(sampler, args.token, args.verbose))
    print("linmon-agent %s listening on %s:%d (interval %.1fs)" %
          (VERSION, args.bind, args.port, sampler.interval), flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
