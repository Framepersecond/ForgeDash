package dash.security;

import dash.ai.AiSecurityTest;
import dash.data.BackupManager;
import dash.web.DashDoctorPage;
import dash.web.HtmlTemplate;
import dash.web.PluginBrowserPage;
import dash.web.StaffPage;
import dash.web.PublicReportLinks;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public final class SecurityUtilitiesTest {
    private SecurityUtilitiesTest() {
    }

    public static void main(String[] args) throws Exception {
        testPasswordHasherAndLegacyCompatibility();
        testLoginRateLimiter();
        testReleaseDigestVerification();
        testCanonicalPrefixTraversalInvariant();
        testDiscordWebhookPolicy();
        testDatapackArchiveAndPathPolicy();
        testJarUploadPolicy();
        testDirectBrowserInstallRequiresDigest();
        testStaffWorkflowPersistence();
        testOneTimePublicReports();
        testDashDoctorCrashActions();
        testGuardrailChallengeContract();
        testBackupArchiveExcludesItself();
        testReloadDoesNotLogOut();
        testEnglishAndSpanishInterfaces();
        OperationsWorkflowTest.run();
        IntelligenceWorkflowTest.run();
        AiSecurityTest.run();
        System.out.println("Dash security utility tests passed.");
    }

    private static void testGuardrailChallengeContract() {
        String scripts = HtmlTemplate.bodyEnd();
        check(scripts.contains("X-Dash-Reason-Required"),
                "mutation requests must recognize the guardrail reason challenge header");
        check(scripts.contains("body.set('reason',reason)"),
                "a challenged mutation must replay its original payload with the operator reason");
        check(scripts.contains("dashRequestActionReason"),
                "the dashboard must expose a focused operator reason dialog");

        java.util.Map<String, String> payload = new java.util.LinkedHashMap<>();
        payload.put("action", "stop");
        payload.put("note", "<script>alert('x')</script>");
        payload.put("password", "challenge-secret-must-not-leak");
        payload.put("return_to", "/server?id=7&tab=controls");
        String html = dash.web.GuardrailChallengePage.render("Reason is required.", payload);
        check(html.contains("name='action' value='stop'"),
                "the full-page challenge must preserve the guarded action");
        check(html.contains("&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt;"),
                "replayed challenge parameters must be HTML escaped");
        check(!html.contains("challenge-secret-must-not-leak"),
                "password parameters must never be replayed into the challenge page");
        check(html.contains("href='/server?id=7&amp;tab=controls'"),
                "the challenge cancel link must preserve a safe local return path");

        String unsafe = dash.web.GuardrailChallengePage.render("Reason is required.",
                java.util.Map.of("action", "stop", "return_to", "//example.invalid"));
        check(unsafe.contains("href='/'"), "external challenge return paths must be rejected");
    }

    private static void testBackupArchiveExcludesItself() throws Exception {
        Path root = Files.createTempDirectory("dash-backup-self-test");
        try {
            Path backupRoot = Files.createDirectories(root.resolve("config/dash/backups"));
            Files.writeString(root.resolve("config/settings.yml"), "enabled: true", StandardCharsets.UTF_8);
            Path mods = Files.createDirectories(root.resolve("mods"));
            Files.write(mods.resolve("example.jar"), new byte[] { 1, 2, 3, 4 });
            Files.writeString(backupRoot.resolve("older.zip"), "must not be nested", StandardCharsets.UTF_8);
            Path archive = backupRoot.resolve("current.zip.part");

            int entries = BackupManager.writeBackupArchive(root, backupRoot, archive, List.of("config", "mods"));
            check(entries == 2, "the backup must include config and jar data without including its own directory");
            try (ZipFile zip = new ZipFile(archive.toFile())) {
                List<String> names = zip.stream().map(java.util.zip.ZipEntry::getName).toList();
                check(names.contains("config/settings.yml"), "configuration files must be included");
                check(names.contains("mods/example.jar"), "plugin or mod jars must be included in restore points");
                check(names.stream().noneMatch(name -> name.contains("/backups/")),
                        "backup archives must never contain the backup directory or themselves");
            }
            check(Files.size(archive) < 1024L * 1024L, "a small fixture backup must stay bounded");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static void testReloadDoesNotLogOut() {
        String html = HtmlTemplate.head("Reload test");
        check(!html.contains("beforeunload"), "normal page unloads must not destroy the authenticated session");
        check(!html.contains("sendBeacon('/api/logout')"), "reloads must never trigger the logout endpoint");
    }

    private static void testPasswordHasherAndLegacyCompatibility() throws Exception {
        String password = "correct horse battery staple";
        byte[] salt = "0123456789abcdef".getBytes(StandardCharsets.US_ASCII);
        String current = PasswordHasher.hash(password, salt);
        check(PasswordHasher.verify(password, salt, current), "PBKDF2 hash must verify");
        check(!PasswordHasher.verify("wrong password", salt, current), "wrong password must be rejected");
        check(!PasswordHasher.needsUpgrade(current), "new PBKDF2 hash must not require migration");

        MessageDigest legacyDigest = MessageDigest.getInstance("SHA-256");
        legacyDigest.update(salt);
        String legacy = Base64.getEncoder().encodeToString(
                legacyDigest.digest(password.getBytes(StandardCharsets.UTF_8)));
        check(PasswordHasher.verify(password, salt, legacy), "legacy salted SHA-256 must remain login-compatible");
        check(PasswordHasher.needsUpgrade(legacy), "legacy hash must be marked for migration");

        String embedded = PasswordHasher.hash(password);
        check(PasswordHasher.verify(password, embedded), "embedded NeoDash-style PBKDF2 hash must verify");
    }

    private static void testLoginRateLimiter() {
        LoginRateLimiter limiter = new LoginRateLimiter(2, Duration.ofMinutes(15), Duration.ofMinutes(15));
        String key = "127.0.0.1|admin";
        check(limiter.isAllowed(key), "new login key must be allowed");
        limiter.recordFailure(key);
        check(limiter.isAllowed(key), "one failure must remain below the threshold");
        limiter.recordFailure(key);
        check(!limiter.isAllowed(key), "threshold failures must lock the key");
        check(limiter.retryAfterSeconds(key) > 0, "locked key must expose a retry delay");
        limiter.recordSuccess(key);
        check(limiter.isAllowed(key), "successful authentication must clear failures");
    }

    private static void testReleaseDigestVerification() throws Exception {
        Path artifact = Files.createTempFile("dash-release-digest", ".jar");
        try {
            Files.writeString(artifact, "verified artifact", StandardCharsets.UTF_8);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String expected = "sha256:" + HexFormat.of().formatHex(
                    digest.digest(Files.readAllBytes(artifact)));
            ReleaseAssetVerifier.verifySha256(artifact, expected);

            boolean rejected = false;
            try {
                ReleaseAssetVerifier.verifySha256(artifact, "sha256:" + "0".repeat(64));
            } catch (java.io.IOException expectedFailure) {
                rejected = true;
            }
            check(rejected, "a mismatched GitHub digest must be rejected");
        } finally {
            Files.deleteIfExists(artifact);
        }
    }

    private static void testCanonicalPrefixTraversalInvariant() throws Exception {
        Path parent = Files.createTempDirectory("dash-prefix-test");
        try {
            Path root = Files.createDirectory(parent.resolve("server"));
            Path sibling = Files.createDirectory(parent.resolve("server-evil"));
            check(!sibling.toFile().getCanonicalFile().toPath()
                            .startsWith(root.toFile().getCanonicalFile().toPath()),
                    "a sibling sharing the root name prefix must not be contained");
        } finally {
            try (var paths = Files.walk(parent)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static void testDiscordWebhookPolicy() {
        check(DiscordWebhookPolicy.isAllowed("https://discord.com/api/webhooks/123/token"), "an official Discord webhook must be accepted");
        check(!DiscordWebhookPolicy.isAllowed("http://discord.com/api/webhooks/123/token"), "Discord webhooks must use HTTPS");
        check(!DiscordWebhookPolicy.isAllowed("https://127.0.0.1/api/webhooks/123/token"), "local webhook targets must be rejected");
        check(!DiscordWebhookPolicy.isAllowed("https://discord.com.evil.test/api/webhooks/123/token"), "lookalike Discord domains must be rejected");
    }

    private static void testDatapackArchiveAndPathPolicy() throws Exception {
        byte[] valid = zipEntry("pack.mcmeta", "{\"pack\":{\"pack_format\":48,\"description\":\"test\"}}");
        DatapackSecurity.validateArchive(valid);
        boolean rejected = false;
        try { DatapackSecurity.validateArchive(zipEntry("../outside.txt", "bad")); }
        catch (java.io.IOException expected) { rejected = true; }
        check(rejected, "datapack traversal entries must be rejected");
        Path root = Files.createTempDirectory("dash-datapack-test");
        try {
            DatapackSecurity.writeAtomically(root, "safe.zip", valid);
            check(DatapackSecurity.resolveInstalled(root, "safe") != null, "an installed datapack must resolve inside its root");
            check(DatapackSecurity.resolveInstalled(root, "../safe") == null, "a crafted datapack action path must be rejected");
        } finally {
            try (var paths = Files.walk(root)) { for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
        }
    }

    private static void testStaffWorkflowPersistence() throws Exception {
        Path directory = Files.createTempDirectory("dash-staff-test");
        try {
            for (String priority : java.util.List.of("normal", "high", "urgent", "low")) {
                check("Staff item created.".equals(StaffPage.create(directory, "ticket", "Report " + priority,
                        "Reproducible details", priority, "Player", "admin")),
                        "staff priority " + priority + " must persist");
            }
            check("Staff item created.".equals(StaffPage.create(directory, "note", "Private note",
                    "Remember this context", "normal", "Player", "admin")),
                    "the private note selection must persist");
            Path file = directory.resolve("staff-workflow.txt");
            check(Files.isRegularFile(file), "the staff workflow file must be created");
            java.util.List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            check(lines.stream().anyMatch(line -> line.contains("|note|")), "the stored workflow must retain private notes");
            for (String priority : java.util.List.of("normal", "high", "urgent", "low")) {
                check(lines.stream().anyMatch(line -> line.contains("|" + priority + "|")),
                        "the stored workflow must retain priority " + priority);
            }
            String id = lines.stream().filter(line -> line.contains("|ticket|")).findFirst().orElseThrow().split("\\|", -1)[0];
            check("Invalid staff status.".equals(StaffPage.updateStatus(directory, id, "arbitrary")), "an unknown staff status must be rejected");
            for (String status : java.util.List.of("open", "reviewing", "waiting_player", "resolved", "dismissed")) {
                check("Staff status updated.".equals(StaffPage.updateStatus(directory, id, status)),
                        "staff status " + status + " must persist");
                check(Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                                .anyMatch(line -> line.startsWith(id + "|") && line.contains("|" + status + "|")),
                        "the persisted staff status must match " + status);
            }
            check("Staff item not found.".equals(StaffPage.updateStatus(directory, "missing", "open")),
                    "unknown staff item ids must fail clearly");
            check(!"Staff item created.".equals(StaffPage.create(directory, "ticket", "x".repeat(161), "details", "normal", "", "admin")), "oversized staff input must be rejected");
            check(!"Staff item created.".equals(StaffPage.create(directory, "ticket", "Title", "details", "normal", "x".repeat(65), "admin")), "oversized target names must be rejected");
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static void testOneTimePublicReports() throws Exception {
        Path directory = Files.createTempDirectory("dash-public-report-test");
        try {
            PublicReportLinks.CreatedLink link = PublicReportLinks.create(directory, "owner", 30, "Target", "player");
            check(link.success(), "a one-time public report link must be generated");
            check(PublicReportLinks.inspect(directory, link.token()) != null, "a fresh report token must validate");
            check(PublicReportLinks.inspect(directory, "invalid-token") == null, "invalid report tokens must be rejected");
            String result = PublicReportLinks.submit(directory, link.token(), "Player report", "Detailed evidence",
                    "player", "Target", "Reporter", "", "");
            check(result.startsWith("Report submitted"), "the first valid public report submission must succeed");
            check(PublicReportLinks.inspect(directory, link.token()) == null, "a consumed report token must not validate again");
            check(PublicReportLinks.submit(directory, link.token(), "Replay", "Replay details", "player", "Target",
                    "Reporter", "", "").contains("already been used"), "a report token must resist replay");
            PublicReportLinks.CreatedLink botLink = PublicReportLinks.create(directory, "owner", 30, "", "other");
            check(PublicReportLinks.submit(directory, botLink.token(), "Bot", "Bot details", "other", "", "", "", "filled")
                    .contains("unavailable"), "the public report bot trap must reject automated submissions");
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static void testJarUploadPolicy() throws Exception {
        byte[] valid = zipEntry("plugin.yml", "name: Test\nmain: example.Test");
        JarUploadSecurity.validateArchive(valid, java.util.Set.of("plugin.yml", "paper-plugin.yml"));
        boolean wrongPlatformRejected = false;
        try {
            JarUploadSecurity.validateArchive(valid, java.util.Set.of("fabric.mod.json"));
        } catch (java.io.IOException expected) {
            wrongPlatformRejected = true;
        }
        check(wrongPlatformRejected, "a JAR for another platform must be rejected");
        boolean traversalRejected = false;
        try {
            JarUploadSecurity.validateArchive(zipEntry("../plugin.yml", "bad"), java.util.Set.of("plugin.yml"));
        } catch (java.io.IOException expected) {
            traversalRejected = true;
        }
        check(traversalRejected, "JAR traversal entries must be rejected");
        Path directory = Files.createTempDirectory("dash-jar-upload-test");
        try {
            Path target = JarUploadSecurity.writeAtomically(directory, "Test Plugin.jar", valid);
            check(target.startsWith(directory) && Files.isRegularFile(target),
                    "a validated JAR must be written inside the selected directory");
            check(java.util.Arrays.equals(valid, Files.readAllBytes(target)),
                    "atomic JAR writes must preserve the uploaded bytes");
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static void testDirectBrowserInstallRequiresDigest() throws Exception {
        Path root = Files.createTempDirectory("dash-browser-digest-test");
        try {
            String result = PluginBrowserPage.installFromUrl(root, "mods",
                    "https://example.com/mod.jar", "mod.jar", "", "");
            check(result.contains("SHA-256"), "direct browser installs must require an explicit digest");
            check(!Files.exists(root.resolve("mods").resolve("mod.jar")),
                    "an unpinned direct install must not create a JAR");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static void testEnglishAndSpanishInterfaces() {
        String options = dash.web.I18n.optionsHtml("es-MX");
        check(options.contains("value=\"en\""), "English must be selectable in every dashboard");
        check(options.contains("value=\"es\" selected"), "Spanish locale variants must select Spanish");
        String spanish = dash.web.I18n.translatorScript("es");
        check(spanish.contains("IA de Dash"), "Spanish must translate the Dash AI workspace");
        check(spanish.contains("Estado de entrega"), "Spanish must translate notification delivery health");
        check(spanish.contains("Propuestas del agente"), "Spanish must translate agentic proposal controls");
        String english = dash.web.I18n.translatorScript("en-GB");
        check(english.contains("window.__dashLang=\"en\""), "English locale variants must normalize to English");
    }

    private static byte[] zipEntry(String name, String content) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(name));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static void testDashDoctorCrashActions() throws Exception {
        Path root = Files.createTempDirectory("dash-doctor-test");
        try {
            Path crashDir = Files.createDirectories(root.resolve("crash-reports"));
            String fileName = "crash-test.txt";
            Files.writeString(crashDir.resolve(fileName), "Description: test crash", StandardCharsets.UTF_8);
            Files.writeString(crashDir.resolve(fileName + ".reviewed"), "older review", StandardCharsets.UTF_8);
            check("Crash report marked reviewed.".equals(DashDoctorPage.resolveCrashAction(root, fileName, false)),
                    "mark reviewed must work even when an older reviewed file exists");
            check(!Files.exists(crashDir.resolve(fileName)), "mark reviewed must move the active report");
            try (var files = Files.list(crashDir)) {
                check(files.filter(path -> path.getFileName().toString().startsWith(fileName)
                                && path.getFileName().toString().endsWith(".reviewed")).count() == 2,
                        "mark reviewed must preserve both reviewed reports");
            }

            Files.writeString(crashDir.resolve(fileName), "Description: delete me", StandardCharsets.UTF_8);
            check("Crash report deleted.".equals(DashDoctorPage.resolveCrashAction(root, fileName, true)),
                    "delete must remove the selected crash report");
            check(!Files.exists(crashDir.resolve(fileName)), "deleted crash reports must no longer exist");
            check("Crash report not found.".equals(DashDoctorPage.resolveCrashAction(root, "../outside.txt", true)),
                    "crash actions must reject traversal attempts");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
