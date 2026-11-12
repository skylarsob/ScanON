# ScanON 2.3 — decompilation analysis

Source: `ScanON-Android-v2.3-arm64-v8a.apk` (com.skyler.scanon, versionCode 23, minSdk 26, targetSdk 34,
compileSdk 37, built **debuggable=true**). Decompiled with jadx 1.5.6 (app classes only) and apktool 3.0.3
(resources). Library versions read from `META-INF/*.version`: Compose UI 1.12.1, Material3 1.4.0,
activity-compose 1.13.0, lifecycle 2.11.0, coroutines 1.11.0, core-ktx 1.19.1.
Assets (stored uncompressed): `GeoLite2-City.mmdb` (50 MB), `GeoLite2-ASN.mmdb` (9 MB), `Vazirmatn.ttf`.
App strings: 100 app strings in `values/` and `values-fa/` (Persian), plus a long English-only guide hard-coded in code.

## What the app is
An honest network scanner for filtered networks (Iran focus). Everything is a real network operation; nothing
is simulated. Single activity, Jetpack Compose, no ViewModels — all state lives in `remember {}` inside screens.

### Shell (`MainActivity`, `MainActivityKt.ScanonRoot`)
* `attachBaseContext` wraps the locale from prefs (`en`/`fa`), Vazirmatn typography for Persian.
* Custom dark/light Material3 schemes, global density override (density ×0.95, fontScale ×0.82).
* Animated full-screen canvas background (infinite 18 s transition: blobs, rings, particles).
* Top bar: radar logo + "ScanON"; actions History / Groups / Guide / Settings open an "overlay" page with Back.
* Bottom nav (5 tabs): IP Scan, DNS, Domain, Diag, Vault. Custom Canvas-drawn icons (`AppIconKind`, 16 kinds).

### Engines
* `NetEngine` — target expansion (IPv4, IPv4 CIDR, a.b.c.d-e / a.b.c.d-w.x.y.z ranges, IPv6 CIDR ≥ /112, hostnames),
  tests: TCP connect, HTTP (raw socket, port 80 when port is 443), HTTPS (raw socket → SSLSocket with SNI and
  hostname verification, real GET), ICMP via `/system/bin/ping` with `isReachable` fallback. `runMethod` repeats
  attempts with `Thread.sleep(120)`. `aggregate` → `TargetReport` (median/avg/p90/jitter, warm-up sample dropped when ≥4).
  `scanIps` launches one `async` per ip×method gated by a `Semaphore(workers)`.
* `TargetReport` — reliability (success/partial/unstable/failed/inconclusive/untested), score
  = 40·successRate + 0.4·latencyScore + 0.2·jitterScore.
* `ScanConfig` + presets very_slow/slow/balanced/fast/very_fast (+ UI-only "iran": 20 workers, 5 s, 7 attempts).
* `CloudflareRanges` — 15 published IPv4 CIDRs, size-weighted random IP picker.
* `DnsEngine` — hand-written DNS wire format (A, NS, CNAME, SOA, PTR, MX, TXT, AAAA, SRV), UDP, TCP, DoT (853),
  DoH (POST over raw TLS socket). `probeResolver` → `DnsReport` with latency stats, rcode, answers and a
  "poisoned" flag (all address answers private/loopback/link-local).
* `DiagEngine` — connectivity snapshot (transport, private IP, gateway, DNS, VPN, validated) and checks: gateway
  ping, system DNS, direct DNS 1.1.1.1, pinned HTTPS to 1.1.1.1, IPv6 AAAA + TCP, captive portal (generate_204),
  Cloudflare trace (exit IP/colo/warp), path MTU via DF pings.
* `GeoDb` / `MmdbReader` — own MaxMind DB reader over a memory-mapped asset; City + ASN lookups → `GeoInfo` (flag, label).

### Data (JSON files in filesDir)
`HistoryStore` (history.json, last 200 runs, text body), `VaultStore` (vault.json: ip, label, SNI hostname,
last check), `GroupsStore` (groups.json: named IP or DNS lists). `Prefs` (SharedPreferences: DNS form, lang, theme).

### Screens
IP scan (Cloudflare quick-scan hero, targets editor with paste/load file/random CF/resolve domain, group fill/save,
methods TCP/HTTP/HTTPS/ICMP, port, SNI, presets, workers/timeout/attempts, progress, stats, export
copy/share/save .txt, result cards with GeoIP, vault star, "deep verify top 10"); DNS scan (Iran optimized card,
servers, presets, record type, protocol Auto/udp/tcp/dot/doh, results with poison warning); Domain scan (all
resolvers + system DNS, majority answer comparison, then HTTPS test of every IP with the domain as SNI);
Diagnostics; Vault (add, retest one/all, export); Groups; History; Guide; Settings (language, theme, about).

## Main problems found (details and fixes in out/CHANGELOG.md)
* All scan state and coroutines live in composition: switching tab, opening an overlay or rotating cancels the
  scan and drops results; `onReport` posts a coroutine per result to the UI scope that can land **after** the final
  `clear()+addAll()` (duplicated rows, done-count > total).
* DNS `readName` loops forever on a compression-pointer loop or truncated pointer (guard increments but nothing
  advances) → a malicious/garbled answer hangs a worker thread forever.
* Fixed DNS query id 0x5CA0 and no id/QR/question validation → injected or stray answers accepted.
* CIDR /31 yields zero hosts; prefix >32 or <0 not rejected; maxHosts is per token, not global (a list of
  /16s expands to millions of strings on the main thread); ranges fall through to `InetAddress.getByName`;
  hostnames are resolved on the main thread (NetworkOnMainThreadException swallowed → silently dropped).
* MMDB reader has no bounds/recursion checks (corrupt data → exception escapes into composition = crash);
  metadata marker searched forward instead of last occurrence; `AssetFileDescriptor` leaked.
* JSON stores: unsynchronized read-modify-write (Vault "retest all" loses updates), non-atomic writes, a single
  bad record wipes the whole list (and the next save persists the empty list).
* `ping` processes never time out / are never destroyed on cancel; `Thread.sleep` blocks cancellation.
* DoH reads headers byte-by-byte into a `List<Byte>`, trusts Content-Length (OOM), ignores chunked encoding.
* File import: stream not closed, any exception crashes the app; SAF save opens with "w" (no truncate), errors crash.
* `String.format` without Locale → Persian digits / commas inside exported data.
* Released as `debuggable=true`; REFUSED/SERVFAIL answers counted as "working" resolvers.
