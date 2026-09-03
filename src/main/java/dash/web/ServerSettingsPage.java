package dash.web;

import dash.FabricDash;

import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

public final class ServerSettingsPage {

    public enum ServerType {
        NEOFORGE
    }

    private ServerSettingsPage() {
    }

    public static ServerType detectServerType() {
        return ServerType.NEOFORGE;
    }

    public static boolean supportsPaperExtras(ServerType type) {
        return false;
    }

    public static boolean supportsSparkUi(ServerType type) {
        return false;
    }

    public static String renderConfigOverview(ServerType type) {
        List<String> files = getConfigFilesForType(type);

        StringBuilder rows = new StringBuilder();
        for (String fileName : files) {
            FileSummary summary = readSummary(fileName);
            rows.append("<div class=\"flex items-center justify-between p-2 rounded bg-white/5\">")
                    .append("<div><p class=\"text-sm text-white font-mono\">").append(escapeHtml(fileName)).append("</p>")
                    .append("<p class=\"text-xs text-slate-500\">").append(escapeHtml(summary.details())).append("</p></div>")
                    .append("<span class=\"text-xs ").append(summary.ok() ? "text-emerald-400\"" : "text-slate-500\"")
                    .append(">")
                    .append(summary.ok() ? "Loaded" : "Not found")
                    .append("</span></div>");
        }

        return "<div class=\"rounded-3xl bg-glass-surface backdrop-blur-xl border border-glass-border overflow-hidden\">"
                + "<div class=\"flex items-center gap-3 px-4 py-3 border-b border-white/5\">"
                + "<span class=\"material-symbols-outlined text-primary\">dns</span>"
                + "<h2 class=\"text-sm font-display font-semibold text-white tracking-tight\">Server Config Files (NeoForge)</h2>"
                + "</div>"
                + "<div class=\"p-3 flex flex-col gap-2\">"
                + rows
                + "</div></div>";
    }

    public static List<String> getConfigFilesForType(ServerType type) {
        List<String> files = new ArrayList<>();
        files.add("server.properties");
        File neoForgeServerConfig = resolveServerFile("config/neoforge-server.toml");
        if (neoForgeServerConfig.exists()) {
            files.add("config/neoforge-server.toml");
        }
        return files;
    }

    public static Properties loadServerPropertiesSafe() {
        File file = resolveServerFile("server.properties");
        Properties props = new Properties();
        if (!file.exists() || !file.isFile()) {
            return props;
        }

        try (FileInputStream fis = new FileInputStream(file)) {
            props.load(fis);
        } catch (Exception ignored) {
            // Fail soft so the settings page can still render on any server distribution.
        }
        return props;
    }

    public static String readServerPropertySafe(String key, String defaultValue) {
        if (key == null || key.isBlank()) {
            return defaultValue;
        }
        try {
            String value = loadServerPropertiesSafe().getProperty(key);
            return value == null ? defaultValue : value;
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private static FileSummary readSummary(String fileName) {
        try {
            File file = resolveServerFile(fileName);
            if (!file.exists() || !file.isFile()) {
                return new FileSummary(false, "File missing");
            }
            long size = Files.size(file.toPath());
            long lines = 0L;
            try (Stream<String> stream = Files.lines(file.toPath())) {
                lines = stream.count();
            } catch (Exception ignored) {
                // If line counting fails, keep size-only summary.
            }
            if (lines > 0) {
                return new FileSummary(true, size + " bytes, " + lines + " lines");
            }
            return new FileSummary(true, size + " bytes");
        } catch (Exception ex) {
            return new FileSummary(false, "Read failed: " + ex.getClass().getSimpleName());
        }
    }

    private record FileSummary(boolean ok, String details) {
    }

    private static File resolveServerFile(String fileName) {
        File runDir = FabricDash.getServerRootDirectory();
        File primary = new File(runDir, fileName);
        if (primary.exists()) {
            return primary;
        }
        try {
            Path resolved = FabricDash.getServer().getServerDirectory().resolve(fileName);
            if (resolved != null) {
                File fromPath = resolved.toFile();
                if (fromPath.exists()) {
                    return fromPath;
                }
            }
        } catch (Exception ignored) {
        }
        return primary;
    }

    private static String escapeHtml(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
