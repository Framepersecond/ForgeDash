package dash.security;

import dash.data.IntelligenceManager;
import dash.web.IntelligencePage;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/** Generates deterministic browser-QA pages from the real intelligence rendering stack. */
public final class IntelligencePreviewGenerator {
    private IntelligencePreviewGenerator() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) throw new IllegalArgumentException("output directory, slug and product name are required");
        Path output = Path.of(args[0]).toAbsolutePath().normalize();
        String slug = args[1].replaceAll("[^a-z0-9-]", "");
        String product = args[2];
        Path root = Files.createTempDirectory("dash-intelligence-preview");
        try {
            Path data = Files.createDirectories(root.resolve(".intelligence"));
            Method fixture = IntelligenceWorkflowTest.class.getDeclaredMethod(
                    "prepareServerFixture", Path.class, Path.class);
            fixture.setAccessible(true);
            fixture.invoke(null, root, data);

            IntelligenceManager manager = new IntelligenceManager(data, root, product);
            manager.captureState("Before preview rollout", "preview-admin");
            Files.writeString(root.resolve("config/example.yml"), "enabled: false\nlimit: 18\n",
                    StandardCharsets.UTF_8);
            manager.recordPerformance(20.0, 9.5, 640, 8, "baseline");
            manager.recordPerformance(17.2, 31.0, 1_120, 14, "sample");
            manager.saveGuardrail("restart*", 5, true, 1, 5, true, true);
            manager.createSupportCase("appeal", "Alex", "Appeal review",
                    "Please review the evidence attached to this case.", "preview-admin");
            IntelligenceManager.SupportCase support = manager.supportCases("open", "Alex", 10).get(0);
            manager.addSupportReply(support.id(), "preview-admin", "Evidence review is in progress.", true);
            manager.grantTemporaryAccess("night-operator", "dash.web.intelligence.read", 30, "preview-admin");
            manager.saveRetentionPolicy(1, 7, 7, 2, "preview-admin");
            manager.recordServiceSample(true, 19.7, 13.2);
            manager.createWarRoom("Authentication latency", "warning",
                    "Some joins are taking longer than the normal envelope.", "preview-admin");
            IntelligenceManager.WarRoom room = manager.warRooms(10).get(0);
            manager.addWarRoomUpdate(room.id(), "preview-admin", "Mitigation is active and latency is falling.",
                    "action", true);
            manager.updateStatusComponent("Authentication", "degraded",
                    "Login may take a few seconds longer than usual.", "preview-admin");

            Files.createDirectories(output);
            List<String> tabs = List.of("lab", "change", "players", "policy", "supply", "reliability", "response");
            IntelligencePage.RuntimeMetrics metrics = new IntelligencePage.RuntimeMetrics(true, 19.7, 13.2, 1_024, 14);
            for (String tab : tabs) {
                String query = "tab=" + tab;
                if ("players".equals(tab)) query += "&player=Alex";
                if ("supply".equals(tab)) query += "&q=Alex";
                String html = IntelligencePage.render(manager, "Preview data is live.", query,
                        true, true, "preview-admin", 0L, List.of(), metrics, null, null);
                html = rewriteLinks(html, slug, tabs);
                Files.writeString(output.resolve(slug + "-" + tab + ".html"), html, StandardCharsets.UTF_8);
            }
            String status = IntelligencePage.renderPublicStatus(manager.publicStatus(), product + " Preview", 0L,
                    List.of());
            Files.writeString(output.resolve(slug + "-status.html"), status, StandardCharsets.UTF_8);
            System.out.println(output.resolve(slug + "-lab.html"));
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static String rewriteLinks(String html, String slug, List<String> tabs) {
        String result = html;
        for (String tab : tabs) {
            result = result.replaceAll("href='/intelligence\\?tab=" + tab + "[^']*'",
                    "href='/" + slug + "-" + tab + ".html'");
        }
        return result.replace("href='/status'", "href='/" + slug + "-status.html'");
    }
}
