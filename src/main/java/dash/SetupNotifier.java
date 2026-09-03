package dash;

import dash.security.DiscordWebhookPolicy;
import dash.web.ServerSettingsPage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import org.slf4j.Logger;

import java.io.OutputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class SetupNotifier {

    private final FabricConfig config;
    private final Logger logger;
    private final WebAuth auth;
    private final RegistrationManager registrationManager;

    public SetupNotifier(FabricConfig config, Logger logger, WebAuth auth, RegistrationManager registrationManager) {
        this.config = config;
        this.logger = logger;
        this.auth = auth;
        this.registrationManager = registrationManager;
    }

    /**
     * Called when a player joins the server.
     */
    public void notifyPlayer(ServerPlayer player) {
        MinecraftServer activeServer = FabricDash.getServer();
        if (!auth.isSetupRequired()
                || activeServer == null
                || !player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)) {
            return;
        }

        String code = registrationManager.generateCode(
                player.getStringUUID(), player.getName().getString(), "ADMIN", List.of());
        String setupUrl = buildSetupUrl(code);
        String baseUrl = buildSetupUrl(null);

        Component clickable = Component.literal("[Dash] ").withStyle(ChatFormatting.AQUA)
                .append(Component.literal("Panel setup is still required. Click here to open setup.")
                        .withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD)
                        .withStyle(s -> s.withClickEvent(new ClickEvent.OpenUrl(URI.create(setupUrl)))));

        // Delay by one tick so the player's connection is fully initialized.
        MinecraftServer server = FabricDash.getServer();
        if (server != null) {
            server.execute(() -> {
                if (player.hasDisconnected()) return;

                player.sendSystemMessage(Component.empty());
                player.sendSystemMessage(clickable);
                player.sendSystemMessage(Component.literal("Setup URL: " + setupUrl).withStyle(ChatFormatting.GRAY));
                player.sendSystemMessage(Component.literal("Code expires in 5 minutes.").withStyle(ChatFormatting.RED));
                player.sendSystemMessage(Component.empty());
                player.sendSystemMessage(Component.literal("If clicking the link doesn't work:").withStyle(ChatFormatting.GRAY));
                player.sendSystemMessage(Component.literal("1. Go to: ").withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(baseUrl).withStyle(ChatFormatting.WHITE)));
                player.sendSystemMessage(Component.literal("2. Enter code: ").withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(code).withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD)));
                player.sendSystemMessage(Component.empty());
            });
        }

        WebActionLogger.log("SETUP_CODE_GENERATED", "Player " + player.getName().getString() + " received setup link");
    }

    public void sendDiscordSetupNotificationIfConfigured() {
        String webhookUrl = config.getString("setup-discord-webhook-url", "").trim();
        if (webhookUrl.isEmpty() || !DiscordWebhookPolicy.isAllowed(webhookUrl)) {
            return;
        }

        CompletableFuture.runAsync(() -> {
            try {
                String setupUrl = buildSetupUrl(null);
                String payload = "{\"content\":\"ForgeDash setup required on server. Open: "
                        + escapeJson(setupUrl) + "\"}";

                HttpURLConnection connection = (HttpURLConnection) DiscordWebhookPolicy.requireAllowed(webhookUrl).toURL().openConnection();
                connection.setInstanceFollowRedirects(false);
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setDoOutput(true);

                try (OutputStream os = connection.getOutputStream()) {
                    os.write(payload.getBytes(StandardCharsets.UTF_8));
                }

                int status = connection.getResponseCode();
                WebActionLogger.log("SETUP_DISCORD_NOTIFY", "Webhook status=" + status);
            } catch (Exception ex) {
                logger.warn("Failed to send setup webhook: {}", ex.getMessage());
            }
        });
    }

    private String buildSetupUrl(String code) {
        return buildSetupUrlStatic(config, code);
    }

    public static String buildSetupUrlStatic(FabricConfig config, String code) {
        String fallback = config.getString("panel-url", "").trim();
        boolean sslEnabled = config.getBoolean("ssl-enabled", config.getBoolean("ssl.enabled", false));
        String host = normalizeHost(config.getString("server-ip", ""));
        if (!isUsableHost(host)) {
            host = normalizeHost(ServerSettingsPage.readServerPropertySafe("server-ip", ""));
        }
        if (!isUsableHost(host)) {
            host = autoDetectHost();
        }
        if (!isUsableHost(host)) {
            host = "localhost";
        }

        String base = "http://" + host + ":" + FabricDash.getWebPort() + "/setup";
        if (!fallback.isEmpty()) {
            String publicBase = normalizePublicBaseUrl(fallback, sslEnabled);
            base = publicBase.endsWith("/") ? publicBase + "setup" : publicBase + "/setup";
        }

        if (code == null || code.isBlank()) {
            return base;
        }

        return base + (base.contains("?") ? "&" : "?") + "code=" + code;
    }

    public static String buildReportUrlStatic(FabricConfig config, String token) {
        boolean sslEnabled = config.getBoolean("ssl-enabled", config.getBoolean("ssl.enabled", false));
        String explicit = config.getString("report-url", "").trim();
        String base;
        if (!explicit.isBlank()) {
            base = normalizePublicBaseUrl(explicit, true);
        } else if (sslEnabled) {
            return "";
        } else {
            String host = normalizeHost(config.getString("server-ip", ""));
            if (!isUsableHost(host)) host = normalizeHost(ServerSettingsPage.readServerPropertySafe("server-ip", ""));
            if (!isUsableHost(host)) host = autoDetectHost();
            if (!isUsableHost(host)) host = "localhost";
            base = "http://" + addPortWhenRequired(host, FabricDash.getWebPort(), false);
        }
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + "/report?token=" + token;
    }

    private static String addPortWhenRequired(String host, int port, boolean sslEnabled) {
        if (sslEnabled || host.matches(".*:\\d+$") || host.startsWith("[") && host.contains("]:")) return host;
        return host.contains(":") && !host.startsWith("[") ? "[" + host + "]:" + port : host + ":" + port;
    }

    private static String normalizePublicBaseUrl(String raw, boolean sslEnabled) {
        String url = raw == null ? "" : raw.trim();
        if (url.isBlank()) {
            return "";
        }
        if (sslEnabled && url.regionMatches(true, 0, "http://", 0, 7)) {
            url = "https://" + url.substring(7);
        } else if (!url.regionMatches(true, 0, "http://", 0, 7)
                && !url.regionMatches(true, 0, "https://", 0, 8)) {
            url = (sslEnabled ? "https://" : "http://") + url;
        }
        while (url.endsWith("/") && url.length() > "https://".length()) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    private String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String normalizeHost(String raw) {
        if (raw == null) {
            return "";
        }
        String host = raw.trim();
        for (int i = 0; i < 6; i++) {
            String before = host;
            while ((host.startsWith("\"") && host.endsWith("\"")) || (host.startsWith("'") && host.endsWith("'"))) {
                host = host.substring(1, host.length() - 1).trim();
            }
            host = host.replace("\\\"", "\"").replace("\\\\", "\\");
            if (host.equals(before)) {
                break;
            }
        }
        if (host.startsWith("http://")) {
            host = host.substring("http://".length());
        } else if (host.startsWith("https://")) {
            host = host.substring("https://".length());
        }
        int slash = host.indexOf('/');
        if (slash >= 0) {
            host = host.substring(0, slash);
        }
        return host.trim();
    }

    private static boolean isUsableHost(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        String lower = host.trim().toLowerCase();
        return !("localhost".equals(lower)
                || "127.0.0.1".equals(lower)
                || "0.0.0.0".equals(lower)
                || "::1".equals(lower));
    }

    private static String autoDetectHost() {
        String publicIp = tryFetchPublicIp();
        if (isUsableHost(publicIp)) {
            return publicIp;
        }
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (nif == null || !nif.isUp() || nif.isLoopback()) {
                    continue;
                }
                for (InetAddress addr : Collections.list(nif.getInetAddresses())) {
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress() && !addr.isLinkLocalAddress()) {
                        String ip = addr.getHostAddress();
                        if (isUsableHost(ip)) {
                            return ip;
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        try {
            InetAddress local = InetAddress.getLocalHost();
            if (local != null) {
                String ip = local.getHostAddress();
                if (isUsableHost(ip)) {
                    return ip;
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private static String tryFetchPublicIp() {
        String[] services = { "https://api.ipify.org", "https://icanhazip.com" };
        for (String endpoint : services) {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(2000);
                conn.setReadTimeout(2000);
                conn.setRequestProperty("User-Agent", "ForgeDash/" + FabricDash.getModVersion());
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String line = reader.readLine();
                    if (line != null) {
                        String ip = line.trim();
                        if (ip.matches("^[0-9a-fA-F:.]{3,}$")) {
                            return ip;
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return "";
    }
}
