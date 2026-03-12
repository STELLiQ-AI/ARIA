#!/usr/bin/env python3
"""
aria_dashboard.py — ARIA Real-Time Device Monitor

Polls Samsung Galaxy S24 Ultra via ADB and serves performance metrics
to an HTML dashboard over HTTP.

Usage:
    python aria_dashboard.py [--port 8080] [--device R5CWC3M25GL]
    Then open http://localhost:8080 in your browser.
"""

import http.server
import json
import os
import re
import subprocess
import sys
import threading
import time
from urllib.parse import urlparse

# ── Configuration ────────────────────────────────────────────────────────

DEFAULT_PORT = 8080
DEFAULT_DEVICE = "R5CWC3M25GL"
POLL_INTERVAL = 2.0          # Fast poll: CPU, GPU, memory (lightweight sysfs)
SLOW_POLL_EVERY = 3          # Slow poll every Nth fast poll (ARIA meminfo + logcat)
ADB_TIMEOUT = 5
ADB_PATH = r"C:\Users\ssage\platform-tools\adb.exe"


# ── ADB Metric Poller ───────────────────────────────────────────────────

class ADBPoller:
    """Background thread that polls device metrics via ADB."""

    def __init__(self, device_id):
        self.device_id = device_id
        self._lock = threading.Lock()
        self._metrics = {"connected": False, "timestamp": 0}
        self._prev_cpu = None
        self._prev_gpu = None
        self._running = False
        self._poll_count = 0
        self._cached_aria = {"aria_mem": {}, "pipeline": {"state": "UNKNOWN", "detail": ""}}

    def start(self):
        self._running = True
        t = threading.Thread(target=self._loop, daemon=True)
        t.start()

    def stop(self):
        self._running = False

    def get(self):
        with self._lock:
            return dict(self._metrics)

    def _adb(self, cmd):
        try:
            r = subprocess.run(
                [ADB_PATH, "-s", self.device_id, "shell", cmd],
                capture_output=True, text=True, timeout=ADB_TIMEOUT
            )
            if r.returncode != 0:
                print(f"  [ADB] rc={r.returncode} stderr={r.stderr[:100]}")
            return r.stdout if r.returncode == 0 else None
        except Exception as e:
            print(f"  [ADB] Exception: {e}")
            return None

    def _loop(self):
        while self._running:
            try:
                m = self._poll()
            except Exception as e:
                m = {"connected": False, "error": str(e)}
                print(f"[Poll] ERROR: {e}")
            m["timestamp"] = time.time()
            status = "OK" if m.get("connected") else "FAIL"
            print(f"[Poll] {status} - {time.strftime('%H:%M:%S')}")
            with self._lock:
                self._metrics = m
            time.sleep(POLL_INTERVAL)

    def _poll(self):
        self._poll_count += 1

        # ── FAST POLL: lightweight sysfs reads only (no dumpsys, no logcat) ──
        out = self._adb(
            'echo "===STAT===";'
            'head -9 /proc/stat;'
            'echo "===FREQ===";'
            'cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq '
            '/sys/devices/system/cpu/cpu1/cpufreq/scaling_cur_freq '
            '/sys/devices/system/cpu/cpu2/cpufreq/scaling_cur_freq '
            '/sys/devices/system/cpu/cpu3/cpufreq/scaling_cur_freq '
            '/sys/devices/system/cpu/cpu4/cpufreq/scaling_cur_freq '
            '/sys/devices/system/cpu/cpu5/cpufreq/scaling_cur_freq '
            '/sys/devices/system/cpu/cpu6/cpufreq/scaling_cur_freq '
            '/sys/devices/system/cpu/cpu7/cpufreq/scaling_cur_freq 2>/dev/null;'
            'echo "===MAXF===";'
            'cat /sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq '
            '/sys/devices/system/cpu/cpu1/cpufreq/cpuinfo_max_freq '
            '/sys/devices/system/cpu/cpu2/cpufreq/cpuinfo_max_freq '
            '/sys/devices/system/cpu/cpu3/cpufreq/cpuinfo_max_freq '
            '/sys/devices/system/cpu/cpu4/cpufreq/cpuinfo_max_freq '
            '/sys/devices/system/cpu/cpu5/cpufreq/cpuinfo_max_freq '
            '/sys/devices/system/cpu/cpu6/cpufreq/cpuinfo_max_freq '
            '/sys/devices/system/cpu/cpu7/cpufreq/cpuinfo_max_freq 2>/dev/null;'
            'echo "===GPUB===";'
            'cat /sys/class/kgsl/kgsl-3d0/gpubusy 2>/dev/null;'
            'echo "===GPUF===";'
            'cat /sys/class/kgsl/kgsl-3d0/devfreq/cur_freq 2>/dev/null;'
            'echo "===GPUM===";'
            'cat /sys/class/kgsl/kgsl-3d0/devfreq/max_freq 2>/dev/null;'
            'echo "===MEM===";'
            'head -3 /proc/meminfo;'
            'echo "===BAT===";'
            'cat /sys/class/power_supply/battery/capacity '
            '/sys/class/power_supply/battery/temp '
            '/sys/class/power_supply/battery/current_now 2>/dev/null;'
            'echo "===END==="'
        )

        if not out:
            return {"connected": False}

        s = self._sections(out)
        m = {"connected": True}
        m["cpu"] = self._parse_cpu(s)
        m["gpu"] = self._parse_gpu(s)
        m["sys_mem"] = self._parse_sysmem(s)
        m["battery"] = self._parse_battery_sysfs(s)

        # Thermal estimated from battery temp
        batt_temp = m["battery"].get("temp_c", 0)
        m["thermal"] = {"cpu_c": batt_temp + 8, "gpu_c": batt_temp + 5, "skin_c": batt_temp}

        # ── SLOW POLL: ARIA meminfo + logcat (expensive, every Nth cycle) ──
        if self._poll_count % SLOW_POLL_EVERY == 1:
            aria = self._adb(
                'echo "===AM===";'
                'dumpsys meminfo com.stelliq.aria 2>/dev/null | head -40;'
                'echo "===AL===";'
                'logcat -d -t 20 2>/dev/null | grep -iE '
                '"SessionController|state.*->|SUMMAR|RECORD|COMPLETE|'
                'Whisper|LLM|Llama|inference|loaded|ARIA" | tail -5;'
                'echo "===END==="'
            )
            if aria:
                asec = self._sections(aria)
                self._cached_aria["aria_mem"] = self._parse_ariamem(asec)
                self._cached_aria["pipeline"] = self._parse_pipeline(asec)

        m["aria_mem"] = self._cached_aria["aria_mem"]
        m["pipeline"] = self._cached_aria["pipeline"]

        return m

    # ── Section splitter ──

    def _sections(self, text):
        result = {}
        key = None
        lines = []
        for line in text.split('\n'):
            s = line.strip()
            if s.startswith('===') and s.endswith('==='):
                if key:
                    result[key] = lines
                key = s.strip('=')
                lines = []
            else:
                lines.append(line.rstrip())
        if key:
            result[key] = lines
        return result

    # ── CPU ──

    def _parse_cpu(self, s):
        # Usage from /proc/stat deltas
        usage = [0.0] * 8
        cur = {}
        for line in s.get('STAT', []):
            m = re.match(r'cpu(\d+)\s+(.*)', line)
            if m:
                idx = int(m.group(1))
                vals = list(map(int, m.group(2).split()))
                idle = vals[3] + (vals[4] if len(vals) > 4 else 0)
                total = sum(vals)
                cur[idx] = (idle, total)

        if self._prev_cpu:
            for i in range(8):
                if i in cur and i in self._prev_cpu:
                    di = cur[i][0] - self._prev_cpu[i][0]
                    dt = cur[i][1] - self._prev_cpu[i][1]
                    usage[i] = round(100.0 * (1 - di / dt), 1) if dt > 0 else 0
        self._prev_cpu = cur

        # Frequencies (kHz from sysfs)
        freqs = []
        for line in s.get('FREQ', []):
            v = line.strip()
            freqs.append(int(v) if v.isdigit() else 0)
        while len(freqs) < 8:
            freqs.append(0)

        max_freqs = []
        for line in s.get('MAXF', []):
            v = line.strip()
            max_freqs.append(int(v) if v.isdigit() else 0)
        while len(max_freqs) < 8:
            max_freqs.append(1)

        cores = []
        for i in range(8):
            max_mhz = round(max_freqs[i] / 1000) if max_freqs[i] > 0 else 1
            cores.append({
                "usage": max(0, min(100, usage[i])),
                "freq_mhz": round(freqs[i] / 1000),
                "max_mhz": max_mhz
            })
        return {"cores": cores}

    # ── GPU ──

    def _parse_gpu(self, s):
        busy_pct = 0.0
        lines = s.get('GPUB', [])
        if lines:
            parts = lines[0].strip().split()
            if len(parts) >= 2:
                try:
                    b, t = int(parts[0]), int(parts[1])
                    if self._prev_gpu is not None:
                        db = b - self._prev_gpu[0]
                        dt = t - self._prev_gpu[1]
                        if dt > 0:
                            busy_pct = round(100.0 * db / dt, 1)
                    self._prev_gpu = (b, t)
                except ValueError:
                    pass

        def parse_freq(key):
            fl = s.get(key, [])
            if fl:
                v = fl[0].strip()
                if v.isdigit():
                    f = int(v)
                    if f > 1_000_000:
                        return f // 1_000_000
                    elif f > 1_000:
                        return f // 1_000
                    return f
            return 0

        freq = parse_freq('GPUF')
        max_freq = parse_freq('GPUM')

        return {
            "busy_pct": max(0, min(100, busy_pct)),
            "freq_mhz": freq,
            "max_mhz": max_freq or 1
        }

    # ── System Memory ──

    def _parse_sysmem(self, s):
        total_kb = avail_kb = 0
        for line in s.get('MEM', []):
            m = re.match(r'MemTotal:\s+(\d+)', line)
            if m:
                total_kb = int(m.group(1))
            m = re.match(r'MemAvailable:\s+(\d+)', line)
            if m:
                avail_kb = int(m.group(1))
        return {
            "total_mb": round(total_kb / 1024),
            "used_mb": round((total_kb - avail_kb) / 1024)
        }

    # ── Battery ──

    def _parse_battery(self, s):
        level = temp = current = 0
        for line in s.get('BAT', []):
            m = re.match(r'\s*level:\s*(\d+)', line)
            if m:
                level = int(m.group(1))
            m = re.match(r'\s*temperature:\s*(\d+)', line)
            if m:
                temp = int(m.group(1))
            m = re.search(r'current\s*now:\s*(-?\d+)', line, re.IGNORECASE)
            if m:
                current = int(m.group(1))
        return {
            "level": level,
            "temp_c": round(temp / 10, 1),
            "current_ma": round(current / 1000) if abs(current) > 10000 else current
        }

    # ── Battery (lightweight sysfs — no dumpsys) ──

    def _parse_battery_sysfs(self, s):
        """Parse battery from direct sysfs reads: capacity, temp, current_now."""
        vals = []
        for line in s.get('BAT', []):
            v = line.strip()
            if v.lstrip('-').isdigit():
                vals.append(int(v))
        level = vals[0] if len(vals) > 0 else 0
        temp_raw = vals[1] if len(vals) > 1 else 0
        current_raw = vals[2] if len(vals) > 2 else 0
        return {
            "level": level,
            "temp_c": round(temp_raw / 10, 1),
            "current_ma": round(current_raw / 1000) if abs(current_raw) > 10000 else current_raw
        }

    # ── Thermal ──

    def _parse_thermal(self, s):
        zones = {}
        for line in s.get('THRM', []):
            parts = line.strip().split(':')
            if len(parts) >= 2:
                name = parts[0].strip()
                raw = parts[-1].strip()
                if raw.lstrip('-').isdigit():
                    val = int(raw)
                    temp_c = round(val / 1000, 1) if abs(val) > 200 else float(val)
                    if 0 < temp_c < 120:
                        zones[name] = temp_c

        cpu_t = gpu_t = skin_t = 0
        for name, t in zones.items():
            nl = name.lower()
            if ('cpu' in nl or 'big' in nl or 'mid' in nl or 'little' in nl) and t > cpu_t:
                cpu_t = t
            elif ('gpu' in nl or 'adreno' in nl) and t > gpu_t:
                gpu_t = t
            elif ('skin' in nl or 'therm' in nl or 'virtual' in nl) and t > skin_t:
                skin_t = max(skin_t, t)

        return {"cpu_c": cpu_t, "gpu_c": gpu_t, "skin_c": skin_t}

    # ── ARIA Process Memory ──

    def _parse_ariamem(self, s):
        r = {"total_mb": 0, "native_mb": 0, "java_mb": 0, "graphics_mb": 0}
        for line in s.get('AM', []):
            m = re.match(r'\s*TOTAL[:\s]+(\d+)', line)
            if m:
                r["total_mb"] = round(int(m.group(1)) / 1024, 1)
            m = re.match(r'\s*Native Heap\s+(\d+)', line)
            if m:
                r["native_mb"] = round(int(m.group(1)) / 1024, 1)
            m = re.match(r'\s*Dalvik Heap\s+(\d+)', line)
            if m:
                r["java_mb"] = round(int(m.group(1)) / 1024, 1)
            m = re.match(r'\s*(Gfx dev|EGL mtrack|GL mtrack)\s+(\d+)', line)
            if m:
                r["graphics_mb"] = round(r["graphics_mb"] + int(m.group(2)) / 1024, 1)
        return r

    # ── Pipeline State ──

    def _parse_pipeline(self, s):
        state = "IDLE"
        detail = ""
        for line in s.get('AL', []):
            # Match "State: X -> Y" transitions
            tm = re.search(r'[Ss]tate.*?:\s*\w+\s*->\s*(\w+)', line)
            if tm:
                state = tm.group(1).upper()
                detail = line.strip()
                continue
            upper = line.upper()
            if 'SUMMARIZ' in upper:
                state = "SUMMARIZING"
                detail = line.strip()
            elif 'COMPLETE' in upper and 'SUMMAR' not in upper:
                state = "COMPLETE"
                detail = line.strip()
            elif 'RECORDING' in upper and 'STOP' not in upper:
                state = "RECORDING"
                detail = line.strip()
        return {"state": state, "detail": detail[-120:] if detail else ""}


# ── HTTP Server ──────────────────────────────────────────────────────────

class Handler(http.server.BaseHTTPRequestHandler):
    poller = None

    def do_GET(self):
        path = urlparse(self.path).path
        if path == '/metrics':
            data = json.dumps(self.poller.get()).encode()
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Access-Control-Allow-Origin', '*')
            self.send_header('Cache-Control', 'no-cache')
            self.end_headers()
            self.wfile.write(data)
        elif path in ('/', '/index.html'):
            html_path = os.path.join(
                os.path.dirname(os.path.abspath(__file__)),
                'aria_dashboard.html'
            )
            try:
                with open(html_path, 'r', encoding='utf-8') as f:
                    content = f.read().encode('utf-8')
                self.send_response(200)
                self.send_header('Content-Type', 'text/html; charset=utf-8')
                self.end_headers()
                self.wfile.write(content)
            except FileNotFoundError:
                self.send_error(404, 'aria_dashboard.html not found')
        else:
            self.send_error(404)

    def log_message(self, fmt, *args):
        pass


# ── Main ─────────────────────────────────────────────────────────────────

def main():
    port = DEFAULT_PORT
    device = DEFAULT_DEVICE

    args = sys.argv[1:]
    for i, a in enumerate(args):
        if a == '--port' and i + 1 < len(args):
            port = int(args[i + 1])
        elif a == '--device' and i + 1 < len(args):
            device = args[i + 1]

    poller = ADBPoller(device)
    poller.start()
    Handler.poller = poller

    server = http.server.HTTPServer(('0.0.0.0', port), Handler)
    print(f"""
 ╔══════════════════════════════════════════════════════════╗
 ║       ARIA Device Monitor — STELLiQ Technologies        ║
 ╠══════════════════════════════════════════════════════════╣
 ║  Dashboard:  http://localhost:{port:<25}║
 ║  Device:     {device:<43}║
 ║  Polling:    every {POLL_INTERVAL}s{' ' * 36}║
 ╚══════════════════════════════════════════════════════════╝
    """)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nShutting down...")
        poller.stop()
        server.shutdown()


if __name__ == '__main__':
    main()
