"""Generates the ORBIS technical documentation as a .docx report."""

import os
from docx import Document
from docx.enum.section import WD_SECTION
from docx.enum.text import WD_ALIGN_PARAGRAPH, WD_BREAK
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.shared import Pt, RGBColor, Inches

OUT_DIR = r"C:\Users\MANTHAN\AndroidStudioProjects\ORBISOptimizedResponsibleBrowsingInterventionSystem\docs"
OUT = os.path.join(OUT_DIR, "ORBIS_Technical_Documentation.docx")

ACCENT = RGBColor(0xB4, 0x4A, 0x1E)
MUTED = RGBColor(0x55, 0x55, 0x55)

doc = Document()

# ---------------------------------------------------------------- base styles
normal = doc.styles["Normal"]
normal.font.name = "Calibri"
normal.font.size = Pt(10.5)
normal.paragraph_format.space_after = Pt(6)
normal.paragraph_format.line_spacing = 1.15

for name, size in (("Heading 1", 18), ("Heading 2", 14), ("Heading 3", 12)):
    st = doc.styles[name]
    st.font.name = "Calibri"
    st.font.size = Pt(size)
    st.font.color.rgb = ACCENT
    st.font.bold = True


def h(text, level=1, page_break=False):
    if page_break:
        doc.add_page_break()
    return doc.add_heading(text, level=level)


def p(text="", bold=False, italic=False, size=None, color=None, align=None):
    par = doc.add_paragraph()
    run = par.add_run(text)
    run.bold = bold
    run.italic = italic
    if size:
        run.font.size = Pt(size)
    if color:
        run.font.color.rgb = color
    if align:
        par.alignment = align
    return par


def bullets(items, style="List Bullet"):
    for item in items:
        par = doc.add_paragraph(style=style)
        if isinstance(item, tuple):
            lead, rest = item
            r = par.add_run(lead)
            r.bold = True
            par.add_run(rest)
        else:
            par.add_run(item)


def numbered(items):
    bullets(items, style="List Number")


def code(text):
    par = doc.add_paragraph()
    par.paragraph_format.left_indent = Inches(0.25)
    par.paragraph_format.space_after = Pt(8)
    run = par.add_run(text)
    run.font.name = "Consolas"
    run.font.size = Pt(9)
    run.font.color.rgb = RGBColor(0x22, 0x22, 0x22)
    return par


def table(headers, rows, widths=None):
    t = doc.add_table(rows=1, cols=len(headers))
    t.style = "Table Grid"
    hdr = t.rows[0].cells
    for i, head in enumerate(headers):
        hdr[i].text = ""
        run = hdr[i].paragraphs[0].add_run(head)
        run.bold = True
        run.font.size = Pt(9.5)
    for row in rows:
        cells = t.add_row().cells
        for i, val in enumerate(row):
            cells[i].text = ""
            run = cells[i].paragraphs[0].add_run(str(val))
            run.font.size = Pt(9.5)
    if widths:
        for r in t.rows:
            for i, w in enumerate(widths):
                r.cells[i].width = Inches(w)
    doc.add_paragraph()
    return t


def caption(text):
    par = doc.add_paragraph()
    run = par.add_run(text)
    run.italic = True
    run.font.size = Pt(9)
    run.font.color.rgb = MUTED
    return par


# ------------------------------------------------------------------ title page
title = doc.add_paragraph()
title.alignment = WD_ALIGN_PARAGRAPH.CENTER
for _ in range(5):
    doc.add_paragraph()

t1 = doc.add_paragraph()
t1.alignment = WD_ALIGN_PARAGRAPH.CENTER
r = t1.add_run("ORBIS")
r.bold = True
r.font.size = Pt(48)
r.font.color.rgb = ACCENT

t2 = doc.add_paragraph()
t2.alignment = WD_ALIGN_PARAGRAPH.CENTER
r = t2.add_run("Optimized Responsible Browsing & Intervention System")
r.font.size = Pt(15)

t3 = doc.add_paragraph()
t3.alignment = WD_ALIGN_PARAGRAPH.CENTER
r = t3.add_run("Technical Documentation and Project Report")
r.font.size = Pt(12)
r.italic = True
r.font.color.rgb = MUTED

for _ in range(3):
    doc.add_paragraph()

meta = doc.add_table(rows=0, cols=2)
meta.style = "Table Grid"
for k, v in [
    ("Platform", "Android (Kotlin, Jetpack Compose)"),
    ("Package", "com.orbis.app"),
    ("Minimum SDK", "26 (Android 8.0)"),
    ("Target / Compile SDK", "36"),
    ("Build system", "Gradle 9.4.1, AGP 9.2.1, Kotlin 2.2.10"),
    ("Verification device", "OnePlus CPH2585, Android 16 (API 36)"),
    ("Source size", "~3,350 lines main, ~1,190 lines test"),
    ("Automated tests", "96 unit, 22 instrumented"),
    ("Document date", "29 July 2026"),
]:
    row = meta.add_row().cells
    run = row[0].paragraphs[0].add_run(k)
    run.bold = True
    run.font.size = Pt(10)
    row[1].paragraphs[0].add_run(v).font.size = Pt(10)
    row[0].width = Inches(2.2)
    row[1].width = Inches(4.0)

doc.add_page_break()

# ------------------------------------------------------------------------ TOC
h("Table of Contents", 1)
toc_par = doc.add_paragraph()
fld = OxmlElement("w:fldSimple")
fld.set(qn("w:instr"), 'TOC \\o "1-3" \\h \\z \\u')
inner = OxmlElement("w:r")
inner_t = OxmlElement("w:t")
inner_t.text = "Right-click and choose \u201cUpdate Field\u201d to populate this table of contents."
inner.append(inner_t)
fld.append(inner)
toc_par._p.append(fld)

# =========================================================== 1. EXEC SUMMARY
h("1. Executive Summary", 1, page_break=True)
p(
    "ORBIS is a personal digital-wellbeing application for Android. It measures which "
    "social applications a user actually overuses, applies proportional network-level "
    "friction to the specific screens within those applications that drive compulsive "
    "scrolling, and redirects the reclaimed attention toward a positively framed dashboard "
    "and a small real-world good-deed challenge."
)
p(
    "The distinguishing characteristic of the system is granularity. Conventional blockers "
    "operate at the level of an entire application: they either permit Instagram or they do "
    "not. ORBIS instead distinguishes between screens inside the same application. Instagram "
    "Reels is slowed; Instagram Stories, direct messages and the main feed are left entirely "
    "untouched. YouTube Shorts is slowed; ordinary long-form YouTube video is not. Snapchat "
    "Spotlight is slowed; Snapchat conversations are not."
)
p(
    "This granularity is not achievable by network filtering alone, and establishing that "
    "fact empirically was a significant part of the work. Section 5 documents the "
    "investigation; Section 6 documents the hybrid architecture it forced."
)
p("Delivered scope:", bold=True)
bullets([
    ("Per-application usage measurement ", "derived from the Android usage-event stream."),
    ("Per-surface detection ", "of Reels, Shorts, Spotlight, and short-video URLs in a browser."),
    ("Adaptive network throttling ", "applied only while such a surface is on screen, with delay scaled to the user's own daily usage."),
    ("A reclaimed-time dashboard ", "measured against the user's own rolling baseline, with a seven-day trend."),
    ("A good-deed challenge ", "comprising a scheduled prompt, in-application photo capture, a local log and a streak."),
])
p(
    "All processing and storage is on-device. There is no server component, no cloud storage "
    "and no synchronisation. Nothing the application observes leaves the handset."
)

# =========================================================== 2. PROBLEM
h("2. Problem Statement", 1, page_break=True)

h("2.1 Context", 2)
p(
    "Short-form video feeds — Instagram Reels, YouTube Shorts, Snapchat Spotlight — are "
    "engineered for continuous, low-friction consumption. Each item is short, playback is "
    "automatic, and the next item arrives without a decision being required. The absence of "
    "a natural stopping point is the defining property: a user does not choose to continue so "
    "much as fail to choose to stop."
)
p(
    "The consequence is a characteristic pattern of use in which elapsed time substantially "
    "exceeds intended time, and in which the user is frequently unable to estimate afterwards "
    "how long was spent."
)

h("2.2 Why existing approaches are insufficient", 2)
p("Three families of intervention are already common, and each has a structural weakness.", )
table(
    ["Approach", "Mechanism", "Structural weakness"],
    [
        ["Application blockers",
         "Deny launch of an entire application.",
         "All-or-nothing. Blocking Instagram also blocks messaging a family member. Users disable the blocker rather than lose the useful function."],
        ["Screen-time dashboards",
         "Report usage after the fact.",
         "Purely retrospective. Information is delivered after the behaviour, when it can no longer influence it."],
        ["Timers and hard limits",
         "Cut access at a threshold.",
         "Adversarial and abrupt. Produces an incentive to circumvent, and offers nothing in place of the removed activity."],
    ],
    widths=[1.3, 2.0, 3.3],
)

h("2.3 The problem ORBIS addresses", 2)
p(
    "The problem is therefore not \u201chow to prevent access to an application\u201d but the "
    "narrower and harder question:"
)
p(
    "How can friction be applied to precisely the screens that drive compulsive consumption, "
    "leaving every other function of the same application at full speed, without relying on "
    "the user's willpower at the moment of use?",
    italic=True,
)
p("This decomposes into three sub-problems, each addressed by a subsystem of the design:")
numbered([
    "Measurement. Determine, reliably and without user effort, how much time is actually spent in each targeted application.",
    "Discrimination. Determine which screen within an application is currently displayed, so that Reels can be distinguished from Stories.",
    "Intervention. Apply friction that is perceptible enough to prompt disengagement, yet mild enough that the application does not appear broken — because an application that appears broken is uninstalled, taking the intervention with it.",
])

# =========================================================== 3. OBJECTIVES
h("3. Objectives, Scope and Invariants", 1, page_break=True)

h("3.1 Objectives", 2)
numbered([
    "Measure per-application foreground time accurately, on-device, with no manual logging.",
    "Detect the specific short-form surface currently displayed, distinguishing it from other screens of the same application.",
    "Slow network traffic for those surfaces only, in proportion to the user's own measured usage.",
    "Present the outcome in encouraging terms, framed as time regained rather than time lost.",
    "Offer a constructive alternative activity, recorded locally, to occupy the reclaimed attention.",
    "Guarantee that communication tools are never impaired.",
])

h("3.2 In scope", 2)
bullets([
    "Instagram, YouTube and Snapchat as throttling targets.",
    "Browsers, for short-video URLs opened outside the native applications.",
    "WhatsApp, measured for reporting purposes but never throttled.",
    "Single-user, single-device operation, entirely offline.",
])

h("3.3 Out of scope", 2)
bullets([
    "Any server component, account system, or cross-device synchronisation.",
    "Content classification or inspection of media itself.",
    "Parental-control or third-party enforcement features.",
    "Publication to the Google Play Store (see Section 14 on accessibility-service policy).",
])

h("3.4 Hard invariants", 2)
p(
    "The following constraints were fixed at design time and treated as non-negotiable "
    "throughout implementation. Several are enforced by automated tests that exist "
    "specifically to prevent regression."
)
table(
    ["#", "Invariant", "How it is enforced"],
    [
        ["I1", "WhatsApp is never throttled under any circumstance.",
         "Excluded from the VPN allow-list, so the operating system never routes its packets to the application. Additionally asserted by unit tests."],
        ["I2", "Only short-form surfaces are throttled; Stories, feeds, DMs, chats and long-form video are not.",
         "Package-scoped detection rules; regression tests cover the inverted-naming trap described in Section 11.4."],
        ["I3", "Detection fails safe. An unrecognised screen is never throttled.",
         "SurfaceDetector returns NORMAL for all unmatched input; asserted by test."],
        ["I4", "All data remains on the device.",
         "No networking code other than the packet relay; no analytics; no INTERNET use beyond forwarding."],
        ["I5", "Dashboard language is encouraging and never admonishing.",
         "Reclaimed time is clamped at zero, so a heavy day reads as zero rather than a deficit."],
        ["I6", "The TUN interface is released on every exit path.",
         "A single shutdown() invoked from stop, revoke, destroy and failure paths."],
        ["I7", "Throttle delay remains modest.",
         "ThrottleEngine bounded to 120–400 ms; asserted by a property-style test across all inputs."],
    ],
    widths=[0.4, 2.9, 3.3],
)

# =========================================================== 4. REQUIREMENTS
h("4. Requirements", 1, page_break=True)

h("4.1 Functional requirements", 2)
table(
    ["ID", "Requirement", "Status"],
    [
        ["FR1", "Record daily foreground time per targeted application.", "Implemented"],
        ["FR2", "Guide the user through granting usage access, which cannot be requested at runtime.", "Implemented"],
        ["FR3", "Identify the on-screen surface within a targeted application.", "Implemented"],
        ["FR4", "Detect short-video URLs displayed in a browser.", "Implemented"],
        ["FR5", "Establish a VPN tunnel only while a throttled surface is displayed.", "Implemented"],
        ["FR6", "Relay traffic with an added delay proportional to daily usage.", "Implemented"],
        ["FR7", "Release the tunnel promptly once the surface is left.", "Implemented (3 s grace)"],
        ["FR8", "Report reclaimed time against the user's own baseline.", "Implemented"],
        ["FR9", "Display a seven-day usage trend.", "Implemented"],
        ["FR10", "Prompt periodically for a good deed.", "Implemented"],
        ["FR11", "Capture a photograph in-application and store it privately.", "Implemented"],
        ["FR12", "Maintain a streak of consecutive days with a logged deed.", "Implemented"],
        ["FR13", "Persist the automatic-throttling preference across restarts.", "Implemented"],
    ],
    widths=[0.5, 4.4, 1.4],
)

h("4.2 Non-functional requirements", 2)
table(
    ["ID", "Requirement", "Approach taken"],
    [
        ["NFR1", "Detection must not degrade device responsiveness.",
         "Accessibility callbacks are rate-limited to 250 ms and the node traversal is capped at 600 nodes."],
        ["NFR2", "Throttling must not collapse throughput to the point of appearing broken.",
         "Delay is applied by scheduling packets on an executor rather than sleeping in the read loop."],
        ["NFR3", "A failure in the tunnel must not deprive the user of connectivity.",
         "The tunnel is scoped to four application groups and runs only while a throttled surface is displayed."],
        ["NFR4", "Business logic must be testable without a device.",
         "All arithmetic and decision logic is pure Kotlin, free of Android types; 96 unit tests run on the JVM."],
        ["NFR5", "Observation must be minimised to what is necessary.",
         "The accessibility service is restricted by package list, and text is read only for browsers."],
        ["NFR6", "Persisted user data must survive upgrades.",
         "A real Room migration is used; destructive fallback is deliberately not configured."],
    ],
    widths=[0.5, 2.6, 3.2],
)

# =========================================================== 5. FEASIBILITY
h("5. Feasibility Investigation", 1, page_break=True)
p(
    "The original design assumed that a local VPN service could distinguish Reels traffic "
    "from other Instagram traffic by inspecting destinations. This assumption was tested "
    "before implementation and found to be false. The investigation and its consequences are "
    "recorded here because they determined the architecture."
)

h("5.1 Why network filtering cannot discriminate by surface", 2)
numbered([
    "A VpnService observes IP packets. For HTTPS traffic it can determine the destination address and, at best, the TLS Server Name Indication hostname. The URL path is inside the encrypted payload and is never visible.",
    "The native applications do not request path-bearing URLs in any case. YouTube requests an internal API endpoint and streams media from a content-delivery network; Shorts and ordinary videos use the same hosts.",
    "Instagram Reels and Instagram Stories both stream from the same content-delivery hosts. No hostname or address rule can separate them.",
    "TLS interception would not resolve this. The applications employ certificate pinning, and interception would require installing a root certificate authority on the device — an unacceptable security posture for a wellbeing tool.",
])
p(
    "The conclusion is categorical rather than a matter of engineering effort: surface-level "
    "discrimination is not obtainable at the network layer.",
    bold=True,
)

h("5.2 The accessibility survey", 2)
p(
    "The remaining mechanism capable of distinguishing screens is direct observation of the "
    "on-screen view hierarchy. Rather than assume this would work, a survey was carried out "
    "on the target device against the live applications, capturing the accessibility tree for "
    "each surface of interest."
)
table(
    ["Surface", "Detectable", "Distinguishing signal"],
    [
        ["Instagram Reels", "Yes", "clips_viewer_view_pager, root_clips_layout, clips_video_container"],
        ["Instagram Stories", "Yes, distinctly", "reel_viewer_root, reel_viewer_progress_bar, reel_viewer_timestamp"],
        ["YouTube Shorts", "Yes", "reel_watch_fragment_root, reel_watch_player, reel_recycler"],
        ["Snapchat Spotlight", "Yes", "spotlight_container"],
        ["Short video in browser", "Yes", "Address-bar text is exposed, e.g. m.youtube.com/shorts/<id>"],
    ],
    widths=[1.5, 1.2, 3.9],
)
caption("Table 5.1 — Surface detection signals, measured on OnePlus CPH2585 running Android 16.")

h("5.3 Findings that changed the design", 2)
bullets([
    ("Instagram's internal naming is inverted with respect to intuition. ",
     "In Instagram, the prefix reel_ denotes Stories; Reels are internally named clips_. A rule matching \u201creel\u201d would therefore have throttled Stories — precisely the surface required to remain unaffected."),
    ("The same prefix means opposite things across applications. ",
     "In YouTube, reel_ denotes Shorts, which must be throttled. Detection must therefore be scoped per package; a cross-application substring match is incorrect by construction."),
    ("Browser coverage is necessary, not optional. ",
     "On the test device the URL youtube.com/shorts opened in Microsoft Edge rather than the YouTube application, so a design covering only native applications would have missed the case entirely."),
    ("Class and fragment names are unusable. ",
     "Instagram and YouTube are obfuscated by R8; only resource identifiers and accessibility labels survive."),
])

h("5.4 Architectural consequence", 2)
p(
    "The survey established that the required behaviour is achievable, but only by combining "
    "two mechanisms: an accessibility service to determine context, and a VPN service to apply "
    "friction. Neither is sufficient alone. The accessibility service cannot slow traffic; the "
    "VPN service cannot tell which screen is displayed. Section 6 describes the resulting "
    "hybrid."
)

# =========================================================== 6. ARCHITECTURE
h("6. System Architecture", 1, page_break=True)

h("6.1 Overview", 2)
code(
    "UsageStatsManager\n"
    "        |\n"
    "        v\n"
    "  UsageRepository ---> UsageLog (Room)\n"
    "        |\n"
    "        v\n"
    "  UsageProfileHolder ------------------+\n"
    "                                       |\n"
    "  OrbisAccessibilityService            |\n"
    "        |                              |\n"
    "        v                              v\n"
    "  SurfaceDetector (pure) ------> ThrottleEngine (pure)\n"
    "        |                              |\n"
    "        v                              v\n"
    "  SurfaceMonitor                 OrbisVpnService\n"
    "        |                        (TUN, UDP relay, delay)\n"
    "        v\n"
    "  Compose UI  <--- DashboardViewModel <--- ReclaimedTime (pure)\n"
    "\n"
    "GoodDeedScheduler (WorkManager) -> Notification -> DeedPhotoCapture\n"
    "        -> GoodDeedRepository -> GoodDeedEntry (Room) -> Streak"
)
caption("Figure 6.1 — Component relationships and principal data flow.")

h("6.2 Control flow of an intervention", 2)
numbered([
    "The user opens Instagram and navigates to Reels.",
    "The accessibility service receives a window-content event scoped to Instagram.",
    "It traverses the visible node tree, collecting resource identifiers.",
    "SurfaceDetector, given the package and identifiers, returns REELS.",
    "The gate observes a throttled surface and consults ThrottleEngine, which scales a delay from the user's measured daily Instagram usage.",
    "OrbisVpnService establishes a TUN interface restricted to the target applications and begins relaying UDP with the computed delay.",
    "Video packets are forwarded after a short delay; playback becomes perceptibly less fluid.",
    "The user leaves Reels. After a three-second grace period the tunnel is torn down and all traffic returns to normal.",
])

h("6.3 Layering and the testability constraint", 2)
p(
    "A constraint was imposed at the outset: all decision-making and arithmetic must be pure "
    "Kotlin, free of Android framework types, so that it is testable on the JVM. Android "
    "classes are confined to thin adapters that gather inputs and apply outputs."
)
table(
    ["Layer", "Contents", "Tested by"],
    [
        ["Pure logic",
         "SurfaceDetector, ThrottleEngine, ForegroundTimeCalculator, UsageProfile, ReclaimedTime, GoodDeedStreak, Ipv4, Ipv6, DurationFormatter",
         "JVM unit tests (96)"],
        ["Adapters",
         "OrbisAccessibilityService, UsageStatsSource, OrbisVpnService, DeedPhotoCapture, UsageAccess, AccessibilityAccess",
         "Instrumented tests and on-device verification"],
        ["Persistence",
         "Room entities, DAOs, repositories, DataStore",
         "Instrumented tests against in-memory databases"],
        ["Presentation",
         "Compose screens and view models",
         "Compose UI tests and device inspection"],
    ],
    widths=[1.1, 3.3, 2.2],
)
p(
    "The value of this constraint is concrete. The packet checksum arithmetic, the streak "
    "boundary rules and the surface-detection inversion are all exercised without a device, "
    "and each is a defect class that is painful to diagnose at runtime."
)

h("6.4 Package structure", 2)
table(
    ["Package", "Responsibility", "Lines"],
    [
        ["com.orbis.app.usage", "Measurement: target definitions, event pairing, ranking, formatting.", "287"],
        ["com.orbis.app.surface", "Context: accessibility service, detection rules, state publication.", "343"],
        ["com.orbis.app.throttle", "Policy: delay computation and the persisted preference.", "119"],
        ["com.orbis.app.vpn", "Enforcement: packet parsing and construction, TUN relay.", "662"],
        ["com.orbis.app.data", "Persistence: Room entities, DAOs, migration, repositories.", "216"],
        ["com.orbis.app.dashboard", "Reporting: reclaimed-time computation.", "69"],
        ["com.orbis.app.deed", "Challenge: streak, scheduling, capture, repository.", "244"],
        ["com.orbis.app.ui", "Presentation: Compose screens, view models, theme.", "1,260"],
    ],
    widths=[1.7, 3.9, 0.7],
)

# =========================================================== 7. DETAILED DESIGN
h("7. Detailed Design", 1, page_break=True)

h("7.1 Usage measurement", 2)
p(
    "Foreground time is computed from the event stream rather than from the framework's "
    "aggregate totals. The aggregate API is coarse and occasionally stale; more importantly, "
    "computing from events places the arithmetic under test."
)
p(
    "UsageStatsSource flattens the framework's cursor-style stream into plain records. "
    "ForegroundTimeCalculator then pairs resumed and paused events per package. Three cases "
    "are easy to mishandle and are covered explicitly by tests:"
)
bullets([
    ("A session still open at the window boundary. ", "The application is in use at the moment of measurement; time is credited to the end of the window."),
    ("A pause with no matching resume. ", "The application was already foreground when the window opened; time is credited from the window start."),
    ("A repeated resume with no intervening pause. ", "The earliest is retained, since the application never actually left the foreground."),
])
p(
    "Totals are written to Room as one row per application per day. A unique index on "
    "(app, date) makes repeated refreshes idempotent; without it, conflict resolution has "
    "only the generated identifier to match on and every refresh would append duplicates."
)

h("7.2 Surface detection", 2)
p("Two rules govern detection, and both are load-bearing.")
p("Rule 1 — Scope by package before matching anything.", bold=True)
p(
    "The identifier prefix reel_ denotes Stories in Instagram and Shorts in YouTube. A "
    "cross-application match on that substring produces exactly the wrong behaviour in one of "
    "the two applications."
)
p("Rule 2 — Match identifiers exactly, never by substring.", bold=True)
p(
    "Snapchat's navigation bar carries ngs_spotlight_icon_container on every screen. A "
    "containment test for \u201cspotlight\u201d would report Spotlight throughout the "
    "application."
)
p(
    "A third rule emerged from device testing and is documented in Section 11.5: only nodes "
    "reported as visible to the user may be counted, because Instagram retains the Reels view "
    "hierarchy in memory while Stories is displayed."
)

h("7.3 Throttle policy", 2)
p(
    "ThrottleEngine maps a surface and a usage profile onto a delay. The delay begins at a "
    "base of 120 ms and rises linearly to a ceiling of 400 ms as daily usage of the owning "
    "application approaches a heavy-use threshold of 45 minutes. A property-style test asserts "
    "that the result lies within those bounds for every combination of surface and profile, "
    "including implausible inputs."
)
p(
    "The ceiling is a deliberate product decision. A delay large enough to make the "
    "application appear broken invites uninstallation, which removes the intervention "
    "entirely; the objective is friction, not failure."
)

h("7.4 The VPN relay", 2)
p("The relay is deliberately the smallest component that can do the job, for three reasons.")
bullets([
    ("Scope is restricted at the operating-system level. ",
     "addAllowedApplication limits the tunnel to the three targets plus browsers. WhatsApp packets never enter the process at all — an enforcement stronger than any conditional in application code."),
    ("The tunnel exists only during an intervention. ",
     "It is established when a throttled surface appears and released three seconds after it is left, so ordinary use is never routed through it."),
    ("Only UDP is relayed. ",
     "Measurement showed QUIC carries approximately 85 % of Reels traffic. TCP is counted and dropped, which is tolerable only because the tunnel is short-lived and surface-gated."),
])
p(
    "Both IPv4 and IPv6 are handled. Handling only IPv4 is not a partial implementation but a "
    "broken one: the tunnel captures IPv6 as well, so anything unhandled is discarded rather "
    "than merely left un-throttled. Section 11.6 documents the failure this caused."
)
p(
    "The delay is applied by scheduling each packet on an executor. Sleeping in the read loop "
    "would serialise every flow behind one packet; at 120 ms that is roughly eight packets per "
    "second, which would itself constitute a denial of service."
)

h("7.5 Reclaimed-time reporting", 2)
p(
    "There is no external standard for how much short-form video a person ought to consume. "
    "The baseline is therefore the user's own rolling average over the preceding fourteen "
    "recorded days, and reclaimed time is defined as the shortfall against that average."
)
p("Two rules prevent the measure from flattering the user:")
bullets([
    ("Days without records are ignored, never treated as zero. ",
     "A day before installation is missing data, not an idle day; counting it as zero would depress the baseline and manufacture reclaimed time."),
    ("Below three recorded days no baseline is offered at all. ",
     "The interface states that it is still learning rather than presenting a number derived from one day of history."),
])
p(
    "The result is clamped at zero, so a heavier-than-usual day is reported as no time "
    "reclaimed rather than as a deficit, in keeping with invariant I5."
)

h("7.6 The good-deed challenge", 2)
p(
    "A WorkManager periodic task raises a notification, which opens the application directly "
    "on the challenge tab. The worker suppresses the prompt on days when a deed has already "
    "been logged."
)
p(
    "Photographs are captured through CameraX and written to application-private storage. "
    "This avoids storage permissions entirely and keeps the images out of the device gallery. "
    "A cancelled capture deletes its file rather than leaving orphaned images. A photograph is "
    "optional; the deed is the point, not the evidence."
)
p("Streak rules are defined in pure code and tested:")
bullets([
    "A deed recorded yesterday sustains the streak, so it is not declared broken immediately after midnight before the user has had an opportunity to act.",
    "Only the current run counts; a longer run earlier in the history does not extend it.",
    "Multiple deeds on one day count once.",
])

# =========================================================== 8. DATA MODEL
h("8. Data Model", 1, page_break=True)

h("8.1 Entities", 2)
table(
    ["Entity", "Fields", "Purpose"],
    [
        ["UsageLog",
         "id, app, date (ISO-8601), durationMillis",
         "One row per application per day. Unique index on (app, date) makes refresh idempotent."],
        ["GoodDeedEntry",
         "id, timestampMillis, photoPath, note, completed",
         "One row per logged deed. photoPath references application-private storage."],
    ],
    widths=[1.1, 2.4, 2.8],
)

h("8.2 Schema evolution", 2)
p(
    "The database is at version 2. Version 1 contained only UsageLog; MIGRATION_1_2 introduces "
    "the good_deed table."
)
code(
    "CREATE TABLE IF NOT EXISTS `good_deed` (\n"
    "  `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,\n"
    "  `timestampMillis` INTEGER NOT NULL,\n"
    "  `photoPath` TEXT,\n"
    "  `note` TEXT NOT NULL,\n"
    "  `completed` INTEGER NOT NULL)"
)
p(
    "Destructive fallback migration is deliberately not configured. By the time this migration "
    "runs, the database holds accumulated usage history, and that history is precisely what "
    "the dashboard baseline is computed from. Discarding it would silently destroy real user "
    "data and reset the reclaimed-time report to its initial state.",
    bold=True,
)

h("8.3 Data not persisted", 2)
p(
    "A ThrottleRule entity appears in the original design but has not been implemented. "
    "Throttle intensity is derived from usage at runtime, so there is nothing to store until "
    "rules become user-editable. Recording this explicitly avoids the impression of an "
    "oversight."
)

# =========================================================== 9. PERMISSIONS
h("9. Permissions and Privacy", 1, page_break=True)

h("9.1 Permissions requested", 2)
table(
    ["Permission", "Type", "Justification"],
    [
        ["PACKAGE_USAGE_STATS", "Special",
         "Reads per-application foreground time. Cannot be requested at runtime; the user grants it in system settings via a deep link."],
        ["BIND_ACCESSIBILITY_SERVICE", "Service binding",
         "Determines which screen is displayed. Restricted by package list to ten applications."],
        ["VPN consent", "System dialog",
         "Not a manifest permission. Raised by VpnService.prepare() and cannot be automated."],
        ["INTERNET", "Normal",
         "Required by the relay to open its own forwarding sockets. See Section 11.6."],
        ["FOREGROUND_SERVICE, FOREGROUND_SERVICE_SPECIAL_USE", "Normal",
         "Keeps the tunnel alive while the application is backgrounded, which is when it is needed."],
        ["POST_NOTIFICATIONS", "Runtime",
         "Good-deed prompt and the tunnel's disclosure notification."],
        ["CAMERA", "Runtime",
         "Optional photograph for a logged deed. Declared with a non-required hardware feature so the application remains installable without a camera."],
    ],
    widths=[1.8, 1.0, 3.5],
)

h("9.2 Privacy boundaries", 2)
p(
    "An accessibility service is among the most powerful capabilities on Android, and the "
    "design constrains it deliberately rather than relying on restraint at the point of use."
)
bullets([
    ("Observation is limited by package. ",
     "The service configuration enumerates ten packages. Events from any other application — banking, messaging, mail — never reach the process. WhatsApp is deliberately absent from that list."),
    ("Text is read only for browsers. ",
     "In browsers a URL is the signal, so visible text is collected. Instagram, YouTube and Snapchat are matched on view identifiers alone, so captions, messages and usernames are never gathered."),
    ("Nothing is transmitted. ",
     "The only network activity is forwarding other applications' packets to their original destinations."),
    ("Photographs remain private. ",
     "Written to the application's private files directory, excluded from the gallery, and removed with the application."),
])

# =========================================================== 10. PHASES
h("10. Implementation Journey", 1, page_break=True)
p(
    "Development proceeded in verified phases. Each phase defined an acceptance criterion in "
    "advance, and no phase was recorded as complete until that criterion had been demonstrated "
    "on real hardware. This section records what was built and what was actually observed."
)

phases = [
    ("Phase 0", "Project setup",
     "Package renamed to com.orbis.app; minimum SDK raised from 24 to 26 as the floor for VpnService and notification channels; dependencies added and pinned in a version catalogue.",
     "Builds, installs and launches on an emulator with no errors.",
     "Achieved. Also uncovered a pre-existing environment fault and an incompatibility between KSP and AGP 9 (Sections 11.1 and 11.2)."),
    ("Phase 1", "Usage tracking",
     "Event-based foreground-time computation, usage-access detection and settings deep link, Room persistence, and a first user interface.",
     "One minute of Instagram use is correctly logged.",
     "Achieved on the physical device: Instagram 13 m 51 s, WhatsApp 5 m 15 s, Snapchat 2 m 7 s, YouTube 39 s, correctly ranked."),
    ("Phase 2a", "Surface detection",
     "Accessibility service, pure detection rules, state publication, and a live read-out in the interface.",
     "Reels, Shorts, Spotlight and browser URLs are detected; Stories is reported as NORMAL.",
     "Achieved after correcting a false positive caused by cached view hierarchies (Section 11.5)."),
    ("Phase 2b", "VPN relay",
     "TUN interface, IPv4 and IPv6 UDP relay, packet construction with checksums, lifecycle and cleanup.",
     "Traffic flows correctly and is attributed to the right application.",
     "Achieved only after three defects were corrected; the first implementation silently discarded all traffic (Section 11.6)."),
    ("Phase 2c", "Surface-gated throttling",
     "The tunnel is raised on a throttled surface and released after a grace period; delay applied by scheduler.",
     "Reels measurably slower than Stories; WhatsApp never affected.",
     "Achieved. Tunnel confirmed restricted to UIDs 10171, 10401 and 10460, with WhatsApp's 10336 absent."),
    ("Phase 3", "Adaptive intensity",
     "Real usage data fed into the throttle policy through a process-wide holder.",
     "The heaviest-used application receives the strongest throttle.",
     "Achieved. With 1 h 1 m of Instagram recorded, the tunnel reported a 400 ms delay rather than the 120 ms base."),
    ("Phase 4", "Dashboard",
     "Reclaimed-time computation against a rolling baseline, stat tiles and a seven-day chart.",
     "Reflects real logged data rather than placeholders.",
     "Achieved. Rendering revealed that a single day of history drew as a meaningless solid block; the chart is now suppressed below two days."),
    ("Phase 5", "Good-deed challenge",
     "Room entity and migration, streak computation, scheduled prompt, camera capture and log.",
     "The full loop from notification to saved entry works.",
     "Achieved. Migration verified against a live database holding real history; notification confirmed posted."),
]
for name, subtitle, work, criterion, outcome in phases:
    h("%s — %s" % (name, subtitle), 2)
    p("Work undertaken. " + work)
    p("Acceptance criterion. " + criterion, italic=True)
    p("Outcome. " + outcome)

# =========================================================== 11. CHALLENGES
h("11. Technical Challenges and Their Resolution", 1, page_break=True)
p(
    "This section is the most substantial part of the report. Every defect recorded here was "
    "found by verification rather than by inspection, and several would have survived a "
    "demonstration. They are documented with symptom, cause, evidence and remedy because the "
    "diagnostic path is the transferable part."
)

challenges = [
    ("11.1", "Build failure caused by a runtime environment, not the project",
     "The very first build failed with \u201cjlink executable does not exist\u201d.",
     "JAVA_HOME was unset. Gradle matched the daemon's pinned toolchain version 21 against any Java 21 installation it could find, selecting a JRE bundled with an unrelated editor extension. A JRE contains no jlink, which the Android Gradle Plugin requires to build its JDK image.",
     "Establishing a green baseline before making any change. Because the template was known to be untouched, the failure could not be attributed to project code.",
     "The build is run with JAVA_HOME pointed at the JDK bundled with Android Studio. A stale daemon must also be stopped, since it retains the incorrect resolution."),
    ("11.2", "KSP incompatible with AGP 9 built-in Kotlin",
     "Room's annotation processor could not be configured: \u201cUsing kotlin.sourceSets DSL to add Kotlin sources is not allowed with built-in Kotlin\u201d.",
     "AGP 9 provides Kotlin support directly and rejects the source-set DSL that KSP uses to register generated sources. The only stable KSP release for Kotlin 2.2.10 still uses it.",
     "Adding Room in isolation rather than alongside other dependencies, so the failure had exactly one candidate cause.",
     "The documented escape hatch android.disallowKotlinSourceSets=false. This is a suppression rather than a fix and is recorded as such for future revision."),
    ("11.3", "Dependency versions constrained by the installed SDK",
     "The build failed at AAR metadata verification after routine dependency updates.",
     "core-ktx 1.19.0 and lifecycle 2.11.0 declare a minimum compile SDK of 37, while only SDK 36 is installed.",
     "Reading the minCompileSdk value directly from each archive rather than assuming. This also revealed that Google's repository metadata advertises alpha builds as the current release for several libraries, so that field is not a safe indicator of the latest stable version.",
     "Versions pinned to 1.18.0 and 2.10.0, with the constraint recorded in the project guidance."),
    ("11.4", "Inverted resource naming between applications",
     "A detection rule matching \u201creel\u201d would have throttled Instagram Stories while correctly throttling YouTube Shorts.",
     "Instagram names Stories internally as reel_ and Reels as clips_. YouTube names Shorts as reel_. The same prefix denotes opposite things in the two applications.",
     "Surveying the live applications before writing detection code, and observing that the deep link instagram://reels in fact opens Stories.",
     "Detection is scoped by package before any matching occurs, and the inversion is captured by two regression tests that fail if the rule is ever generalised."),
    ("11.5", "False positive from a cached view hierarchy",
     "With detection running, Instagram Stories was reported as REELS — the exact surface required to remain unaffected.",
     "Instagram retains the Reels view pager in the accessibility tree while Stories is displayed. An unfiltered traversal therefore finds clips_ identifiers during Stories.",
     "Logging the identifiers the detector actually received at the moment of decision. Notably, a uiautomator dump does not reveal this, because it reports largely visible nodes whereas the accessibility root also exposes cached ones.",
     "Only nodes reported as visible to the user are counted. On the device this reduced the identifier set from 43 to 13 and corrected the verdict to NORMAL."),
    ("11.6", "A tunnel that silently discarded all traffic",
     "With the tunnel active, the targeted applications became unusable. The reported experience was of extreme throttling, although the configured delay was zero.",
     "Three independent defects. First, the TUN descriptor was left in non-blocking mode, so reads returned zero indefinitely and the relay consumed a core while forwarding nothing. Second, only an IPv4 address was claimed, so Android marked all IPv6 unreachable for the routed applications — and the device carries IPv6 on both networks. Third, and decisively, the INTERNET permission was absent, so the relay's first socket creation failed with EPERM and the relay thread terminated.",
     "Surfacing diagnostics in the user interface rather than the log, after it emerged that the device vendor suppresses this application's log output. The counters made the difference between a working tunnel and a black hole visible; without them the two are indistinguishable, since neither crashes nor logs.",
     "Blocking mode restored, IPv6 relayed with its mandatory pseudo-header checksum, and INTERNET declared. Measured result: reads rose from 7 packets with none forwarded, to 569 read and 466 forwarded, carrying roughly one megabyte through the interface in twelve seconds."),
    ("11.7", "A permission check that could never succeed",
     "The application would have remained on its permission prompt indefinitely even after the user granted access.",
     "The app-operation check returns one of allowed, ignored or default. Default means \u201cdefer to the permission\u201d, but the implementation treated anything other than allowed as denied.",
     "Attempting to grant the permission through developer tooling and observing that the reported state was default rather than allowed.",
     "The default case now falls back to a permission check. This defect would have affected any device that does not write an explicit app-operation entry."),
    ("11.8", "A test that reported success without executing",
     "An instrumented test appeared in a passing build while never actually running.",
     "The instrumented-test task reinstalls the application, and reinstallation clears app-operation grants. The permission granted beforehand was therefore absent by the time the test ran, and the test skipped.",
     "Reading the test output rather than only the build result. The distinction between 8 tests finished and 8 tests passed is easily missed.",
     "The tests grant the permission themselves through shell execution during setup. A related trap is recorded: a result of NORMAL from the detector is also its default value, so a genuine detection must be confirmed by the accompanying package field rather than the verdict alone."),
    ("11.9", "A notification path that was dead on arrival",
     "On a clean installation the good-deed prompt could never appear.",
     "POST_NOTIFICATIONS was declared in the manifest and checked by the worker, but never requested at runtime. It is denied by default from Android 13, so the worker returned early and posted nothing — without crash or log entry. It appeared functional on the test device only because the permission had been granted earlier by developer tooling.",
     "Auditing the completed feature against its declared permissions rather than trusting the device's current state.",
     "The permission is requested when the challenge screen is first opened, and a manual trigger was added so the daily loop can be exercised immediately. Confirmed by observing the posted notification record."),
]
for num, title_text, symptom, cause, detection, remedy in challenges:
    h("%s %s" % (num, title_text), 2)
    p("Symptom. " + symptom)
    p("Cause. " + cause)
    p("How it was found. " + detection)
    p("Resolution. " + remedy)

h("11.10 Observations arising from these defects", 2)
p(
    "Several of these defects share a property worth stating plainly: they produced no crash, "
    "no exception and no log entry. A blackholing tunnel is externally indistinguishable from "
    "a working one; a permission that is never requested simply results in nothing happening; "
    "a skipped test appears within a passing build."
)
p("Three practices proved effective and are recommended for comparable work:")
numbered([
    "Establish a known-good baseline before making changes, so that failures can be attributed.",
    "Introduce risky components in isolation, so that a failure has one candidate cause rather than several.",
    "Instrument for observability where the platform is unreliable. Vendor log suppression made the user interface the only trustworthy diagnostic channel on the test device.",
])

# =========================================================== 12. TESTING
h("12. Testing", 1, page_break=True)

h("12.1 Strategy", 2)
p(
    "Testing follows the layering described in Section 6.3. Logic that can be made pure is "
    "made pure and tested on the JVM, where execution is fast and edge cases can be "
    "enumerated. Framework interaction is verified by instrumented tests on the target device. "
    "Behaviour that can only be judged by observation — whether video actually stutters — is "
    "verified manually and reported as such."
)

h("12.2 Unit tests", 2)
table(
    ["Test class", "Cases", "Coverage focus"],
    [
        ["ForegroundTimeCalculatorTest", "10", "Session pairing: open sessions, orphaned pauses, repeated resumes, unordered input, window boundaries."],
        ["SurfaceDetectorTest", "15", "Package scoping, the reel_ inversion, exact-match rules, browser URLs, the WhatsApp invariant."],
        ["ReclaimedTimeTest", "11", "Baseline formation, exclusion of today, missing days, clamping at zero, week windowing."],
        ["UsageProfileTest", "7", "Ranking order, exclusion of untracked packages, protected-app skipping."],
        ["Ipv4Test", "11", "Header parsing, checksum verification, unsigned port and address handling, malformed input."],
        ["Ipv6Test", "10", "Pseudo-header checksum, mandatory non-zero checksum, payload and address sensitivity."],
        ["GoodDeedStreakTest", "9", "Streak continuity across midnight, broken runs, same-day duplicates."],
        ["ThrottleEngineTest", "7", "Bounds across all inputs, passthrough mode, monotonicity with usage."],
        ["TargetAppTest", "5", "Throttle eligibility, package resolution, WhatsApp exclusion."],
        ["DurationFormatterTest", "5", "Formatting boundaries including the one-minute acceptance case."],
        ["RoutedPackagesTest", "5", "Tunnel allow-list composition; WhatsApp absence."],
        ["ExampleUnitTest", "1", "Template placeholder retained."],
    ],
    widths=[1.9, 0.6, 4.0],
)
p("Total: 96 unit tests, all passing.", bold=True)

h("12.3 Instrumented tests", 2)
table(
    ["Test class", "Cases", "Coverage focus"],
    [
        ["UsageLogDaoTest", "4", "Idempotent upsert via the unique index, ordering, date-range queries."],
        ["GoodDeedDaoTest", "5", "Insertion, observation, streak integration, optional photograph."],
        ["DashboardScreenTest", "5", "Chart rendering with synthetic history, the two-day threshold, the absent-baseline and absent-permission states."],
        ["UsageRepositoryTest", "2", "End-to-end write path from live system events into Room."],
        ["UsageStatsSourceTest", "2", "Real framework event reading and foreground-time computation."],
        ["ExampleInstrumentedTest", "1", "Package identity."],
    ],
    widths=[1.9, 0.6, 4.0],
)
p(
    "Total: 22 instrumented tests. On the physical device 19 pass and 3 skip; the skipped "
    "tests are those requiring usage access, which the device vendor prevents developer "
    "tooling from granting. They execute on an emulator. They skip explicitly rather than "
    "passing without executing, which is a deliberate choice discussed in Section 11.8.",
    bold=False,
)

h("12.4 Tests that exist to protect invariants", 2)
p(
    "Certain tests are not general coverage but guards against specific, identified failure "
    "modes."
)
table(
    ["Test", "Failure it prevents"],
    [
        ["instagram reel_ ids are Stories and must stay normal",
         "Throttling Stories — the precise surface required to remain unaffected."],
        ["youtube reel_ ids are Shorts and must be throttled",
         "Generalising the rule across packages and losing Shorts detection."],
        ["snapchat nav bar icon alone is not Spotlight",
         "Reporting Spotlight throughout Snapchat because of a persistent navigation icon."],
        ["whatsapp is never throttled whatever it shows",
         "Violation of invariant I1 through any future change to detection."],
        ["whatsapp is never routed through the tunnel",
         "Violation of I1 at the transport layer, independent of detection."],
        ["a heavier day reads as zero reclaimed never negative",
         "Presenting a deficit, contrary to invariant I5."],
        ["missing days are ignored rather than counted as zero",
         "Manufacturing reclaimed time from days that predate installation."],
        ["delay is always within the documented bounds",
         "An intensity that renders the application unusable, contrary to I7."],
        ["yesterday still counts",
         "Declaring a streak broken immediately after midnight."],
    ],
    widths=[2.7, 3.6],
)

h("12.5 Manual verification on hardware", 2)
table(
    ["Check", "Method", "Result"],
    [
        ["Usage measured correctly", "Compared reported totals against actual use.", "Correct and correctly ranked."],
        ["Stories not throttled", "Opened Stories with detection logging enabled.", "NORMAL, 13 visible identifiers."],
        ["Reels detected", "Opened the Reels tab.", "REELS, 29 visible identifiers."],
        ["Shorts detected", "Opened Shorts in the YouTube application.", "SHORTS."],
        ["Browser short video detected", "Opened m.youtube.com/shorts in Edge.", "BROWSER_SHORT_VIDEO."],
        ["WhatsApp never observed", "Held WhatsApp in the foreground on a conversation.", "Zero events reached the application."],
        ["WhatsApp never routed", "Inspected the tunnel's UID set.", "10171, 10401, 10460 present; 10336 absent."],
        ["Relay carries traffic", "Sampled interface counters during Reels.", "Approximately 1.05 MB in 12 s."],
        ["Adaptive delay", "Observed reported delay with 1 h of usage recorded.", "400 ms, against a 120 ms base."],
        ["Foreground service", "Inspected the service record.", "isForeground=true, type SPECIAL_USE."],
        ["Preference persistence", "Force-stopped and relaunched.", "Setting retained."],
        ["Migration integrity", "Upgraded a database holding real history.", "No exception; history intact."],
        ["Notification path", "Triggered the prompt manually.", "Notification record posted."],
    ],
    widths=[1.7, 2.4, 2.2],
)

h("12.6 Measured results", 2)
table(
    ["Metric", "Before correction", "After correction"],
    [
        ["Packets read from the interface", "7", "569"],
        ["UDP packets forwarded", "0", "466"],
        ["Interface throughput", "0", "~1.05 MB per 12 s"],
        ["Relay status", "relay died: EPERM", "relay running"],
        ["IPv6 routing", "::/0 unreachable", "::/0 routed to tun0"],
        ["Applied delay", "0 ms (nothing forwarded)", "400 ms, usage-scaled"],
    ],
    widths=[2.3, 2.0, 2.0],
)
caption("Table 12.1 — Relay behaviour before and after the defects described in Section 11.6.")
p(
    "A further measurement of interest: of 569 packets observed during Reels playback, 86 "
    "were TCP, approximately 15 %. QUIC therefore carries the large majority of short-form "
    "video traffic, which substantiates the decision to relay UDP only."
)

# =========================================================== 13. LIMITATIONS
h("13. Limitations and Known Issues", 1, page_break=True)
table(
    ["Limitation", "Nature", "Consequence and mitigation"],
    [
        ["Detection depends on unversioned internals",
         "Structural",
         "Resource identifiers belong to third-party applications and will change without notice. Detection then silently ceases to fire. The survey procedure is documented so it can be repeated; a longer-term remedy is a remotely updatable rule set, which would conflict with the offline design."],
        ["TCP is discarded during an intervention",
         "By design",
         "Roughly 15 % of traffic. Acceptable only because the tunnel is short-lived and surface-gated. A full userspace TCP implementation would remove the limitation at considerable cost and risk."],
        ["Browser throttling is not tab-specific",
         "Inherent",
         "While a browser is routed, all of its traffic passes through the tunnel; a VPN cannot distinguish tabs. Surface gating keeps the window narrow."],
        ["Accessibility service policy",
         "External",
         "Non-accessibility use of the API attracts review on the Google Play Store. Acceptable for personal and academic distribution; an obstacle to publication."],
        ["Vendor log suppression",
         "Environmental",
         "The test device discards this application's log output, which is why diagnostics are surfaced in the interface."],
        ["Grants cleared on reinstallation",
         "Environmental",
         "Usage access and the accessibility service must be re-enabled after each installation; the device vendor prevents developer tooling from granting the former."],
        ["Spotlight not exercised on hardware",
         "Verification gap",
         "Detection is implemented and unit-tested, and the identifier was captured during the survey, but the end-to-end path has not been observed on a device."],
        ["Comparative timing not captured",
         "Verification gap",
         "Reels is demonstrably slowed and Stories demonstrably untouched, but a side-by-side timing measurement has not been recorded."],
    ],
    widths=[1.7, 0.9, 3.7],
)

# =========================================================== 14. FUTURE
h("14. Future Scope", 1, page_break=True)
numbered([
    "Full TCP relay. Would remove the discard behaviour and make throttling independent of the transport an application selects, at the cost of implementing userspace connection tracking.",
    "Resilient detection. Complementing resource identifiers with accessibility labels and structural heuristics would reduce breakage when the target applications are redesigned.",
    "Configurable intensity. Exposing the delay bounds, with the ThrottleRule entity persisted as originally designed.",
    "Scheduling. Applying interventions only during chosen hours, so that deliberate evening viewing is unaffected.",
    "Longer-horizon reporting. Monthly trends and per-surface attribution, distinguishing Reels time from other Instagram time.",
    "Richer good-deed features. Categories, reminders at chosen times, and an optional export of the log.",
    "Automated migration testing. Adding Room's migration test helper with exported schemas, to verify upgrades without a device.",
    "Accessibility review of the interface itself, including content descriptions and dynamic type support.",
])

# =========================================================== 15. CONCLUSION
h("15. Conclusion", 1, page_break=True)
p(
    "ORBIS demonstrates that per-surface intervention is achievable on an unmodified Android "
    "device, and that it requires combining mechanisms neither of which is sufficient alone. "
    "The accessibility service supplies context that a VPN cannot obtain; the VPN applies "
    "friction that an accessibility service cannot. Establishing that this combination was "
    "necessary — and that the simpler network-only design was impossible rather than merely "
    "difficult — was as significant as the implementation."
)
p(
    "The system meets its stated objectives. Usage is measured, surfaces are discriminated "
    "correctly including the two cases where naming actively misleads, throttling is applied "
    "in proportion to measured behaviour and confined to the intended screens, and the "
    "reclaimed attention is directed toward a constructive alternative. The invariant that "
    "communication tools remain unaffected is enforced by the operating system rather than by "
    "application logic, which is the strongest form available."
)
p(
    "The most instructive outcome, however, concerns verification. Several defects recorded in "
    "Section 11 produced no crash, no exception and no log entry: a tunnel discarding every "
    "packet, a permission never requested, a test skipped inside a passing build. Each would "
    "have survived a demonstration and been discovered in use. They were found because each "
    "phase was checked against a criterion defined in advance, on real hardware, and because "
    "an absence of errors was not accepted as evidence of correctness."
)

# =========================================================== APPENDICES
h("Appendix A — Build and Verification Commands", 1, page_break=True)
code(
    "# Java must point at the JDK bundled with Android Studio (see 11.1)\n"
    "$env:JAVA_HOME = \"C:\\Program Files\\Android\\Android Studio\\jbr\"\n\n"
    "./gradlew assembleDebug            # build the debug package\n"
    "./gradlew test                     # 96 JVM unit tests\n"
    "./gradlew connectedAndroidTest     # 22 instrumented tests (device required)\n"
    "./gradlew lint                     # static analysis\n"
    "./gradlew installDebug             # install on the connected device\n\n"
    "# A single unit test\n"
    "./gradlew test --tests \"com.orbis.app.surface.SurfaceDetectorTest\""
)

h("Appendix B — Environment Notes", 1)
bullets([
    ("Gradle daemon JVM. ", "The project pins toolchain version 21. With JAVA_HOME unset, Gradle may select a JRE lacking jlink and the build fails. Stop stale daemons after correcting it."),
    ("Accessibility service after installation. ", "Reinstallation disables it. Re-enable through settings, or with: adb shell settings put secure enabled_accessibility_services com.orbis.app/com.orbis.app.surface.OrbisAccessibilityService"),
    ("Usage access after installation. ", "Also revoked, and the test device prevents developer tooling from granting it. It must be re-enabled manually."),
    ("Force-stopping the application. ", "Disables its accessibility service, which affects test procedures that use it."),
    ("Emulator behaviour. ", "None of the four target applications are present on a clean emulator, so an absence of tracked usage there is correct rather than a fault."),
])

h("Appendix C — Detection Reference", 1)
table(
    ["Application", "Throttled surface", "Identifiers", "Left unaffected"],
    [
        ["Instagram", "Reels", "clips_viewer_view_pager, clips_video_container, root_clips_layout", "Stories (reel_viewer_root), feed, direct messages"],
        ["YouTube", "Shorts", "reel_watch_fragment_root, reel_watch_player, reel_recycler", "Long-form watch interface"],
        ["Snapchat", "Spotlight", "spotlight_container (exact match only)", "Chats, Stories, camera"],
        ["Browsers", "Short-video URLs", "Address-bar text matching youtube.com/shorts, instagram.com/reel, snapchat.com/spotlight", "All other pages"],
        ["WhatsApp", "None", "Not observed at all", "Everything"],
    ],
    widths=[1.0, 1.1, 2.5, 1.7],
)
p(
    "Note. In Instagram, reel_ denotes Stories. In YouTube, reel_ denotes Shorts. Matching "
    "must always be scoped by package first.",
    bold=True,
)

h("Appendix D — Source Inventory", 1)
table(
    ["Component", "File", "Lines"],
    [
        ["VPN relay", "vpn/OrbisVpnService.kt", "390"],
        ["Dashboard interface", "ui/DashboardScreen.kt", "347"],
        ["Good-deed interface", "ui/GoodDeedScreen.kt", "304"],
        ["Controls interface", "ui/UsageScreen.kt", "302"],
        ["Application entry", "MainActivity.kt", "190"],
        ["Accessibility service", "surface/OrbisAccessibilityService.kt", "163"],
        ["IPv4 handling", "vpn/Ipv4.kt", "151"],
        ["IPv6 handling", "vpn/Ipv6.kt", "121"],
        ["Deed scheduling", "deed/GoodDeedScheduler.kt", "105"],
        ["Deed view model", "ui/GoodDeedViewModel.kt", "86"],
        ["Usage repository", "data/UsageRepository.kt", "71"],
        ["Reclaimed-time logic", "dashboard/ReclaimedTime.kt", "69"],
        ["Detection rules", "surface/SurfaceDetector.kt", "67"],
        ["Throttle policy", "throttle/ThrottleEngine.kt", "61"],
        ["Event pairing", "usage/ForegroundTimeCalculator.kt", "57"],
        ["Streak logic", "deed/GoodDeedStreak.kt", "38"],
    ],
    widths=[2.0, 3.2, 0.8],
)

h("Appendix E — Revision History", 1)
table(
    ["Commit", "Description"],
    [
        ["937b29c", "Initial commit: template and project guidance"],
        ["fa4bd99", "Phase 0: package rename, minimum SDK raised to 26"],
        ["9e545ad", "Phase 0: dependencies added, Room and KSP toolchain verified"],
        ["8dd053a", "Phase 1: usage tracking and usage-access flow"],
        ["22856bf", "Surface-detection survey recorded"],
        ["a725cdf", "Phase 2a: surface detection via accessibility service"],
        ["568ae3b", "Phase 2b: VPN tunnel with UDP relay"],
        ["c653787", "Blackholing tunnel corrected: IPv6, blocking reads, gating"],
        ["6bae4e5", "INTERNET permission added; relay could not open sockets"],
        ["e25c325", "Phase 2 gaps closed: adaptive delay, browsers, persistence"],
        ["ab5db91", "Tunnel promoted to a foreground service"],
        ["3e86aff", "Phase 4: reclaimed-time dashboard"],
        ["23de4c7", "Phase 5: good-deed challenge"],
        ["4c598ce", "Notification permission requested; dead code removed"],
    ],
    widths=[1.0, 5.0],
)

h("Appendix F — Glossary", 1)
table(
    ["Term", "Meaning"],
    [
        ["Surface", "A distinguishable screen within an application, such as Reels as opposed to Stories."],
        ["TUN interface", "A virtual network interface through which the application receives other applications' packets."],
        ["QUIC", "A UDP-based transport protocol used by modern video services in preference to TCP."],
        ["SNI", "Server Name Indication; the hostname visible during TLS negotiation. The URL path is not visible."],
        ["Throttling", "Adding latency to traffic so that playback becomes less fluid, as distinct from blocking it."],
        ["Baseline", "The user's own rolling average usage, against which reclaimed time is measured."],
        ["Gating", "Raising the tunnel only while a throttled surface is displayed."],
        ["KSP", "Kotlin Symbol Processing; the annotation processor used by Room."],
        ["Blackhole", "A tunnel that captures traffic without forwarding it, silently discarding it."],
    ],
    widths=[1.2, 5.0],
)

os.makedirs(OUT_DIR, exist_ok=True)
doc.save(OUT)
print("WROTE:", OUT)
print("SIZE:", os.path.getsize(OUT), "bytes")
