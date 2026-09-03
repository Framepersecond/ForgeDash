package dash.web;

import dash.data.OperationsManager;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Compact, task-oriented operations workspace for local Dash installations. */
public final class OperationsPage {
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    private OperationsPage() {
    }

    public static String render(OperationsManager manager, String message, boolean canWrite, String actor,
            Map<String, List<String>> roles, String rawQuery, boolean bridgeEnabled, String bridgeFingerprint) {
        Map<String, String> query = parseQuery(rawQuery);
        String activeTab = allowedTab(query.get("tab"));
        boolean mobile = "1".equals(query.get("mobile"));
        OperationsManager.Snapshot snapshot = manager.snapshot();
        OperationsManager.ChangePreview preview = "1".equals(query.get("preview"))
                ? manager.previewChange(query.get("change_type"), query.get("target"), query.get("proposed_value"))
                : null;
        String simulatedRole = safeRole(query.get("simulate_role"), roles);
        String simulatedPermission = clean(query.get("simulate_permission"), 160, "dash.web.settings.read");
        Set<String> simulatedGrants = Set.copyOf(roles.getOrDefault(simulatedRole, List.of()));
        boolean simulatedAllowed = OperationsManager.permissionMatches(simulatedGrants, simulatedPermission);
        String player = clean(query.get("player"), 64, "");
        OperationsManager.PlayerEvidence playerEvidence = manager.playerEvidence(player);

        long openIncidents = snapshot.incidents().stream().filter(item -> "open".equals(item.status())).count();
        long activePlans = snapshot.plans().stream()
                .filter(item -> List.of("ready", "running").contains(item.status())).count();
        long openHandovers = snapshot.handovers().stream().filter(item -> "open".equals(item.status())).count();
        long activeAlerts = snapshot.alertBundles().stream().filter(item -> !item.acknowledged()).count();

        String banner = message == null || message.isBlank() ? ""
                : "<div class='rounded-xl border border-cyan-500/30 bg-cyan-500/10 px-4 py-3 text-sm text-cyan-100'>"
                        + esc(message) + "</div>";
        String disabled = canWrite ? "" : " disabled";
        String content = HtmlTemplate.statsHeader()
                + "<style>"
                + ".ops-tabs{display:flex;gap:.5rem;overflow-x:auto;padding-bottom:.2rem}.ops-tab{white-space:nowrap;border:1px solid rgba(148,163,184,.2);background:rgba(15,23,42,.55);padding:.65rem .9rem;border-radius:.65rem;color:#94a3b8;font-size:.78rem;font-weight:700}.ops-tab[aria-selected='true']{border-color:rgba(34,211,238,.5);background:rgba(34,211,238,.12);color:#a5f3fc}.ops-panel{display:none}.ops-panel[data-active='true']{display:block;animation:opsIn .3s cubic-bezier(.16,1,.3,1)}@keyframes opsIn{from{opacity:0;transform:translateY(7px)}to{opacity:1;transform:none}}.ops-card{border:1px solid rgba(100,116,139,.35);background:rgba(15,23,42,.48);border-radius:.75rem;padding:1rem}.ops-list{max-height:360px;overflow:auto}.ops-mobile-dock{display:none}@media(max-width:767px){#main-content:has(.ops-mobile) #live-stats-header{display:none}.ops-mobile{padding-bottom:5.25rem;transform:none!important}.ops-mobile-dock{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));position:fixed;left:.5rem;right:.5rem;bottom:.5rem;z-index:60;gap:.35rem;border:1px solid rgba(100,116,139,.45);background:rgba(2,6,23,.94);backdrop-filter:blur(14px);padding:.45rem;border-radius:.8rem}.ops-mobile-dock a{display:flex;flex-direction:column;align-items:center;gap:.2rem;padding:.45rem .2rem;font-size:.62rem;color:#cbd5e1}.ops-mobile .ops-desktop-extra{display:none}.ops-card{padding:.8rem}}"
                + "</style>"
                + "<main class='flex-1 min-w-0 p-4 sm:p-6 " + (mobile ? "ops-mobile" : "") + "'><div class='max-w-7xl mx-auto space-y-4'>"
                + header(mobile, openIncidents, activePlans, activeAlerts)
                + banner
                + metricStrip(openIncidents, activePlans, openHandovers, activeAlerts)
                + tabBar(activeTab)
                + panel("overview", activeTab, overview(snapshot, canWrite, disabled))
                + panel("planner", activeTab, planner(snapshot, preview, query, canWrite, disabled))
                + panel("incidents", activeTab, incidents(snapshot, canWrite, disabled))
                + panel("recovery", activeTab, recovery(snapshot, canWrite, disabled))
                + panel("security", activeTab, security(snapshot, roles, simulatedRole, simulatedPermission,
                        simulatedAllowed, bridgeEnabled, bridgeFingerprint))
                + panel("automation", activeTab, automation(snapshot, canWrite, disabled))
                + panel("players", activeTab, players(playerEvidence, player))
                + mobileDock()
                + "</div></main>"
                + script(activeTab)
                + HtmlTemplate.statsScript();
        return HtmlTemplate.page("Operations", "/operations", content);
    }

    private static String header(boolean mobile, long incidents, long plans, long alerts) {
        return "<section class='rounded-2xl bg-glass-surface border border-glass-border p-5'>"
                + "<div class='flex flex-col lg:flex-row lg:items-center lg:justify-between gap-4'><div>"
                + "<div class='inline-flex items-center gap-2 rounded-full border border-cyan-500/25 bg-cyan-500/10 px-3 py-1 text-xs font-bold text-cyan-300 mb-3'><span class='material-symbols-outlined text-[16px]'>space_dashboard</span>Operations Center</div>"
                + "<h1 class='text-2xl sm:text-3xl font-bold text-white'>Operate with a plan, not a guess</h1>"
                + "<p class='mt-1 text-sm text-slate-400'>Maintenance, incidents, recovery, security and handover in one focused workspace.</p></div>"
                + "<div class='flex flex-wrap gap-2'>"
                + "<a href='/operations?mobile=" + (mobile ? "0" : "1") + "' data-dash-command='Mobile Operations Mode' class='inline-flex items-center gap-2 rounded-xl border border-slate-700 px-3 py-2 text-xs font-bold text-slate-200'><span class='material-symbols-outlined text-[17px]'>smartphone</span>" + (mobile ? "Desktop mode" : "Mobile mode") + "</a>"
                + "<a href='/api/operations/evidence' data-dash-command='Export security evidence' data-dash-full-submit class='inline-flex items-center gap-2 rounded-xl bg-cyan-500 px-3 py-2 text-xs font-bold text-black'><span class='material-symbols-outlined text-[17px]'>verified_user</span>Evidence export</a>"
                + "</div></div>"
                + (incidents + plans + alerts == 0
                        ? "<div class='mt-4 rounded-xl border border-emerald-500/25 bg-emerald-500/10 px-3 py-2 text-xs text-emerald-200'>No active operational blocker is recorded.</div>"
                        : "")
                + "</section>";
    }

    private static String metricStrip(long incidents, long plans, long handovers, long alerts) {
        return "<section class='grid grid-cols-2 lg:grid-cols-4 gap-3'>"
                + metric("Open incidents", incidents, "warning")
                + metric("Active plans", plans, "event_note")
                + metric("Open handovers", handovers, "handshake")
                + metric("Alert bundles", alerts, "notifications_active")
                + "</section>";
    }

    private static String metric(String label, long value, String icon) {
        return "<article class='ops-card flex items-center justify-between gap-3'><div><p class='text-[11px] uppercase tracking-wider text-slate-500'>"
                + esc(label) + "</p><p class='mt-1 text-2xl font-bold text-white'>" + value
                + "</p></div><span class='material-symbols-outlined text-cyan-300'>" + icon + "</span></article>";
    }

    private static String tabBar(String active) {
        String[][] tabs = {
                {"overview", "Overview", "dashboard"},
                {"planner", "Planner & Calendar", "event_note"},
                {"incidents", "Incident Mode", "emergency_home"},
                {"recovery", "Recovery & Capacity", "restore"},
                {"security", "Security & Compatibility", "shield"},
                {"automation", "Automation Recipes", "automation"},
                {"players", "Player 360", "person_search"}
        };
        StringBuilder out = new StringBuilder("<nav class='ops-tabs' aria-label='Operations sections'>");
        for (String[] tab : tabs) {
            out.append("<button type='button' class='ops-tab inline-flex items-center gap-2' data-ops-tab='")
                    .append(tab[0]).append("' aria-selected='").append(tab[0].equals(active)).append("'><span class='material-symbols-outlined text-[16px]'>")
                    .append(tab[2]).append("</span>").append(tab[1]).append("</button>");
        }
        return out.append("</nav>").toString();
    }

    private static String panel(String id, String active, String body) {
        return "<section class='ops-panel' data-ops-panel='" + id + "' data-active='" + id.equals(active) + "'>" + body + "</section>";
    }

    private static String overview(OperationsManager.Snapshot snapshot, boolean canWrite, String disabled) {
        return "<div class='grid grid-cols-1 xl:grid-cols-[.9fr_1.1fr] gap-4'>"
                + "<div class='space-y-4'>" + quickActions(snapshot, canWrite)
                + "<article class='ops-card'><div class='flex items-center justify-between gap-3 mb-3'><h2 class='font-bold text-white'>Smart alert bundles</h2><span class='text-xs text-slate-500'>Grouped from recent logs</span></div>"
                + alertBundles(snapshot.alertBundles(), canWrite, disabled) + "</article></div>"
                + "<div class='space-y-4'><article class='ops-card'><h2 class='font-bold text-white mb-3'>Shift handover</h2>"
                + (canWrite ? "<form method='post' action='/action' class='space-y-2 mb-4'><input type='hidden' name='action' value='operations_handover_create'><textarea name='summary' maxlength='3000' rows='3' required placeholder='Open risks, unfinished work and the next safe action'></textarea><button class='w-full rounded-xl bg-cyan-500 px-3 py-2 text-sm font-bold text-black'>Publish handover</button></form>" : "")
                + handovers(snapshot.handovers(), canWrite, disabled) + "</article></div></div>";
    }

    private static String quickActions(OperationsManager.Snapshot snapshot, boolean canWrite) {
        List<String> actions = new ArrayList<>();
        if (snapshot.backups().isEmpty()) {
            actions.add(actionCard("No verified backup", "Create a restore point before maintenance.", "backup", "/maintenance"));
        }
        if (snapshot.drift().baselinePresent() && snapshot.drift().changedCount() + snapshot.drift().addedCount() + snapshot.drift().missingCount() > 0) {
            actions.add(actionCard("Configuration drift", "Review " + snapshot.drift().changedCount() + " changed configurations.", "difference", "/operations?tab=recovery"));
        }
        if (snapshot.compatibility().invalidJars() > 0 || snapshot.compatibility().duplicateArtifacts() > 0) {
            actions.add(actionCard("Compatibility risk", "Invalid or duplicate JARs need review before restart.", "extension_off", "/operations?tab=security"));
        }
        long activeAlerts = snapshot.alertBundles().stream().filter(item -> !item.acknowledged()).count();
        if (activeAlerts > 0) {
            actions.add(actionCard("Incident signal", activeAlerts + " alert bundles need an owner.", "emergency_home", "/operations?tab=incidents"));
        }
        if (snapshot.capacity().daysRemaining() >= 0 && snapshot.capacity().daysRemaining() < 14) {
            actions.add(actionCard("Capacity risk", snapshot.capacity().message(), "storage", "/operations?tab=recovery"));
        }
        if (actions.isEmpty()) actions.add(actionCard("Operations look calm", "No immediate contextual action is required.", "check_circle", "/graphs"));
        return "<article class='ops-card'><div class='flex items-center justify-between mb-3'><h2 class='font-bold text-white'>Contextual quick actions</h2><span class='material-symbols-outlined text-cyan-300'>bolt</span></div><div class='grid gap-2'>"
                + String.join("", actions) + "</div></article>";
    }

    private static String actionCard(String title, String detail, String icon, String href) {
        return "<a href='" + href + "' data-dash-command='" + esc(title) + "' class='flex items-center gap-3 rounded-xl border border-slate-800 bg-slate-950/35 p-3 hover:border-cyan-500/40'><span class='material-symbols-outlined text-cyan-300'>"
                + icon + "</span><span class='min-w-0'><span class='block text-sm font-bold text-white'>" + esc(title)
                + "</span><span class='block text-xs text-slate-500'>" + esc(detail) + "</span></span></a>";
    }

    private static String alertBundles(List<OperationsManager.AlertBundle> bundles, boolean canWrite, String disabled) {
        if (bundles.isEmpty()) return empty("No warning patterns in the latest log window.");
        StringBuilder out = new StringBuilder("<div class='space-y-2 ops-list'>");
        for (OperationsManager.AlertBundle bundle : bundles) {
            String color = "critical".equals(bundle.severity()) ? "rose" : "amber";
            out.append("<div class='rounded-xl border border-").append(color).append("-500/25 bg-").append(color)
                    .append("-500/10 p-3 ").append(bundle.acknowledged() ? "opacity-60" : "").append("'><div class='flex items-start justify-between gap-3'><div><p class='text-sm font-bold text-white'>")
                    .append(esc(bundle.category())).append(" <span class='text-xs text-slate-400'>x").append(bundle.count()).append("</span></p><p class='mt-1 text-xs text-slate-400 line-clamp-2'>")
                    .append(esc(bundle.latestLine())).append("</p></div>");
            if (canWrite && !bundle.acknowledged()) {
                out.append("<form method='post' action='/action'><input type='hidden' name='action' value='operations_alert_ack'><input type='hidden' name='signature' value='")
                        .append(esc(bundle.signature())).append("'><button").append(disabled).append(" class='rounded-lg border border-slate-700 px-2 py-1 text-xs text-slate-200'>Acknowledge</button></form>");
            }
            out.append("</div></div>");
        }
        return out.append("</div>").toString();
    }

    private static String handovers(List<OperationsManager.Handover> rows, boolean canWrite, String disabled) {
        if (rows.isEmpty()) return empty("No shift handover has been published.");
        StringBuilder out = new StringBuilder("<div class='space-y-2 ops-list'>");
        for (OperationsManager.Handover row : rows) {
            out.append("<div class='rounded-xl border border-slate-800 bg-slate-950/35 p-3'><div class='flex items-start justify-between gap-3'><div><p class='text-xs text-slate-500'>")
                    .append(esc(row.author())).append(" - ").append(time(row.createdAt())).append("</p><p class='mt-1 text-sm text-slate-200 whitespace-pre-wrap'>")
                    .append(esc(row.summary())).append("</p></div><span class='rounded-full border px-2 py-1 text-[10px] ")
                    .append("open".equals(row.status()) ? "border-amber-500/30 text-amber-300" : "border-emerald-500/30 text-emerald-300")
                    .append("'>").append(esc(row.status())).append("</span></div>");
            if (canWrite && "open".equals(row.status())) {
                out.append("<form method='post' action='/action' class='mt-2'><input type='hidden' name='action' value='operations_handover_ack'><input type='hidden' name='handover_id' value='")
                        .append(esc(row.id())).append("'><button").append(disabled).append(" class='text-xs font-bold text-cyan-300'>Acknowledge handover</button></form>");
            }
            out.append("</div>");
        }
        return out.append("</div>").toString();
    }

    private static String planner(OperationsManager.Snapshot snapshot, OperationsManager.ChangePreview preview,
            Map<String, String> query, boolean canWrite, String disabled) {
        return "<div class='grid grid-cols-1 xl:grid-cols-2 gap-4'>"
                + "<div class='space-y-4'><article class='ops-card'><h2 class='font-bold text-white mb-3'>Maintenance Planner</h2>"
                + (canWrite ? "<form method='post' action='/action' class='space-y-3'><input type='hidden' name='action' value='operations_plan_create'>"
                        + field("Title", "<input name='title' required maxlength='120' placeholder='Update proxy and lobby plugins'>")
                        + "<div class='grid grid-cols-1 sm:grid-cols-2 gap-3'>"
                        + field("Change type", select("change_type", List.of("configuration", "plugin", "update", "restart", "security"), "configuration"))
                        + field("Scheduled", "<input type='datetime-local' name='scheduled_at'>") + "</div>"
                        + field("Details", "<textarea name='details' rows='3' maxlength='2000' placeholder='Scope, owner and expected outcome'></textarea>")
                        + "<button" + disabled + " class='w-full rounded-xl bg-cyan-500 px-3 py-2 text-sm font-bold text-black'>Create maintenance plan</button></form>" : empty("Read-only operations access."))
                + "</article>" + plans(snapshot.plans(), canWrite, disabled, snapshot.backups()) + "</div>"
                + "<div class='space-y-4'><article class='ops-card'><h2 class='font-bold text-white mb-3'>Change Preview</h2>"
                + "<form method='get' action='/operations' class='space-y-3'><input type='hidden' name='tab' value='planner'><input type='hidden' name='preview' value='1'>"
                + field("Change type", select("change_type", List.of("configuration", "plugin", "update", "restart", "security"), query.get("change_type")))
                + field("Target", "<input name='target' maxlength='240' value='" + esc(clean(query.get("target"), 240, "server.properties")) + "' placeholder='server.properties'>")
                + field("Proposed value", "<textarea name='proposed_value' rows='2' maxlength='500' placeholder='Optional summary'>" + esc(clean(query.get("proposed_value"), 500, "")) + "</textarea>")
                + "<button class='w-full rounded-xl border border-cyan-500/40 bg-cyan-500/10 px-3 py-2 text-sm font-bold text-cyan-200'>Preview impact</button></form>"
                + (preview == null ? "" : preview(preview)) + "</article>"
                + "<article class='ops-card'><h2 class='font-bold text-white mb-3'>Maintenance Calendar</h2>"
                + calendar(snapshot.plans()) + "</article></div></div>";
    }

    private static String plans(List<OperationsManager.Plan> rows, boolean canWrite, String disabled, List<String> backups) {
        if (rows.isEmpty()) return "<article class='ops-card'>" + empty("No maintenance plans yet.") + "</article>";
        StringBuilder out = new StringBuilder("<article class='ops-card'><h2 class='font-bold text-white mb-3'>Plan queue</h2><div class='space-y-3 ops-list'>");
        for (OperationsManager.Plan plan : rows) {
            out.append("<div class='rounded-xl border border-slate-800 bg-slate-950/35 p-3'><div class='flex items-start justify-between gap-3'><div><p class='text-sm font-bold text-white'>")
                    .append(esc(plan.title())).append("</p><p class='text-xs text-slate-500'>").append(esc(plan.changeType())).append(" - ")
                    .append(time(plan.scheduledAt())).append("</p></div><span class='rounded-full border border-cyan-500/30 px-2 py-1 text-[10px] text-cyan-300'>")
                    .append(esc(plan.status())).append("</span></div><p class='mt-2 text-xs text-slate-400'>").append(esc(plan.runbook())).append("</p><p class='mt-2 text-[11px] text-slate-500'>")
                    .append(esc(plan.lastResult())).append("</p>");
            if (canWrite && List.of("draft", "ready", "running").contains(plan.status())) {
                out.append("<div class='mt-3 grid grid-cols-1 sm:grid-cols-2 gap-2'>");
                if ("draft".equals(plan.status())) {
                    out.append("<form method='post' action='/action'><input type='hidden' name='action' value='operations_plan_prepare'><input type='hidden' name='plan_id' value='")
                            .append(esc(plan.id())).append("'><button").append(disabled).append(" class='w-full rounded-lg bg-emerald-500 px-2 py-2 text-xs font-bold text-black'>Preflight + backup</button></form>");
                }
                out.append("<form method='post' action='/action' class='grid grid-cols-[1fr_auto] gap-2'><input type='hidden' name='action' value='operations_plan_status'><input type='hidden' name='plan_id' value='")
                        .append(esc(plan.id())).append("'>").append(select("status", List.of("draft", "ready", "running", "completed", "cancelled"), plan.status()))
                        .append("<button").append(disabled).append(" class='rounded-lg border border-slate-700 px-3 text-xs font-bold text-slate-200'>Save</button></form></div>");
            }
            out.append("</div>");
        }
        return out.append("</div></article>").toString();
    }

    private static String preview(OperationsManager.ChangePreview preview) {
        StringBuilder out = new StringBuilder("<div class='mt-4 rounded-xl border ")
                .append(preview.valid() ? "border-cyan-500/30 bg-cyan-500/10" : "border-rose-500/30 bg-rose-500/10")
                .append(" p-3'><div class='flex justify-between gap-3'><p class='text-sm font-bold text-white'>Risk: ")
                .append(esc(preview.risk())).append("</p><span class='text-xs text-slate-300'>Restart: ")
                .append(preview.restartRequired() ? "yes" : "no").append("</span></div><ul class='mt-2 space-y-1 text-xs text-slate-300'>");
        for (String effect : preview.effects()) out.append("<li>- ").append(esc(effect)).append("</li>");
        return out.append("</ul><p class='mt-2 text-[11px] text-slate-500'>").append(esc(preview.message())).append("</p></div>").toString();
    }

    private static String calendar(List<OperationsManager.Plan> plans) {
        List<OperationsManager.Plan> sorted = plans.stream()
                .filter(plan -> !List.of("completed", "cancelled").contains(plan.status()))
                .sorted(Comparator.comparingLong(OperationsManager.Plan::scheduledAt)).limit(20).toList();
        if (sorted.isEmpty()) return empty("No upcoming maintenance window.");
        StringBuilder out = new StringBuilder("<div class='space-y-2 ops-list'>");
        String previousDay = "";
        for (OperationsManager.Plan plan : sorted) {
            String day = DATE_TIME.format(Instant.ofEpochMilli(plan.scheduledAt())).substring(0, 10);
            if (!day.equals(previousDay)) out.append("<p class='pt-2 text-[11px] font-bold uppercase tracking-wider text-cyan-300'>").append(day).append("</p>");
            out.append("<div class='flex items-center justify-between gap-3 rounded-lg bg-slate-950/35 px-3 py-2'><span class='truncate text-sm text-slate-200'>")
                    .append(esc(plan.title())).append("</span><span class='text-xs text-slate-500'>")
                    .append(DATE_TIME.format(Instant.ofEpochMilli(plan.scheduledAt())).substring(11)).append("</span></div>");
            previousDay = day;
        }
        return out.append("</div>").toString();
    }

    private static String incidents(OperationsManager.Snapshot snapshot, boolean canWrite, String disabled) {
        return "<div class='grid grid-cols-1 xl:grid-cols-[.75fr_1.25fr] gap-4'><div class='space-y-4'>"
                + "<article class='ops-card'><h2 class='font-bold text-white mb-3'>Start Incident Mode</h2>"
                + (canWrite ? "<form method='post' action='/action' class='space-y-3'><input type='hidden' name='action' value='operations_incident_create'>"
                        + field("Title", "<input name='title' required maxlength='140' placeholder='TPS collapse after plugin update'>")
                        + field("Severity", select("severity", List.of("warning", "critical", "info"), "warning"))
                        + field("Observed impact", "<textarea name='summary' required rows='4' maxlength='4000' placeholder='What users see, when it started and what changed'></textarea>")
                        + "<button" + disabled + " class='w-full rounded-xl bg-rose-500 px-3 py-2 text-sm font-bold text-white'>Open incident</button></form>" : empty("Read-only incident access."))
                + "</article>"
                + "<article class='ops-card'><h2 class='font-bold text-white mb-3'>Incident quick actions</h2><div class='grid gap-2'>"
                + actionCard("Diagnostics", "Inspect crash reports and likely causes.", "medical_services", "/maintenance")
                + actionCard("Live graphs", "Compare TPS, RAM and load.", "query_stats", "/graphs")
                + actionCard("Maintenance", "Create a verified backup or start profiling.", "stethoscope", "/maintenance")
                + "</div></article></div>"
                + "<article class='ops-card'><h2 class='font-bold text-white mb-3'>Incident timeline and reports</h2>"
                + incidentRows(snapshot.incidents(), canWrite, disabled) + "</article></div>";
    }

    private static String incidentRows(List<OperationsManager.Incident> rows, boolean canWrite, String disabled) {
        if (rows.isEmpty()) return empty("No incident has been recorded.");
        StringBuilder out = new StringBuilder("<div class='space-y-3 ops-list'>");
        for (OperationsManager.Incident incident : rows) {
            out.append("<div class='rounded-xl border border-").append("critical".equals(incident.severity()) ? "rose" : "amber")
                    .append("-500/25 bg-slate-950/35 p-3'><div class='flex items-start justify-between gap-3'><div><p class='text-sm font-bold text-white'>")
                    .append(esc(incident.title())).append("</p><p class='text-xs text-slate-500'>").append(esc(incident.owner())).append(" - ")
                    .append(time(incident.createdAt())).append("</p></div><span class='rounded-full border border-slate-700 px-2 py-1 text-[10px] text-slate-300'>")
                    .append(esc(incident.status())).append("</span></div><p class='mt-2 text-sm text-slate-300 whitespace-pre-wrap'>")
                    .append(esc(incident.summary())).append("</p>");
            if (canWrite && "open".equals(incident.status())) {
                out.append("<form method='post' action='/action' class='mt-3 space-y-2'><input type='hidden' name='action' value='operations_incident_close'><input type='hidden' name='incident_id' value='")
                        .append(esc(incident.id())).append("'><textarea name='resolution' rows='2' maxlength='4000' placeholder='Resolution and preventive follow-up'></textarea><button")
                        .append(disabled).append(" class='w-full rounded-lg bg-emerald-500 px-3 py-2 text-xs font-bold text-black'>Close and generate report</button></form>");
            }
            if (!incident.report().isBlank()) {
                out.append("<details class='mt-3 rounded-lg border border-slate-800 p-2'><summary class='cursor-pointer text-xs font-bold text-cyan-300'>Post-incident report</summary><pre class='mt-2 whitespace-pre-wrap text-xs text-slate-300'>")
                        .append(esc(incident.report())).append("</pre></details>");
            }
            out.append("</div>");
        }
        return out.append("</div>").toString();
    }

    private static String recovery(OperationsManager.Snapshot snapshot, boolean canWrite, String disabled) {
        return "<div class='grid grid-cols-1 xl:grid-cols-3 gap-4'>"
                + "<article class='ops-card'><h2 class='font-bold text-white mb-3'>Restore Drill</h2><p class='text-xs text-slate-500 mb-3'>Validates paths, duplicates, entry limits and archive size without extracting into production.</p>"
                + (canWrite ? "<form method='post' action='/action' class='space-y-2'><input type='hidden' name='action' value='operations_restore_drill'>"
                        + select("backup", snapshot.backups(), snapshot.backups().isEmpty() ? "" : snapshot.backups().get(0))
                        + "<button" + disabled + (snapshot.backups().isEmpty() ? " disabled" : "") + " class='w-full rounded-xl bg-cyan-500 px-3 py-2 text-sm font-bold text-black'>Run isolated verification</button></form>" : "")
                + drillRows(snapshot.drills()) + "</article>"
                + "<article class='ops-card'><h2 class='font-bold text-white mb-3'>Configuration Drift</h2>"
                + drift(snapshot.drift())
                + (canWrite ? "<form method='post' action='/action' class='mt-3'><input type='hidden' name='action' value='operations_drift_baseline'><button" + disabled + " class='w-full rounded-xl border border-cyan-500/40 bg-cyan-500/10 px-3 py-2 text-sm font-bold text-cyan-200'>Save current baseline</button></form>" : "")
                + "</article>"
                + "<article class='ops-card'><h2 class='font-bold text-white mb-3'>Capacity Forecast</h2>"
                + capacity(snapshot.capacity(), snapshot.capacitySamples())
                + (canWrite ? "<form method='post' action='/action' class='mt-3'><input type='hidden' name='action' value='operations_capacity_sample'><button" + disabled + " class='w-full rounded-xl border border-slate-700 px-3 py-2 text-sm font-bold text-slate-200'>Sample now</button></form>" : "")
                + "</article></div>";
    }

    private static String drillRows(List<OperationsManager.RestoreDrill> rows) {
        if (rows.isEmpty()) return "<div class='mt-3'>" + empty("No restore drill has run yet.") + "</div>";
        StringBuilder out = new StringBuilder("<div class='mt-3 space-y-2 ops-list'>");
        for (OperationsManager.RestoreDrill row : rows.stream().limit(12).toList()) {
            out.append("<div class='rounded-lg border border-slate-800 bg-slate-950/35 p-2'><div class='flex justify-between gap-2'><span class='truncate text-xs font-mono text-slate-300'>")
                    .append(esc(row.backupName())).append("</span><span class='text-[10px] ")
                    .append("passed".equals(row.status()) ? "text-emerald-300" : "text-rose-300").append("'>")
                    .append(esc(row.status())).append("</span></div><p class='mt-1 text-[11px] text-slate-500'>")
                    .append(esc(row.details())).append("</p></div>");
        }
        return out.append("</div>").toString();
    }

    private static String drift(OperationsManager.DriftReport drift) {
        if (!drift.baselinePresent()) return empty("No drift baseline exists yet.");
        StringBuilder out = new StringBuilder("<div class='grid grid-cols-3 gap-2'>")
                .append(smallMetric("Changed", drift.changedCount()))
                .append(smallMetric("Added", drift.addedCount()))
                .append(smallMetric("Missing", drift.missingCount())).append("</div>");
        if (!drift.details().isEmpty()) {
            out.append("<div class='mt-3 max-h-48 overflow-auto space-y-1'>");
            for (String detail : drift.details()) out.append("<p class='text-[11px] text-slate-400'>").append(esc(detail)).append("</p>");
            out.append("</div>");
        }
        return out.toString();
    }

    private static String capacity(OperationsManager.CapacityForecast forecast, List<OperationsManager.CapacitySample> samples) {
        return "<div class='grid grid-cols-2 gap-2'>" + smallMetric("Managed", bytes(forecast.managedBytes()))
                + smallMetric("Free", bytes(forecast.usableBytes())) + "</div>"
                + "<p class='mt-3 text-sm text-slate-300'>" + esc(forecast.message()) + "</p>"
                + "<p class='mt-1 text-[11px] text-slate-500'>" + samples.size() + " retained samples; daily growth "
                + bytes((long) Math.max(0.0d, forecast.growthPerDay())) + ".</p>";
    }

    private static String security(OperationsManager.Snapshot snapshot, Map<String, List<String>> roles,
            String simulatedRole, String permission, boolean allowed, boolean bridgeEnabled, String bridgeFingerprint) {
        OperationsManager.CompatibilityReport compatibility = snapshot.compatibility();
        return "<div class='grid grid-cols-1 xl:grid-cols-3 gap-4'>"
                + "<article class='ops-card'><h2 class='font-bold text-white mb-3'>Compatibility Center</h2><div class='grid grid-cols-2 gap-2'>"
                + smallMetric("Platform", compatibility.platform()) + smallMetric("Minecraft", compatibility.minecraftVersion().isBlank() ? "Unknown" : compatibility.minecraftVersion())
                + smallMetric("Java", compatibility.javaVersion()) + smallMetric("JARs", compatibility.jarCount())
                + smallMetric("Invalid", compatibility.invalidJars()) + smallMetric("Duplicates", compatibility.duplicateArtifacts())
                + "</div>" + warningList(compatibility.warnings()) + "</article>"
                + "<article class='ops-card'><h2 class='font-bold text-white mb-3'>Permission Simulator</h2><form method='get' action='/operations' class='space-y-3'><input type='hidden' name='tab' value='security'>"
                + field("Role", select("simulate_role", new ArrayList<>(roles.keySet()), simulatedRole))
                + field("Permission", "<input name='simulate_permission' maxlength='160' value='" + esc(permission) + "'>")
                + "<button class='w-full rounded-xl border border-cyan-500/40 bg-cyan-500/10 px-3 py-2 text-sm font-bold text-cyan-200'>Simulate access</button></form>"
                + "<div class='mt-3 rounded-xl border " + (allowed ? "border-emerald-500/30 bg-emerald-500/10 text-emerald-200" : "border-rose-500/30 bg-rose-500/10 text-rose-200")
                + " px-3 py-2 text-sm font-bold'>" + esc(simulatedRole) + (allowed ? " would be allowed." : " would be denied.") + "</div></article>"
                + "<article class='ops-card'><h2 class='font-bold text-white mb-3'>Security Evidence</h2>"
                + "<div class='rounded-xl border border-slate-800 bg-slate-950/35 p-3'><p class='text-xs text-slate-500'>Bridge status</p><p class='mt-1 text-sm font-bold text-white'>" + (bridgeEnabled ? "Connected / enabled" : "Not enabled") + "</p><p class='mt-1 text-xs font-mono text-cyan-300'>Fingerprint: " + esc(bridgeFingerprint) + "</p></div>"
                + "<p class='mt-3 text-xs text-slate-400'>Secret rotation is coordinated from NeoDash so the fleet record and local bridge change atomically. This page exposes only a fingerprint.</p>"
                + "<a href='/api/operations/evidence' data-dash-full-submit class='mt-3 inline-flex w-full items-center justify-center gap-2 rounded-xl bg-cyan-500 px-3 py-2 text-sm font-bold text-black'><span class='material-symbols-outlined text-[17px]'>download</span>Export signed-context JSON</a>"
                + "</article></div>";
    }

    private static String automation(OperationsManager.Snapshot snapshot, boolean canWrite, String disabled) {
        return "<div class='grid grid-cols-1 xl:grid-cols-[.8fr_1.2fr] gap-4'>"
                + "<article class='ops-card'><h2 class='font-bold text-white mb-3'>Automation Recipes</h2><p class='text-xs text-slate-500 mb-3'>Recipes create real scheduled tasks or configure the real backup scheduler.</p>"
                + (canWrite ? "<form method='post' action='/action' class='space-y-3'><input type='hidden' name='action' value='operations_recipe_create'>"
                        + field("Recipe", select("recipe", List.of("daily_backup", "nightly_restart", "hourly_save", "maintenance_notice"), "daily_backup"))
                        + field("Interval minutes", "<input type='number' name='interval' min='1' max='10080' value='1440'>")
                        + field("Message", "<input name='payload' maxlength='500' placeholder='Used by the maintenance notice recipe'>")
                        + "<button" + disabled + " class='w-full rounded-xl bg-cyan-500 px-3 py-2 text-sm font-bold text-black'>Activate recipe</button></form>" : empty("Read-only automation access."))
                + "</article><article class='ops-card'><h2 class='font-bold text-white mb-3'>Active recipes</h2>"
                + automationRows(snapshot.automations(), canWrite, disabled) + "</article></div>";
    }

    private static String automationRows(List<OperationsManager.Automation> rows, boolean canWrite, String disabled) {
        if (rows.isEmpty()) return empty("No automation recipe is active.");
        StringBuilder out = new StringBuilder("<div class='space-y-2 ops-list'>");
        for (OperationsManager.Automation row : rows) {
            out.append("<div class='rounded-xl border border-slate-800 bg-slate-950/35 p-3'><div class='flex items-start justify-between gap-3'><div><p class='text-sm font-bold text-white'>")
                    .append(esc(row.recipe().replace('_', ' '))).append("</p><p class='text-xs text-slate-500'>Every ").append(row.intervalMinutes())
                    .append(" minutes - task #").append(row.taskId()).append("</p></div><span class='text-xs ")
                    .append(row.enabled() ? "text-emerald-300" : "text-slate-500").append("'>")
                    .append(row.enabled() ? "enabled" : "paused").append("</span></div>");
            if (canWrite) {
                out.append("<form method='post' action='/action' class='mt-2'><input type='hidden' name='action' value='operations_recipe_toggle'><input type='hidden' name='automation_id' value='")
                        .append(esc(row.id())).append("'><input type='hidden' name='enabled' value='").append(row.enabled() ? "false" : "true")
                        .append("'><button").append(disabled).append(" class='text-xs font-bold text-cyan-300'>")
                        .append(row.enabled() ? "Pause" : "Enable").append("</button></form>");
            }
            out.append("</div>");
        }
        return out.append("</div>").toString();
    }

    private static String players(OperationsManager.PlayerEvidence evidence, String player) {
        String result = player.isBlank() ? "" : "<article class='ops-card'><div class='flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3'><div><h2 class='text-xl font-bold text-white'>"
                + esc(evidence.playerName()) + "</h2><p class='text-xs text-slate-500'>Latest operations activity: "
                + (evidence.latestActivity() <= 0 ? "None" : time(evidence.latestActivity())) + "</p></div><a href='/players/"
                + URLEncoder.encode(evidence.playerName(), StandardCharsets.UTF_8) + "' class='rounded-xl bg-cyan-500 px-3 py-2 text-center text-sm font-bold text-black'>Open full profile</a></div>"
                + "<div class='mt-4 grid grid-cols-3 gap-3'>" + smallMetric("Tickets", evidence.tickets())
                + smallMetric("Staff notes", evidence.notes()) + smallMetric("Incidents", evidence.incidents()) + "</div>"
                + "<p class='mt-3 text-sm text-slate-300'>Player 360 combines the existing session/playtime profile with Staff workflow and incident evidence.</p></article>";
        return "<div class='max-w-3xl mx-auto space-y-4'><article class='ops-card'><h2 class='font-bold text-white mb-3'>Player 360</h2><form method='get' action='/operations' class='flex flex-col sm:flex-row gap-2'><input type='hidden' name='tab' value='players'><input name='player' maxlength='64' required value='"
                + esc(player) + "' placeholder='Exact player name' class='flex-1'><button class='rounded-xl bg-cyan-500 px-4 py-2 text-sm font-bold text-black'>Build player view</button></form></article>"
                + result + "</div>";
    }

    private static String mobileDock() {
        return "<nav class='ops-mobile-dock' aria-label='Mobile operations'>"
                + dock("/operations?tab=overview&mobile=1", "dashboard", "Status")
                + dock("/operations?tab=incidents&mobile=1", "emergency_home", "Incident")
                + dock("/operations?tab=recovery&mobile=1", "restore", "Recovery")
                + dock("/operations?tab=players&mobile=1", "person_search", "Player")
                + "</nav>";
    }

    private static String dock(String href, String icon, String label) {
        return "<a href='" + href + "'><span class='material-symbols-outlined text-[18px]'>" + icon + "</span>" + label + "</a>";
    }

    private static String warningList(List<String> warnings) {
        if (warnings.isEmpty()) return "<div class='mt-3 rounded-xl border border-emerald-500/25 bg-emerald-500/10 px-3 py-2 text-xs text-emerald-200'>No local compatibility warning detected.</div>";
        StringBuilder out = new StringBuilder("<div class='mt-3 max-h-52 overflow-auto space-y-1'>");
        for (String warning : warnings) out.append("<p class='text-[11px] text-amber-300'>- ").append(esc(warning)).append("</p>");
        return out.append("</div>").toString();
    }

    private static String smallMetric(String label, Object value) {
        return "<div class='rounded-xl border border-slate-800 bg-slate-950/35 p-2 min-w-0'><p class='text-[10px] uppercase tracking-wider text-slate-500'>"
                + esc(label) + "</p><p class='mt-1 truncate text-sm font-bold text-white' title='" + esc(String.valueOf(value)) + "'>"
                + esc(String.valueOf(value)) + "</p></div>";
    }

    private static String field(String label, String control) {
        return "<label class='block'><span class='mb-1 block text-[11px] uppercase tracking-wider text-slate-500'>" + esc(label)
                + "</span>" + control + "</label>";
    }

    private static String select(String name, List<String> options, String selected) {
        StringBuilder out = new StringBuilder("<select name='").append(esc(name)).append("'>");
        if (options == null || options.isEmpty()) out.append("<option value=''>None available</option>");
        else for (String option : options) {
            out.append("<option value='").append(esc(option)).append("'")
                    .append(option.equalsIgnoreCase(selected == null ? "" : selected) ? " selected" : "")
                    .append(">").append(esc(option.replace('_', ' '))).append("</option>");
        }
        return out.append("</select>").toString();
    }

    private static String empty(String text) {
        return "<div class='rounded-xl border border-slate-800 bg-slate-950/25 p-4 text-center text-xs text-slate-500'>" + esc(text) + "</div>";
    }

    private static String script(String activeTab) {
        return "<script>(function(){var tabs=Array.from(document.querySelectorAll('[data-ops-tab]')),panels=Array.from(document.querySelectorAll('[data-ops-panel]'));function open(id,write){tabs.forEach(function(tab){tab.setAttribute('aria-selected',String(tab.dataset.opsTab===id));});panels.forEach(function(panel){panel.dataset.active=String(panel.dataset.opsPanel===id);});if(write){var u=new URL(location.href);u.searchParams.set('tab',id);history.replaceState(history.state,'',u);try{localStorage.setItem('dash-ops-tab',id);}catch(_){}}}tabs.forEach(function(tab){tab.addEventListener('click',function(){open(tab.dataset.opsTab,true);});});open('"
                + esc(activeTab) + "',false);})();</script>";
    }

    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> values = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) return values;
        for (String part : raw.split("&")) {
            String[] pair = part.split("=", 2);
            if (pair.length > 0) values.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                    pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "");
        }
        return values;
    }

    private static String allowedTab(String value) {
        String tab = value == null ? "" : value.toLowerCase(Locale.ROOT);
        return Set.of("overview", "planner", "incidents", "recovery", "security", "automation", "players").contains(tab)
                ? tab : "overview";
    }

    private static String safeRole(String role, Map<String, List<String>> roles) {
        if (roles == null || roles.isEmpty()) return "";
        if (role != null) for (String candidate : roles.keySet()) if (candidate.equalsIgnoreCase(role)) return candidate;
        return roles.keySet().iterator().next();
    }

    private static String clean(String value, int max, String fallback) {
        String text = value == null ? "" : value.trim();
        if (text.isBlank()) return fallback == null ? "" : fallback;
        return text.length() <= max ? text : text.substring(0, max);
    }

    private static String time(long epochMillis) {
        if (epochMillis <= 0) return "Not scheduled";
        return DATE_TIME.format(Instant.ofEpochMilli(epochMillis));
    }

    private static String bytes(long value) {
        double number = Math.max(0L, value);
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int index = 0;
        while (number >= 1024.0d && index < units.length - 1) { number /= 1024.0d; index++; }
        return String.format(Locale.ROOT, index == 0 ? "%.0f %s" : "%.1f %s", number, units[index]);
    }

    private static String esc(String text) {
        return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
