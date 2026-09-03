package dash.security;

import dash.data.IntelligenceManager;
import dash.web.BundledStyles;
import dash.web.IntelligencePage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** End-to-end contract for the advanced intelligence feature set shared by every Dash variant. */
public final class IntelligenceWorkflowTest {
    private IntelligenceWorkflowTest() {
    }

    public static void run() throws Exception {
        Path root = Files.createTempDirectory("dash-intelligence-test");
        try {
            Path data = Files.createDirectories(root.resolve(".intelligence"));
            prepareServerFixture(root, data);
            IntelligenceManager manager = new IntelligenceManager(data, root, "TestDash");

            testShadowBootAndDiagnosis(manager, root, data);
            testStateAndPerformance(manager, root);
            testArtifactsAndRecovery(manager, root);
            testPoliciesAndApprovals(manager);
            testPlayersAndSupport(manager);
            testAccessAndConfiguration(manager, root);
            testReliabilityAndResponse(manager, root);
            testInterface(manager);
            testRetention(manager, root, data);
            testPersistence(data, root);
        } finally {
            deleteTree(root);
        }
    }

    private static void testShadowBootAndDiagnosis(IntelligenceManager manager, Path root, Path data) throws Exception {
        check(manager.startShadowBootLab(Runnable::run, "admin").contains("started"),
                "Shadow Boot Lab must queue through the supplied executor");
        IntelligenceManager.ShadowLab lab = manager.shadowLabs(10).get(0);
        check("failed".equals(lab.status()) && lab.finishedAt() > 0
                        && lab.summary().toLowerCase().contains("launcher"),
                "Shadow Boot Lab must persist a safe terminal result when no launcher exists");

        IntelligenceManager.RootCauseReport report = manager.rootCauseReport();
        check(Set.of("warning", "critical").contains(report.severity()) && !report.evidence().isEmpty()
                        && !report.recommendations().isEmpty(),
                "Root Cause Explorer must correlate log signatures with actionable evidence");

        Path probe = data.resolve("shadow-labs/isolation-probe");
        java.lang.reflect.Method copyInputs = IntelligenceManager.class
                .getDeclaredMethod("copyShadowInputs", Path.class);
        copyInputs.setAccessible(true);
        copyInputs.invoke(manager, probe);
        Files.writeString(probe.resolve("config/example.yml"), "enabled: shadow-only\n",
                StandardCharsets.UTF_8);
        check(Files.readString(root.resolve("config/example.yml")).contains("enabled: true"),
                "Shadow Boot Lab inputs must be independent copies, never hard links to live configuration");
        deleteTree(probe);
    }

    private static void testStateAndPerformance(IntelligenceManager manager, Path root) throws Exception {
        Path config = root.resolve("config/example.yml");
        check(manager.captureState("Before rollout", "admin").contains("captured"),
                "State Time Machine must capture a bounded server state");
        IntelligenceManager.StateSnapshot snapshot = manager.stateSnapshots(10).get(0);
        check(snapshot.files() >= 3 && Files.isRegularFile(root.resolve(".intelligence/state-time-machine")
                        .resolve(snapshot.archive())),
                "State Time Machine must archive configuration and runtime artifacts");
        Files.writeString(config, "enabled: false\nlimit: 10\n", StandardCharsets.UTF_8);
        Path later = root.resolve("config/later.yml");
        Files.writeString(later, "created: after-snapshot\n", StandardCharsets.UTF_8);
        IntelligenceManager.StateDiff diff = manager.diffState(snapshot.id());
        check(diff.found() && diff.changed() >= 1 && diff.added() >= 1
                        && diff.details().stream().anyMatch(line -> line.contains("config/example.yml")),
                "State Time Machine must report changed and later-added files");
        check(manager.restoreState(snapshot.id(), "admin").contains("restored"),
                "State Time Machine must restore a selected snapshot");
        check(Files.readString(config).contains("enabled: true"),
                "State Time Machine restore must replace the live file atomically");
        check(!Files.exists(later),
                "State Time Machine restore must remove later-added managed files after preserving a safety snapshot");
        check(!manager.restoreState("../outside", "admin").contains("restored"),
                "State Time Machine must reject unknown or crafted snapshot ids");

        check(manager.recordPerformance(20.0, 8.0, 512, 3, "baseline").contains("recorded"),
                "Performance Regression Radar must store a baseline");
        check(manager.recordPerformance(15.0, 42.0, 1_200, 3, "sample").contains("recorded"),
                "Performance Regression Radar must store current samples");
        check(manager.recordPerformance(20.0, 10.0, 512, 1, "invented").contains("Unsupported"),
                "Performance Regression Radar must reject unknown sample selections");
        IntelligenceManager.PerformanceRegression regression = manager.performanceRegression();
        check("regression".equals(regression.status()) && regression.tpsDelta() < 0
                        && regression.msptDelta() > 0 && regression.memoryDeltaMb() > 0,
                "Performance Regression Radar must detect meaningful regressions");
    }

    private static void testArtifactsAndRecovery(IntelligenceManager manager, Path root) throws Exception {
        check(manager.artifactNames().contains("ExamplePlugin.jar"),
                "the Intelligence landing page must list artifacts without opening every JAR");
        List<IntelligenceManager.ResourceAttribution> attribution = manager.resourceAttribution();
        check(attribution.size() == 1 && attribution.get(0).artifact().equals("ExamplePlugin.jar")
                        && attribution.get(0).classes() == 1,
                "Plugin Resource Attribution must inventory classes and log signals");

        IntelligenceManager.DependencyGraph graph = manager.dependencyGraph();
        check(graph.nodes().stream().anyMatch(node -> "ExamplePlugin".equals(node.id()))
                        && graph.edges().stream().anyMatch(edge -> "MissingDependency".equals(edge.target()))
                        && graph.missingRequired().stream().anyMatch(item -> item.equalsIgnoreCase("MissingDependency")),
                "Dependency & Impact Map must parse required and missing dependencies");

        check(manager.backupCandidates().contains("fixture.zip"),
                "Backup Content Explorer must discover managed backup locations");
        IntelligenceManager.BackupView backup = manager.browseBackup("fixture.zip", "server", 20);
        check("Ready".equals(backup.message()) && backup.totalEntries() == 2
                        && backup.entries().stream().anyMatch(entry -> "server.properties".equals(entry.path())),
                "Backup Content Explorer must validate and list archive entries");
        check(manager.restoreBackupEntry("fixture.zip", "server.properties", "admin").contains("Restored"),
                "Backup Content Explorer must selectively restore a verified entry");
        check(Files.readString(root.resolve("server.properties")).contains("Backup Fixture"),
                "selective backup restore must update only the chosen live file");
        check(!manager.restoreBackupEntry("fixture.zip", "../outside.txt", "admin").contains("Restored"),
                "selective backup restore must reject traversal entries");

        check(manager.quarantineArtifact("ExamplePlugin.jar", "admin").contains("quarantined"),
                "Guided Safe Mode must quarantine a selected plugin");
        check(!Files.exists(root.resolve("plugins/ExamplePlugin.jar")),
                "Guided Safe Mode must remove the selected artifact from the live runtime");
        IntelligenceManager.SafeModeItem quarantine = manager.safeModeItems(10).get(0);
        check(manager.restoreQuarantinedArtifact(quarantine.id()).contains("restored"),
                "Guided Safe Mode must restore a quarantined artifact");
        check(Files.isRegularFile(root.resolve("plugins/ExamplePlugin.jar")),
                "Guided Safe Mode restore must return the artifact to its original folder");

        List<IntelligenceManager.SupplyArtifact> supply = manager.supplyChain();
        check(supply.size() == 1 && supply.get(0).sha256().matches("[0-9a-f]{64}")
                        && supply.get(0).entries() >= 3 && "unsigned".equals(supply.get(0).trust()),
                "Supply Chain Center must fully read and fingerprint runtime artifacts");
    }

    private static void testPoliciesAndApprovals(IntelligenceManager manager) {
        IntelligenceManager.MaintenanceWindow maintenance = manager.maintenanceWindow();
        check(!"Unknown".equals(maintenance.day()) && maintenance.confidence() > 0.0,
                "Adaptive Maintenance Window must use recorded player sessions");

        check(manager.saveGuardrail("delete_*", 0, false, -1, -1, false, false).contains("saved"),
                "Action Guardrails must persist player-count policies");
        IntelligenceManager.GuardDecision playerBlock = manager.authorizeAction("delete_world", "admin", "",
                1, Map.of("world", "world"));
        check(!playerBlock.allowed() && playerBlock.message().contains("players are online"),
                "Action Guardrails must block dangerous work above the online-player threshold");

        check(manager.saveGuardrail("restart_*", -1, false, -1, -1, true, true).contains("saved"),
                "Action Guardrails must persist reason and dual-control policies");
        check(manager.saveGuardrail("stop", -1, false, 2, -1, false, false).contains("require both"),
                "Action Guardrails must reject half-configured maintenance windows");
        Map<String, String> payload = Map.of("action", "restart_server", "mode", "safe");
        check(!manager.authorizeAction("restart_server", "admin", "", 0, payload).allowed(),
                "Action Guardrails must enforce operator reasons");
        IntelligenceManager.GuardDecision pending = manager.authorizeAction("restart_server", "admin",
                "Apply tested update", 0, payload);
        check(!pending.allowed() && !pending.approvalId().isBlank(),
                "Dual-Control Actions must create a pending request bound to the payload");
        check(manager.decideApproval(pending.approvalId(), "admin", true).contains("different operator"),
                "Dual-Control Actions must reject self-approval");
        check(manager.decideApproval(pending.approvalId(), "reviewer", true).contains("approved"),
                "Dual-Control Actions must accept a second operator's decision");
        check(manager.authorizeAction("restart_server", "admin", "Apply tested update", 0, payload).allowed(),
                "Dual-Control Actions must consume an approved request exactly once");
        check(!manager.authorizeAction("restart_server", "admin", "Apply tested update", 0, payload).allowed(),
                "Dual-Control Actions must not reuse a consumed approval");
        check(manager.approvals(10).stream().anyMatch(item -> "used".equals(item.status()) && item.usedAt() > 0),
                "Dual-Control Actions must preserve an auditable used state");
    }

    private static void testPlayersAndSupport(IntelligenceManager manager) {
        int initialCases = manager.supportCases("", "", 300).size();
        for (String type : List.of("support", "appeal", "bug", "billing", "other")) {
            check(manager.createSupportCase(type, "Selection-" + type, "Case " + type,
                    "Selection workflow for " + type + ".", "admin").contains("created"),
                    "Support & Appeals Inbox must accept case type " + type);
        }
        Set<String> storedTypes = manager.supportCases("", "", 300).stream()
                .map(IntelligenceManager.SupportCase::type).collect(java.util.stream.Collectors.toSet());
        check(storedTypes.containsAll(Set.of("support", "appeal", "bug", "billing", "other")),
                "Support & Appeals Inbox must retain every type selection");
        check(manager.createSupportCase("invented", "Alex", "Invalid", "Must be rejected", "admin")
                        .contains("Unsupported"),
                "Support & Appeals Inbox must reject unknown case types instead of silently changing them");
        check(manager.supportCases("", "", 300).size() == initialCases + 5,
                "an invalid support type must not create a case");

        check(manager.createSupportCase("appeal", "Alex", "Ban appeal", "Please review my case.", "admin")
                        .contains("created"),
                "Support & Appeals Inbox must create cases");
        IntelligenceManager.SupportCase support = manager.supportCases("open", "Alex", 10).get(0);
        check(manager.addSupportReply(support.id(), "admin", "Evidence is under review.", true).contains("added"),
                "Support & Appeals Inbox must add public replies");
        for (String status : List.of("open", "investigating", "waiting", "resolved", "rejected")) {
            check(manager.updateSupportCase(support.id(), status, "reviewer").contains("updated")
                            && manager.supportCases(status, "Alex", 10).stream()
                                    .anyMatch(item -> item.id().equals(support.id())),
                    "Support & Appeals Inbox must persist status selection " + status);
        }
        check(manager.updateSupportCase(support.id(), "invalid", "reviewer").contains("Unsupported"),
                "Support & Appeals Inbox must reject unknown status selections");
        check(manager.updateSupportCase(support.id(), "investigating", "reviewer").contains("updated"),
                "Support & Appeals Inbox must return the fixture case to investigating state");
        IntelligenceManager.SupportCase updated = manager.supportCases("investigating", "Alex", 10).get(0);
        check(updated.replies().size() == 1 && updated.replies().get(0).publicReply(),
                "Support & Appeals Inbox must persist reply visibility");

        IntelligenceManager.PlayerIdentity identity = manager.playerIdentity("Alex");
        check("player-uuid-1".equals(identity.uuid()) && identity.sessions() == 3
                        && "TestDash".equals(identity.source()),
                "Cross-Server Player Identity must resolve stable UUID and local server evidence");
        check(manager.searchPlayerIdentities("lex", 10).stream().anyMatch(player -> "Alex".equals(player.name())),
                "Cross-Server Player Identity must support partial-name lookup");
        check(manager.searchPlayerIdentities("%", 10).isEmpty()
                        && manager.searchPlayerIdentities("_", 10).isEmpty(),
                "player identity search must treat SQL wildcard characters as literal input");

        IntelligenceManager.PlayerJourney journey = manager.playerJourney("Alex", 50);
        Set<String> categories = journey.events().stream().map(IntelligenceManager.JourneyEvent::category)
                .collect(java.util.stream.Collectors.toSet());
        check(categories.containsAll(Set.of("session", "staff", "guardian", "support")),
                "Player Journey Replay must combine sessions, notes, Guardian and support evidence");

        IntelligenceManager.ExperienceScore experience = manager.playerExperience("Alex");
        check(experience.sessions() == 3 && experience.abruptSessions() == 1
                        && experience.score() < 100 && !experience.signals().isEmpty(),
                "Player Experience Score must quantify abrupt and short sessions plus log errors");
    }

    private static void testAccessAndConfiguration(IntelligenceManager manager, Path root) throws Exception {
        check(manager.grantTemporaryAccess("operator", "dash.web.intelligence.*", 30, "admin")
                        .contains("granted"),
                "Just-in-Time Staff Access must persist bounded temporary grants");
        check(manager.grantTemporaryAccess("operator", "invalid permission!", 30, "admin").contains("invalid")
                        && manager.grantTemporaryAccess("operator", "dash.web.intelligence.read", 0, "admin")
                                .contains("1-1440"),
                "Just-in-Time Staff Access must reject malformed permissions and out-of-range durations");
        check(manager.hasTemporaryGrant("operator", "dash.web.intelligence.write"),
                "Just-in-Time Staff Access must honor wildcard permission grants");
        IntelligenceManager.TemporaryGrant grant = manager.temporaryGrants(true).get(0);
        check(manager.revokeTemporaryAccess(grant.id(), "admin").contains("revoked")
                        && !manager.hasTemporaryGrant("operator", "dash.web.intelligence.write"),
                "Just-in-Time Staff Access must revoke grants immediately");

        IntelligenceManager.ConfigDocument document = manager.inspectConfig("config/example.yml");
        check(document.editable() && document.fields().stream().anyMatch(field -> "enabled".equals(field.key())
                        && "boolean".equals(field.type())),
                "Config Schema Assistant must infer editable scalar types");
        IntelligenceManager.ConfigDocument nested = manager.inspectConfig("config/nested.yml");
        check(nested.fields().stream().noneMatch(field -> "host".equals(field.key()))
                        && nested.fields().stream().filter(field -> "duplicate".equals(field.key()))
                                .allMatch(field -> !field.editable()),
                "Config Schema Assistant must keep nested and duplicate YAML keys read-only");
        check(!manager.updateConfigScalar("config/nested.yml", "host", "remote", "admin").contains("updated"),
                "Config Schema Assistant must reject nested keys");
        check(manager.updateConfigScalar("config/example.yml", "enabled", "maybe", "admin")
                        .contains("true or false"),
                "Config Schema Assistant must preserve inferred boolean types");
        check(manager.updateConfigScalar("config/example.yml", "enabled", "true\nunsafe: value", "admin")
                        .contains("single safe scalar"),
                "Config Schema Assistant must reject multiline scalar injection");
        String invalidPort = manager.updateConfigScalar("server.properties", "server-port", "70000", "admin");
        check(invalidPort.contains("between 1 and 65535"),
                "Config Schema Assistant must enforce semantic port constraints: " + invalidPort);
        check(manager.updateConfigScalar("config/example.yml", "enabled", "false", "admin").contains("updated"),
                "Config Schema Assistant must update an existing scalar after a safety snapshot");
        check(Files.readString(root.resolve("config/example.yml")).contains("enabled: false"),
                "Config Schema Assistant must persist the chosen scalar value");
        check(!manager.updateConfigScalar("../outside.yml", "enabled", "true", "admin").contains("updated"),
                "Config Schema Assistant must reject paths outside the server root");

        List<IntelligenceManager.SearchHit> search = manager.universalSearch("Alex", 50);
        Set<String> types = search.stream().map(IntelligenceManager.SearchHit::type)
                .collect(java.util.stream.Collectors.toSet());
        check(types.containsAll(Set.of("player", "support", "log")),
                "Fleet Universal Search must return ranked player, support and log results");
    }

    private static void testReliabilityAndResponse(IntelligenceManager manager, Path root) {
        IntelligenceManager.WorldHealth world = manager.worldHealth();
        check("healthy".equals(world.status()) && world.worlds() == 1 && world.regionFiles() == 1
                        && world.invalidRegions() == 0,
                "World Health Center must validate region headers without loading chunks");

        IntelligenceManager.StorageReport storage = manager.storageIntelligence();
        check(storage.managedBytes() > 0 && storage.usableBytes() > 0
                        && storage.buckets().stream().anyMatch(bucket -> "world".equals(bucket.category())),
                "Storage Intelligence must classify bounded server storage and available capacity");

        check(manager.recordServiceSample(true, 20.0, 10.0).contains("recorded"),
                "Service Level Dashboard must persist runtime samples");
        IntelligenceManager.ServiceLevel service = manager.serviceLevel();
        check(service.samples() == 1 && service.availabilityPercent() == 100.0
                        && "meeting".equals(service.status()),
                "Service Level Dashboard must calculate availability and health objectives");

        check(manager.createWarRoom("Login disruption", "critical", "Players cannot authenticate.", "admin")
                        .contains("opened"),
                "Operator War Room must open an incident workspace");
        check(manager.createWarRoom("Invalid severity", "invented", "Must not open.", "admin")
                        .contains("Unsupported"),
                "Operator War Room must reject unknown severity selections");
        for (String severity : List.of("info", "warning")) {
            check(manager.createWarRoom("Severity " + severity, severity, "Selection coverage.", "admin")
                            .contains("opened"),
                    "Operator War Room must accept severity selection " + severity);
        }
        IntelligenceManager.WarRoom room = manager.warRooms(20).stream()
                .filter(item -> "Login disruption".equals(item.title())).findFirst().orElseThrow();
        for (String kind : List.of("update", "decision", "action", "resolution")) {
            check(manager.addWarRoomUpdate(room.id(), "admin", "Timeline " + kind, kind,
                    "action".equals(kind)).contains("added"),
                    "Operator War Room must accept timeline kind " + kind);
        }
        check(manager.addWarRoomUpdate(room.id(), "admin", "Invalid", "invented", false)
                        .contains("Unsupported"),
                "Operator War Room must reject unknown timeline kinds");
        check(manager.updateStatusComponent("Authentication", "degraded", "Login retries may be required.", "admin")
                        .contains("updated"),
                "Public Status Page must persist a sanitized component state");
        IntelligenceManager.PublicStatus active = manager.publicStatus();
        check("major-outage".equals(active.overall())
                        && active.incidents().stream().anyMatch(incident -> incident.title().equals("Login disruption")),
                "Public Status Page must let a published critical incident raise the overall service state");
        check(manager.closeWarRoom(room.id(), "admin", "").contains("resolution is required"),
                "Operator War Room must require an explicit public resolution");
        check(manager.closeWarRoom(room.id(), "admin", "Authentication has recovered.").contains("closed"),
                "Operator War Room must close with a public resolution");
        int closedUpdates = manager.warRooms(20).stream().filter(item -> item.id().equals(room.id()))
                .findFirst().orElseThrow().updates().size();
        check(!manager.addWarRoomUpdate(room.id(), "admin", "Late mutation", "update", true)
                        .contains("added")
                        && manager.warRooms(20).stream().filter(item -> item.id().equals(room.id()))
                                .findFirst().orElseThrow().updates().size() == closedUpdates,
                "closed war rooms must reject later timeline or public-status mutations");
        check("degraded".equals(manager.publicStatus().overall()),
                "a resolved incident must stop overriding the current component health");

        for (String status : List.of("operational", "degraded", "partial-outage", "major-outage", "maintenance")) {
            check(manager.updateStatusComponent("Selection Probe", status, "State " + status, "admin")
                            .contains("updated")
                            && manager.publicStatus().components().stream().anyMatch(component ->
                                    "Selection Probe".equals(component.component()) && status.equals(component.status())),
                    "Public Status Page must retain component state selection " + status);
        }
        check(manager.updateStatusComponent("Selection Probe", "invented", "Invalid", "admin").contains("invalid"),
                "Public Status Page must reject unknown component states");
        manager.updateStatusComponent("Selection Probe", "operational", "All checks complete.", "admin");
        check(manager.warRooms(20).stream().filter(item -> item.id().equals(room.id()))
                        .findFirst().orElseThrow().updates().size() == 4
                        && "closed".equals(manager.warRooms(20).stream().filter(item -> item.id().equals(room.id()))
                                .findFirst().orElseThrow().status()),
                "Operator War Room must retain every valid timeline selection and terminal state");
    }

    private static void testRetention(IntelligenceManager manager, Path root, Path data) throws Exception {
        check(manager.saveRetentionPolicy(1, 1, 1, 1, "admin").contains("saved"),
                "Retention Center must persist bounded policies");
        check(manager.saveRetentionPolicy(0, 1, 1, 1, "admin").contains("outside"),
                "Retention Center must reject unsupported policy selections");
        IntelligenceManager.RetentionPreview preview = manager.retentionPreview();
        check(preview.files() >= 3 && preview.bytes() > 0,
                "Retention Center must preview eligible files before deletion");
        check(manager.applyRetention("admin").contains("Retention applied"),
                "Retention Center must apply only its previewed managed-file policy");
        check(!Files.exists(root.resolve("logs/old.log")) && !Files.exists(root.resolve("crash-reports/old.txt"))
                        && Files.exists(root.resolve("logs/latest.log"))
                        && Files.exists(data.resolve("backups/fixture.zip")),
                "Retention Center must delete expired diagnostics while preserving live logs and fresh backups");
        long oldBackups;
        try (var files = Files.list(data.resolve("backups"))) {
            oldBackups = files.filter(path -> path.getFileName().toString().startsWith("old-")).count();
        }
        check(oldBackups == 1, "Retention Center must preserve the configured minimum old backup generation");
    }

    private static void testInterface(IntelligenceManager manager) {
        check(manager.grantTemporaryAccess("ui-operator", "dash.web.intelligence.read", 5, "admin")
                        .contains("granted"),
                "interface fixture must create an active temporary grant");
        IntelligenceManager.TemporaryGrant uiGrant = manager.temporaryGrants(true).stream()
                .filter(grant -> "ui-operator".equals(grant.username())).findFirst().orElseThrow();
        check(manager.quarantineArtifact("ExamplePlugin.jar", "admin").contains("quarantined"),
                "interface fixture must create an active quarantine");
        IntelligenceManager.SafeModeItem uiQuarantine = manager.safeModeItems(20).stream()
                .filter(item -> item.restoredAt() == 0).findFirst().orElseThrow();
        check(manager.saveRetentionPolicy(1, 1, 1, 1, "admin").contains("saved"),
                "interface fixture must expose a non-empty retention preview");
        IntelligencePage.RuntimeMetrics metrics = new IntelligencePage.RuntimeMetrics(true, 19.8, 12.0, 768, 4);
        String initialLab = IntelligencePage.render(manager, null, null,
                true, true, "admin", 0L, List.of(), metrics, null, null);
        check(!initialLab.contains("<b>2 entries</b>"),
                "opening Intelligence must not inspect a backup until the operator selects it");
        String unavailable = IntelligencePage.renderUnavailable("render-123");
        check(unavailable.contains("Intelligence is recovering") && unavailable.contains("render-123"),
                "Intelligence render failures must return a usable diagnostic page instead of dropping HTTP");
        StringBuilder surface = new StringBuilder();
        for (String tab : List.of("lab", "change", "players", "policy", "supply", "reliability", "response")) {
            String query = "tab=" + tab + "&player=Alex&q=Alex"
                    + ("lab".equals(tab) ? "&backup=fixture.zip" : "");
            surface.append(IntelligencePage.render(manager, "Ready", query,
                    true, true, "admin", 0L, List.of(), metrics, null, null));
        }
        String html = surface.toString();
        check(BundledStyles.css().length > 50_000,
                "the precompiled dashboard stylesheet must be bundled in the release artifact");
        check(html.contains("href=\"/assets/dash-4.3.css\"") && !html.contains("cdn.tailwindcss.com"),
                "dashboard pages must use the local production stylesheet instead of Tailwind's runtime compiler");
        check(html.contains("remember();layer.classList.remove('hidden')")
                        && html.contains("Max-Age=31536000")
                        && html.contains("SameSite=Lax"),
                "the release introduction must be remembered when first shown and persist across reloads");
        manager.revokeTemporaryAccess(uiGrant.id(), "admin");
        manager.restoreQuarantinedArtifact(uiQuarantine.id());
        for (String feature : List.of(
                "Shadow Boot Lab", "Root Cause Explorer", "State Time Machine",
                "Performance Regression Radar", "Plugin Resource Attribution", "Guided Safe Mode",
                "Dependency &amp; Impact Map", "Backup Content Explorer", "Adaptive Maintenance Window",
                "Action Guardrails", "Player Journey Replay", "Support &amp; Appeals Inbox",
                "Cross-Server Player Identity", "Player Experience Score", "Just-in-Time Staff Access",
                "Dual-Control Actions", "Supply Chain Center", "Retention Center",
                "Config Schema Assistant", "Fleet Universal Search", "World Health Center",
                "Storage Intelligence", "Service Level Dashboard", "Operator War Room",
                "Public Status Page")) {
            check(html.contains(feature), "Intelligence interface is missing: " + feature);
        }
        for (String action : List.of("intel_approval_decide", "intel_backup_restore", "intel_config_update",
                "intel_guardrail_delete", "intel_guardrail_save", "intel_jit_grant", "intel_jit_revoke",
                "intel_performance_sample", "intel_retention_apply", "intel_retention_save",
                "intel_safe_quarantine", "intel_safe_restore", "intel_service_sample", "intel_shadow_start",
                "intel_state_capture", "intel_state_restore", "intel_status_update", "intel_support_create",
                "intel_support_reply", "intel_support_update", "intel_war_close", "intel_war_create",
                "intel_war_update")) {
            check(html.contains("value='" + action + "'"), "Intelligence interface is missing action: " + action);
        }
        for (String option : List.of("support", "appeal", "bug", "billing", "other", "open",
                "investigating", "waiting", "resolved", "rejected", "info", "warning", "critical",
                "update", "decision", "action", "resolution", "operational", "degraded",
                "partial-outage", "major-outage", "maintenance")) {
            check(html.contains("<option value='" + option + "'"),
                    "Intelligence interface is missing selection option: " + option);
        }
        String status = IntelligencePage.renderPublicStatus(manager.publicStatus(), "Test server", 0L, List.of());
        check(status.contains("Authentication") && status.contains("Authentication has recovered.")
                        && !status.contains("Players cannot authenticate."),
                "Public Status Page must expose published resolution text without the private war-room summary");
        check(status.contains("Test server Status") && !status.contains("Dash Status"),
                "Public Status Page branding must follow the selected service name");
    }

    private static void testPersistence(Path data, Path root) {
        IntelligenceManager reopened = new IntelligenceManager(data, root, "TestDash");
        check(!reopened.stateSnapshots(10).isEmpty(),
                "State Time Machine history must survive manager recreation");
        check(reopened.performanceRegression().status().equals("regression"),
                "Performance Regression Radar history must survive manager recreation");
        check(reopened.supportCases("investigating", "Alex", 10).size() == 1,
                "support workflow state must survive manager recreation");
        check(reopened.temporaryGrants(false).stream().anyMatch(grant -> grant.revokedAt() > 0),
                "temporary access audit history must survive manager recreation");
        check(reopened.approvals(10).stream().anyMatch(item -> "used".equals(item.status())),
                "dual-control audit history must survive manager recreation");
        check(reopened.warRooms(10).stream().anyMatch(room -> "closed".equals(room.status())),
                "war-room history must survive manager recreation");
        check(reopened.publicStatus().components().stream()
                        .anyMatch(component -> "Authentication".equals(component.component())),
                "public status components must survive manager recreation");
    }

    private static void prepareServerFixture(Path root, Path data) throws Exception {
        Files.writeString(root.resolve("server.properties"),
                "motd=Live Fixture\nmax-players=20\nserver-port=25565\n",
                StandardCharsets.UTF_8);
        Files.createDirectories(root.resolve("config"));
        Files.writeString(root.resolve("config/example.yml"), "enabled: true\nlimit: 10\n",
                StandardCharsets.UTF_8);
        Files.writeString(root.resolve("config/nested.yml"),
                "enabled: true\ndatabase:\n  host: localhost\nitems:\n  - one\nduplicate: first\nduplicate: second\n",
                StandardCharsets.UTF_8);
        Files.writeString(root.resolve("config/read-only.json"), "{\"enabled\":true,\"limit\":10}\n",
                StandardCharsets.UTF_8);

        Files.createDirectories(root.resolve("logs"));
        Files.writeString(root.resolve("logs/latest.log"),
                "[Server thread/ERROR]: ExamplePlugin failed with NoClassDefFoundError: MissingDependency\n"
                        + "[Server thread/WARN]: Alex timeout while joining the server\n",
                StandardCharsets.UTF_8);
        Files.writeString(root.resolve("logs/old.log"), "expired diagnostic\n", StandardCharsets.UTF_8);
        Files.createDirectories(root.resolve("crash-reports"));
        Files.writeString(root.resolve("crash-reports/old.txt"), "expired crash\n", StandardCharsets.UTF_8);

        createPluginJar(root.resolve("plugins/ExamplePlugin.jar"));
        Path backups = Files.createDirectories(data.resolve("backups"));
        createBackup(backups.resolve("fixture.zip"),
                "motd=Backup Fixture\nmax-players=12\nserver-port=25565\n");
        createBackup(backups.resolve("old-a.zip"), "motd=Old A\n");
        createBackup(backups.resolve("old-b.zip"), "motd=Old B\n");

        FileTime expired = FileTime.from(Instant.now().minus(10, ChronoUnit.DAYS));
        Files.setLastModifiedTime(root.resolve("logs/old.log"), expired);
        Files.setLastModifiedTime(root.resolve("crash-reports/old.txt"), expired);
        Files.setLastModifiedTime(backups.resolve("old-a.zip"), FileTime.from(Instant.now().minus(12, ChronoUnit.DAYS)));
        Files.setLastModifiedTime(backups.resolve("old-b.zip"), expired);

        createPlayerDatabase(data.resolve("playerdata.db"));
        createGuardianDatabase(data.resolve("guardian.db"));
        Path region = root.resolve("world/region/r.0.0.mca");
        Files.createDirectories(region.getParent());
        Files.write(region, new byte[8_192]);
        Files.write(root.resolve("world/level.dat"), new byte[] { 1, 2, 3, 4 });
    }

    private static void createPluginJar(Path jar) throws Exception {
        Files.createDirectories(jar.getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            addZipEntry(zip, "plugin.yml", "name: ExamplePlugin\nversion: 1.0\nmain: test.Example\n"
                    + "depend: [MissingDependency]\nsoftdepend: [Vault]\n");
            addZipEntry(zip, "test/Example.class", "fixture-bytecode");
            addZipEntry(zip, "assets/example.txt", "fixture");
        }
    }

    private static void createBackup(Path backup, String properties) throws Exception {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(backup))) {
            addZipEntry(zip, "server.properties", properties);
            addZipEntry(zip, "config/example.yml", "enabled: true\nlimit: 10\n");
        }
    }

    private static void addZipEntry(ZipOutputStream zip, String name, String value) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(value.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static void createPlayerDatabase(Path database) throws Exception {
        long now = System.currentTimeMillis();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE players(uuid TEXT PRIMARY KEY,name TEXT NOT NULL,first_join INTEGER NOT NULL,last_join INTEGER NOT NULL,total_playtime INTEGER DEFAULT 0)");
            statement.execute("CREATE TABLE sessions(id INTEGER PRIMARY KEY AUTOINCREMENT,uuid TEXT NOT NULL,join_time INTEGER NOT NULL,leave_time INTEGER,ip_address TEXT)");
            statement.execute("CREATE TABLE notes(id INTEGER PRIMARY KEY AUTOINCREMENT,uuid TEXT NOT NULL,admin_name TEXT NOT NULL,note TEXT NOT NULL,created_at INTEGER NOT NULL)");
            statement.execute("INSERT INTO players VALUES('player-uuid-1','Alex'," + (now - 86_400_000L)
                    + "," + now + ",7200000)");
            statement.execute("INSERT INTO sessions(uuid,join_time,leave_time,ip_address) VALUES"
                    + "('player-uuid-1'," + (now - 7_200_000L) + "," + (now - 3_600_000L) + ",'127.0.0.1'),"
                    + "('player-uuid-1'," + (now - 120_000L) + "," + (now - 60_000L) + ",'127.0.0.1'),"
                    + "('player-uuid-1'," + (now - 30_000L) + ",NULL,'127.0.0.1')");
            statement.execute("INSERT INTO notes(uuid,admin_name,note,created_at) VALUES"
                    + "('player-uuid-1','admin','Helpful player'," + (now - 20_000L) + ")");
        }
    }

    private static void createGuardianDatabase(Path database) throws Exception {
        long now = System.currentTimeMillis();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE guardian_block_log(id INTEGER PRIMARY KEY AUTOINCREMENT,timestamp INTEGER NOT NULL,player_name TEXT NOT NULL,action INTEGER NOT NULL,world TEXT NOT NULL,x INTEGER NOT NULL,y INTEGER NOT NULL,z INTEGER NOT NULL)");
            statement.execute("CREATE TABLE guardian_container_log(id INTEGER PRIMARY KEY AUTOINCREMENT,timestamp INTEGER NOT NULL,player_name TEXT NOT NULL,action INTEGER NOT NULL,world TEXT NOT NULL,x INTEGER NOT NULL,y INTEGER NOT NULL,z INTEGER NOT NULL)");
            statement.execute("INSERT INTO guardian_block_log(timestamp,player_name,action,world,x,y,z) VALUES("
                    + (now - 10_000L) + ",'Alex',1,'world',10,64,10)");
            statement.execute("INSERT INTO guardian_container_log(timestamp,player_name,action,world,x,y,z) VALUES("
                    + (now - 5_000L) + ",'Alex',2,'world',11,64,10)");
        }
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
