package dash.security;

import dash.data.OperationsManager;
import dash.web.HtmlTemplate;
import dash.web.OperationsPage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class OperationsWorkflowTest {
    private OperationsWorkflowTest() {
    }

    public static void run() throws Exception {
        Path root = Files.createTempDirectory("dash-operations-test");
        try {
            Path data = Files.createDirectories(root.resolve(".ops"));
            Files.writeString(root.resolve("server.properties"), "motd=Operations Test\n", StandardCharsets.UTF_8);
            Files.createDirectories(root.resolve("logs"));
            Files.writeString(root.resolve("logs/latest.log"),
                    "[Server thread/INFO]: Starting minecraft server version 1.21.1\n"
                            + "[Server thread/WARN]: Can't keep up! Is the server overloaded?\n"
                            + "[Server thread/ERROR]: Test exception was contained\n",
                    StandardCharsets.UTF_8);
            createPluginJar(root.resolve("plugins/example.jar"));
            createBackup(root.resolve(".neodash/backups/fixture.zip"));
            String encodedPlayer = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString("Alex".getBytes(StandardCharsets.UTF_8));
            Files.writeString(data.resolve("staff-workflow.txt"),
                    "id|note|x|x|x|x|x|" + encodedPlayer + "|" + System.currentTimeMillis() + "\n",
                    StandardCharsets.UTF_8);

            OperationsManager manager = new OperationsManager(data, root, "Test");
            check(manager.createPlan("Upgrade plugins", "plugin", "", "Controlled rollout", "admin")
                    .contains("created"), "maintenance plans must persist");
            OperationsManager.Plan plan = manager.snapshot().plans().get(0);
            check(manager.preparePlan(plan.id(), "fixture.zip").contains("passed"),
                    "preflight must accept a verified backup and compatible plugin set");
            check(manager.updatePlanStatus(plan.id(), "running").contains("updated"),
                    "a ready plan must enter running state");
            check(manager.updatePlanStatus(plan.id(), "completed").contains("updated"),
                    "a running plan must complete");

            check(manager.previewChange("configuration", "server.properties", "motd=New").valid(),
                    "safe change previews must be accepted without mutating files");
            check(!manager.previewChange("configuration", "../outside.yml", "x").valid(),
                    "change previews must reject traversal targets");

            manager.createIncident("Alex login failures", "critical", "Alex cannot join", "admin");
            check(manager.snapshot().incidents().stream().anyMatch(item -> "open".equals(item.status())),
                    "incident mode must persist an open incident");
            OperationsManager.Incident incident = manager.snapshot().incidents().get(0);
            check(manager.closeIncident(incident.id(), "Rotated credentials and verified login", "admin")
                    .contains("closed"), "incidents must close with a generated report");
            check(!manager.snapshot().incidents().get(0).report().isBlank(),
                    "post-incident reports must be generated");

            check(manager.createHandover("Monitor login latency after rotation", "admin").contains("published"),
                    "shift handovers must persist");
            OperationsManager.Handover handover = manager.snapshot().handovers().get(0);
            check(manager.acknowledgeHandover(handover.id(), "operator").contains("acknowledged"),
                    "shift handovers must be acknowledgeable");

            check(manager.saveDriftBaseline().contains("saved"), "configuration baselines must persist");
            Files.writeString(root.resolve("server.properties"), "motd=Changed\n", StandardCharsets.UTF_8);
            check(manager.driftReport().changedCount() >= 1, "configuration drift must detect changed files");

            check(manager.runRestoreDrill("fixture.zip").contains("passed"),
                    "restore drills must validate archives without extraction");
            createUnsafeBackup(root.resolve(".neodash/backups/unsafe.zip"));
            check(manager.runRestoreDrill("unsafe.zip").contains("failed safely"),
                    "restore drills must reject normalized traversal aliases");
            check(manager.compatibilityReport().jarCount() == 1
                            && manager.compatibilityReport().invalidJars() == 0,
                    "compatibility scans must recognize a valid loader artifact");

            OperationsManager.AlertBundle alert = manager.alertBundles().get(0);
            check(manager.acknowledgeAlert(alert.signature()).contains("acknowledged"),
                    "smart alert bundles must be acknowledgeable");
            check(manager.recordCapacity(true).toLowerCase().contains("recorded"),
                    "capacity samples must persist");

            check(manager.recordAutomation("hourly_save", 60, "save-all", 1, true).contains("activated"),
                    "automation recipes must persist");
            OperationsManager.Automation automation = manager.snapshot().automations().get(0);
            check(manager.setAutomationEnabled(automation.id(), false).contains("updated"),
                    "automation recipes must be pausable");

            OperationsManager.PlayerEvidence evidence = manager.playerEvidence("Alex");
            check(evidence.notes() == 1 && evidence.incidents() == 1,
                    "Player 360 must combine staff and incident evidence");
            check(OperationsManager.permissionMatches(Set.of("dash.web.settings.*"), "dash.web.settings.write"),
                    "permission simulation must honor wildcard grants");
            String export = manager.securityEvidenceJson("admin");
            check(export.startsWith("{") && export.contains("\"restoreDrills\"")
                            && !export.contains(root.toString()),
                    "security evidence must be structured and must not expose the server path");
            OperationsManager reopened = new OperationsManager(data, root, "Test");
            OperationsManager.Snapshot persisted = reopened.snapshot();
            check(persisted.plans().stream().anyMatch(item -> "completed".equals(item.status()))
                            && persisted.incidents().stream().anyMatch(item -> !item.report().isBlank())
                            && persisted.handovers().stream().anyMatch(item -> "acknowledged".equals(item.status()))
                            && persisted.drills().stream().anyMatch(item -> "passed".equals(item.status()))
                            && persisted.drills().stream().anyMatch(item -> "failed".equals(item.status())),
                    "operations state must survive manager recreation");
            String page = OperationsPage.render(manager, "Ready", true, "admin",
                    Map.of("ADMIN", List.of("*")), "tab=overview", true,
                    manager.secretFingerprint("test-secret-value"));
            String featureSurface = page.toLowerCase(java.util.Locale.ROOT);
            for (String feature : List.of("maintenance planner", "incident mode", "change preview",
                    "restore drill", "configuration drift", "compatibility center", "permission simulator",
                    "security evidence", "shift handover", "player 360", "smart alert bundles",
                    "capacity forecast", "contextual quick actions", "automation recipes",
                    "maintenance calendar", "mobile operations mode", "post-incident report")) {
                check(featureSurface.contains(feature), "operations interface is missing: " + feature);
            }
            check(page.contains("operations_recipe_create") && page.contains("operations_incident_create"),
                    "the operations interface must expose its write actions");
            check(HtmlTemplate.bodyEnd().contains("dash-command-palette")
                            && HtmlTemplate.bodyEnd().contains("Search pages and actions..."),
                    "the unified command palette must be present in the application shell");
            check(!page.contains("Deployment Rings"), "Deployment Rings must remain excluded");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static void createPluginJar(Path jar) throws Exception {
        Files.createDirectories(jar.getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(new ZipEntry("plugin.yml"));
            zip.write("name: Example\nversion: 1.0\nmain: test.Example\n".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }

    private static void createBackup(Path backup) throws Exception {
        Files.createDirectories(backup.getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(backup))) {
            zip.putNextEntry(new ZipEntry("server.properties"));
            zip.write("motd=Fixture\n".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }

    private static void createUnsafeBackup(Path backup) throws Exception {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(backup))) {
            zip.putNextEntry(new ZipEntry("nested/../server.properties"));
            zip.write("motd=Unsafe\n".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
