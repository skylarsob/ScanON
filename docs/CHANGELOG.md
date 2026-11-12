# ScanON 3.0.1 (versionCode 38) — bug-fix release, no new features

Three bugs that were in 3.0.0, fixed in source. (An external bytecode patch
of the 3.0.0 APK found and verified these first; this release applies the
same fixes properly in the Kotlin source.)

- HIGH — crash when a scan ends quickly (ForegroundServiceDidNotStartInTime).
  ScanKeepAliveService.stop() called stopService() directly, racing the
  pending startForeground() when a scan finished before the service had
  started (tiny target lists, instant cancels). The stop now goes through
  the service's own "stop" start command, which queues behind the start
  command; the direct stopService remains only as a fallback when
  startService throws (background-start limits).
- CRITICAL — crash on every launch after restoring a backup with settings.
  The backup importer stored whole-number float preferences (radius_scale,
  glass) as Int, because org.json reads "1" back as Integer; the settings
  reader's getFloat() then threw ClassCastException in Application.onCreate.
  Two-sided fix: the importer keeps FLOAT_KEYS as floats, and the settings
  reader now reads floats tolerantly via getAll(), which also heals installs
  whose preferences are already corrupted.
- LOW — themed (monochrome) launcher icon was a blank disc: the adaptive
  icon pointed its monochrome layer at the full-colour foreground PNG.
  There is now a real single-colour silhouette (ic_launcher_monochrome.xml:
  the ring, the Iran outline and the Milad tower as strokes).

# ScanON 3.0.0 (versionCode 37) — changelog vs 2.9.1

The accuracy release (Skylar: "make the scanning really, really accurate,
especially DNS"). Foundations were already honest (verified query IDs,
certificate checking, medians); this release removes the distortions that
were left.

DNS accuracy:
- Cache-busting ranking probes: attempt 1 asks the plain name, every later
  attempt a fresh random subdomain, so timed runs are real recursions, not
  cache hits. (Tunnel probes already used random names; the Domain scanner
  opts out because it needs the domain's own answers.)
- Honesty probe per ranked resolver: a name that cannot exist must return
  NXDOMAIN. Inventing an address sets DnsReport.hijack, shows a warning on
  the card, marks exports HIJACK, and sorts the resolver below honest ones.
  0.0.0.0 / :: filter-sink answers now count as poisoned too.
- Refine pass: after the main scan, the top 8 working resolvers are
  re-probed with extra attempts and their samples pooled (DnsCatalog
  .mergeReports), so the final ranking survived two rounds.
- Real pooled statistics everywhere: DnsReport keeps its raw samples;
  merged UDP+TCP and tunnel reports pool them instead of averaging
  medians. Jitter is now MAD (median absolute deviation), not stddev.

IP accuracy:
- Quick Scan uses /cdn-cgi/trace, and httpsTest verifies the body really
  is a Cloudflare trace (fl=/colo=) before counting a run — a 200 with a
  valid certificate is no longer enough. The colo is shown in the result.
- Jitter switched to MAD in TargetReport aggregation as well.

Fields: labels now sit ABOVE the box everywhere (the floating label
collided with entered text, e.g. the timeout field).

Iran: the resolver card gains the ranges those resolvers live in —
3,754 unique /24 networks derived from the bundled list, bundled as
ir-ranges.txt — loadable as chips (25/50/100/All) or a custom amount,
straight into the custom-range field; resolver samples also accept a
custom amount now. Guide section 19 explains the accuracy model.

# ScanON 2.9.1 (versionCode 36) — changelog vs 2.9.0

The visible-polish follow-up: 2.9.0's field/button changes were real but
too subtle to see (surfaceVariant sits almost on top of the glass card's
own colours, and the default tonal button is muted). This build makes the
refinement unmistakable, per the review's "make them prettier":

- Buttons: one shared system now. Every secondary action uses ScanButton —
  a solid primaryContainer fill with onPrimaryContainer content, 12 dp
  radius, 16x10 padding — and every primary action uses PrimaryButton with
  matching radius and 20x12 padding. IconTextButton (Paste/Load/Sort/Clear
  and friends) is a ScanButton now instead of a bare text link.
- Fields: fill is surfaceVariant tinted 12% toward the accent so fields
  visibly lift off the cards, corners are 14 dp, and the unfocused outline
  is stronger (60% alpha).
- Theme shapes: the Material shape scale is rounder app-wide (small 12,
  medium 16, large 20, extraLarge 24), so chips, cards, dialogs, menus and
  sheets share the same corner language as the fields and buttons.
- Alignment: the IP targets actions are an even 2x2 grid of equal-width
  buttons, the DNS resolver actions are one equal-width row, and the IP
  scan page's card spacing matches the DNS page at 8 dp.

# ScanON 2.9.0 (versionCode 35) — changelog vs 2.8.6

UI/UX refinement pass from Hossein's review (relayed by Skylar). Background,
palette and identity untouched; no functionality removed.

- Language system reworked. The reported bug: pick فارسی, then "System",
  and the layout flipped LTR while the device language is Persian. Cause:
  for "system" the app returned the base context untouched and trusted
  framework/per-app-locale state. Now the effective locale is resolved
  deterministically in-app: manual fa/en wins; "system" reads the DEVICE
  locale via Resources.getSystem() (fa/en supported, anything else falls
  back to English), and the context wrap always applies locale AND layout
  direction. ViewModel strings (snackbars, history, notifications) used to
  resolve from the raw Application resources, which never see the Activity
  wrap and could disagree with the visible UI; they now come from
  AppContainer.localizedResources(), and ScanNotifier builds its
  notifications from a locale-wrapped context too.
- Persian typography: all 343 ZWNJ half-spaces in values-fa replaced with
  full spaces, per the brief.
- Input fields are solid now: Field has a filled surfaceVariant container,
  a 45%-alpha outline that turns primary on focus, and a readable
  onSurfaceVariant placeholder — no glass bleed-through anywhere.
- Buttons: every OutlinedButton (transparent, read as glass over the
  cards) is now a FilledTonalButton with a solid tonal background.
  Primary filled Buttons unchanged. TextButtons (inline text actions)
  unchanged by design.
- Scan pages: the IP target box shrinks (minLines 4 to 3, maxLines 10 to
  8 — the tall empty void in the review screenshots), and card spacing on
  the IP/DNS scan pages tightens from 10 to 8 dp.

# ScanON 2.8.6 (versionCode 34) — changelog vs 2.8.5

- Icon replaced again with the second artwork Skylar sent (cleaner ring,
  centred wordmark), regenerated into the same adaptive/round/legacy
  density set. The 2.8.5 text fix below was built as 2.8.5 but ships for
  the first time in this build.

# ScanON 2.8.5 (versionCode 33) — changelog vs 2.8.4

- Black-text fix (Skylar: "the apps text in some places is black, makes it
  hard to see"). Root cause: Material3 sets no global content colour and
  GlassCard is a plain Box, so every Text without an explicit colour used
  LocalContentColor's default — black — on the dark themes. Her 2.8
  onboarding screenshot showed it exactly: bare titles black, explicitly
  coloured descriptions fine. ScanonTheme now provides the scheme's
  onSurface as the default content colour app-wide (correct in every
  preset, including Frosted light), and GlassCard provides it for its own
  content as well.

# ScanON 2.8.4 (versionCode 32) — changelog vs 2.8.3

- New app icon: the artwork Skylar sent (Iran outline, Milad tower,
  IP/DNS scanner panels) replaces the drawn radar icon. Generated as a
  full density set: adaptive foreground (logo at 72% on transparency over
  a near-black blue background, with a monochrome layer for themed
  icons), legacy square icons from the full artwork, and round icons
  with the logo filling the frame. The 2.8.3 fixes below were built and
  verified as 2.8.3 but ship for the first time in this build.

# ScanON 2.8.3 (versionCode 31) — changelog vs 2.8.2

Skylar's debug list: "the notification stuff is buggy", the Iran DNS picks
were the wrong list, and the DNS speed presets "don't work".

- Notifications, the real bug: ScanKeepAliveService.stop() stopped the
  foreground service by *starting* it with a STOP action — startService from
  the background throws on Android 12+, the exception was swallowed, and the
  service plus its progress notification stayed stuck after the scan ended.
  Now stopService() (legal from the background) + an explicit notification
  cancel. Also: BackgroundWork.end() posted a "finished" alert for EVERY
  scan end, even while another scan still ran — now only the last active
  scan posts it. And finished alerts moved to their own DEFAULT-importance
  channel (they were on the LOW progress channel, so they never popped).
- Iran DNS replaced: the hand-picked provider groups (Shecan, Electro,
  403.online, RadarGame, TIC, Shatel) are gone. The Iran card now loads
  from the net2share/ir-resolvers catalogue bundled as an asset — 7,854
  resolver IPs that actually run inside Iranian datacenters/ISPs — as a
  random sample of 100 / 250 / 500 / 1000 / all, replacing the server list.
- DNS speed presets: the value path was actually sound, but the chips
  never showed a selected state and the fields they change sit further
  down the card, so a tap looked like nothing happened. The chips moved
  directly above the attempts/timeout/workers rows, now highlight the
  active preset (values matched; hand edits clear the highlight), and
  each tap confirms with a snackbar naming the applied values.

# ScanON 2.8.2 (versionCode 30) — changelog vs 2.8

- Intro language fix (was built as 2.8.1, never shipped separately): in 2.8
  the language picked on the first intro page only applied when Finish was
  tapped, so the rest of the intro stayed in the old language (Skylar's
  report, with a screenshot of the English behaviour page after picking
  Persian). It now applies the moment it is tapped, like the Settings
  screen does; the activity restarts for the new locale and the intro page
  survives the restart (rememberSaveable).
- Tunnel mode redesigned around protocols, after Skylar's correction that
  2.8 (a) pre-filled her own welmiv.ir domains into the profiles, and
  (b) listed apps as if they were protocols ("WhiteDNS is an app, not a
  protocol"). Researched against the protocols' own docs/repos first:
  * All personal domains removed; no mode carries a domain. Selecting a
    mode changes ONLY which carrier record types are probed — it never
    touches the server list, the test domain or any other setting.
  * Modes are protocols now: Auto (default; the union — TXT, A, AAAA,
    CNAME, MX, NS, SRV, NULL, HTTPS, SVCB on fresh random subdomains, plus
    a ~155-char long-name probe; per-protocol verdicts per resolver),
    DNSTT (TXT; NoizDNS and Slipstream share the wire format), VayDNS
    (TXT + its alternate CNAME/A/AAAA/MX/NS/SRV carriers), MasterDNS
    (TXT/NULL/HTTPS/SVCB/MX — MasterDnsVPN/StormDNS/CottenDNS family),
    Soar (TXT + AAAA fallback + long names), TheFeed (TXT both ways).
    QTYPES gained NULL (10) and SVCB (64).
  * Starting a tunnel scan with no test domain (or the example.com
    placeholder) now warns first: a tunnel scan without the user's own
    tunnel zone proves nothing. Same warning for an E2E run with a blank
    domain. "Scan anyway" proceeds.
  * DnsReport gained a `proto` verdict field, shown on tunnel cards and
    included in exports and the tunnel report. Guide section 17 and the
    tunnel card text rewritten in EN + FA.

# ScanON 2.8 (versionCode 28) — changelog vs 2.7

Skylar's brief: background scans, a first-run introduction, a DNS scanner
aimed at the tunnel apps (SlipNet, WhiteDNS, TheFeed, VayDNS, DNSTT,
Slipstream, NoizDNS, Cotten/Master/Storm/Soar), more settings everywhere,
Iran DNS selectable in bulk — and, from her SlipNet screenshots, the SlipNet
scanner's own options. Engines stay honest: only full-transaction medians
are ever headlined.

## Background scans (the real fix)
- Until 2.7 scans lived in their ViewModels: viewModelScope jobs, and
  onCleared() cancelled them — closing the app killed the scan even though
  the keep-alive service held the process. Now IP/DNS/Domain scan state,
  jobs and cancellers live in process-wide sessions in the AppContainer;
  the jobs run on the application scope, no onCleared() cancels anything,
  and the UI re-attaches to live progress/results when reopened. Vault
  retest-all moved to the app scope too, and IP deep-verify / speed-test
  now hold the background notification like the main scans.

## First-run introduction
- Three pages, shown exactly once (Settings -> About can replay it):
  welcome + language (applied on Finish so the locale restart never
  interrupts the intro), appearance (preset, accent, glass, density,
  classic theme mode), behaviour (reduce motion, haptics, keep-screen-on,
  notify-on-finish) + a note that scans run in the background.
  Persisted as the new `onboarded` setting.

## DNS tunnel mode
- New engine/Tunnel.kt: 11 profiles (SlipNet, WhiteDNS, TheFeed, VayDNS,
  DNSTT, Slipstream, NoizDNS, CottenDNS, MasterDnsVPN, StormDNS, Soar).
  A profile fills the tunnel domain, tunnel-tuned settings (Auto protocol,
  5 attempts, 5 s timeout, 16 workers) and every plausible resolver
  (known globals + all Iranian).
- DnsCatalog.probeTunnel asks each resolver exactly what a real client
  asks: A and TXT for the tunnel domain plus a fresh random subdomain
  (only the tunnel's authoritative server can answer it), attempts x3,
  merged into one report with the per-part breakdown kept. Poisoned
  (bogon) resolvers can never be "working".
- tunnelScore: success rate x60 + speed + jitter penalty; an E2E fail
  caps the score at 25, an E2E pass adds 5. Tunnel card shows the best
  resolver with Copy best + a shareable ranked tunnel report; the scan
  is also saved to History as a tunnel report.

## SlipNet parity (from Skylar's screenshots)
- Custom DNS port (UDP/TCP), transport "Both" (UDP and TCP measured and
  merged with a per-transport breakdown), shuffle toggle, nearby /24
  expansion phase (working resolver -> its whole /24, max 16 subnets,
  2 attempts), E2E tunnel verification (DnsEngine.verifyE2e: 48-char
  long-label query, TXT query, outside A lookup for cloudflare.com that
  must be non-bogon) with its own timeout (500-30000 ms) and concurrency
  (1-10), and a resolver list with Default / Import (file) / country
  sets / custom ranges — ranges are expanded at scan start (cap 100k,
  sampled) and every IP is tried as a resolver. Workers now 1-128.

## More settings
- DNS: speed presets (Fast / Balanced / Thorough), workers field,
  Iranian provider groups (Shecan, Electro, 403.online, RadarGame, TIC,
  Shatel — IranDns catalogue; DnsCatalog.IRAN_RESOLVERS now derives from
  it) with per-group add and add-all.
- IP: new presets Iran fast / Iran deep / Battery saver, a
  delay-between-attempts field (persisted, wired into ScanConfig), and
  Cloudflare port preset chips (443 / CF HTTPS ports / CF HTTP ports).
- Guide sections 16-18 (EN + FA). DnsReport gained an `e2e` verdict
  field, exported in TXT lines and tunnel reports.

Not runtime-tested on a phone (sandbox has no device): first-launch
checks are the intro appearing once, a tunnel scan against
dnstt.welmiv.ir, and a background scan surviving the app being closed.

---

# ScanON 2.7 (versionCode 27) — changelog vs 2.6

Skylar on 2.6: "more active animations and effects and thats done." So 2.7
makes the app feel alive *while it works*, not just while navigating.
Engines, ViewModels, stores and data are unchanged again.

- Scan-reactive background: while any scanner runs, a boost layer fades in
  behind everything — fast pulse rings (2.6 s cycle) from the radar centre
  plus a brighter accent wash — and fades out when the scan ends.
  (`AnimatedBackground(boost = anyRunning)` in MainActivity.)
- Arrival flash: every fresh IP/DNS result card and resolver row glows with
  the accent colour and settles over 750 ms (`Modifier.flashOnAppear`), so
  results streaming in visibly land.
- Sparklines draw themselves in left → right over 650 ms when a card
  appears (clipped draw-in; the end dot pops in when the line completes).
- The title-bar radar now turns slowly all the time (9 s/rev; the progress
  card keeps its fast 1.4 s spin). SpinningRadar gained a duration param.
- The IP scanner's Advanced section expands/collapses with a spring
  AnimatedVisibility instead of snapping open.
- The main action buttons (quick scan, start scan, DNS start, domain run)
  get the same press-dip spring as the result cards.
- Guide: new section 15 (EN + FA) describing the reactive motion.

All of it still honours Reduce motion: flash, draw-in and the fast spin go
still, and the background boost stays calm.

---

# ScanON 2.6 (versionCode 26) — changelog vs 2.5

Skylar's verdict on 2.5: "it doesn't look modern, no proper slide
animations, no effects." Fair — 2.5's tab slide moved only width/7 (read as
a plain fade), the glass card was a flat surface with an 8% plain border,
and nothing else moved. 2.6 is a motion + depth pass; engines, ViewModels,
stores and data are again unchanged.

## Navigation motion
- Tab switches are now directional: the incoming tab slides a full third of
  the width from the side it sits on (spring 300 / 0.82) with fade + a slight
  scale settle; the outgoing tab leaves the opposite way.
- Overlay pages (Settings, History, Groups, Guide) slide up + fade in over
  the tabs instead of cutting.
- Bottom bar: gradient hairline on top, and the selected icon springs to
  1.18× with an underdamped spring.

## Depth & tactility
- GlassCard rebuilt as real glass: vertical surface→surfaceVariant gradient,
  a specular gradient border (bright top-left → accent-tinted bottom), a 1px
  highlight line under the top edge, and an accent-tinted soft shadow.
- Entrance cascade: each screen's cards fade + rise in a 55 ms stagger
  (`Modifier.entrance(index)`).
- Press feedback: IP/DNS result cards dip to 0.975× with a spring while
  pressed; the IP card's details section now expands/collapses with an
  AnimatedVisibility spring instead of snapping.
- Result rows on DNS/Domain/Vault/Groups/History/Diagnostics now animate in
  and re-place with the same spring (animateItem), not just IP results.

## Living surfaces
- AnimatedBackground: dot grid, a slow aurora wash drifting diagonally and
  shifting hue between primary/secondary, a rotating radar sweep wedge in
  the top-right, brighter blobs — all still scheme-coloured and scaled by
  the glass setting.
- Quick-scan hero: its gradient slowly trades colours (5.2 s ping-pong).
- Progress: stock LinearProgressIndicator replaced by a gradient bar whose
  fill animates to the real fraction with a shimmer band travelling inside
  it; indeterminate state sweeps a band across the track; the percentage
  counts instead of snapping.
- Stat boxes: Tested/Working counts animate to their values.
- Guide: new section 14 (EN + FA) explaining the motion system and the
  reduce-motion / static-background escape hatches.

Everything honours the existing Reduce motion setting (and the system
animator scale): entrances, presses, shimmer, hero and background all go
still when it is on.

---

# ScanON 2.5 (versionCode 25) — changelog vs 2.4

Built from the 2.4 source (sent by Skylar) with the "Tactile Glass & Precision"
design brief applied to the Android app. Engines, ViewModels, stores and data
are unchanged from 2.4; everything below is presentation + settings.

## Appearance engine (Settings → Appearance)
- Five presets: Classic Teal (the 2.4 look, still honours system/dark/light),
  Obsidian Dark (carbon + electric cyan, new default), Tokyo Night (indigo +
  neon magenta), Matrix Phosphor (monochrome emerald), Frosted Daylight
  (alabaster + cobalt).
- Accent override: 8 swatches replace the preset's primary colour (container
  and on-colours derived by blending), or back to the preset accent.
- Glass intensity slider: GlassCard surface alpha (0.97 → 0.52) and the
  animated background's glow strength follow it. Cards also gained the brief's
  1px specular border (onSurface at 8%).
- Density: Compact (= the old compact toggle values), Comfortable, Spacious —
  drives both the Density override and a spacing scale used by cards.
- Corner radius slider (0.5×–1.5×) applied to every glass card.

## Motion & components
- Tab switches morph with spring physics (stiffness 300, damping ratio 0.8,
  the Compose mapping of the brief's 300/28) via AnimatedContent; result rows
  animate into the list with the same spring (animateItem).
- AnimatedBackground now takes its colours from the active scheme (preset +
  accent) instead of hard-coded teal/sky/violet.
- StatusDot: pulsing reliability dot on every IP result card (still under
  reduce-motion).
- Sparkline: each IP card draws its real per-attempt total times (the same
  numbers printed as "total runs"); the inspector shows a large version.
- Inspector: the info button on an IP card opens a bottom sheet with the full
  measurement (median/p90/jitter/loss/score, TCP range, TLS, cipher, cert
  days, download, every run, failures) plus copy IP / copy endpoint / save to
  Vault. Tap-to-copy on the card itself is unchanged.
- DNS answers are colour-coded record pills (A, AAAA, CNAME, MX, TXT, NS)
  with tap-to-copy data and TTL; resolver rows in Domain scan use them too.
- IPs, endpoints and numeric readouts on result cards use the monospace font.
- Quick-scan hero gradient follows the scheme (primary → secondary).

## Notes
- New settings persist alongside the old ones (preset, accent_argb, glass,
  density_mode, radius_scale); existing 2.4 settings/backups import unchanged.
- Signed with a NEW key (the 2.4 keystore was not in the source zip):
  CN=ScanON, O=Skyler, C=IR, cert SHA-256 ba88a8fa70e593a32c22f6394d55bcdb73b96ef7e433f68d0d9c030b70b60df6.
  Installing 2.5 over 2.4 needs one uninstall — export a backup in 2.4 first
  (Settings → Backup) and import it in 2.5. The keystore + signing.properties
  live in the project root, so every future build from this line installs
  cleanly over 2.5.
- Unit tests: the 40 JVM tests pass (39/40 in the sandbox's hand-rolled
  runner; filterAndSort needs Gradle's mockable android.jar for
  android.util.LruCache and cannot run under the SDK stub jar).

---

# ScanON 2.4 (versionCode 24) — changelog vs 2.3

ScanON 2.4 is a rebuild from the decompiled 2.3 APK: every class was re-written as idiomatic Kotlin/Compose
(no decompiled code ships), keeping the package `com.skyler.scanon`, the screens, strings (English + Persian),
icons, GeoLite2 databases and Vazirmatn font. Signed with a new key: it cannot update over a 2.3 install
(uninstall 2.3 first; Vault/Groups/History of 2.3 are not carried over unless you copy them).

Unit tests: 40 JVM tests in 4 suites (IP/CIDR parsing, DNS wire format, MMDB + stats + export + filters, stores) — all pass.

---

## Bug fixes (cause → fix)

### Crashes, hangs and races
1. **Scans died when you switched tab, opened History/Settings or the activity was recreated** — all state and coroutines lived in `remember {}`/`rememberCoroutineScope` inside the screen. → Each screen has a ViewModel (`ui/vm/*`); scans run in `viewModelScope`.
2. **Duplicated result rows, "done" counter above total** — every result posted its own coroutine to the UI scope; late ones landed after the final `clear()+addAll(sorted)`. → Results go into a queue drained by one flusher every 300 ms; final flush happens after the workers finish (`IpScanViewModel.flush`).
3. **DNS worker thread spun forever on a compression-pointer loop or a truncated pointer** (`readName` guard counted but nothing advanced). → Pointers must point backwards, max 64 jumps, every read bounds-checked (`DnsEngine.readName`, tests `compressionLoopTerminates`, `truncatedPointerTerminates`).
4. **Corrupt/truncated GeoLite2 data crashed the UI** — no bounds checks in the MMDB decoder, exceptions escaped into composition. → `MmdbException`, bounds checks on every read, recursion depth limit, lookups wrapped in `GeoDb` (test `corruptDatabaseFailsCleanly`).
5. **Uncaught exceptions in launched coroutines crashed the app** (file import `FileNotFound/SecurityException`, save errors, scan errors). → try/catch with snackbar messages in every ViewModel action.
6. **JSON stores lost updates** — unsynchronized read-modify-write; "Retest all" finished checks in parallel and overwrote each other. → `JsonListStore` with a `Mutex` (test `vaultConcurrentUpdatesAreNotLost`).
7. **One bad record wiped the whole Vault/Groups/History** — any parse error returned an empty list and the next save persisted it. → Per-record tolerance; an unreadable file is kept as `<name>.corrupt-<time>` (tests `badRecordDoesNotWipeTheFile`, `corruptFileIsKeptAside`).
8. **Non-atomic writes** — a crash mid-write truncated the file. → temp file + fsync + rename.
9. **Cancel did not stop the scan** — blocking `connect/read` ignore coroutine cancellation, `Thread.sleep(120)` between attempts. → `Canceller` closes every in-flight socket/process; `delay()` instead of `Thread.sleep`.
10. **`ping` processes could hang forever and were never killed** (`waitFor()` without timeout). → Hard deadline, `destroyForcibly()`, registered with the Canceller.
11. **"Scanning…" could stay stuck after a cancelled/failed scan** — `running` was only reset on the success path. → Reset in `finally`.
12. **Sharing a big result list crashed** (`TransactionTooLargeException` from a huge `EXTRA_TEXT`). → Large/CSV/JSON exports are shared as a file via `FileProvider`.

### Main-thread work and resource leaks
13. **Target expansion ran on the main thread** (up to 65 536 hosts *per line*). → Parsing runs on `Dispatchers.Default`, live preview debounced.
14. **Hostnames/ranges hit `InetAddress.getByName` on the main thread** — `NetworkOnMainThreadException` was swallowed, hostnames silently dropped, ranges fell through to a DNS lookup. → Strict IP parser never resolves; hostnames are resolved on IO and failures listed in the notes.
15. **File import leaked the InputStream and read unbounded text** (OOM on a big file). → `use {}` + 4 MiB cap.
16. **`AssetFileDescriptor` leaked in GeoDb; one database failing disabled both.** → Closed with `use {}`; `READY/PARTIAL/FAILED` state.
17. **GeoIP line never appeared on cards composed before the 60 MB databases finished loading** (readiness was a plain volatile). → `GeoDb.state` StateFlow observed by `GeoLine`.

### Parsing and correctness
18. **CIDR /31 produced zero hosts.** → /31 = both addresses, /32 = one, ≤/30 skips network+broadcast (test `cidrSlash31And32`).
19. **Prefix >32 or negative silently mis-parsed** (shift overflow). → Rejected with a note (test `badPrefixesRejected`).
20. **Host budget was per token** — ten /16 lines expanded to 655 360 strings. → One global budget (test `globalBudgetAndSampling`).
21. **Ranges with end < start or last octet >255 silently produced nothing/garbage.** → Validated with a note (test `ranges`).
22. **IPv6 CIDR ORed host bits into an unaligned base and refused anything wider than /112.** → BigInteger expansion with proper masking (test `ipv6Cidr`).
23. **MMDB metadata marker searched forwards** (first match in the last 128 KiB). → Searched backwards: the last occurrence is the real one (spec).
24. **MMDB short int32 was sign-extended.** → Zero-padded per spec.
25. **DNS used a fixed query id (0x5CA0) and accepted any datagram** → spoofed/injected or stray answers were trusted. → Random id, id + QR bit verified, UDP socket connected to the resolver, wrong-id packets skipped (test `rejectsWrongIdAndQueries`).
26. **REFUSED/SERVFAIL answers counted as "working" resolvers.** → Only NOERROR/NXDOMAIN count; others recorded as `rcode_REFUSED` etc. (test `rcodeAndFlags`).
27. **Truncated UDP answers (TC bit) were treated as complete.** → Automatic TCP retry; EDNS0 (1232 B) advertised.
28. **DoH read headers byte-by-byte into a `List<Byte>`, trusted Content-Length (OOM) and failed on chunked bodies** ("response too short"). → Buffered head read, 64 KiB body cap, chunked decoding (test `chunkedDohBody`).
29. **DoT always used the bare IP as TLS name** — verification fails for resolvers without an IP SAN. → Uses the resolver's published DoT/DoH name when known (`DnsCatalog.DOH_HOSTS`).
30. **Domain scanner HTTPS-tested hijack answers** (10.10.34.x block pages) as if they were the domain's servers. → Bogon answers are excluded and listed separately.
31. **ICMP counted N pings as one test**, so success rate/loss meant something different than for TCP/HTTPS. → N pings = N tests, real packet loss (test `icmpAggregateUsesPingCounts`).
32. **IPv6 ping used `ping`** (fails on devices that need `ping6`). → `ping6` when present.
33. **HTTP status regex required `HTTP/x.y`.** → Also accepts `HTTP/2 200` (test `readHeadParsesStatus`).
34. **Vault deduped by IP only** — the same IP verified with another SNI/port could not be saved. → Key = IP + SNI + port (test `vaultDedupesByIpSniAndPort`).
35. **Vault retest = one attempt on hard-coded port 443.** → Entry's own port, 3 attempts, median stored.
36. **MTU probe logic** reported "unsupported" when a size simply got no reply. → Walks 1472→548 and only reports unsupported when ping rejects `-M do`.
37. **Snapshot showed "Wi-Fi" for a VPN running over Wi-Fi.** → VPN transport checked first.
38. **Numbers formatted with the default locale** — Persian digits/commas inside exports and copied reports. → `Fmt` always uses `Locale.US` (test `formattingIgnoresLocale`).
39. **SAF save opened the file with mode "w"** (no truncate on some providers → old bytes left at the end). → Mode `"wt"`, written on IO, errors reported.

### UI / platform
40. **Release APK was built `debuggable=true`.** → Release is non-debuggable, R8-minified and resource-shrunk.
41. **Global density/fontScale override (×0.95 / ×0.82) ignored the user's accessibility font size.** → Follows the system; the old look is an opt-in "Compact layout".
42. **System Back closed the app from History/Groups/Guide/Settings.** → `BackHandler` closes the overlay (and returns to the first tab).
43. **Tab/overlay reset to the first tab on recreate** (plain `remember`). → `rememberSaveable`.
44. **Persian "About" said "اسکن‌آن ۲."** (version number cut). → "۲.۴".
45. **Preset chips showed raw ids ("very_fast") in Persian; Domain scanner phases were hard-coded English.** → String resources in both languages.
46. **Back arrow not mirrored in RTL.** → Directional icons mirror in Persian.

---

## Improvements (68)

### Architecture & code quality
1. ViewModel per screen: `IpScanViewModel`, `DnsScanViewModel`, `DomainScanViewModel`, `DiagViewModel`, `VaultViewModel`, `HistoryViewModel`, `GroupsViewModel`, `SettingsViewModel`.
2. Immutable state exposed as `StateFlow`; `TargetReport`/`DnsReport`/`TestResult` are immutable data classes (2.3 mutated reports from worker threads).
3. Target text kept in `SavedStateHandle` (survives process death).
4. DI-light `AppContainer` (one instance of settings, stores, notifier, keep-alive) in `ScanonApplication`.
5. Engine split into focused units: `IpUtils`, `TargetParser`, `Stats`, `Exporters`, `Canceller`, `DnsCatalog`, `NetEngine`, `DnsEngine`, `MmdbReader`, `GeoDb`, `DiagEngine`.
6. 40 JVM unit tests (JUnit 5): CIDR/ranges/IPv6/bogons/host normalisation, DNS wire build/parse/loops/chunked DoH, real GeoLite2 lookups + corrupt DB, stats/aggregation/score, CSV/JSON export, sort/filter, store concurrency/corruption/undo.
7. Release build with R8 + resource shrinking and explicit ViewModel keep rules.
8. `build.sh` for reproducible builds on arm64 hosts (native aapt2 override).

### Performance
9. Fixed worker pool fed by a bounded `Channel` on `Dispatchers.IO.limitedParallelism(workers)` — no 65k Deferreds up front.
10. Batched UI updates every 300 ms instead of one main-thread coroutine per result.
11. Sorting/filtering computed off the main thread (`combine` + `flowOn(Default)`), not on every recomposition.
12. Adaptive timeout: after 20 successes the per-attempt timeout shrinks to ~3× observed p95 (option).
13. Fail-fast: a target is dropped after 2 consecutive failures with no success (option, default on).
14. Diagnostics: all 8 checks run concurrently, results appear as they finish.
15. DNS scanner probes up to 32 resolvers in parallel with a bound (2.3: unbounded).
16. GeoIP LRU cache (2048 entries) for result cards and search.
17. MMDB IPv4 start node computed once instead of walking 96 bits per lookup.
18. Shared `SSLSocketFactory`, `TCP_NODELAY` on test sockets.
19. Live target-count preview, debounced 250 ms and computed in the background.
20. Reduce-motion setting + automatic static background when the system animator scale is 0 (battery).
21. Stable `key`s for every lazy list item.

### Scanner features
22. Multiple ports per scan: list and ranges (`443, 2053, 8443-8445`, max 64).
23. New **WS** method: real RFC 6455 upgrade over TLS with SNI; only HTTP 101 counts.
24. TLS version, cipher suite and certificate days-left on HTTPS/WS results.
25. Configurable HTTP path for HTTP/HTTPS/WS tests.
26. Packet loss % on every result (real ICMP loss for ping).
27. Download speed test (Mbit/s) through the top 5 Cloudflare IPs via `speed.cloudflare.com/__down` with SNI pinned to each IP.
28. Random **IPv6** Cloudflare addresses (published v6 ranges) next to the IPv4 picker.
29. Random sampling of ranges larger than the host budget instead of truncating to the first addresses.
30. Configurable host budget per scan (1–262 144).
31. Hostnames accepted as targets (resolved in the background).
32. `#` comments in target lists and a "Clean up" button (canonicalise + de-duplicate).
33. "Rescan failed" re-tests only the targets that did not work and merges results.
34. Deep verify merges by ip+port+method and keeps measured speeds.
35. Sort by score, latency, jitter, success, loss, IP or speed — reversible.
36. Search results by IP, country, city, ASN or organisation.
37. Latency-cap filter chips (≤150/300/600 ms) and working-only filter.
38. CSV export (with spreadsheet formula-injection protection).
39. JSON export (samples, failures, TLS details, GeoIP).
40. Export bar: copy IP list vs copy full report; working-only or all; TXT/CSV/JSON; share; save file.
41. "Share to ScanON": text shared from any app is added to the IP scanner targets.
42. DNS: EDNS0, automatic TCP retry on truncation, HTTPS (65) and CAA record types, SOA serial.
43. DNS: IPv6 resolver preset (Cloudflare/Google/Quad9 v6).
44. DNS: DNSSEC AD flag and loss % shown per resolver.
45. Hijack detection covers CGNAT, benchmark, documentation, multicast, ULA and IPv4-mapped space.
46. Domain scanner: optional AAAA testing; URL/port/user-info/IDN input normalisation.
47. Diagnostics: IPv6 address, metered flag, link bandwidth estimate, TLS version in the HTTPS check, TLS/HTTP version in the Cloudflare trace, one-tap copy of the report; saved to History.

### UX
48. Progress card with %, items/s and ETA on every scanner.
49. Theme: System / Dark / Light.
50. Language: System / English / Persian, registered with Android 13+ per-app language.
51. RTL polish: IPs, ports, hostnames and numbers always laid out LTR; directional icons mirrored.
52. All new UI translated to Persian; reliability words, presets and sort keys localised.
53. Empty states on every screen and a "no matches" state for filters.
54. Snackbar feedback for copies, saves, errors and scan summaries.
55. Haptic feedback on copy/save and when a scan finishes (toggle).
56. Inline validation (ports, timeout, workers, attempts, host budget, SNI, DNS servers, domain); Start disabled until valid.
57. Keep screen on while a scan runs (setting).
58. Foreground keep-alive service with a progress notification, plus a "scan finished" notification when in background (`POST_NOTIFICATIONS` asked once).
59. Running-scan dot on the bottom-navigation tabs.
60. Expandable result cards (TCP/TLS/TTFB/samples/failures on demand).
61. IP scanner settings remembered between launches (2.3 only remembered the DNS form).
62. Collapsible "Advanced options" section.

### Data
63. History: search, filter by type, clear-all with confirmation, undo delete.
64. Vault: edit label, 4 sort orders, trend sparkline of the last 8 retests, per-entry port, undo removal.
65. Vault "Retest all" runs 8 in parallel, cancellable, with a summary.
66. Groups: rename, item de-duplication, case-insensitive name matching.
67. One-file backup/restore of Vault, Groups, History and settings (merge on restore).
68. Android backup rules: Vault, Groups and settings in cloud backup; everything on device transfer.
