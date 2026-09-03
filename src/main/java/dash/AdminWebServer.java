package dash;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.UserBanListEntry;
import net.minecraft.server.players.UserWhiteListEntry;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRules;

import dash.web.*;
import dash.ai.AiAgentManager;
import dash.ai.AiHttpHandler;
import com.google.gson.JsonObject;
import dash.data.GuardianDataManager;
import dash.data.IntelligenceManager;
import dash.data.OperationsManager;
import dash.guardian.GuardianActionService;
import dash.bridge.ConsoleCatcher;
import dash.bridge.BridgeSecurity;
import dash.security.HttpSecurity;
import dash.security.LoginRateLimiter;
import dash.security.DiscordWebhookPolicy;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.time.Duration;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class AdminWebServer {

    private static final String SESSION_COOKIE_NAME = "dash_auth";
    private static final String BETA_FEATURES_CONFIG_KEY = "beta.enabled";
    private static final long SESSION_TTL_MS = 3600000L;
    private static final long SSO_SIGNATURE_MAX_AGE_SECONDS = 300L;

    private final MinecraftServer server;
    private final WebAuth auth;
    private final OperationsManager operationsManager;
    private final IntelligenceManager intelligenceManager;
    private final AiAgentManager aiAgentManager;
    private HttpServer httpServer;
    private ExecutorService httpExecutor;
    private int port;
    private final Map<String, SessionInfo> sessions = new ConcurrentHashMap<>();
    private final Map<String, Long> usedSsoSignatures = new ConcurrentHashMap<>();
    private final LoginRateLimiter loginRateLimiter = new LoginRateLimiter(
            5, Duration.ofMinutes(15), Duration.ofMinutes(15));

    private static class SessionInfo {
        final String username;
        final long expiresAt;
        final boolean bridgeBound;
        final String bridgeSecretSnapshot;

        SessionInfo(String username, long expiresAt, boolean bridgeBound, String bridgeSecretSnapshot) {
            this.username = username;
            this.expiresAt = expiresAt;
            this.bridgeBound = bridgeBound;
            this.bridgeSecretSnapshot = bridgeSecretSnapshot == null ? "" : bridgeSecretSnapshot.trim();
        }
    }

    private boolean portBindFailed = false;

    public AdminWebServer(MinecraftServer server, int port) {
        this.server = server;
        this.port = port;
        this.auth = new WebAuth(FabricDash.getConfigDir(), FabricDash.LOGGER);
        this.operationsManager = new OperationsManager(
                FabricDash.getDataDir(),
                FabricDash.getServerRootDirectory().toPath(),
                "NeoForge");
        this.intelligenceManager = new IntelligenceManager(
                FabricDash.getDataDir().resolve("intelligence"),
                FabricDash.getServerRootDirectory().toPath(),
                "NeoForge");
        this.aiAgentManager = new AiAgentManager(FabricDash.getDataDir().resolve("ai"));
    }

    public boolean hasPortBindFailed() {
        return portBindFailed;
    }

    public int getPort() {
        return port;
    }

    public void start() {
        ConsoleLogAppender.register();
        ensureUpdaterConfigStructure();
        PluginBrowserPage.recordStartupScan(
                FabricDash.getServerRootDirectory().toPath().toAbsolutePath().normalize(),
                FabricDash.getDataDir().toAbsolutePath().normalize(),
                "mods",
                "forge");
        portBindFailed = false;

        try {
            httpServer = HttpServer.create(new InetSocketAddress(port), 0);
            registerContexts();
            httpExecutor = Executors.newFixedThreadPool(8, runnable -> {
                Thread thread = new Thread(runnable, "forgedash-http");
                thread.setDaemon(true);
                return thread;
            });
            httpServer.setExecutor(httpExecutor);
            httpServer.start();
            FabricDash.LOGGER.info("Web admin started on port " + port);
        } catch (java.net.BindException e) {
            portBindFailed = true;
            FabricDash.LOGGER.error("================================================================");
            FabricDash.LOGGER.error(" [ForgeDash] Port {} is already in use!", port);
            FabricDash.LOGGER.error(" Use /dash port <port> in-game or console to change the port.");
            FabricDash.LOGGER.error("================================================================");
            notifyOpsPortConflict();
        } catch (IOException e) {
            FabricDash.LOGGER.error("Failed to start web server: " + e.getMessage());
        }
    }

    private void registerContexts() {
        httpServer.createContext(BundledStyles.PATH, this::serveBundledStyles);
        httpServer.createContext("/", new PageHandler());
        httpServer.createContext("/login", new PageHandler());
        httpServer.createContext("/console", new PageHandler());
        httpServer.createContext("/players", new PageHandler());
        httpServer.createContext("/files", new PageHandler());
        httpServer.createContext("/files/edit", new PageHandler());
        httpServer.createContext("/plugins", new PageHandler());
        httpServer.createContext("/plugin-browser", new PageHandler());
        httpServer.createContext("/intelligence", new PageHandler());
        httpServer.createContext("/status", new PageHandler());
        httpServer.createContext("/maintenance", new PageHandler());
        httpServer.createContext("/doctor", new PageHandler());
        httpServer.createContext("/ai", new PageHandler());
        httpServer.createContext("/staff", new PageHandler());
        httpServer.createContext("/report", new PageHandler());
        httpServer.createContext("/notifications", new PageHandler());
        httpServer.createContext("/graphs", new PageHandler());
        httpServer.createContext("/guardian", new PageHandler());
        httpServer.createContext("/users", new PageHandler());
        httpServer.createContext("/permissions", new PageHandler());
        httpServer.createContext("/settings", new PageHandler());
        httpServer.createContext("/setup", new PageHandler());
        httpServer.createContext("/waiting-room", new PageHandler());
        httpServer.createContext("/audit", new PageHandler());
        httpServer.createContext("/plugin-settings", new PageHandler());
        httpServer.createContext("/scheduled-tasks", new PageHandler());
        httpServer.createContext("/updates", new PageHandler());

        httpServer.createContext("/api/console", new ConsoleApiHandler());
        httpServer.createContext("/api/health", new HealthApiHandler());
        httpServer.createContext("/api/server/state", new HealthApiHandler());
        httpServer.createContext("/api/ping", new HealthApiHandler());
        httpServer.createContext("/api/logout", new LogoutApiHandler());
        httpServer.createContext("/api/stats", new StatsApiHandler());
        httpServer.createContext("/api/stats/history", new StatsHistoryHandler());
        httpServer.createContext("/api/guardian", new GuardianApiHandler());
        httpServer.createContext("/api/settings", new SettingsApiHandler());
        httpServer.createContext("/api/plugin-browser/search", new PluginBrowserSearchHandler());
        httpServer.createContext("/api/files/save", new FileSaveHandler());
        httpServer.createContext("/api/files/download", new FileDownloadHandler());
        httpServer.createContext("/api/backups/download", new BackupDownloadHandler());
        httpServer.createContext("/api/upload/icon", new IconUploadHandler());
        httpServer.createContext("/api/upload/datapack", new DatapackUploadHandler());
        httpServer.createContext("/api/upload/file", new FileUploadHandler());
        httpServer.createContext("/api/upload/plugin", new PluginUploadHandler());
        httpServer.createContext("/api/update/download", new UpdateDownloadHandler());
        httpServer.createContext("/api/update/restart", new UpdateRestartHandler());
        httpServer.createContext("/api/players", new BridgePlayersHandler());
        httpServer.createContext("/updates/check", new UpdateCheckHandler());
        httpServer.createContext("/api/settings/global", new GlobalSettingsApiHandler());
        httpServer.createContext("/api/players/profile", new PlayerProfileHandler());
        httpServer.createContext("/api/bridge/console", new BridgeConsoleHandler());
        httpServer.createContext("/api/bridge/rotate-secret", new BridgeSecretRotationHandler());
        httpServer.createContext("/api/webhook/approve", new WebhookApproveHandler());
        httpServer.createContext("/api/ui-language", new UiLanguageHandler());
        httpServer.createContext("/api/ai", new AiHttpHandler(aiAgentManager, this::aiUserContext,
                () -> configuredPublicPanelUrl(), this::configuredHttps, this::executeAiReadTool,
                this::executeAiMutation, value -> WebActionLogger.log("AI", value)));
        httpServer.createContext("/sso", new SsoAuthHandler(auth, this));

        httpServer.createContext("/action", new ActionHandler());
    }

    private void notifyOpsPortConflict() {
        server.execute(() -> {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (server.getPlayerList().isOp(player.nameAndId())) {
                    player.sendSystemMessage(Component.empty());
                    player.sendSystemMessage(Component.literal("[ForgeDash] ").withStyle(ChatFormatting.RED, ChatFormatting.BOLD)
                            .append(Component.literal("Port " + port + " is already in use!").withStyle(ChatFormatting.RED)));
                    player.sendSystemMessage(Component.literal("[ForgeDash] ").withStyle(ChatFormatting.RED, ChatFormatting.BOLD)
                            .append(Component.literal("Use ").withStyle(ChatFormatting.YELLOW))
                            .append(Component.literal("/dash port <port>").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD))
                            .append(Component.literal(" to change and retry.").withStyle(ChatFormatting.YELLOW)));
                    player.sendSystemMessage(Component.empty());
                }
            }
        });
    }

    /** Stops the current server, rebinds on a new port, and starts again. */
    public void rebindToPort(int newPort) {
        stop();
        try {
            Thread.sleep(200);
        } catch (InterruptedException ignored) {}
        this.port = newPort;
        start();
    }

    public void stop() {
        aiAgentManager.close();
        if (httpServer != null)
            httpServer.stop(0);
        if (httpExecutor != null) {
            httpExecutor.shutdownNow();
            httpExecutor = null;
        }
    }

    private boolean isAuthenticated(HttpExchange t) {
        return resolveSession(t) != null;
    }

    private String getSessionUser(HttpExchange t) {
        SessionInfo session = resolveSession(t);
        return session == null ? null : session.username;
    }

    private void setSession(HttpExchange t, String username) {
        WebAuth.UserInfo userInfo = auth.getUsers().get(username);
        String bridgeSecret = (userInfo != null && userInfo.bridgeUser())
                ? FabricDash.getConfig().getString("bridge.secret", "")
                : null;
        setSession(t, username, bridgeSecret);
    }

    private void setSession(HttpExchange t, String username, String bridgeSecretSnapshot) {
        long now = System.currentTimeMillis();
        sessions.entrySet().removeIf(entry -> entry.getValue().expiresAt < now);
        String token = HttpSecurity.newSessionToken();
        boolean bridgeBound = bridgeSecretSnapshot != null && !bridgeSecretSnapshot.isBlank();
        sessions.put(token, new SessionInfo(username, now + SESSION_TTL_MS, bridgeBound,
                bridgeSecretSnapshot));
        t.getResponseHeaders().add(
                "Set-Cookie",
                SESSION_COOKIE_NAME + "=" + token + "; Path=/; Max-Age=3600; HttpOnly; SameSite=Lax"
                        + HttpSecurity.secureCookieSuffix(t, configuredHttps()));
    }

    public void createAuthenticatedSession(HttpExchange t, String username) {
        setSession(t, username);
    }

    private SessionInfo resolveSession(HttpExchange t) {
        String token = getSessionToken(t);
        if (token == null) {
            return null;
        }

        SessionInfo session = sessions.get(token);
        if (session == null) {
            return null;
        }

        if (session.expiresAt <= System.currentTimeMillis()) {
            sessions.remove(token);
            return null;
        }

        if (session.bridgeBound && !bridgeSecretStillValid(session.bridgeSecretSnapshot)) {
            sessions.remove(token);
            return null;
        }

        return session;
    }

    private boolean bridgeSecretStillValid(String snapshot) {
        String current = FabricDash.getConfig().getString("bridge.secret", "");
        if (current == null || current.isBlank() || snapshot == null || snapshot.isBlank()) {
            return false;
        }
        return BridgeSecurity.equalsConstantTime(snapshot.trim().getBytes(StandardCharsets.UTF_8),
                current.trim().getBytes(StandardCharsets.UTF_8));
    }

    private String getSessionToken(HttpExchange t) {
        String cookie = t.getRequestHeaders().getFirst("Cookie");
        if (cookie == null || cookie.isBlank()) {
            return null;
        }
        String[] parts = cookie.split(";");
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.startsWith(SESSION_COOKIE_NAME + "=")) {
                String value = trimmed.substring((SESSION_COOKIE_NAME + "=").length()).trim();
                return value.isBlank() ? null : value;
            }
        }
        return null;
    }

    private void clearSession(HttpExchange t) {
        String token = getSessionToken(t);
        if (token != null) {
            sessions.remove(token);
        }
        t.getResponseHeaders().add(
                "Set-Cookie",
                SESSION_COOKIE_NAME + "=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax"
                        + HttpSecurity.secureCookieSuffix(t, configuredHttps()));
    }

    private boolean bootstrapBridgeSessionFromSignedQuery(HttpExchange t, String path) throws IOException {
        if (isAuthenticated(t)) {
            return false;
        }

        if (!FabricDash.getConfig().getBoolean("bridge.enabled", true)) {
            return false;
        }

        String rawQuery = t.getRequestURI().getRawQuery();
        String user = getQueryParam(rawQuery, "user");
        String timestampRaw = getQueryParam(rawQuery, "timestamp");
        String signature = getQueryParam(rawQuery, "signature");

        if (signature == null || user == null || timestampRaw == null) {
            return false;
        }

        if (user.isBlank()) {
            redirect(t, "/login?error=sso_invalid");
            return true;
        }
        String normalizedUser = user.trim();

        String bridgeSecret = FabricDash.getConfig().getString("bridge.secret", "").trim();
        if (bridgeSecret.isBlank()) {
            redirect(t, "/login?error=sso_invalid");
            return true;
        }

        long incomingTimestamp;
        try {
            incomingTimestamp = Long.parseLong(timestampRaw);
        } catch (NumberFormatException ex) {
            redirect(t, "/login?error=sso_invalid");
            return true;
        }

        long now = Instant.now().getEpochSecond();
        long timeDelta = now - incomingTimestamp;
        if (Math.abs(timeDelta) > SSO_SIGNATURE_MAX_AGE_SECONDS) {
            redirect(t, "/login?error=sso_expired");
            return true;
        }

        String localHmacInput = normalizedUser.toLowerCase(Locale.ROOT) + ":" + timestampRaw;
        String expected = hmacSha256Hex(localHmacInput, bridgeSecret);
        String provided = BridgeSecurity.normalizeHex(signature);
        if (expected == null
                || provided.length() != expected.length()
                || !BridgeSecurity.equalsConstantTime(expected, provided)
                || isReplaySignature(provided, System.currentTimeMillis())) {
            redirect(t, "/login?error=sso_invalid");
            return true;
        }

        WebAuth.BridgeSsoResult result = auth.getOrCreateBridgeUserForSso(normalizedUser);
        String sessionUser = result.username() == null ? normalizedUser : result.username();
        WebAuth.UserInfo bridgeUserInfo = auth.getUsers().get(sessionUser);
        boolean pendingBridgeUser = bridgeUserInfo != null && bridgeUserInfo.bridgeUser() && !bridgeUserInfo.bridgeApproved();
        if (!result.approved() || pendingBridgeUser || !isApprovedBridgeUser(sessionUser)) {
            if (bridgeUserInfo == null || !bridgeUserInfo.bridgeUser()) {
                redirect(t, "/login?error=sso_invalid");
                return true;
            }
            redirect(t, "/waiting-room?user=" + encodeForQuery(normalizedUser));
            return true;
        }

        persistNeoDashReturnUrlsFromQuery(rawQuery);
        setSession(t, sessionUser, bridgeSecret);
        String cleanedQuery = stripAuthBootstrapParams(rawQuery);
        String redirectPath = path;
        if (cleanedQuery != null && !cleanedQuery.isBlank()) {
            redirectPath = path + "?" + cleanedQuery;
        }
        if ("/login".equals(path) && (cleanedQuery == null || cleanedQuery.isBlank())) {
            redirectPath = "/";
        }
        redirect(t, redirectPath);
        return true;
    }

    private String hmacSha256Hex(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception ex) {
            return null;
        }
    }

    private boolean isReplaySignature(String signature, long now) {
        cleanupUsedSignatures(now);
        Long existing = usedSsoSignatures.putIfAbsent(signature, now);
        return existing != null && (now - existing) <= (SSO_SIGNATURE_MAX_AGE_SECONDS * 1000L);
    }

    private void cleanupUsedSignatures(long now) {
        usedSsoSignatures.entrySet().removeIf(e -> (now - e.getValue()) > (SSO_SIGNATURE_MAX_AGE_SECONDS * 1000L));
    }

    private boolean isApprovedBridgeUser(String username) {
        if (username == null || username.isBlank()) {
            return false;
        }
        WebAuth.UserInfo info = auth.getUsers().get(username);
        return info != null && info.bridgeUser() && info.bridgeApproved();
    }

    private String stripAuthBootstrapParams(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return null;
        }
        String[] parts = rawQuery.split("&");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }
            String[] pair = part.split("=", 2);
            String key = pair.length > 0 ? pair[0] : "";
            if ("user".equals(key) || "timestamp".equals(key) || "signature".equals(key) || "token".equals(key)
                    || "master_url".equals(key) || "restart_url".equals(key)) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append("&");
            }
            out.append(part);
        }
        return out.toString();
    }

    private void persistNeoDashReturnUrlsFromQuery(String rawQuery) {
        String masterUrl = getQueryParam(rawQuery, "master_url");
        String restartUrl = getQueryParam(rawQuery, "restart_url");
        FabricConfig config = FabricDash.getConfig();
        boolean changed = false;
        if (isSafePanelUrl(masterUrl)) {
            config.set("bridge.master_url", masterUrl.trim());
            changed = true;
        }
        if (isSafePanelUrl(restartUrl)) {
            config.set("bridge.restart_url", restartUrl.trim());
            changed = true;
        }
        if (changed) {
            config.save();
        }
    }

    private boolean isSafePanelUrl(String value) {
        String lower = value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    private String getClientIp(HttpExchange t) {
        if (FabricDash.getConfig().getBoolean("proxy.trust-forwarded-headers", false)) {
            String forwarded = t.getRequestHeaders().getFirst("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                String first = forwarded.split(",")[0];
                String cleaned = sanitizeIp(first);
                if (!cleaned.isBlank()) {
                    return cleaned;
                }
            }
            String realIp = t.getRequestHeaders().getFirst("X-Real-IP");
            if (realIp != null && !realIp.isBlank()) {
                String cleaned = sanitizeIp(realIp);
                if (!cleaned.isBlank()) {
                    return cleaned;
                }
            }
        }
        if (t.getRemoteAddress() != null && t.getRemoteAddress().getAddress() != null) {
            return sanitizeIp(t.getRemoteAddress().getAddress().getHostAddress());
        }
        return "";
    }

    private String sanitizeIp(String raw) {
        if (raw == null) {
            return "";
        }
        String cleaned = raw.trim();
        while ((cleaned.startsWith("\"") && cleaned.endsWith("\""))
                || (cleaned.startsWith("'") && cleaned.endsWith("'"))) {
            cleaned = cleaned.substring(1, cleaned.length() - 1).trim();
        }
        cleaned = cleaned.replace("\\\"", "\"");
        while (cleaned.startsWith("/")) {
            cleaned = cleaned.substring(1);
        }
        int portSep = cleaned.lastIndexOf(':');
        if (portSep > 0 && cleaned.indexOf(':') == portSep) {
            String portPart = cleaned.substring(portSep + 1);
            if (portPart.chars().allMatch(Character::isDigit)) {
                cleaned = cleaned.substring(0, portSep);
            }
        }
        return cleaned;
    }

    private String decodePathComponent(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (Exception ex) {
            return value;
        }
    }

    private boolean ensurePermission(HttpExchange t, String permission, boolean jsonResponse) throws IOException {
        String feature = FeatureFlags.featureForPath(t.getRequestURI().getPath());
        if (feature != null && !FeatureFlags.enabled(feature)) {
            sendResponseWithStatus(t, 404, jsonResponse ? "{\"success\":false,\"error\":\"Feature disabled\"}" : "Feature disabled");
            return false;
        }
        if (!isAuthenticated(t)) {
            t.sendResponseHeaders(403, 0);
            t.close();
            return false;
        }
        if (!ensureSameOriginMutation(t, jsonResponse)) {
            return false;
        }

        String username = getSessionUser(t);
        if (username == null || !userHasWebPermission(username, permission)) {
            WebActionLogger.log("ACCESS_DENIED",
                    "user=" + (username == null ? "anonymous" : username) + " ip=" + getClientIp(t)
                            + " path=" + t.getRequestURI().getPath() + " required=" + permission);
            t.getResponseHeaders().add("Content-Type", jsonResponse ? "application/json" : "text/html");
            if (jsonResponse) {
                sendResponseWithStatus(t, 403, "{\"success\": false, \"error\": \"Forbidden\"}");
            } else {
                sendResponseWithStatus(t, 403,
                        "<html><body style='background:#0f172a;color:#fff;font-family:sans-serif;display:flex;align-items:center;justify-content:center;height:100vh'><div><h1>403 Forbidden</h1><p>Missing permission: "
                                + permission + "</p></div></body></html>");
            }
            return false;
        }

        return true;
    }

    private boolean ensureAnyPermission(HttpExchange t, boolean jsonResponse, String... permissions) throws IOException {
        if (!isAuthenticated(t)) {
            t.sendResponseHeaders(403, 0);
            t.close();
            return false;
        }
        if (!ensureSameOriginMutation(t, jsonResponse)) {
            return false;
        }

        String username = getSessionUser(t);
        if (username != null) {
            for (String permission : permissions) {
                if (permission != null && !permission.isBlank() && userHasWebPermission(username, permission)) {
                    return true;
                }
            }
        }

        String required = String.join(" OR ", Arrays.stream(permissions)
                .filter(p -> p != null && !p.isBlank())
                .toList());
        WebActionLogger.log("ACCESS_DENIED",
                "user=" + (username == null ? "anonymous" : username) + " ip=" + getClientIp(t)
                        + " path=" + t.getRequestURI().getPath() + " requiredAny=" + required);
        t.getResponseHeaders().add("Content-Type", jsonResponse ? "application/json" : "text/html");
        if (jsonResponse) {
            sendResponseWithStatus(t, 403, "{\"success\": false, \"error\": \"Forbidden\"}");
        } else {
            sendResponseWithStatus(t, 403,
                    "<html><body style='background:#0f172a;color:#fff;font-family:sans-serif;display:flex;align-items:center;justify-content:center;height:100vh'><div><h1>403 Forbidden</h1><p>Missing permission: "
                            + required + "</p></div></body></html>");
        }
        return false;
    }

    private boolean userHasWebPermission(String username, String permission) {
        if (username == null || username.isBlank() || permission == null || permission.isBlank()) return false;
        if (auth.userHasPermission(username, permission)
                || intelligenceManager.hasTemporaryGrant(username, permission)) return true;
        if ("dash.web.intelligence.read".equals(permission)) {
            return auth.userHasPermission(username, "dash.web.stats.read")
                    || auth.userHasPermission(username, "dash.web.settings.read");
        }
        if ("dash.web.intelligence.write".equals(permission)) {
            return auth.userHasPermission(username, "dash.web.settings.write");
        }
        if ("dash.web.ai.read".equals(permission)) {
            return auth.userHasPermission(username, "dash.web.stats.read")
                    || auth.userHasPermission(username, "dash.web.settings.read");
        }
        if ("dash.web.ai.use".equals(permission)) return auth.userHasPermission(username, "dash.web.stats.read");
        return false;
    }

    private Set<String> effectiveUiPermissions(String username) {
        Set<String> permissions = new LinkedHashSet<>(auth.getEffectivePermissions(username));
        if (auth.userHasPermission(username, "dash.web.stats.read")
                || auth.userHasPermission(username, "dash.web.settings.read")) {
            permissions.add("dash.web.intelligence.read");
        }
        if (auth.userHasPermission(username, "dash.web.settings.write")) {
            permissions.add("dash.web.intelligence.write");
        }
        if (auth.userHasPermission(username, "dash.web.stats.read")
                || auth.userHasPermission(username, "dash.web.settings.read")) permissions.add("dash.web.ai.read");
        if (auth.userHasPermission(username, "dash.web.stats.read")) permissions.add("dash.web.ai.use");
        intelligenceManager.temporaryGrants(true).stream()
                .filter(grant -> grant.username().equalsIgnoreCase(username))
                .map(IntelligenceManager.TemporaryGrant::permission)
                .forEach(permissions::add);
        return Set.copyOf(permissions);
    }

    private IntelligencePage.RuntimeMetrics intelligenceRuntimeMetrics() {
        StatsCollector collector = FabricDash.getStatsCollector();
        StatsCollector.StatsSample latest = collector == null ? null : collector.getLatest();
        return new IntelligencePage.RuntimeMetrics(
                server != null,
                latest == null ? 20.0d : latest.tps,
                latest == null ? 0.0d : latest.mspt,
                latest == null ? 0L : latest.ramUsed,
                server == null ? 0 : server.getPlayerList().getPlayers().size());
    }

    private boolean ensureBridgeBearer(HttpExchange t) throws IOException {
        if (!FabricDash.getConfig().getBoolean("bridge.enabled", true)) {
            sendResponseWithStatus(t, 404, "Bridge disabled");
            return false;
        }

        String authHeader = t.getRequestHeaders().getFirst("Authorization");
        String secret = FabricDash.getConfig().getString("bridge.secret", "");
        if (!BridgeSecurity.bearerMatchesSecret(authHeader, secret)) {
            sendResponseWithStatus(t, 401, "Unauthorized");
            return false;
        }
        return true;
    }

    private boolean isBridgeBearerAuthorized(HttpExchange t) {
        if (!FabricDash.getConfig().getBoolean("bridge.enabled", true)) {
            return false;
        }
        String authHeader = t.getRequestHeaders().getFirst("Authorization");
        String secret = FabricDash.getConfig().getString("bridge.secret", "");
        return BridgeSecurity.bearerMatchesSecret(authHeader, secret);
    }

    private class PageHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            String path = t.getRequestURI().getPath();
            String query = t.getRequestURI().getQuery();

            if (bootstrapBridgeSessionFromSignedQuery(t, path)) {
                return;
            }

            if (path.equals("/status")) {
                if (!FeatureFlags.enabled("intelligence")) { sendResponseWithStatus(t, 404, "Feature disabled"); return; }
                sendResponse(t, IntelligencePage.renderPublicStatus(
                        intelligenceManager.publicStatus(), "NeoForge server", 0L, List.of()));
                return;
            }

            if (path.equals("/report")) {
                if (!FeatureFlags.enabled("tickets")) { sendResponseWithStatus(t, 404, "Reports are disabled"); return; }
                String token = getQueryParam(query, "token");
                String message = null;
                if ("POST".equalsIgnoreCase(t.getRequestMethod())) {
                    Map<String, String> params = parseFormData(new String(readRequestBodyStrict(t, 24L * 1024L), StandardCharsets.UTF_8));
                    token = params.get("token");
                    message = PublicReportLinks.submit(FabricDash.getDataDir(), token,
                            params.get("title"), params.get("body"), params.get("category"),
                            params.get("target_player"), params.get("reporter"), params.get("contact"), params.get("website"));
                    if (message.startsWith("Report submitted") && FabricDash.getServer() != null) {
                        String reporter = params.getOrDefault("reporter", "Public report");
                        String reportTitle = params.getOrDefault("title", "New public report");
                        FabricDash.getServer().execute(() -> DashCommand.notifyTicketOperators(reportTitle, reporter));
                    }
                    WebActionLogger.log("PUBLIC_REPORT_SUBMIT", "result=" + message.replaceAll("[^A-Za-z ]", ""));
                } else if (!"GET".equalsIgnoreCase(t.getRequestMethod())) {
                    sendResponseWithStatus(t, 405, "Method not allowed"); return;
                }
                sendResponse(t, PublicReportLinks.render(FabricDash.getDataDir(), token, message));
                return;
            }

            if (path.equals("/setup")) {
                String code = null;
                String msg = null;
                if (query != null && query.startsWith("code=")) {
                    code = URLDecoder.decode(query.substring(5), "UTF-8");
                }
                msg = getQueryParam(query, "msg");
                sendResponse(t, SetupPage.render(code, auth.isSetupRequired(), msg));
                return;
            }

            if (path.equals("/waiting-room")) {
                String pendingUser = getQueryParam(query, "user");
                if (pendingUser != null && !pendingUser.isBlank()) {
                    auth.getOrCreateBridgeUserForSso(pendingUser.trim());
                }
                sendResponse(t, WaitingRoomPage.render(pendingUser));
                return;
            }

            if (auth.isSetupRequired()) {
                sendResponse(t, SetupPage.render(null, true, null));
                return;
            }

            if (path.equals("/login")) {
                serveLogin(t);
                return;
            }

            if (!isAuthenticated(t)) {
                serveLogin(t);
                return;
            }

            String sessionUser = getSessionUser(t);
            HtmlTemplate.setUiPermissions(effectiveUiPermissions(sessionUser));
            HtmlTemplate.setUiUser(sessionUser);
            WebAuth.UserInfo sessionUserInfo = auth.getUsers().get(sessionUser);
            boolean sessionIsBridgeUser = sessionUserInfo != null && sessionUserInfo.bridgeUser();
            String bridgeMasterUrl = FabricDash.getConfig().getString("bridge.master_url", "");
            HtmlTemplate.setBridgeContext(sessionIsBridgeUser, bridgeMasterUrl);
            HtmlTemplate.setUiLanguage(auth.getUserLanguage(sessionUser));

            String html;
            try {
                String requestedFeature = FeatureFlags.featureForPath(path);
                if (requestedFeature != null && !FeatureFlags.enabled(requestedFeature)) {
                    redirect(t, "/settings?msg=" + encodeForQuery("The " + requestedFeature + " feature is disabled in Settings."));
                    return;
                }
                if (path.equals("/")) {
                    if (!ensurePermission(t, "dash.web.stats.read", false))
                        return;
                    html = DashboardPage.render();
                } else if (path.equals("/console")) {
                    if (!ensurePermission(t, "dash.web.console.read", false))
                        return;
                    html = dash.web.ConsolePage.render();
                } else if (path.equals("/players")) {
                    if (!ensurePermission(t, "dash.web.players.read", false))
                        return;
                    html = PlayersPage.render(query, auth);
                } else if (path.startsWith("/players/") && path.endsWith("/inventory")) {
                    if (!ensurePermission(t, "dash.web.players.read", false))
                        return;
                    String playerName = decodePathComponent(path.replace("/players/", "").replace("/inventory", ""));
                    html = InventoryPage.render(playerName);
                } else if (path.startsWith("/players/") && path.endsWith("/enderchest")) {
                    if (!ensurePermission(t, "dash.web.players.read", false))
                        return;
                    String playerName = decodePathComponent(path.replace("/players/", "").replace("/enderchest", ""));
                    html = InventoryPage.renderEnderChest(playerName);
                } else if (path.equals("/plugins")) {
                    if (!ensurePermission(t, "dash.web.plugins.read", false))
                        return;
                    html = PluginsPage.render();
                } else if (path.equals("/plugin-browser")) {
                    if (!ensurePermission(t, "dash.web.plugins.read", false))
                        return;
                    String msg = getQueryParam(query, "msg");
                    html = PluginBrowserPage.render(msg);
                } else if (path.equals("/operations")) {
                    redirect(t, "/intelligence");
                    return;
                } else if (path.equals("/intelligence")) {
                    if (!ensurePermission(t, "dash.web.intelligence.read", false))
                        return;
                    String msg = getQueryParam(query, "msg");
                    try {
                        html = IntelligencePage.render(
                                intelligenceManager,
                                msg,
                                query,
                                userHasWebPermission(sessionUser, "dash.web.intelligence.write"),
                                userHasWebPermission(sessionUser, "dash.web.users.manage"),
                                sessionUser,
                                0L,
                                List.of(),
                                intelligenceRuntimeMetrics(),
                                null,
                                null);
                    } catch (RuntimeException | LinkageError ex) {
                        String incident = java.util.UUID.randomUUID().toString().substring(0, 8);
                        FabricDash.LOGGER.error("Intelligence page render failed (incident {})", incident, ex);
                        html = IntelligencePage.renderUnavailable(incident);
                    }
                } else if (path.equals("/ai")) {
                    if (!ensurePermission(t, "dash.web.ai.read", false)) return;
                    WebAuth.UserInfo aiUser = auth.getUsers().get(sessionUser);
                    html = AiPage.render(aiAgentManager, sessionUser, aiUser == null ? "USER" : aiUser.role(),
                            auth.isMainAdmin(sessionUser), userHasWebPermission(sessionUser, "dash.web.ai.audit"),
                            getQueryParam(query, "conversation"), getQueryParam(query, "msg"),
                            "1".equals(getQueryParam(query, "setup")));
                } else if (path.equals("/maintenance")) {
                    if (!ensurePermission(t, "dash.web.settings.read", false))
                        return;
                    String msg = getQueryParam(query, "msg");
                    html = MaintenancePage.render(msg);
                } else if (path.equals("/doctor")) {
                    redirect(t, "/ai");
                    return;
                } else if (path.equals("/staff")) {
                    if (!ensurePermission(t, "dash.web.stats.read", false))
                        return;
                    String msg = getQueryParam(query, "msg");
                    html = StaffPage.render(msg, query);
                } else if (path.equals("/notifications")) {
                    if (!ensurePermission(t, "dash.web.pluginsettings.read", false))
                        return;
                    String msg = getQueryParam(query, "msg");
                    html = NotificationSettingsPage.render(msg);
                } else if (path.equals("/graphs")) {
                    if (!ensurePermission(t, "dash.web.stats.read", false))
                        return;
                    html = GraphsPage.render();
                } else if (path.equals("/guardian")) {
                    if (!ensurePermission(t, "dash.web.guardian.read", false))
                        return;
                    String msg = getQueryParam(query, "msg");
                    html = GuardianPage.render(msg);
                } else if (path.equals("/users")) {
                    if (!ensurePermission(t, "dash.web.users.manage", false))
                        return;
                    auth.reload();
                    String inviteCode = getQueryParam(query, "code");
                    String message = getQueryParam(query, "msg");
                    html = UsersPage.render(auth.getUsers(), auth.getRoleNames(), auth.getRoleValues(), sessionUser,
                            auth.isMainAdmin(sessionUser), inviteCode, message,
                            FabricDash.getRegistrationApprovalManager() == null ? List.of()
                                    : FabricDash.getRegistrationApprovalManager().listPending(),
                            auth.getPendingBridgeUsers());
                } else if (path.equals("/permissions")) {
                    if (!ensurePermission(t, "dash.web.users.manage", false))
                        return;
                    auth.reload();
                    String selectedRole = getQueryParam(query, "role");
                    String message = getQueryParam(query, "msg");
                    html = PermissionsPage.render(auth.getRolesWithPermissions(), auth.getRoleValues(), selectedRole,
                            message, auth.isMainAdmin(sessionUser), auth.getActorRoleValue(sessionUser));
                } else if (path.equals("/settings")) {
                    if (!ensurePermission(t, "dash.web.settings.read", false))
                        return;
                    String msg = getQueryParam(query, "msg");
                    int updateIntervalMinutes = 120;
                    dash.data.GlobalSettingsManager gsm = FabricDash.getGlobalSettingsManager();
                    if (gsm != null) {
                        try {
                            updateIntervalMinutes = Integer.parseInt(gsm.getGlobalSetting("update_interval_minutes", "120"));
                        } catch (NumberFormatException ignored) {
                        }
                    }
                    try {
                        html = SettingsPage.render(sessionUser, auth.isMainAdmin(sessionUser), msg, updateIntervalMinutes);
                    } catch (Exception ex) {
                        FabricDash.LOGGER.warn("Failed to render settings page: " + ex.getMessage());
                        html = HtmlTemplate.page("Settings", "/settings",
                                "<main class=\"p-4 sm:p-6 flex-1 w-full\"><div class=\"rounded-2xl border border-rose-500/30 bg-rose-500/10 p-4 text-rose-200\">"
                                        + "Settings are temporarily unavailable on this server type. Check missing config files and try again."
                                        + "</div></main>");
                    }
                } else if (path.equals("/audit")) {
                    if (!ensurePermission(t, "dash.web.audit.read", false))
                        return;
                    String searchQ = getQueryParam(query, "q");
                    html = AuditLogPage.render(searchQ);
                } else if (path.equals("/plugin-settings")) {
                    if (!ensurePermission(t, "dash.web.pluginsettings.read", false))
                        return;
                    String msg = getQueryParam(query, "msg");
                    html = PluginSettingsPage.render(msg);
                } else if (path.equals("/scheduled-tasks")) {
                    if (!ensurePermission(t, "dash.web.tasks.read", false))
                        return;
                    String msg = getQueryParam(query, "msg");
                    html = ScheduledTasksPage.render(msg);
                } else if (path.equals("/updates")) {
                    if (!ensurePermission(t, "dash.web.settings.read", false))
                        return;
                    GithubUpdater updater = FabricDash.getGithubUpdater();
                    String currentVersion = FabricDash.getModVersion();
                    String latestVersion = currentVersion;
                    boolean updateAvailable = false;
                    boolean updatePrepared = false;
                    boolean updaterEnabled = updater != null && updater.isEnabled();
                    if (updaterEnabled) {
                        latestVersion = updater.getLatestVersion();
                        updateAvailable = updater.isUpdateAvailable();
                        updatePrepared = updater.isUpdatePrepared();
                    }
                    html = UpdatesPage.render(currentVersion, latestVersion, updateAvailable, updatePrepared,
                            updaterEnabled);
                } else if (path.equals("/files")) {
                    if (!ensurePermission(t, "dash.web.files.read", false))
                        return;
                    String filePath = getQueryParam(query, "path");
                    if (filePath == null) {
                        filePath = "";
                    }
                    html = FilesPage.render(filePath);
                } else if (path.equals("/files/edit")) {
                    if (!ensurePermission(t, "dash.web.files.read", false))
                        return;
                    String filePath = getQueryParam(query, "path");
                    if (filePath == null) {
                        filePath = "";
                    }
                    html = FilesPage.renderEditor(filePath);
                } else if (path.startsWith("/players/") && path.endsWith("/profile")) {
                    if (!ensurePermission(t, "dash.web.players.read", false))
                        return;
                    String playerName = decodePathComponent(path.replace("/players/", "").replace("/profile", ""));
                    html = PlayerProfilePage.render(playerName);
                } else if (path.startsWith("/players/") && path.endsWith("/teleport")) {
                    if (!ensurePermission(t, "dash.web.players.moderate", false))
                        return;
                    String playerName = decodePathComponent(path.replace("/players/", "").replace("/teleport", ""));
                    html = TeleportPage.render(playerName);
                } else if (path.startsWith("/players/")
                        && !path.substring("/players/".length()).contains("/")) {
                    if (!ensurePermission(t, "dash.web.players.read", false))
                        return;
                    String playerName = decodePathComponent(path.replace("/players/", ""));
                    if (!playerName.isEmpty()) {
                        html = PlayerProfilePage.render(playerName);
                    } else {
                        html = PlayersPage.render(query, auth);
                    }
                } else {
                    html = DashboardPage.render();
                }
            } finally {
                HtmlTemplate.clearUiPermissions();
                HtmlTemplate.clearBridgeContext();
                HtmlTemplate.clearUiLanguage();
                HtmlTemplate.clearUiUser();
            }

            sendResponse(t, html);
        }
    }

    private void serveRegistration(HttpExchange t) throws IOException {
        String html = "<!DOCTYPE html><html class=\"dark\" lang=\"en\"><head><meta charset=\"utf-8\"/><title>Dash Setup</title>"
                +
                "<link rel=\"stylesheet\" href=\"/assets/dash-4.3.css\"></head><body class=\"bg-slate-900 text-white flex items-center justify-center h-screen\">"
                +
                "<div class=\"bg-slate-800 p-8 rounded-xl shadow-2xl w-96 border border-slate-700\">" +
                "<h2 class=\"text-2xl font-bold mb-6 text-center text-sky-400\">Dash Admin Setup</h2>" +
                "<p class=\"text-sm text-slate-400 mb-4 text-center\">Enter the registration code from /dash register</p>"
                +
                "<form action=\"/action\" method=\"post\" class=\"flex flex-col gap-4\">" +
                "<input type=\"hidden\" name=\"action\" value=\"register\">" +
                "<div><label class=\"text-sm text-slate-400\">Registration Code</label><input type=\"text\" name=\"code\" required placeholder=\"XXXXXXXX\" class=\"w-full bg-slate-900 border border-slate-600 rounded p-2 focus:border-sky-500 outline-none uppercase tracking-widest text-center font-mono\"></div>"
                +
                "<div><label class=\"text-sm text-slate-400\">Username</label><input type=\"text\" name=\"username\" required class=\"w-full bg-slate-900 border border-slate-600 rounded p-2 focus:border-sky-500 outline-none\"></div>"
                +
                "<div><label class=\"text-sm text-slate-400\">Password</label><input type=\"password\" name=\"password\" required class=\"w-full bg-slate-900 border border-slate-600 rounded p-2 focus:border-sky-500 outline-none\"></div>"
                +
                "<button type=\"submit\" class=\"bg-sky-500 hover:bg-sky-600 text-white font-bold py-2 rounded transition\">Complete Setup</button>"
                +
                "</form></div></body></html>";
        sendResponse(t, html);
    }

    private void serveLogin(HttpExchange t) throws IOException {
        String error = getQueryParam(t.getRequestURI().getRawQuery(), "error");
        sendResponse(t, LoginPage.render(error));
    }

    private class ConsoleApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if (!ensurePermission(t, "dash.web.console.read", true)) {
                return;
            }
            List<String> logs = ConsoleLogAppender.getLogs();
            String response = String.join("\n", logs);
            sendResponse(t, response);
        }
    }

    private class StatsApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            try {
                if (!"GET".equalsIgnoreCase(t.getRequestMethod())) {
                    t.sendResponseHeaders(405, -1);
                    t.close();
                    return;
                }

                if (!isSetupTelemetryBypass(t)) {
                    String authHeader = t.getRequestHeaders().getFirst("Authorization");
                    String bridgeSecret = FabricDash.getConfig().getString("bridge.secret", "");
                    boolean bridgeAuthorized = BridgeSecurity.bearerMatchesSecret(authHeader, bridgeSecret);
                    if (!bridgeAuthorized && !ensurePermission(t, "dash.web.stats.read", true)) {
                        return;
                    }
                }

                double currentTps = 20.0;
                try {
                    double mspt = server.getAverageTickTimeNanos() / 1_000_000.0;
                    currentTps = Math.min(20.0, 1000.0 / mspt);
                } catch (Throwable ignored) {
                }

                long maxMem = Runtime.getRuntime().maxMemory() / 1024 / 1024;
                long totalMem = Runtime.getRuntime().totalMemory() / 1024 / 1024;
                long freeMem = Runtime.getRuntime().freeMemory() / 1024 / 1024;
                long usedMem = totalMem - freeMem;

                double cpuPercent = 0.0;
                try {
                    java.lang.management.OperatingSystemMXBean osBean = java.lang.management.ManagementFactory
                            .getOperatingSystemMXBean();
                    if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
                        double load = ((com.sun.management.OperatingSystemMXBean) osBean).getSystemCpuLoad();
                        cpuPercent = (load < 0.0) ? 0.0 : (load * 100.0);
                    }
                } catch (Exception e) {
                }

                String uptimeStr = formatUptime();
                String dashVersion = FabricDash.getModVersion();

                StatsCollector collector = FabricDash.getStatsCollector();
                StatsCollector.StatsSample latestSample = collector != null ? collector.getLatest() : null;
                double mspt = latestSample != null ? latestSample.mspt : (currentTps > 0 ? 1000.0 / currentTps : 50.0);
                int overworldChunks = latestSample != null ? latestSample.overworldChunks : 0;
                int netherChunks = latestSample != null ? latestSample.netherChunks : 0;
                int endChunks = latestSample != null ? latestSample.endChunks : 0;

                String json = String.format(
                        "{\"tps\": %.2f, \"ram_used\": %d, \"ram_max\": %d, \"ramUsed\": %d, \"ramMax\": %d, \"cpu_percent\": %.2f, \"cpuPercent\": %.2f, \"cpuUsage\": %.2f, \"uptime\": \"%s\", \"dashVersion\": \"%s\", \"overworld_chunks\": %d, \"nether_chunks\": %d, \"end_chunks\": %d, \"mspt\": %.2f}",
                        currentTps, usedMem, maxMem, usedMem, maxMem, cpuPercent, cpuPercent, cpuPercent,
                        jsonEscape(uptimeStr), jsonEscape(dashVersion),
                        overworldChunks, netherChunks, endChunks, mspt);

                t.getResponseHeaders().add("Content-Type", "application/json");
                sendResponse(t, json);
            } catch (Exception e) {
                sendResponse(t, "{\"error\": \"Internal Error\"}");
            }
        }
    }

    private class HealthApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if (!"GET".equalsIgnoreCase(t.getRequestMethod())) {
                t.sendResponseHeaders(405, -1);
                t.close();
                return;
            }

            if (!isSetupTelemetryBypass(t)) {
                String authHeader = t.getRequestHeaders().getFirst("Authorization");
                String secret = FabricDash.getConfig().getString("bridge.secret", "");
                if (!BridgeSecurity.bearerMatchesSecret(authHeader, secret)) {
                    sendResponseWithStatus(t, 401, "{\"error\":\"Unauthorized\"}");
                    return;
                }
            }

            String dashVersion = FabricDash.getModVersion();
            String json = "{\"status\":\"online\",\"uptime\":\"" + jsonEscape(formatUptime())
                    + "\",\"dashVersion\":\"" + jsonEscape(dashVersion) + "\"}";
            t.getResponseHeaders().add("Content-Type", "application/json");
            sendResponse(t, json);
        }
    }

    private class LogoutApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if (!"POST".equalsIgnoreCase(t.getRequestMethod()) && !"GET".equalsIgnoreCase(t.getRequestMethod())) {
                t.sendResponseHeaders(405, -1);
                t.close();
                return;
            }

            clearSession(t);
            t.getResponseHeaders().add("Content-Type", "application/json");
            sendResponse(t, "{\"success\":true}");
        }
    }

    private class StatsHistoryHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if (!ensurePermission(t, "dash.web.stats.read", true)) {
                return;
            }
            try {
                StatsCollector collector = FabricDash.getStatsCollector();
                String range = getQueryParam(t.getRequestURI().getRawQuery(), "range");
                long span = graphRangeMillis(range);
                String json = collector != null
                        ? collector.getHistoryJson(System.currentTimeMillis() - span, 1_200)
                        : "[]";
                t.getResponseHeaders().add("Content-Type", "application/json");
                sendResponse(t, json);
            } catch (Exception e) {
                sendResponse(t, "[]");
            }
        }
    }

    private long graphRangeMillis(String range) {
        if ("7d".equals(range)) return TimeUnit.DAYS.toMillis(7);
        if ("24h".equals(range)) return TimeUnit.HOURS.toMillis(24);
        if ("6h".equals(range)) return TimeUnit.HOURS.toMillis(6);
        return TimeUnit.HOURS.toMillis(1);
    }

    private class GuardianApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            String path = t.getRequestURI().getPath();
            t.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            boolean bridgeAuthorized = isBridgeBearerAuthorized(t);
            if (path.startsWith("/api/guardian/export/")) {
                if (!bridgeAuthorized && !ensurePermission(t, "dash.web.guardian.export", true)) return;
                handleGuardianExport(t, path);
                return;
            }
            if (path.equals("/api/guardian/coreprotect/import")) {
                if (!"POST".equalsIgnoreCase(t.getRequestMethod())) {
                    sendResponseWithStatus(t, 405, "{\"success\":false,\"error\":\"method_not_allowed\"}");
                    return;
                }
                if (!bridgeAuthorized && !ensurePermission(t, "dash.web.guardian.import", true)) return;
                sendResponseWithStatus(t, 400, "{\"success\":false,\"error\":\"CoreProtect import is only available on Bukkit/Paper servers.\"}");
                return;
            }
            if (path.equals("/api/guardian/rollback") || path.equals("/api/guardian/restore")) {
                if (!"POST".equalsIgnoreCase(t.getRequestMethod())) {
                    sendResponseWithStatus(t, 405, "{\"success\":false,\"error\":\"method_not_allowed\"}");
                    return;
                }
                if (bridgeAuthorized) {
                    // NeoDash has already checked the human user's Guardian action permission.
                } else if (path.endsWith("/restore")) {
                    if (!ensureAnyPermission(t, true, "dash.web.guardian.restore", "dash.web.guardian.rollback",
                            "dash.web.guardian.manage")) return;
                } else if (!ensureAnyPermission(t, true, "dash.web.guardian.rollback", "dash.web.guardian.manage")) {
                    return;
                }
                handleGuardianAction(t, path.endsWith("/restore") ? "restore" : "rollback");
                return;
            }
            if (path.equals("/api/guardian/purge")) {
                if (!"POST".equalsIgnoreCase(t.getRequestMethod())) {
                    sendResponseWithStatus(t, 405, "{\"success\":false,\"error\":\"method_not_allowed\"}");
                    return;
                }
                if (!bridgeAuthorized
                        && !ensureAnyPermission(t, true, "dash.web.guardian.purge", "dash.web.guardian.manage")) return;
                handleGuardianPurge(t);
                return;
            }
            if (path.equals("/api/guardian/cases") && "POST".equalsIgnoreCase(t.getRequestMethod())) {
                if (!bridgeAuthorized
                        && !ensureAnyPermission(t, true, "dash.web.guardian.cases", "dash.web.guardian.manage")) return;
                handleGuardianCaseCreate(t);
                return;
            }
            if (path.equals("/api/guardian/cases/update") && "POST".equalsIgnoreCase(t.getRequestMethod())) {
                if (!bridgeAuthorized
                        && !ensureAnyPermission(t, true, "dash.web.guardian.cases", "dash.web.guardian.manage")) return;
                handleGuardianCaseUpdate(t);
                return;
            }
            if (path.equals("/api/guardian/cases/evidence") && "POST".equalsIgnoreCase(t.getRequestMethod())) {
                if (!bridgeAuthorized
                        && !ensureAnyPermission(t, true, "dash.web.guardian.cases", "dash.web.guardian.manage")) return;
                handleGuardianEvidenceAdd(t);
                return;
            }
            if (path.equals("/api/guardian/player-notes") && "POST".equalsIgnoreCase(t.getRequestMethod())) {
                if (!bridgeAuthorized
                        && !ensureAnyPermission(t, true, "dash.web.guardian.notes", "dash.web.guardian.manage")) return;
                handleGuardianPlayerNoteSave(t);
                return;
            }
            if (path.equals("/api/guardian/player-notes/delete") && "POST".equalsIgnoreCase(t.getRequestMethod())) {
                if (!bridgeAuthorized
                        && !ensureAnyPermission(t, true, "dash.web.guardian.notes", "dash.web.guardian.manage")) return;
                handleGuardianPlayerNoteDelete(t);
                return;
            }
            if (path.equals("/api/guardian/saved-filters") && "POST".equalsIgnoreCase(t.getRequestMethod())) {
                if (!bridgeAuthorized
                        && !ensureAnyPermission(t, true, "dash.web.guardian.filters", "dash.web.guardian.manage")) return;
                handleGuardianFilterSave(t);
                return;
            }
            if (path.equals("/api/guardian/saved-filters/delete") && "POST".equalsIgnoreCase(t.getRequestMethod())) {
                if (!bridgeAuthorized
                        && !ensureAnyPermission(t, true, "dash.web.guardian.filters", "dash.web.guardian.manage")) return;
                handleGuardianFilterDelete(t);
                return;
            }
            if (path.equals("/api/guardian/protected-regions") && "POST".equalsIgnoreCase(t.getRequestMethod())) {
                if (!bridgeAuthorized && !ensurePermission(t, "dash.web.guardian.manage", true)) return;
                handleGuardianProtectedRegionSave(t);
                return;
            }
            if (path.equals("/api/guardian/protected-regions/delete") && "POST".equalsIgnoreCase(t.getRequestMethod())) {
                if (!bridgeAuthorized && !ensurePermission(t, "dash.web.guardian.manage", true)) return;
                handleGuardianProtectedRegionDelete(t);
                return;
            }
            if (path.equals("/api/guardian/alert-rules") && "POST".equalsIgnoreCase(t.getRequestMethod())) {
                if (!bridgeAuthorized && !ensurePermission(t, "dash.web.guardian.manage", true)) return;
                handleGuardianAlertRuleSave(t);
                return;
            }
            if (path.equals("/api/guardian/alert-rules/delete") && "POST".equalsIgnoreCase(t.getRequestMethod())) {
                if (!bridgeAuthorized && !ensurePermission(t, "dash.web.guardian.manage", true)) return;
                handleGuardianAlertRuleDelete(t);
                return;
            }
            if (path.equals("/api/guardian/alert-rules/evaluate") && "POST".equalsIgnoreCase(t.getRequestMethod())) {
                if (!bridgeAuthorized
                        && !ensureAnyPermission(t, true, "dash.web.guardian.cases", "dash.web.guardian.manage")) return;
                handleGuardianAlertRuleEvaluate(t);
                return;
            }
            if (path.equals("/api/guardian/retention") && "POST".equalsIgnoreCase(t.getRequestMethod())) {
                if (!bridgeAuthorized
                        && !ensureAnyPermission(t, true, "dash.web.guardian.purge", "dash.web.guardian.manage")) return;
                handleGuardianRetentionSave(t);
                return;
            }
            if (path.equals("/api/guardian/retention/apply") && "POST".equalsIgnoreCase(t.getRequestMethod())) {
                if (!bridgeAuthorized
                        && !ensureAnyPermission(t, true, "dash.web.guardian.purge", "dash.web.guardian.manage")) return;
                handleGuardianRetentionApply(t);
                return;
            }
            if (!"GET".equalsIgnoreCase(t.getRequestMethod())) {
                sendResponseWithStatus(t, 405, "{\"success\":false,\"error\":\"method_not_allowed\"}");
                return;
            }
            if (!bridgeAuthorized && !ensurePermission(t, "dash.web.guardian.read", true)) return;

            GuardianDataManager guardian = FabricDash.getGuardianDataManager();
            if (guardian == null) {
                sendResponseWithStatus(t, 503, "{\"success\":false,\"error\":\"guardian_unavailable\"}");
                return;
            }

            String query = t.getRequestURI().getRawQuery();
            if (path.equals("/api/guardian") || path.equals("/api/guardian/stats")) {
                sendResponse(t, guardianStatsJson(guardian.getServerStats()));
            } else if (path.equals("/api/guardian/status")) {
                sendResponse(t, guardianStatusJson(guardian.getStatus()));
            } else if (path.equals("/api/guardian/logs/blocks")) {
                sendResponse(t, guardianBlockLogsJson(guardian.searchBlockLogs(
                        getQueryParam(query, "player"), getQueryParam(query, "world"), sinceFromQuery(query), null,
                        GuardianDataManager.parseBlockAction(getQueryParam(query, "action")),
                        parseInt(getQueryParam(query, "page"), 1), parseInt(getQueryParam(query, "limit"), 50))));
            } else if (path.equals("/api/guardian/logs/containers")) {
                sendResponse(t, guardianContainerLogsJson(guardian.searchContainerLogs(
                        getQueryParam(query, "player"), getQueryParam(query, "world"), sinceFromQuery(query), null,
                        GuardianDataManager.parseContainerAction(getQueryParam(query, "action")),
                        parseInt(getQueryParam(query, "page"), 1), parseInt(getQueryParam(query, "limit"), 50))));
            } else if (path.equals("/api/guardian/lookup") || path.equals("/api/guardian/near")) {
                sendResponse(t, guardianLookupJson(guardian, query, path.equals("/api/guardian/near")));
            } else if (path.equals("/api/guardian/has-placed") || path.equals("/api/guardian/has-removed")) {
                sendResponse(t, guardianHasActionJson(guardian, query, path.endsWith("has-placed")));
            } else if (path.equals("/api/guardian/worlds")) {
                sendResponse(t, stringArrayJson(guardian.getDistinctWorlds()));
            } else if (path.equals("/api/guardian/timeline")) {
                long to = System.currentTimeMillis() / 1000L;
                sendResponse(t, guardianTimelineJson(guardian.getTimelineStats(sinceFromQuery(query, 24), to)));
            } else if (path.equals("/api/guardian/timeline/events") || path.equals("/api/guardian/timeline/player")) {
                sendResponse(t, guardianTimelineEventsJson(guardian.searchTimeline(
                        getQueryParam(query, "q"),
                        getQueryParam(query, "player"),
                        getQueryParam(query, "world"),
                        sinceFromQuery(query),
                        null,
                        parseInt(getQueryParam(query, "limit"), 100))));
            } else if (path.equals("/api/guardian/cases")) {
                sendResponse(t, guardianCasesJson(guardian.listCases(
                        getQueryParam(query, "status"),
                        getQueryParam(query, "player"),
                        parseInt(getQueryParam(query, "limit"), 30))));
            } else if (path.equals("/api/guardian/cases/evidence")) {
                sendResponse(t, guardianEvidenceJson(guardian.listCaseEvidence(
                        parseLong(getQueryParam(query, "caseId"), 0L))));
            } else if (path.equals("/api/guardian/cases/bundle")) {
                sendResponse(t, guardianCaseBundleJson(guardian, parseLong(getQueryParam(query, "caseId"), 0L)));
            } else if (path.equals("/api/guardian/player-notes")) {
                if (!bridgeAuthorized
                        && !ensureAnyPermission(t, true, "dash.web.guardian.notes", "dash.web.guardian.manage")) return;
                sendResponse(t, guardianPlayerNotesJson(guardian.listPlayerNotes(
                        getQueryParam(query, "q"),
                        getQueryParam(query, "severity"),
                        parseInt(getQueryParam(query, "limit"), 20))));
            } else if (path.equals("/api/guardian/saved-filters")) {
                sendResponse(t, guardianFiltersJson(guardian.listSavedFilters()));
            } else if (path.equals("/api/guardian/incidents")) {
                sendResponse(t, guardianIncidentsJson(guardian.listIncidents(sinceFromQuery(query, 24),
                        parseInt(getQueryParam(query, "limit"), 20))));
            } else if (path.equals("/api/guardian/scores")) {
                sendResponse(t, guardianScoresJson(guardian.listSuspicionScores(sinceFromQuery(query, 24),
                        parseInt(getQueryParam(query, "limit"), 20))));
            } else if (path.equals("/api/guardian/preview-diff")) {
                sendResponse(t, guardianPreviewDiffJson(guardianPreviewDiff(guardian, query)));
            } else if (path.equals("/api/guardian/replay")) {
                sendResponse(t, guardianTimelineEventsJson(guardian.searchTimelineReplay(
                        getQueryParam(query, "q"),
                        getQueryParam(query, "player"),
                        getQueryParam(query, "world"),
                        sinceFromQuery(query),
                        null,
                        parseInt(getQueryParam(query, "limit"), 80))));
            } else if (path.equals("/api/guardian/container-restore-plan")) {
                sendResponse(t, guardianItemAmountsJson(guardian.containerRestorePlan(
                        getQueryParam(query, "player"),
                        getQueryParam(query, "world"),
                        sinceFromQuery(query),
                        parseOptionalInt(getQueryParam(query, "x")),
                        parseOptionalInt(getQueryParam(query, "y")),
                        parseOptionalInt(getQueryParam(query, "z")),
                        parseOptionalInt(getQueryParam(query, "radius")),
                        parseInt(getQueryParam(query, "limit"), 500))));
            } else if (path.equals("/api/guardian/protected-regions")) {
                sendResponse(t, guardianProtectedRegionsJson(guardian.listProtectedRegions()));
            } else if (path.equals("/api/guardian/protected-regions/hits")) {
                sendResponse(t, guardianProtectedRegionHitsJson(guardian.listProtectedRegionHits(sinceFromQuery(query, 24),
                        parseInt(getQueryParam(query, "limit"), 20))));
            } else if (path.equals("/api/guardian/alert-rules")) {
                sendResponse(t, guardianAlertRulesJson(guardian.listAlertRules()));
            } else if (path.equals("/api/guardian/alert-rules/hits")) {
                sendResponse(t, guardianAlertHitsJson(guardian.evaluateAlertRules(false, actorLabel(t))));
            } else if (path.equals("/api/guardian/retention")) {
                sendResponse(t, guardianRetentionJson(guardian.getRetentionPolicy()));
            } else if (path.equals("/api/guardian/inbox")) {
                sendResponse(t, guardianInboxJson(guardian.getInbox(sinceFromQuery(query, 24))));
            } else if (path.equals("/api/guardian/activity")) {
                sendResponse(t, guardianActivityJson());
            } else if (path.equals("/api/guardian/heatmap")) {
                sendResponse(t, guardianHeatmapJson(guardian.getHeatmapData(sinceFromQuery(query, 24),
                        parseInt(getQueryParam(query, "limit"), 50))));
            } else if (path.equals("/api/guardian/suspicious")) {
                sendResponse(t, guardianSuspiciousJson(guardian.getSuspiciousPlayers(sinceFromQuery(query, 24))));
            } else if (path.equals("/api/guardian/peak-hours")) {
                sendResponse(t, intIntMapJson(guardian.getPeakHoursData(sinceFromQuery(query, 168))));
            } else if (path.equals("/api/guardian/top-players")) {
                sendResponse(t, guardianPlayerActivityJson(guardian.getTopPlayersData(sinceFromQuery(query, 168),
                        parseInt(getQueryParam(query, "limit"), 10))));
            } else if (path.equals("/api/guardian/block-types")) {
                sendResponse(t, stringIntMapJson(guardian.getBlockTypesData(sinceFromQuery(query, 168),
                        getQueryParam(query, "action"), parseInt(getQueryParam(query, "limit"), 20))));
            } else if (path.equals("/api/guardian/custom")) {
                long from = sinceFromQuery(query, 168);
                sendResponse(t, guardianCustomStatsJson(guardian, from, getQueryParam(query, "action"),
                        parseInt(getQueryParam(query, "limit"), 10)));
            } else if (path.equals("/api/guardian/coreprotect/status")) {
                sendResponse(t, "{\"available\":false,\"apiVersion\":0,\"message\":\"CoreProtect import is only available on Bukkit/Paper servers.\"}");
            } else {
                sendResponseWithStatus(t, 404, "{\"success\":false,\"error\":\"not_found\"}");
            }
        }
    }

    private void handleGuardianAction(HttpExchange t, String mode) throws IOException {
        GuardianDataManager guardian = FabricDash.getGuardianDataManager();
        if (guardian == null) {
            sendResponseWithStatus(t, 503, "{\"success\":false,\"error\":\"guardian_unavailable\"}");
            return;
        }
        String body = new String(readRequestBodyStrict(t, 1024L * 1024L), StandardCharsets.UTF_8);
        Map<String, String> params = parseFormData(body);
        GuardianActionService.ActionRequest request = guardianActionRequest(params);
        GuardianActionService.ActionResult result = "restore".equals(mode)
                ? new GuardianActionService().restore(guardian, request)
                : new GuardianActionService().rollback(guardian, request);
        WebActionLogger.log("GUARDIAN_" + mode.toUpperCase(Locale.ROOT),
                "user=" + getSessionUser(t) + " preview=" + request.preview() + " player=" + request.player()
                        + " world=" + request.world() + " changedBlocks=" + result.changedBlocks()
                        + " changedContainers=" + result.changedContainers());
        sendResponse(t, guardianActionResultJson(result));
    }

    private void handleGuardianPurge(HttpExchange t) throws IOException {
        GuardianDataManager guardian = FabricDash.getGuardianDataManager();
        if (guardian == null) {
            sendResponseWithStatus(t, 503, "{\"success\":false,\"error\":\"guardian_unavailable\"}");
            return;
        }
        String body = new String(readRequestBodyStrict(t, 1024L * 1024L), StandardCharsets.UTF_8);
        Map<String, String> params = parseFormData(body);
        int hours = Math.max(1, Math.min(parseInt(params.get("hours"), 720), 24000));
        long cutoff = (System.currentTimeMillis() / 1000L) - (hours * 3600L);
        GuardianDataManager.PurgeResult result = guardian.purgeOlderThan(cutoff, params.get("world"),
                csvList(params.get("include")));
        WebActionLogger.log("GUARDIAN_PURGE",
                "user=" + getSessionUser(t) + " hours=" + hours + " world=" + params.get("world")
                        + " blocks=" + result.blockRows() + " containers=" + result.containerRows());
        sendResponse(t, "{\"success\":true,\"blockRows\":" + result.blockRows()
                + ",\"containerRows\":" + result.containerRows()
                + ",\"message\":\"Purged " + (result.blockRows() + result.containerRows()) + " Guardian rows.\"}");
    }

    private void handleGuardianCaseCreate(HttpExchange t) throws IOException {
        GuardianDataManager guardian = FabricDash.getGuardianDataManager();
        if (guardian == null) {
            sendResponseWithStatus(t, 503, "{\"success\":false,\"error\":\"guardian_unavailable\"}");
            return;
        }
        Map<String, String> params = parseFormData(new String(readRequestBodyStrict(t, 1024L * 1024L), StandardCharsets.UTF_8));
        GuardianDataManager.CaseRecord created = guardian.createCase(
                params.get("title"),
                params.get("priority"),
                params.get("player"),
                params.get("world"),
                parseOptionalInt(params.get("x")),
                parseOptionalInt(params.get("y")),
                parseOptionalInt(params.get("z")),
                params.get("notes"),
                actorLabel(t));
        if (created == null) {
            sendResponseWithStatus(t, 400, "{\"success\":false,\"error\":\"case_create_failed\"}");
            return;
        }
        WebActionLogger.log("GUARDIAN_CASE_CREATE",
                "user=" + actorLabel(t) + " case=" + created.id() + " player=" + created.playerName());
        sendResponse(t, "{\"success\":true,\"case\":" + guardianCaseJson(created) + "}");
    }

    private void handleGuardianCaseUpdate(HttpExchange t) throws IOException {
        GuardianDataManager guardian = FabricDash.getGuardianDataManager();
        if (guardian == null) {
            sendResponseWithStatus(t, 503, "{\"success\":false,\"error\":\"guardian_unavailable\"}");
            return;
        }
        Map<String, String> params = parseFormData(new String(readRequestBodyStrict(t, 1024L * 1024L), StandardCharsets.UTF_8));
        long caseId = parseLong(params.get("caseId"), 0L);
        boolean updated = guardian.updateCase(caseId, params.get("status"), params.get("priority"),
                params.get("notes"), actorLabel(t));
        if (!updated) {
            sendResponseWithStatus(t, 404, "{\"success\":false,\"error\":\"case_not_found\"}");
            return;
        }
        GuardianDataManager.CaseRecord record = guardian.getCase(caseId);
        WebActionLogger.log("GUARDIAN_CASE_UPDATE",
                "user=" + actorLabel(t) + " case=" + caseId + " status=" + params.get("status"));
        sendResponse(t, "{\"success\":true,\"case\":" + guardianCaseJson(record) + "}");
    }

    private void handleGuardianEvidenceAdd(HttpExchange t) throws IOException {
        GuardianDataManager guardian = FabricDash.getGuardianDataManager();
        if (guardian == null) {
            sendResponseWithStatus(t, 503, "{\"success\":false,\"error\":\"guardian_unavailable\"}");
            return;
        }
        Map<String, String> params = parseFormData(new String(readRequestBodyStrict(t, 1024L * 1024L), StandardCharsets.UTF_8));
        long caseId = parseLong(params.get("caseId"), 0L);
        long eventId = parseLong(params.get("eventId"), 0L);
        boolean added = guardian.addCaseEvidence(caseId, params.get("eventType"), eventId, params.get("label"),
                actorLabel(t));
        if (!added) {
            sendResponseWithStatus(t, 400, "{\"success\":false,\"error\":\"evidence_add_failed\"}");
            return;
        }
        WebActionLogger.log("GUARDIAN_EVIDENCE_ADD",
                "user=" + actorLabel(t) + " case=" + caseId + " type=" + params.get("eventType")
                        + " event=" + eventId);
        sendResponse(t, "{\"success\":true,\"evidence\":" + guardianEvidenceJson(guardian.listCaseEvidence(caseId)) + "}");
    }

    private void handleGuardianPlayerNoteSave(HttpExchange t) throws IOException {
        GuardianDataManager guardian = FabricDash.getGuardianDataManager();
        if (guardian == null) {
            sendResponseWithStatus(t, 503, "{\"success\":false,\"error\":\"guardian_unavailable\"}");
            return;
        }
        Map<String, String> params = parseFormData(new String(readRequestBodyStrict(t, 1024L * 1024L), StandardCharsets.UTF_8));
        GuardianDataManager.PlayerNoteRecord note = guardian.upsertPlayerNote(
                params.get("player"),
                params.get("severity"),
                params.get("notes"),
                actorLabel(t));
        if (note == null) {
            sendResponseWithStatus(t, 400, "{\"success\":false,\"error\":\"player_note_save_failed\"}");
            return;
        }
        WebActionLogger.log("GUARDIAN_PLAYER_NOTE_SAVE",
                "user=" + actorLabel(t) + " player=" + note.playerName() + " severity=" + note.severity());
        sendResponse(t, "{\"success\":true,\"note\":" + guardianPlayerNoteJson(note)
                + ",\"notes\":" + guardianPlayerNotesJson(guardian.listPlayerNotes(null, null, 20)) + "}");
    }

    private void handleGuardianPlayerNoteDelete(HttpExchange t) throws IOException {
        GuardianDataManager guardian = FabricDash.getGuardianDataManager();
        if (guardian == null) {
            sendResponseWithStatus(t, 503, "{\"success\":false,\"error\":\"guardian_unavailable\"}");
            return;
        }
        Map<String, String> params = parseFormData(new String(readRequestBodyStrict(t, 1024L * 1024L), StandardCharsets.UTF_8));
        boolean deleted = guardian.deletePlayerNote(params.get("player"));
        WebActionLogger.log("GUARDIAN_PLAYER_NOTE_DELETE",
                "user=" + actorLabel(t) + " player=" + params.get("player"));
        sendResponse(t, "{\"success\":" + deleted
                + ",\"notes\":" + guardianPlayerNotesJson(guardian.listPlayerNotes(null, null, 20)) + "}");
    }

    private void handleGuardianFilterSave(HttpExchange t) throws IOException {
        GuardianDataManager guardian = FabricDash.getGuardianDataManager();
        if (guardian == null) {
            sendResponseWithStatus(t, 503, "{\"success\":false,\"error\":\"guardian_unavailable\"}");
            return;
        }
        Map<String, String> params = parseFormData(new String(readRequestBodyStrict(t, 1024L * 1024L), StandardCharsets.UTF_8));
        boolean saved = guardian.saveFilter(params.get("name"), params.get("query"), actorLabel(t));
        if (!saved) {
            sendResponseWithStatus(t, 400, "{\"success\":false,\"error\":\"filter_save_failed\"}");
            return;
        }
        WebActionLogger.log("GUARDIAN_FILTER_SAVE", "user=" + actorLabel(t) + " name=" + params.get("name"));
        sendResponse(t, "{\"success\":true,\"filters\":" + guardianFiltersJson(guardian.listSavedFilters()) + "}");
    }

    private void handleGuardianFilterDelete(HttpExchange t) throws IOException {
        GuardianDataManager guardian = FabricDash.getGuardianDataManager();
        if (guardian == null) {
            sendResponseWithStatus(t, 503, "{\"success\":false,\"error\":\"guardian_unavailable\"}");
            return;
        }
        Map<String, String> params = parseFormData(new String(readRequestBodyStrict(t, 1024L * 1024L), StandardCharsets.UTF_8));
        boolean deleted = guardian.deleteFilter(parseLong(params.get("id"), 0L));
        sendResponse(t, "{\"success\":" + deleted + ",\"filters\":"
                + guardianFiltersJson(guardian.listSavedFilters()) + "}");
    }

    private void handleGuardianProtectedRegionSave(HttpExchange t) throws IOException {
        GuardianDataManager guardian = FabricDash.getGuardianDataManager();
        if (guardian == null) {
            sendResponseWithStatus(t, 503, "{\"success\":false,\"error\":\"guardian_unavailable\"}");
            return;
        }
        Map<String, String> params = parseFormData(new String(readRequestBodyStrict(t, 1024L * 1024L), StandardCharsets.UTF_8));
        long idValue = parseLong(params.get("id"), 0L);
        GuardianDataManager.ProtectedRegionRecord region = guardian.upsertProtectedRegion(
                idValue > 0 ? idValue : null, params.get("name"), params.get("world"),
                parseOptionalInt(params.get("x1")), parseOptionalInt(params.get("y1")), parseOptionalInt(params.get("z1")),
                parseOptionalInt(params.get("x2")), parseOptionalInt(params.get("y2")), parseOptionalInt(params.get("z2")),
                params.get("severity"), actorLabel(t));
        if (region == null) {
            sendResponseWithStatus(t, 400, "{\"success\":false,\"error\":\"region_save_failed\"}");
            return;
        }
        WebActionLogger.log("GUARDIAN_REGION_SAVE", "user=" + actorLabel(t) + " region=" + region.name());
        sendResponse(t, "{\"success\":true,\"region\":" + guardianProtectedRegionJson(region)
                + ",\"regions\":" + guardianProtectedRegionsJson(guardian.listProtectedRegions()) + "}");
    }

    private void handleGuardianProtectedRegionDelete(HttpExchange t) throws IOException {
        GuardianDataManager guardian = FabricDash.getGuardianDataManager();
        if (guardian == null) {
            sendResponseWithStatus(t, 503, "{\"success\":false,\"error\":\"guardian_unavailable\"}");
            return;
        }
        Map<String, String> params = parseFormData(new String(readRequestBodyStrict(t, 1024L * 1024L), StandardCharsets.UTF_8));
        long id = parseLong(params.get("id"), 0L);
        boolean deleted = guardian.deleteProtectedRegion(id);
        WebActionLogger.log("GUARDIAN_REGION_DELETE", "user=" + actorLabel(t) + " id=" + id);
        sendResponse(t, "{\"success\":" + deleted
                + ",\"regions\":" + guardianProtectedRegionsJson(guardian.listProtectedRegions()) + "}");
    }

    private void handleGuardianAlertRuleSave(HttpExchange t) throws IOException {
        GuardianDataManager guardian = FabricDash.getGuardianDataManager();
        if (guardian == null) {
            sendResponseWithStatus(t, 503, "{\"success\":false,\"error\":\"guardian_unavailable\"}");
            return;
        }
        Map<String, String> params = parseFormData(new String(readRequestBodyStrict(t, 1024L * 1024L), StandardCharsets.UTF_8));
        long idValue = parseLong(params.get("id"), 0L);
        GuardianDataManager.AlertRuleRecord rule = guardian.upsertAlertRule(
                idValue > 0 ? idValue : null,
                params.get("name"),
                isChecked(params, "enabled"),
                parseInt(params.get("windowSeconds"), parseInt(params.get("window_seconds"), 600)),
                parseInt(params.get("minActions"), parseInt(params.get("min_actions"), 25)),
                params.get("action"), params.get("material"),
                isChecked(params, "autoCase") || isChecked(params, "auto_case"),
                params.get("priority"), actorLabel(t));
        if (rule == null) {
            sendResponseWithStatus(t, 400, "{\"success\":false,\"error\":\"alert_rule_save_failed\"}");
            return;
        }
        WebActionLogger.log("GUARDIAN_ALERT_RULE_SAVE", "user=" + actorLabel(t) + " rule=" + rule.name());
        sendResponse(t, "{\"success\":true,\"rule\":" + guardianAlertRuleJson(rule)
                + ",\"rules\":" + guardianAlertRulesJson(guardian.listAlertRules()) + "}");
    }

    private void handleGuardianAlertRuleDelete(HttpExchange t) throws IOException {
        GuardianDataManager guardian = FabricDash.getGuardianDataManager();
        if (guardian == null) {
            sendResponseWithStatus(t, 503, "{\"success\":false,\"error\":\"guardian_unavailable\"}");
            return;
        }
        Map<String, String> params = parseFormData(new String(readRequestBodyStrict(t, 1024L * 1024L), StandardCharsets.UTF_8));
        long id = parseLong(params.get("id"), 0L);
        boolean deleted = guardian.deleteAlertRule(id);
        WebActionLogger.log("GUARDIAN_ALERT_RULE_DELETE", "user=" + actorLabel(t) + " id=" + id);
        sendResponse(t, "{\"success\":" + deleted
                + ",\"rules\":" + guardianAlertRulesJson(guardian.listAlertRules()) + "}");
    }

    private void handleGuardianAlertRuleEvaluate(HttpExchange t) throws IOException {
        GuardianDataManager guardian = FabricDash.getGuardianDataManager();
        if (guardian == null) {
            sendResponseWithStatus(t, 503, "{\"success\":false,\"error\":\"guardian_unavailable\"}");
            return;
        }
        Map<String, String> params = parseFormData(new String(readRequestBodyStrict(t, 1024L * 1024L), StandardCharsets.UTF_8));
        boolean autoCase = isChecked(params, "autoCase") || isChecked(params, "auto_case");
        List<GuardianDataManager.AlertHitRecord> hits = guardian.evaluateAlertRules(autoCase, actorLabel(t));
        WebActionLogger.log("GUARDIAN_ALERT_RULE_EVALUATE",
                "user=" + actorLabel(t) + " hits=" + hits.size() + " autoCase=" + autoCase);
        sendResponse(t, "{\"success\":true,\"hits\":" + guardianAlertHitsJson(hits)
                + ",\"cases\":" + guardianCasesJson(guardian.listCases("OPEN", null, 10)) + "}");
    }

    private void handleGuardianRetentionSave(HttpExchange t) throws IOException {
        GuardianDataManager guardian = FabricDash.getGuardianDataManager();
        if (guardian == null) {
            sendResponseWithStatus(t, 503, "{\"success\":false,\"error\":\"guardian_unavailable\"}");
            return;
        }
        Map<String, String> params = parseFormData(new String(readRequestBodyStrict(t, 1024L * 1024L), StandardCharsets.UTF_8));
        GuardianDataManager.RetentionPolicyRecord policy = guardian.saveRetentionPolicy(
                parseInt(params.get("logDays"), parseInt(params.get("log_days"), 90)),
                isChecked(params, "keepCases") || isChecked(params, "keep_cases"), actorLabel(t));
        WebActionLogger.log("GUARDIAN_RETENTION_SAVE", "user=" + actorLabel(t) + " days=" + policy.logDays());
        sendResponse(t, "{\"success\":true,\"policy\":" + guardianRetentionJson(policy) + "}");
    }

    private void handleGuardianRetentionApply(HttpExchange t) throws IOException {
        GuardianDataManager guardian = FabricDash.getGuardianDataManager();
        if (guardian == null) {
            sendResponseWithStatus(t, 503, "{\"success\":false,\"error\":\"guardian_unavailable\"}");
            return;
        }
        GuardianDataManager.PurgeResult result = guardian.applyRetentionPolicy();
        WebActionLogger.log("GUARDIAN_RETENTION_APPLY",
                "user=" + actorLabel(t) + " blocks=" + result.blockRows() + " containers=" + result.containerRows());
        sendResponse(t, "{\"success\":true,\"blockRows\":" + result.blockRows()
                + ",\"containerRows\":" + result.containerRows()
                + ",\"message\":\"Retention purged " + (result.blockRows() + result.containerRows()) + " rows.\"}");
    }

    private void handleGuardianExport(HttpExchange t, String path) throws IOException {
        GuardianDataManager guardian = FabricDash.getGuardianDataManager();
        if (guardian == null) {
            sendResponseWithStatus(t, 503, "Guardian unavailable");
            return;
        }
        String query = t.getRequestURI().getRawQuery();
        if (path.endsWith("/blocks")) {
            t.getResponseHeaders().set("Content-Type", "text/csv; charset=utf-8");
            t.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"guardian-blocks.csv\"");
            sendResponse(t, guardianBlocksCsv(guardian.searchBlockLogs(
                    getQueryParam(query, "player"), getQueryParam(query, "world"), sinceFromQuery(query), null,
                    GuardianDataManager.parseBlockAction(getQueryParam(query, "action")), 1, 100000)));
        } else if (path.endsWith("/containers")) {
            t.getResponseHeaders().set("Content-Type", "text/csv; charset=utf-8");
            t.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"guardian-containers.csv\"");
            sendResponse(t, guardianContainersCsv(guardian.searchContainerLogs(
                    getQueryParam(query, "player"), getQueryParam(query, "world"), sinceFromQuery(query), null,
                    GuardianDataManager.parseContainerAction(getQueryParam(query, "action")), 1, 100000)));
        } else {
            sendResponseWithStatus(t, 404, "Not found");
        }
    }

    private class UpdateDownloadHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if (!"POST".equalsIgnoreCase(t.getRequestMethod())) {
                t.sendResponseHeaders(405, -1);
                t.close();
                return;
            }
            if (!ensurePermission(t, "dash.web.settings.write", true)) {
                return;
            }

            GithubUpdater updater = FabricDash.getGithubUpdater();
            if (updater == null || !updater.isEnabled()) {
                sendResponseWithStatus(t, 400, "{\"success\":false,\"error\":\"Updater disabled\"}");
                return;
            }

            boolean downloaded = updater.downloadUpdate();
            if (downloaded) {
                sendResponseWithStatus(t, 200,
                        "{\"success\":true,\"message\":\"Update downloaded and staged. It will be applied automatically when the server stops.\"}");
            } else {
                sendResponseWithStatus(t, 500, "{\"success\":false,\"error\":\"Update download failed\"}");
            }
        }
    }

    private class UpdateRestartHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if (!"POST".equalsIgnoreCase(t.getRequestMethod())) {
                t.sendResponseHeaders(405, -1);
                t.close();
                return;
            }
            if (!ensurePermission(t, "dash.web.server.control", true)) {
                return;
            }

            FabricDash.getInstance().getScheduler().schedule(() -> server.halt(false), 2, TimeUnit.SECONDS);
            sendResponseWithStatus(t, 200, "{\"success\":true,\"message\":\"Server stop scheduled — update will be applied automatically.\"}");
        }
    }

    private class UpdateCheckHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if (!"POST".equalsIgnoreCase(t.getRequestMethod())) {
                t.sendResponseHeaders(405, -1);
                t.close();
                return;
            }
            if (!ensurePermission(t, "dash.web.settings.read", true)) {
                return;
            }

            GithubUpdater updater = FabricDash.getGithubUpdater();
            if (updater == null || !updater.isEnabled()) {
                sendResponseWithStatus(t, 400, "{\"success\":false,\"error\":\"Updater disabled\"}");
                return;
            }

            String latestVersion = updater.checkForUpdates();
            boolean updateAvailable = updater.isUpdateAvailable();
            sendResponseWithStatus(t, 200,
                    "{\"success\":true,\"latestVersion\":\"" + jsonEscape(latestVersion) + "\",\"updateAvailable\":" + updateAvailable + "}");
        }
    }

    private class GlobalSettingsApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if (!"POST".equalsIgnoreCase(t.getRequestMethod())) {
                t.sendResponseHeaders(405, -1);
                t.close();
                return;
            }
            if (!ensurePermission(t, "dash.web.settings.write", true)) {
                return;
            }

            byte[] requestBody = readRequestBodyOrReject(t, 1024L * 1024L);
            if (requestBody == null) {
                return;
            }
            String body = new String(requestBody, StandardCharsets.UTF_8);
            Map<String, String> params = parseFormData(body);

            String intervalRaw = params.get("update_interval_minutes");
            if (intervalRaw != null && !intervalRaw.isBlank()) {
                try {
                    int minutes = Integer.parseInt(intervalRaw.trim());
                    if (minutes < 20 || minutes > 10080) {
                        sendResponseWithStatus(t, 400, "{\"success\":false,\"error\":\"Interval must be between 20 and 10080 minutes\"}");
                        return;
                    }
                    dash.data.GlobalSettingsManager gsm = FabricDash.getGlobalSettingsManager();
                    if (gsm != null) {
                        gsm.setGlobalSetting("update_interval_minutes", String.valueOf(minutes));
                    }
                    FabricDash.getInstance().rescheduleUpdater();
                    WebActionLogger.logSettingChange("update_interval_minutes", String.valueOf(minutes), getClientIp(t));
                    sendResponseWithStatus(t, 200, "{\"success\":true}");
                } catch (NumberFormatException e) {
                    sendResponseWithStatus(t, 400, "{\"success\":false,\"error\":\"Invalid interval value\"}");
                }
                return;
            }

            sendResponseWithStatus(t, 400, "{\"success\":false,\"error\":\"No recognised settings provided\"}");
        }
    }

    private class BridgeConsoleHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if (!ensureBridgeBearer(t)) {
                return;
            }

            if ("GET".equalsIgnoreCase(t.getRequestMethod())) {
                List<String> logs = ConsoleCatcher.getRecentLogs();
                String response = String.join("\n", logs);
                t.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
                sendResponse(t, response);
                return;
            }

            if ("POST".equalsIgnoreCase(t.getRequestMethod())) {
                String body = new String(readRequestBodyStrict(t, 1024L * 1024L), StandardCharsets.UTF_8);
                String cmd = extractBridgeCommand(body, t.getRequestHeaders().getFirst("Content-Type"));
                if (cmd == null || cmd.isBlank()) {
                    t.getResponseHeaders().add("Content-Type", "application/json");
                    sendResponseWithStatus(t, 400, "{\"success\":false,\"error\":\"Missing command\"}");
                    return;
                }

                server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), cmd));
                WebActionLogger.log("BRIDGE_COMMAND", "cmd=" + cmd + " ip=" + getClientIp(t));
                t.getResponseHeaders().add("Content-Type", "application/json");
                sendResponse(t, "{\"success\":true}");
                return;
            }

            t.sendResponseHeaders(405, -1);
            t.close();
        }
    }

    private class WebhookApproveHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if (!"GET".equalsIgnoreCase(t.getRequestMethod())) {
                t.sendResponseHeaders(405, -1);
                t.close();
                return;
            }

            String token = getQueryParam(t.getRequestURI().getRawQuery(), "token");
            String action = getQueryParam(t.getRequestURI().getRawQuery(), "action");
            boolean allow = "allow".equalsIgnoreCase(action);
            boolean deny = "deny".equalsIgnoreCase(action);
            if (!allow && !deny) {
                sendResponseWithStatus(t, 400, "Invalid action");
                return;
            }

            WebAuth.AuthResult result = auth.approveBridgeByToken(token, allow, "MODERATOR");
            if (!result.success()) {
                sendResponseWithStatus(t, 400, "Request failed: " + result.message());
                return;
            }

            String auditMsg = allow ? "allowed user=" + result.message() : "denied pending bridge user";
            WebActionLogger.log("BRIDGE_WEBHOOK_APPROVAL", auditMsg + " from " + getClientIp(t));
            sendResponseWithStatus(t, 200, "Success");
        }
    }

    private class FileSaveHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if (!ensurePermission(t, "dash.web.files.write", true)) {
                return;
            }

            if (!"POST".equals(t.getRequestMethod())) {
                t.sendResponseHeaders(405, -1);
                t.close();
                return;
            }

            String body = new String(readRequestBodyStrict(t, 4L * 1024L * 1024L), StandardCharsets.UTF_8);
            Map<String, String> params = parseFormData(body);
            String filePath = params.get("path");
            String content = params.get("content");
            t.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");

            if (filePath == null || content == null) {
                sendResponse(t, "{\"success\":false,\"error\":\"Missing parameters\"}");
                return;
            }
            if (content.getBytes(StandardCharsets.UTF_8).length > 1024L * 1024L) {
                sendResponse(t, "{\"success\":false,\"error\":\"Content exceeds the 1 MiB editor limit\"}");
                return;
            }

            File serverDir = FabricDash.getServerRootDirectory().getCanonicalFile();
            File file = new File(serverDir, filePath).getCanonicalFile();

            try {
                java.nio.file.Path rootPath = serverDir.toPath();
                java.nio.file.Path fileTarget = file.toPath();
                if (!fileTarget.startsWith(rootPath)) {
                    sendResponse(t, "{\"success\":false,\"error\":\"Access denied\"}");
                    return;
                }
                if (isProtectedLockFile(file) || !FilesPage.isEditableFile(file.getName())) {
                    sendResponse(t, "{\"success\":false,\"error\":\"This file type cannot be edited\"}");
                    return;
                }
                if (Files.exists(fileTarget) && (!Files.isRegularFile(fileTarget) || Files.size(fileTarget) > 1024L * 1024L)) {
                    sendResponse(t, "{\"success\":false,\"error\":\"File is not editable in the web editor\"}");
                    return;
                }
                java.nio.file.Path parent = fileTarget.getParent();
                if (parent == null || !Files.isDirectory(parent)) {
                    sendResponse(t, "{\"success\":false,\"error\":\"Parent folder does not exist\"}");
                    return;
                }

                if (Files.exists(fileTarget)) {
                    Files.copy(fileTarget, java.nio.file.Path.of(fileTarget + ".bak"),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }

                java.nio.file.Path temp = Files.createTempFile(parent, "." + file.getName() + "-", ".dash-save");
                try {
                    Files.writeString(temp, content, StandardCharsets.UTF_8);
                    try {
                        Files.move(temp, fileTarget,
                                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                        Files.move(temp, fileTarget, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                } finally {
                    Files.deleteIfExists(temp);
                }

                WebActionLogger.logFileEdit(filePath, getClientIp(t));
                sendResponse(t, "{\"success\":true}");
            } catch (Exception e) {
                sendResponse(t, "{\"success\":false,\"error\":\"" + jsonEscape(
                        e.getMessage() == null ? "Save failed" : e.getMessage()) + "\"}");
            }
        }
    }

    private class FileDownloadHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if (!ensurePermission(t, "dash.web.files.read", true)) {
                return;
            }

            String query = t.getRequestURI().getQuery();
            String relativePath = null;
            if (query != null) {
                for (String part : query.split("&")) {
                    if (part.startsWith("path=")) {
                        relativePath = URLDecoder.decode(part.substring(5), StandardCharsets.UTF_8);
                        break;
                    }
                }
            }

            if (relativePath == null || relativePath.isBlank()) {
                t.sendResponseHeaders(400, 0);
                t.close();
                return;
            }

            File serverDir = FabricDash.getServerRootDirectory();
            File file = new File(serverDir, relativePath);

            try {
                if (!file.getCanonicalFile().toPath().startsWith(serverDir.getCanonicalFile().toPath())) {
                    t.sendResponseHeaders(403, 0);
                    t.close();
                    return;
                }
            } catch (IOException e) {
                t.sendResponseHeaders(403, 0);
                t.close();
                return;
            }

            if (!file.exists()) {
                t.sendResponseHeaders(404, 0);
                t.close();
                return;
            }
            if (file.isDirectory()) {
                streamDirectoryZip(t, file, serverDir);
                return;
            }
            if (!file.isFile()) {
                t.sendResponseHeaders(404, 0);
                t.close();
                return;
            }

            String fileName = file.getName();
            String contentType = resolveContentType(fileName);
            String encodedName = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");

            t.getResponseHeaders().set("Content-Type", contentType);
            t.getResponseHeaders().set("Content-Disposition",
                    "attachment; filename=\"" + fileName.replace("\"", "_") + "\"; filename*=UTF-8''" + encodedName);
            t.sendResponseHeaders(200, file.length());

            try (OutputStream os = t.getResponseBody();
                 FileInputStream fis = new FileInputStream(file)) {
                byte[] buffer = new byte[8192];
                int len;
                while ((len = fis.read(buffer)) != -1) {
                    os.write(buffer, 0, len);
                }
            }
        }

        private void streamDirectoryZip(HttpExchange exchange, File directory, File serverRoot) throws IOException {
            String downloadName = directory.getName().replace("\"", "_") + ".zip";
            String encodedName = URLEncoder.encode(downloadName, StandardCharsets.UTF_8).replace("+", "%20");
            exchange.getResponseHeaders().set("Content-Type", "application/zip");
            exchange.getResponseHeaders().set("Content-Disposition",
                    "attachment; filename=\"" + downloadName + "\"; filename*=UTF-8''" + encodedName);
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            exchange.sendResponseHeaders(200, 0);

            java.nio.file.Path root = directory.toPath();
            java.nio.file.Path allowedRoot = serverRoot.getCanonicalFile().toPath();
            try (OutputStream output = exchange.getResponseBody();
                    java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(output);
                    java.util.stream.Stream<java.nio.file.Path> paths = java.nio.file.Files.walk(root)) {
                java.util.Iterator<java.nio.file.Path> iterator = paths.sorted().iterator();
                while (iterator.hasNext()) {
                    java.nio.file.Path path = iterator.next();
                    if (path.equals(root) || java.nio.file.Files.isSymbolicLink(path)) continue;
                    java.nio.file.Path canonical = path.toFile().getCanonicalFile().toPath();
                    if (!canonical.startsWith(allowedRoot)) continue;
                    String entryName = root.relativize(path).toString().replace('\\', '/');
                    if (java.nio.file.Files.isDirectory(path)) entryName += "/";
                    zip.putNextEntry(new java.util.zip.ZipEntry(entryName));
                    if (java.nio.file.Files.isRegularFile(path)) java.nio.file.Files.copy(path, zip);
                    zip.closeEntry();
                }
                zip.finish();
            }
        }

        private String resolveContentType(String fileName) {
            String lower = fileName.toLowerCase();
            if (lower.endsWith(".jar")) return "application/java-archive";
            if (lower.endsWith(".zip")) return "application/zip";
            if (lower.endsWith(".gz") || lower.endsWith(".tar.gz")) return "application/gzip";
            if (lower.endsWith(".json")) return "application/json";
            if (lower.endsWith(".yml") || lower.endsWith(".yaml")) return "text/yaml";
            if (lower.endsWith(".xml")) return "application/xml";
            if (lower.endsWith(".log") || lower.endsWith(".txt") || lower.endsWith(".properties")
                    || lower.endsWith(".cfg") || lower.endsWith(".conf") || lower.endsWith(".ini")
                    || lower.endsWith(".toml") || lower.endsWith(".md")) return "text/plain";
            return "application/octet-stream";
        }
    }


    private class OperationsEvidenceHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if (!"GET".equalsIgnoreCase(t.getRequestMethod())) {
                t.sendResponseHeaders(405, -1);
                t.close();
                return;
            }
            if (!ensureAnyPermission(t, true, "dash.web.audit.read", "dash.web.settings.read")) {
                return;
            }
            t.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            t.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"dash-security-evidence.json\"");
            t.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            sendResponse(t, operationsManager.securityEvidenceJson(getSessionUser(t)));
        }
    }

    private class BridgeSecretRotationHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            t.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            if (!"POST".equalsIgnoreCase(t.getRequestMethod())) {
                sendResponseWithStatus(t, 405, "{\"success\":false,\"error\":\"Method not allowed\"}");
                return;
            }
            if (!isBridgeBearerAuthorized(t)) {
                sendResponseWithStatus(t, 403, "{\"success\":false,\"error\":\"Forbidden\"}");
                return;
            }
            byte[] body = readRequestBodyOrReject(t, 4096L);
            if (body == null) return;
            String newSecret = parseFormData(new String(body, StandardCharsets.UTF_8))
                    .getOrDefault("new_secret", "").trim();
            if (!newSecret.matches("[A-Za-z0-9_-]{32,256}")) {
                sendResponseWithStatus(t, 400,
                        "{\"success\":false,\"error\":\"Secret must contain 32-256 URL-safe characters\"}");
                return;
            }
            String oldSecret = FabricDash.getConfig().getString("bridge.secret", "");
            try {
                FabricDash.getConfig().set("bridge.secret", newSecret);
                FabricDash.getConfig().save();
                sessions.entrySet().removeIf(entry -> entry.getValue().bridgeBound);
                WebActionLogger.log("BRIDGE_SECRET_ROTATE",
                        "newFingerprint=" + operationsManager.secretFingerprint(newSecret) + " from " + getClientIp(t));
                sendResponse(t, "{\"success\":true}");
            } catch (Exception ex) {
                FabricDash.getConfig().set("bridge.secret", oldSecret);
                try { FabricDash.getConfig().save(); } catch (Exception ignored) { }
                sendResponseWithStatus(t, 500, "{\"success\":false,\"error\":\"Secret save failed\"}");
            }
        }
    }

    private class ActionHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if (!"POST".equalsIgnoreCase(t.getRequestMethod())) {
                t.sendResponseHeaders(405, -1);
                t.close();
                return;
            }

            byte[] requestBody = readRequestBodyOrReject(t, 1024L * 1024L);
            if (requestBody == null) {
                return;
            }
            String body = new String(requestBody, StandardCharsets.UTF_8);
            Map<String, String> params = parseFormData(body);
            String action = params.get("action");

            if (action == null || action.isBlank()) {
                redirect(t, "/?error=missing_action");
                return;
            }

            if ("register_code".equals(action)) {
                String code = params.getOrDefault("code", "").trim().toUpperCase();
                String username = params.getOrDefault("username", "").trim();
                String password = params.getOrDefault("password", "");
                String passwordConfirm = params.getOrDefault("password_confirm", "");

                if (code.isEmpty() || username.isEmpty() || password.isEmpty()) {
                    redirect(t, "/setup?msg=" + encodeForQuery("All fields are required."));
                    return;
                }
                if (!username.matches("[A-Za-z0-9_.-]{3,32}")) { redirect(t, "/setup?msg=" + encodeForQuery("Username must contain 3-32 letters, numbers, dots, underscores or hyphens. The code was not consumed.")); return; }
                if (password.length() < 12 || password.length() > 256) { redirect(t, "/setup?msg=" + encodeForQuery("Password must contain 12-256 characters. The registration code is still valid.")); return; }
                if (!password.equals(passwordConfirm)) { redirect(t, "/setup?msg=" + encodeForQuery("Passwords do not match. The registration code is still valid.")); return; }

                boolean firstSetup = auth.isSetupRequired();
                boolean registered;
                if (firstSetup) {
                    registered = auth.registerFirstAdminWithCode(code, username, password);
                } else {
                    registered = auth.registerWithCode(code, username, password);
                }
                if (registered) {
                    if (firstSetup) FeatureFlags.applySetupPreset(params.get("setup_profile"), isChecked(params, "beta_opt_in"));
                    setSession(t, username);
                    WebActionLogger.log("REGISTER", "user=" + username + " ip=" + getClientIp(t));
                    redirect(t, "/");
                } else {
                    redirect(t, "/setup?msg=" + encodeForQuery("Registration failed. Check your code."));
                }
                return;
            }

            if ("login".equals(action)) {
                String username = params.getOrDefault("username", "").trim();
                String password = params.getOrDefault("password", "");
                String twoFa = params.getOrDefault("2fa", "").trim();
                String loginKey = getClientIp(t) + "|" + username;
                if (!loginRateLimiter.isAllowed(loginKey)) {
                    t.getResponseHeaders().set("Retry-After",
                            Long.toString(loginRateLimiter.retryAfterSeconds(loginKey)));
                    redirect(t, "/login?error=" + encodeForQuery("Login temporarily unavailable. Try again later."));
                    return;
                }

                boolean authenticated = auth.check(username, password);
                if (authenticated) {
                    if (!twoFa.isBlank()) {
                        if (!auth.verifyOwner2faCode(twoFa)) {
                            loginRateLimiter.recordFailure(loginKey);
                            WebActionLogger.log("LOGIN_FAILED", "user=" + username + " ip=" + getClientIp(t) + " reason=bad_2fa");
                            redirect(t, "/login?error=" + encodeForQuery("Invalid 2FA code"));
                            return;
                        }
                    }
                    loginRateLimiter.recordSuccess(loginKey);
                    setSession(t, username);
                    WebActionLogger.log("LOGIN", "user=" + username + " ip=" + getClientIp(t));
                    redirect(t, "/");
                } else {
                    loginRateLimiter.recordFailure(loginKey);
                    WebActionLogger.log("LOGIN_FAILED", "user=" + username + " ip=" + getClientIp(t));
                    redirect(t, "/login?error=" + encodeForQuery("Invalid username or password"));
                }
                return;
            }

            if (isAuthenticated(t) && !ensureSameOriginMutation(t, false)) {
                return;
            }

            if ("logout".equals(action)) {
                String username = getSessionUser(t);
                clearSession(t);
                WebActionLogger.log("LOGOUT", "user=" + (username == null ? "unknown" : username) + " ip=" + getClientIp(t));
                redirect(t, "/login");
                return;
            }

            if (!isAuthenticated(t)) {
                redirect(t, "/login");
                return;
            }

            String actionFeature = FeatureFlags.featureForAction(action);
            if (actionFeature != null && !FeatureFlags.enabled(actionFeature)) { redirect(t, "/settings?msg=" + encodeForQuery("The " + actionFeature + " feature is disabled in Settings.")); return; }

            if ("set_motd".equals(action)) {
                if (!ensureAnyPermission(t, false, "dash.web.settings.write", "dash.web.settings.motd.write")) {
                    return;
                }
            } else if ("set_distance".equals(action)) {
                if (!ensureAnyPermission(t, false, "dash.web.settings.write",
                        "dash.web.settings.distance.view", "dash.web.settings.distance.simulation")) {
                    return;
                }
            } else {
                String requiredPermission = requiredPermissionForAction(action);
                if (requiredPermission == null && !usesSeparateActionAuthorization(action)) {
                    sendResponseWithStatus(t, 400, "<html><body>Unknown or unauthorized action.</body></html>");
                    return;
                }
                if (requiredPermission != null && !requiredPermission.isBlank()) {
                    if (!ensurePermission(t, requiredPermission, false)) {
                        return;
                    }
                }
            }

            if ("save_beta_settings".equals(action)) {
                boolean enabled = isChecked(params, "beta_enabled");
                FabricDash.getConfig().set(BETA_FEATURES_CONFIG_KEY, enabled);
                FabricDash.getConfig().save();
                WebActionLogger.log("SETTINGS_SAVE", "beta.enabled=" + enabled + " ip=" + getClientIp(t));
                redirect(t, "/settings?msg=" + encodeForQuery(enabled ? "Beta features enabled." : "Beta features disabled."));
                return;
            }
            if ("save_feature_settings".equals(action)) {
                Map<String, Boolean> features = new LinkedHashMap<>();
                for (String id : FeatureFlags.IDS) features.put(id, isChecked(params, "feature_" + id));
                boolean beta = isChecked(params, "beta_enabled"); FeatureFlags.save(beta, features);
                WebActionLogger.log("FEATURE_SETTINGS_SAVE", "beta=" + beta + " user=" + getSessionUser(t));
                redirect(t, "/settings?msg=" + encodeForQuery("Feature availability updated.")); return;
            }

            if (isBetaFeatureAction(action) && !ensureBetaFeatureEnabled(t)) {
                return;
            }

            String sessionUser = getSessionUser(t);

            if (action.startsWith("intel_jit_")
                    && !ensurePermission(t, "dash.web.users.manage", false)) {
                return;
            }

            if (shouldApplyGuardrails(action)) {
                IntelligenceManager.GuardDecision decision = intelligenceManager.authorizeAction(
                        action,
                        sessionUser,
                        params.get("reason"),
                        server == null ? 0 : server.getPlayerList().getPlayers().size(),
                        params);
                if (!decision.allowed()) {
                    WebActionLogger.log("ACTION_GUARDRAIL",
                            "action=" + action + " actor=" + sessionUser + " result=" + decision.message());
                    if (sendGuardrailChallengeIfNeeded(t, decision, params)) {
                        return;
                    }
                    if (action.startsWith("intel_")) {
                        redirect(t, intelligenceRedirect(action, decision.message()));
                    } else {
                        redirect(t, withActionMessage(resolveServerActionRedirect(action, params), decision.message()));
                    }
                    return;
                }
            }

            if (action.startsWith("intel_")) {
                handleIntelligenceAction(t, params, sessionUser, getClientIp(t));
                return;
            }

            if (action != null && action.startsWith("operations_")) {
                redirect(t, "/intelligence?msg=" + encodeForQuery("Operations was removed in ForgeDash 4.4."));
                return;
            }

            if ("restart".equals(action) && redirectToNeoDashRestartIfAvailable(t)) {
                return;
            }

            switch (action) {
                case "invite_generate" -> {
                    RegistrationManager regManager = FabricDash.getRegistrationManager();
                    String inviteCode = regManager.generateCode("UNBOUND", sessionUser, "MODERATOR", List.of());
                    if (inviteCode != null) {
                        WebActionLogger.log("INVITE_GENERATE", "user=" + sessionUser + " ip=" + getClientIp(t));
                        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                                if (server.getPlayerList().isOp(player.nameAndId())) {
                                    String setupUrl = SetupNotifier.buildSetupUrlStatic(FabricDash.getConfig(), inviteCode);
                                    Component msg = Component.literal("[Dash] ").withStyle(ChatFormatting.GOLD)
                                            .append(Component.literal("Invitation code generated: ").withStyle(ChatFormatting.YELLOW))
                                            .append(Component.literal(inviteCode).withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD))
                                            .append(Component.literal(" - ").withStyle(ChatFormatting.GRAY))
                                                    .append(Component.literal("Click to open setup")
                                                            .withStyle(ChatFormatting.AQUA, ChatFormatting.UNDERLINE)
                                                    .withStyle(s -> s.withClickEvent(new ClickEvent.OpenUrl(URI.create(setupUrl)))));
                                    player.sendSystemMessage(msg);
                                }
                        }
                        redirect(t, "/users?code=" + encodeForQuery(inviteCode) + "&msg=" + encodeForQuery("Invite code: " + inviteCode));
                    } else {
                        redirect(t, "/users?msg=" + encodeForQuery("Failed to generate invite code"));
                    }
                }

                case "role_permissions_save" -> {
                    String role = params.getOrDefault("role", "").trim();
                    if (role.isBlank()) {
                        redirect(t, "/permissions?msg=" + encodeForQuery("Missing role"));
                        return;
                    }
                    List<String> permissions = new ArrayList<>();
                    for (Map.Entry<String, String> entry : params.entrySet()) {
                        if (entry.getKey().startsWith("perm_") && "on".equals(entry.getValue())) {
                            permissions.add(entry.getKey().substring(5));
                        }
                    }
                    WebAuth.AuthResult result = auth.updateRolePermissionsSafe(sessionUser, role, permissions, List.of());
                    if (result.success()) {
                        WebActionLogger.log("ROLE_PERMISSIONS_SAVE", "user=" + sessionUser + " role=" + role + " perms=" + permissions.size() + " ip=" + getClientIp(t));
                    }
                    String msg = result.success() ? "Permissions saved for role: " + role : humanizeRolePermissionError(result.message());
                    redirect(t, "/permissions?role=" + encodeForQuery(role) + "&msg=" + encodeForQuery(msg));
                }

                case "role_create" -> {
                    String roleName = params.getOrDefault("role_name", "").trim();
                    String roleValueStr = params.getOrDefault("role_value", "").trim();
                    if (roleName.isBlank() || roleValueStr.isBlank()) {
                        redirect(t, "/permissions?msg=" + encodeForQuery("Role name and value required"));
                        return;
                    }
                    int roleValue;
                    try {
                        roleValue = Integer.parseInt(roleValueStr);
                    } catch (NumberFormatException e) {
                        redirect(t, "/permissions?msg=" + encodeForQuery("Invalid role value"));
                        return;
                    }
                    WebAuth.AuthResult result = auth.createRoleSafe(sessionUser, roleName, "MODERATOR");
                    String msg = result.success() ? "Role created: " + roleName : humanizeRoleCreationError(result.message());
                    WebActionLogger.log("ROLE_CREATE", "user=" + sessionUser + " role=" + roleName + " value=" + roleValue + " ip=" + getClientIp(t));
                    redirect(t, "/permissions?msg=" + encodeForQuery(msg));
                }

                case "role_set_value" -> {
                    String role = params.getOrDefault("role", "").trim();
                    String newValueStr = params.getOrDefault("new_value", "").trim();
                    if (role.isBlank() || newValueStr.isBlank()) {
                        redirect(t, "/permissions?msg=" + encodeForQuery("Missing role or value"));
                        return;
                    }
                    int newValue;
                    try {
                        newValue = Integer.parseInt(newValueStr);
                    } catch (NumberFormatException e) {
                        redirect(t, "/permissions?msg=" + encodeForQuery("Invalid value"));
                        return;
                    }
                    WebAuth.AuthResult result = auth.setRoleValueSafe(sessionUser, role, newValue);
                    if (result.success()) {
                        WebActionLogger.log("ROLE_SET_VALUE", "user=" + sessionUser + " role=" + role + " value=" + newValue + " ip=" + getClientIp(t));
                    }
                    redirect(t, "/permissions?msg=" + encodeForQuery(result.success() ? "Role value updated" : result.message()));
                }

                case "role_delete" -> {
                    String role = params.getOrDefault("role", "").trim();
                    if (role.isBlank()) {
                        redirect(t, "/permissions?msg=" + encodeForQuery("Missing role"));
                        return;
                    }
                    WebAuth.AuthResult result = auth.deleteRoleSafe(sessionUser, role);
                    if (result.success()) {
                        WebActionLogger.log("ROLE_DELETE", "user=" + sessionUser + " role=" + role + " ip=" + getClientIp(t));
                    }
                    redirect(t, "/permissions?msg=" + encodeForQuery(result.success() ? "Role deleted" : result.message()));
                }

                case "user_set_role" -> {
                    String targetUser = params.getOrDefault("username", "").trim();
                    String role = params.getOrDefault("role", "").trim();
                    if (targetUser.isBlank() || role.isBlank()) {
                        redirect(t, "/users?msg=" + encodeForQuery("Missing username or role"));
                        return;
                    }
                    WebAuth.AuthResult result = auth.setUserRoleSafe(sessionUser, targetUser, role);
                    if (result.success()) {
                        WebActionLogger.log("USER_SET_ROLE", "user=" + sessionUser + " target=" + targetUser + " role=" + role + " ip=" + getClientIp(t));
                    }
                    redirect(t, "/users?msg=" + encodeForQuery(result.success() ? "Role updated for " + targetUser : result.message()));
                }

                case "user_make_main_admin" -> {
                    String targetUser = params.getOrDefault("username", "").trim();
                    if (targetUser.isBlank()) {
                        redirect(t, "/users?msg=" + encodeForQuery("Missing username"));
                        return;
                    }
                    WebAuth.AuthResult result = auth.transferMainAdmin(sessionUser, targetUser);
                    if (result.success()) {
                        WebActionLogger.log("USER_MAKE_MAIN_ADMIN", "from=" + sessionUser + " to=" + targetUser + " ip=" + getClientIp(t));
                    }
                    redirect(t, "/users?msg=" + encodeForQuery(result.success() ? "Main admin transferred to " + targetUser : result.message()));
                }

                case "user_delete" -> {
                    String targetUser = params.getOrDefault("username", "").trim();
                    if (targetUser.isBlank()) {
                        redirect(t, "/users?msg=" + encodeForQuery("Missing username"));
                        return;
                    }
                    WebAuth.AuthResult result = auth.deleteUserSafe(sessionUser, targetUser);
                    if (result.success()) {
                        WebActionLogger.log("USER_DELETE", "user=" + sessionUser + " deleted=" + targetUser + " ip=" + getClientIp(t));
                    }
                    redirect(t, "/users?msg=" + encodeForQuery(result.success() ? "User deleted: " + targetUser : result.message()));
                }

                case "save_plugin_settings" -> {
                    String error = applyPluginSettings(params, t);
                    if (error != null) {
                        redirect(t, "/plugin-settings?msg=" + encodeForQuery(error));
                        return;
                    }
                    redirect(t, "/plugin-settings?msg=" + encodeForQuery("Settings saved"));
                }

                case "task_add" -> {
                    String taskType = firstNonBlank(params.get("task_type"), params.get("task_name")).trim();
                    String taskPayload = firstNonBlank(params.get("payload"), params.get("task_command")).trim();
                    String taskInterval = firstNonBlank(params.get("interval"), params.get("task_interval")).trim();
                    String taskUnit = params.getOrDefault("task_unit", "MINUTES").trim();
                    if (taskType.isBlank() || taskPayload.isBlank() || taskInterval.isBlank()) {
                        redirect(t, "/scheduled-tasks?msg=" + encodeForQuery("Missing required fields"));
                        return;
                    }
                    long intervalVal;
                    try {
                        intervalVal = Long.parseLong(taskInterval);
                    } catch (NumberFormatException e) {
                        redirect(t, "/scheduled-tasks?msg=" + encodeForQuery("Invalid interval"));
                        return;
                    }
                    dash.data.ScheduledTaskManager stm = FabricDash.getScheduledTaskManager();
                    if (stm != null) {
                        int intervalMinutes = (int) intervalVal;
                        if ("HOURS".equalsIgnoreCase(taskUnit)) intervalMinutes = (int) (intervalVal * 60);
                        else if ("SECONDS".equalsIgnoreCase(taskUnit)) intervalMinutes = Math.max(1, (int) (intervalVal / 60));
                        if ("message".equalsIgnoreCase(taskType)) {
                            taskType = dash.data.ScheduledTaskManager.TYPE_BROADCAST;
                        }
                        int createdId = stm.addTask(taskType, intervalMinutes, taskPayload, true);
                        if (createdId > 0) {
                            WebActionLogger.log("TASK_ADD", "user=" + sessionUser + " task=" + taskType + " payload=" + taskPayload + " ip=" + getClientIp(t));
                        } else {
                            redirect(t, "/scheduled-tasks?msg=" + encodeForQuery("Invalid task values"));
                            return;
                        }
                    }
                    redirect(t, "/scheduled-tasks?msg=" + encodeForQuery("Task added"));
                }

                case "task_toggle" -> {
                    String taskId = params.getOrDefault("task_id", "").trim();
                    dash.data.ScheduledTaskManager stm = FabricDash.getScheduledTaskManager();
                    if (stm != null && !taskId.isBlank()) {
                        try {
                            int id = Integer.parseInt(taskId);
                            dash.data.ScheduledTaskManager.ScheduledTask task = stm.getTask(id);
                            if (task != null) {
                                stm.setEnabled(id, !task.enabled());
                            }
                        } catch (NumberFormatException ignored) {}
                        WebActionLogger.log("TASK_TOGGLE", "user=" + sessionUser + " task=" + taskId + " ip=" + getClientIp(t));
                    }
                    redirect(t, "/scheduled-tasks?msg=" + encodeForQuery("Task toggled"));
                }

                case "task_delete" -> {
                    String taskId = params.getOrDefault("task_id", "").trim();
                    dash.data.ScheduledTaskManager stm = FabricDash.getScheduledTaskManager();
                    if (stm != null && !taskId.isBlank()) {
                        try { stm.deleteTask(Integer.parseInt(taskId)); } catch (NumberFormatException ignored) {}
                        WebActionLogger.log("TASK_DELETE", "user=" + sessionUser + " task=" + taskId + " ip=" + getClientIp(t));
                    }
                    redirect(t, "/scheduled-tasks?msg=" + encodeForQuery("Task deleted"));
                }

                case "registration_approve" -> {
                    String pendingId = params.getOrDefault("username", "").trim();
                    dash.RegistrationApprovalManager ram = FabricDash.getRegistrationApprovalManager();
                    if (ram != null && !pendingId.isBlank()) {
                        RegistrationApprovalManager.PendingRegistration pending = ram.consume(pendingId);
                        if (pending != null) {
                            auth.registerApprovedPending(pending);
                            WebActionLogger.log("REGISTRATION_APPROVE", "user=" + sessionUser + " approved=" + pending.username() + " ip=" + getClientIp(t));
                        }
                    }
                    redirect(t, "/users?msg=" + encodeForQuery("Registration approved"));
                }

                case "registration_deny" -> {
                    String pendingId = params.getOrDefault("username", "").trim();
                    dash.RegistrationApprovalManager ram = FabricDash.getRegistrationApprovalManager();
                    if (ram != null && !pendingId.isBlank()) {
                        ram.deny(pendingId);
                        WebActionLogger.log("REGISTRATION_DENY", "user=" + sessionUser + " denied=" + pendingId + " ip=" + getClientIp(t));
                    }
                    redirect(t, "/users?msg=" + encodeForQuery("Registration denied"));
                }

                case "bridge_user_allow" -> {
                    String bridgeUser = params.getOrDefault("username", "").trim();
                    String bridgeRole = params.getOrDefault("role", "MODERATOR").trim();
                    if (!bridgeUser.isBlank()) {
                        WebAuth.AuthResult result = auth.approveBridgeUserSafe(sessionUser, bridgeUser, bridgeRole, List.of());
                        if (result.success()) {
                            WebActionLogger.log("BRIDGE_USER_ALLOW", "user=" + sessionUser + " allowed=" + bridgeUser + " role=" + bridgeRole + " ip=" + getClientIp(t));
                        }
                        redirect(t, "/users?msg=" + encodeForQuery(result.success() ? "Bridge user approved: " + bridgeUser : result.message()));
                    } else {
                        redirect(t, "/users?msg=" + encodeForQuery("Missing username"));
                    }
                }

                case "bridge_user_deny" -> {
                    String bridgeUser = params.getOrDefault("username", "").trim();
                    if (!bridgeUser.isBlank()) {
                        WebAuth.AuthResult result = auth.denyBridgeUserSafe(sessionUser, bridgeUser);
                        if (result.success()) {
                            WebActionLogger.log("BRIDGE_USER_DENY", "user=" + sessionUser + " denied=" + bridgeUser + " ip=" + getClientIp(t));
                        }
                        redirect(t, "/users?msg=" + encodeForQuery(result.success() ? "Bridge user denied: " + bridgeUser : result.message()));
                    } else {
                        redirect(t, "/users?msg=" + encodeForQuery("Missing username"));
                    }
                }

                case "owner_2fa_regen" -> {
                    String regenResult = auth.regenerateOwner2faSecret(sessionUser);
                    boolean success = regenResult != null && !regenResult.isBlank();
                    if (success) {
                        WebActionLogger.log("2FA_REGEN", "user=" + sessionUser + " ip=" + getClientIp(t));
                    }
                    redirect(t, "/users?msg=" + encodeForQuery(success ? "2FA secret regenerated" : "2FA regeneration failed"));
                }

                case "file_delete" -> {
                    String filePath = params.getOrDefault("path", "").trim();
                    if (filePath.isBlank()) {
                        redirect(t, "/files?msg=" + encodeForQuery("Missing path"));
                        return;
                    }
                    File serverDir = FabricDash.getServerRootDirectory();
                    File file = new File(serverDir, filePath);
                    try {
                        if (!file.getCanonicalFile().toPath().startsWith(serverDir.getCanonicalFile().toPath())) {
                            redirect(t, "/files?msg=" + encodeForQuery("Access denied"));
                            return;
                        }
                        if (isProtectedLockFile(file)) {
                            redirect(t, "/files?msg=" + encodeForQuery("Protected file cannot be deleted"));
                            return;
                        }
                        if (file.isDirectory()) {
                            deleteRecursively(file);
                        } else {
                            file.delete();
                        }
                        WebActionLogger.log("FILE_DELETE", "path=" + filePath + " ip=" + getClientIp(t));
                        String parentDir = filePath.contains("/") ? filePath.substring(0, filePath.lastIndexOf('/')) : "";
                        redirect(t, "/files?path=" + encodeForQuery(parentDir));
                    } catch (Exception e) {
                        redirect(t, "/files?msg=" + encodeForQuery("Delete failed: " + e.getMessage()));
                    }
                }

                case "file_rename" -> {
                    String oldPath = params.getOrDefault("path", "").trim();
                    String newName = params.getOrDefault("new_name", "").trim();
                    if (oldPath.isBlank() || newName.isBlank()) {
                        redirect(t, "/files?msg=" + encodeForQuery("Missing path or name"));
                        return;
                    }
                    File serverDir = FabricDash.getServerRootDirectory();
                    File oldFile = new File(serverDir, oldPath);
                    try {
                        if (!oldFile.getCanonicalFile().toPath().startsWith(serverDir.getCanonicalFile().toPath())) {
                            redirect(t, "/files?msg=" + encodeForQuery("Access denied"));
                            return;
                        }
                        File newFile = new File(oldFile.getParentFile(), newName);
                        if (!newFile.getCanonicalFile().toPath().startsWith(serverDir.getCanonicalFile().toPath())) {
                            redirect(t, "/files?msg=" + encodeForQuery("Access denied"));
                            return;
                        }
                        oldFile.renameTo(newFile);
                        WebActionLogger.log("FILE_RENAME", "from=" + oldPath + " to=" + newName + " ip=" + getClientIp(t));
                        String parentDir = oldPath.contains("/") ? oldPath.substring(0, oldPath.lastIndexOf('/')) : "";
                        redirect(t, "/files?path=" + encodeForQuery(parentDir));
                    } catch (Exception e) {
                        redirect(t, "/files?msg=" + encodeForQuery("Rename failed: " + e.getMessage()));
                    }
                }

                case "plugin_delete" -> {
                    String pluginName = params.getOrDefault("plugin", "").trim();
                    if (pluginName.isBlank()) {
                        redirect(t, "/plugins?msg=" + encodeForQuery("Missing mod name"));
                        return;
                    }
                    File modsDir = new File(FabricDash.getServerRootDirectory(), "mods");
                    File[] modFiles = modsDir.listFiles((d, name) -> name.toLowerCase().contains(pluginName.toLowerCase()) && name.endsWith(".jar"));
                    if (modFiles != null && modFiles.length > 0) {
                        for (File mf : modFiles) {
                            mf.delete();
                        }
                        WebActionLogger.log("MOD_DELETE", "user=" + sessionUser + " mod=" + pluginName + " ip=" + getClientIp(t));
                        redirect(t, "/plugins?msg=" + encodeForQuery("Mod file(s) deleted: " + pluginName + " (restart required)"));
                    } else {
                        redirect(t, "/plugins?msg=" + encodeForQuery("No matching mod files found for: " + pluginName));
                    }
                }

                case "plugin_browser_install" -> {
                    try {
                        if (FabricDash.getConfig().getBoolean("backups.pre_update", true) && FabricDash.getBackupManager() != null) {
                            FabricDash.getBackupManager().createBackup();
                        }
                        String msg = PluginBrowserPage.installFromUrl(
                                FabricDash.getServerRootDirectory().toPath().toAbsolutePath().normalize(),
                                "mods",
                                params.get("download_url"),
                                params.get("file_name"),
                                params.get("sha256"),
                                params.get("sha512"));
                        WebActionLogger.log("MOD_BROWSER_INSTALL", "user=" + sessionUser + " file=" + params.get("file_name") + " ip=" + getClientIp(t));
                        redirect(t, "/plugin-browser?msg=" + encodeForQuery(msg));
                    } catch (Exception ex) {
                        redirect(t, "/plugin-browser?msg=" + encodeForQuery("Install failed: " + ex.getMessage()));
                    }
                }

                case "test_notification" -> {
                    DiscordWebhookManager manager = FabricDash.getDiscordWebhookManager();
                    int targets = manager == null ? 0 : manager.dispatchTest(
                            "ForgeDash test notification from " + sessionUser + ". Delivery is working.");
                    WebActionLogger.log("NOTIFICATION_TEST", "user=" + sessionUser + " targets=" + targets + " ip=" + getClientIp(t));
                    String msg = targets == 0 ? "No Discord destination is configured."
                            : "Test notification queued for " + targets + " Discord destination" + (targets == 1 ? "." : "s.");
                    redirect(t, "/notifications?msg=" + encodeForQuery(msg));
                }

                case "save_notification_settings" -> {
                    saveNotificationSettings(params);
                    WebActionLogger.log("NOTIFICATION_SETTINGS_SAVE", "user=" + sessionUser + " ip=" + getClientIp(t));
                    redirect(t, "/notifications?msg=" + encodeForQuery("Notification and cloud backup settings saved."));
                }

                case "doctor_delete_crash", "doctor_mark_reviewed" -> {
                    String msg = DashDoctorPage.resolveCrashAction(
                            FabricDash.getServerRootDirectory().toPath().toAbsolutePath().normalize(),
                            params.get("file"),
                            "doctor_delete_crash".equals(action));
                    WebActionLogger.log("DASH_DOCTOR_ACTION", "user=" + sessionUser + " action=" + action + " file=" + params.get("file") + " ip=" + getClientIp(t));
                redirect(t, "/maintenance?msg=" + encodeForQuery(msg));
                }

                case "staff_ticket_create", "staff_note_create" -> {
                    String msg = "staff_ticket_create".equals(action)
                            ? StaffPage.createDetailed(FabricDash.getDataDir(), params.get("title"), params.get("body"),
                                    params.get("priority"), params.get("target_player"), sessionUser, params.get("category"))
                            : StaffPage.create(FabricDash.getDataDir(), "note", params.get("title"), params.get("body"),
                                    params.get("priority"), params.get("target_player"), sessionUser);
                    WebActionLogger.log("STAFF_WORKFLOW_CREATE", "user=" + sessionUser + " action=" + action + " ip=" + getClientIp(t));
                    if ("staff_ticket_create".equals(action) && msg.toLowerCase(Locale.ROOT).contains("created")
                            && FabricDash.getServer() != null) {
                        FabricDash.getServer().execute(() -> DashCommand.notifyTicketOperators(
                                params.getOrDefault("title", "New web ticket"), sessionUser));
                    }
                    redirect(t, "/staff?msg=" + encodeForQuery(msg));
                }

                case "staff_report_link_create" -> {
                    PublicReportLinks.CreatedLink link = PublicReportLinks.create(FabricDash.getDataDir(), sessionUser,
                            parseInt(params.get("lifetime_minutes"), 1440), params.get("target_player"), params.get("category"));
                    WebActionLogger.log("PUBLIC_REPORT_LINK_CREATE", "user=" + sessionUser + " ip=" + getClientIp(t));
                    redirect(t, "/staff?view=links" + (link.success()
                            ? "&report_token=" + encodeForQuery(link.token())
                            : "&msg=" + encodeForQuery("Report link could not be created.")));
                }

                case "staff_ticket_status" -> {
                    String msg = StaffPage.updateStatus(FabricDash.getDataDir(), params.get("ticket_id"), params.get("status"));
                    WebActionLogger.log("STAFF_WORKFLOW_STATUS", "user=" + sessionUser + " ticket=" + params.get("ticket_id") + " ip=" + getClientIp(t));
                    redirect(t, "/staff?msg=" + encodeForQuery(msg));
                }

                case "staff_ticket_reply" -> {
                    String msg = StaffPage.appendReply(FabricDash.getDataDir(), params.get("ticket_id"), sessionUser, params.get("reply"));
                    WebActionLogger.log("STAFF_WORKFLOW_REPLY", "user=" + sessionUser + " ticket=" + params.get("ticket_id") + " ip=" + getClientIp(t));
                    redirect(t, "/staff?msg=" + encodeForQuery(msg));
                }

                case "staff_ticket_delete" -> {
                    String msg = StaffPage.delete(FabricDash.getDataDir(), params.get("ticket_id"));
                    WebActionLogger.log("STAFF_WORKFLOW_DELETE", "user=" + sessionUser + " ticket=" + params.get("ticket_id") + " ip=" + getClientIp(t));
                    redirect(t, "/staff?msg=" + encodeForQuery(msg));
                }

                case "command", "restart", "stop", "kick", "ban", "freeze",
                     "tp_to_coords", "tp_player_to_player",
                     "gamerule",
                     "gamerule_keep_inventory", "gamerule_mob_spawning", "gamerule_daylight_cycle",
                     "gamerule_weather_cycle", "gamerule_mob_griefing", "gamerule_fire_tick",
                     "gamerule_natural_regeneration",
                     "whitelist_add", "whitelist_remove", "whitelist_toggle",
                     "plugin_enable", "plugin_disable",
                     "chat", "mute",
                     "backup_create", "backup_delete", "backup_schedule",
                     "datapack_toggle", "datapack_delete",
                     "set_motd", "spark_profile",
                     "add_note", "delete_note", "set_distance",
                     "give_item", "give_enderchest" -> {
                    handleServerAction(action, params, t, sessionUser);
                }

                default -> redirect(t, "/?error=unknown_action");
            }
        }
    }

    private void handleServerAction(String action, Map<String, String> params, HttpExchange t, String sessionUser) throws IOException {
        if ("backup_create".equals(action)) {
            dash.data.BackupManager manager = FabricDash.getBackupManager();
            if (manager == null) {
                redirect(t, withActionMessage(resolveServerActionRedirect(action, params),
                        "Backup service is unavailable."));
                return;
            }
            try {
                dash.data.BackupManager.BackupResult result = manager.createBackupAsync().get(15, TimeUnit.MINUTES);
                if (result.success()) {
                    WebActionLogger.log("BACKUP_CREATE",
                            "user=" + sessionUser + " file=" + result.fileName() + " ip=" + getClientIp(t));
                    redirect(t, withActionMessage(resolveServerActionRedirect(action, params),
                            "Verified backup created: " + result.fileName() + "."));
                } else {
                    redirect(t, withActionMessage(resolveServerActionRedirect(action, params),
                            "Backup failed: " + (result.error() == null ? "Unknown error" : result.error())));
                }
            } catch (java.util.concurrent.TimeoutException ex) {
                redirect(t, withActionMessage(resolveServerActionRedirect(action, params),
                        "Backup is still running. Refresh this page when verification finishes."));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                redirect(t, withActionMessage(resolveServerActionRedirect(action, params),
                        "Backup was interrupted. Please retry."));
            } catch (Exception ex) {
                redirect(t, withActionMessage(resolveServerActionRedirect(action, params),
                        "Backup failed safely."));
            }
            return;
        }

        if ("backup_delete".equals(action)) {
            String backupName = firstNonBlank(params.get("backup"), params.get("name")).trim();
            dash.data.BackupManager manager = FabricDash.getBackupManager();
            boolean deleted = manager != null && !backupName.isBlank() && manager.deleteBackup(backupName);
            if (deleted) {
                WebActionLogger.log("BACKUP_DELETE",
                        "user=" + sessionUser + " backup=" + backupName + " ip=" + getClientIp(t));
            }
            redirect(t, withActionMessage(resolveServerActionRedirect(action, params),
                    deleted ? "Backup deleted." : "Backup was not found or could not be deleted."));
            return;
        }

        CompletableFuture<Void> actionCompletion = new CompletableFuture<>();
        server.execute(() -> {
            try {
                switch (action) {
                    case "command" -> {
                        String cmd = firstNonBlank(params.get("command"), params.get("cmd")).trim();
                        if (!cmd.isBlank()) {
                            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), cmd);
                            WebActionLogger.log("COMMAND", "user=" + sessionUser + " cmd=" + cmd + " ip=" + getClientIp(t));
                        }
                    }

                    case "restart" -> {
                        WebActionLogger.log("RESTART", "user=" + sessionUser + " ip=" + getClientIp(t));
                        server.getPlayerList().broadcastSystemMessage(
                                Component.literal("[Dash] Server is restarting...").withStyle(ChatFormatting.YELLOW), false);
                        FabricDash.getInstance().getScheduler().schedule(() -> server.halt(false), 2, TimeUnit.SECONDS);
                    }

                    case "stop" -> {
                        WebActionLogger.log("STOP", "user=" + sessionUser + " ip=" + getClientIp(t));
                        server.getPlayerList().broadcastSystemMessage(
                                Component.literal("[Dash] Server is stopping...").withStyle(ChatFormatting.RED), false);
                        FabricDash.getInstance().getScheduler().schedule(() -> server.halt(false), 2, TimeUnit.SECONDS);
                    }

                    case "kick" -> {
                        String playerName = params.getOrDefault("player", "").trim();
                        String reason = params.getOrDefault("reason", "Kicked by admin").trim();
                        if (!playerName.isBlank()) {
                            ServerPlayer player = server.getPlayerList().getPlayerByName(playerName);
                            if (player != null) {
                                player.connection.disconnect(Component.literal(reason));
                                WebActionLogger.log("KICK", "user=" + sessionUser + " player=" + playerName + " reason=" + reason + " ip=" + getClientIp(t));
                            }
                        }
                    }

                    case "ban" -> {
                        String playerName = params.getOrDefault("player", "").trim();
                        String reason = params.getOrDefault("reason", "Banned by admin").trim();
                        if (!playerName.isBlank()) {
                            ServerPlayer onlineBanTarget = server.getPlayerList().getPlayerByName(playerName);
                            NameAndId banProfile = onlineBanTarget != null ? onlineBanTarget.nameAndId() : NameAndId.createOffline(playerName);
                            server.getPlayerList().getBans().add(
                                    new UserBanListEntry(banProfile, null, sessionUser, null, reason));
                            if (onlineBanTarget != null) {
                                onlineBanTarget.connection.disconnect(Component.literal("Banned: " + reason));
                            }
                            WebActionLogger.log("BAN", "user=" + sessionUser + " player=" + playerName + " reason=" + reason + " ip=" + getClientIp(t));
                        }
                    }

                    case "freeze" -> {
                        String playerName = params.getOrDefault("player", "").trim();
                        if (!playerName.isBlank()) {
                            ServerPlayer player = server.getPlayerList().getPlayerByName(playerName);
                            if (player != null) {
                                boolean frozenNow = FreezeManager.toggleFreeze(player.getUUID());
                                if (frozenNow) {
                                    server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                                            "title " + playerName + " title {\"text\":\"You have been frozen\",\"color\":\"red\",\"bold\":true}");
                                } else {
                                    server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                                            "title " + playerName + " title {\"text\":\"You are unfrozen\",\"color\":\"green\",\"bold\":true}");
                                }
                                WebActionLogger.log("FREEZE", "user=" + sessionUser + " player=" + playerName + " state=" + (frozenNow ? "frozen" : "unfrozen") + " ip=" + getClientIp(t));
                            }
                        }
                    }

                    case "tp_to_coords" -> {
                        String playerName = params.getOrDefault("player", "").trim();
                        String xStr = params.getOrDefault("x", "").trim();
                        String yStr = params.getOrDefault("y", "").trim();
                        String zStr = params.getOrDefault("z", "").trim();
                        if (!playerName.isBlank() && !xStr.isBlank() && !yStr.isBlank() && !zStr.isBlank()) {
                            ServerPlayer player = server.getPlayerList().getPlayerByName(playerName);
                            if (player != null) {
                                try {
                                    double x = Double.parseDouble(xStr);
                                    double y = Double.parseDouble(yStr);
                                    double z = Double.parseDouble(zStr);
                                    player.teleportTo(x, y, z);
                                    WebActionLogger.log("TELEPORT", "user=" + sessionUser + " player=" + playerName
                                            + " to=" + x + "," + y + "," + z + " ip=" + getClientIp(t));
                                } catch (NumberFormatException ignored) {
                                }
                            }
                        }
                    }

                    case "tp_player_to_player" -> {
                        String target = firstNonBlank(params.get("player"), params.get("target")).trim();
                        String dest = params.getOrDefault("destination", "").trim();
                        if (!target.isBlank() && !dest.isBlank()) {
                            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "tp " + target + " " + dest);
                            WebActionLogger.log("TELEPORT_TO_PLAYER", "user=" + sessionUser + " target=" + target + " dest=" + dest + " ip=" + getClientIp(t));
                        }
                    }

                    case "gamerule" -> {
                        String ruleName = params.getOrDefault("rule", "").trim();
                        switch (ruleName) {
                            case "keepInventory" ->
                                    setGameRuleBool(params, GameRules.KEEP_INVENTORY, "keepInventory", sessionUser, t);
                            case "doMobSpawning" ->
                                    setGameRuleBool(params, GameRules.SPAWN_MOBS, "doMobSpawning", sessionUser, t);
                            case "doDaylightCycle" ->
                                    setGameRuleBool(params, GameRules.ADVANCE_TIME, "doDaylightCycle", sessionUser, t);
                            case "doWeatherCycle" ->
                                    setGameRuleBool(params, GameRules.ADVANCE_WEATHER, "doWeatherCycle", sessionUser, t);
                            case "mobGriefing" ->
                                    setGameRuleBool(params, GameRules.MOB_GRIEFING, "mobGriefing", sessionUser, t);
                            case "naturalRegeneration" ->
                                    setGameRuleBool(params, GameRules.NATURAL_HEALTH_REGENERATION, "naturalRegeneration", sessionUser, t);
                            default -> {
                            }
                        }
                    }

                    case "gamerule_keep_inventory" -> setGameRuleBool(params, GameRules.KEEP_INVENTORY, "keepInventory", sessionUser, t);
                    case "gamerule_mob_spawning" -> setGameRuleBool(params, GameRules.SPAWN_MOBS, "doMobSpawning", sessionUser, t);
                    case "gamerule_daylight_cycle" -> setGameRuleBool(params, GameRules.ADVANCE_TIME, "doDaylightCycle", sessionUser, t);
                    case "gamerule_weather_cycle" -> setGameRuleBool(params, GameRules.ADVANCE_WEATHER, "doWeatherCycle", sessionUser, t);
                    case "gamerule_mob_griefing" -> setGameRuleBool(params, GameRules.MOB_GRIEFING, "mobGriefing", sessionUser, t);
                    case "gamerule_natural_regeneration" -> setGameRuleBool(params, GameRules.NATURAL_HEALTH_REGENERATION, "naturalRegeneration", sessionUser, t);

                    case "whitelist_add" -> {
                        String playerName = params.getOrDefault("player", "").trim();
                        if (!playerName.isBlank()) {
                            ServerPlayer wlPlayer = server.getPlayerList().getPlayerByName(playerName);
                            NameAndId wlProfile = wlPlayer != null ? wlPlayer.nameAndId() : NameAndId.createOffline(playerName);
                            server.getPlayerList().getWhiteList().add(new UserWhiteListEntry(wlProfile));
                            WebActionLogger.log("WHITELIST_ADD", "user=" + sessionUser + " player=" + playerName + " ip=" + getClientIp(t));
                        }
                    }

                    case "whitelist_remove" -> {
                        String playerName = params.getOrDefault("player", "").trim();
                        if (!playerName.isBlank()) {
                            ServerPlayer wlrPlayer = server.getPlayerList().getPlayerByName(playerName);
                            NameAndId wlrProfile = wlrPlayer != null ? wlrPlayer.nameAndId() : NameAndId.createOffline(playerName);
                            server.getPlayerList().getWhiteList().remove(wlrProfile);
                            WebActionLogger.log("WHITELIST_REMOVE", "user=" + sessionUser + " player=" + playerName + " ip=" + getClientIp(t));
                        }
                    }

                    case "whitelist_toggle" -> {
                        boolean current = server.getPlayerList().isUsingWhitelist();
                        server.setUsingWhitelist(!current);
                        WebActionLogger.log("WHITELIST_TOGGLE", "user=" + sessionUser + " enabled=" + !current + " ip=" + getClientIp(t));
                    }

                    case "plugin_enable" -> {
                        FabricDash.LOGGER.warn("NeoForge mods cannot be toggled at runtime. Ignoring plugin_enable.");
                    }

                    case "plugin_disable" -> {
                        FabricDash.LOGGER.warn("NeoForge mods cannot be toggled at runtime. Ignoring plugin_disable.");
                    }

                    case "chat" -> {
                        String message = params.getOrDefault("message", "").trim();
                        if (!message.isBlank()) {
                            server.getPlayerList().broadcastSystemMessage(
                                    Component.literal("<Dash> " + message).withStyle(ChatFormatting.LIGHT_PURPLE), false);
                            WebActionLogger.log("CHAT", "user=" + sessionUser + " msg=" + message + " ip=" + getClientIp(t));
                        }
                    }

                    case "mute" -> {
                        String playerName = params.getOrDefault("player", "").trim();
                        if (!playerName.isBlank()) {
                        FabricDash.LOGGER.warn("Mute is not natively supported in NeoForge. Consider using a mute mod or command.");
                            WebActionLogger.log("MUTE_ATTEMPT", "user=" + sessionUser + " player=" + playerName + " ip=" + getClientIp(t));
                        }
                    }

                    case "backup_schedule" -> {
                        String intervalStr = firstNonBlank(params.get("interval"), params.get("hours")).trim();
                        dash.data.BackupManager bm = FabricDash.getBackupManager();
                        if (bm != null && !intervalStr.isBlank()) {
                            try {
                                int interval = Integer.parseInt(intervalStr);
                                if (!Set.of(0, 1, 6, 12, 24).contains(interval)) {
                                    break;
                                }
                                if (interval <= 0) {
                                    bm.stopSchedule();
                                } else {
                                    bm.startSchedule(interval);
                                }
                                WebActionLogger.log("BACKUP_SCHEDULE", "user=" + sessionUser + " interval=" + interval + " ip=" + getClientIp(t));
                            } catch (NumberFormatException ignored) {
                            }
                        }
                    }

                    case "datapack_toggle" -> {
                        String packName = firstNonBlank(params.get("datapack"), params.get("name")).trim();
                        boolean enable = Boolean.parseBoolean(params.getOrDefault("enable", "true"));
                        if (!packName.isBlank()) {
                            if (dash.data.DatapackManager.toggleDatapack(packName, enable)) {
                                WebActionLogger.log("DATAPACK_TOGGLE", "user=" + sessionUser + " pack=" + packName + " enable=" + enable + " ip=" + getClientIp(t));
                            }
                        }
                    }

                    case "datapack_delete" -> {
                        String packName = firstNonBlank(params.get("datapack"), params.get("name")).trim();
                        if (!packName.isBlank()) {
                            if (dash.data.DatapackManager.deleteDatapack(packName)) {
                                WebActionLogger.log("DATAPACK_DELETE", "user=" + sessionUser + " pack=" + packName + " ip=" + getClientIp(t));
                            }
                        }
                    }

                    case "set_motd" -> {
                        String motd = params.getOrDefault("motd", "");
                        File propsFile = new File(FabricDash.getServerRootDirectory(), "server.properties");
                        Properties props = new Properties();
                        if (propsFile.exists()) {
                            try (FileInputStream in = new FileInputStream(propsFile)) {
                                props.load(in);
                            }
                        }
                        props.setProperty("motd", motd);
                        try (FileOutputStream out = new FileOutputStream(propsFile)) {
                            props.store(out, "Updated by ForgeDash");
                        }
                        try {
                            server.setMotd(motd);
                        } catch (Exception ignored) {
                        }
                        WebActionLogger.log("SET_MOTD", "user=" + sessionUser + " ip=" + getClientIp(t));
                    }

                    case "spark_profile" -> {
                        if (server.getCommands().getDispatcher().getRoot().getChild("spark") != null) {
                            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "spark profiler start --timeout 60");
                            WebActionLogger.log("SPARK_PROFILE", "user=" + sessionUser + " timeout=60 ip=" + getClientIp(t));
                        } else {
                            FabricDash.LOGGER.warn("Spark profiling requested, but the spark command is unavailable.");
                        }
                    }

                    case "add_note" -> {
                        String playerName = params.getOrDefault("player", "").trim();
                        String uuidParam = params.getOrDefault("uuid", "").trim();
                        String note = params.getOrDefault("note", "").trim();
                        if ((!playerName.isBlank() || !uuidParam.isBlank()) && !note.isBlank()) {
                            dash.data.PlayerDataManager pdm = FabricDash.getPlayerDataManager();
                            if (pdm != null) {
                                String uuid = uuidParam;
                                if (uuid.isBlank()) {
                                    uuid = playerName;
                                    ServerPlayer notePlayer = server.getPlayerList().getPlayerByName(playerName);
                                    if (notePlayer != null) {
                                        uuid = notePlayer.getUUID().toString();
                                    }
                                }
                                pdm.addNote(uuid, sessionUser, note);
                                WebActionLogger.log("ADD_NOTE", "user=" + sessionUser + " player=" + playerName + " ip=" + getClientIp(t));
                            }
                        }
                    }

                    case "delete_note" -> {
                        String playerName = params.getOrDefault("player", "").trim();
                        String noteIndex = firstNonBlank(params.get("note_index"), params.get("id")).trim();
                        if (!noteIndex.isBlank()) {
                            dash.data.PlayerDataManager pdm = FabricDash.getPlayerDataManager();
                            if (pdm != null) {
                                try {
                                    int noteId = Integer.parseInt(noteIndex);
                                    pdm.deleteNote(noteId);
                                    WebActionLogger.log("DELETE_NOTE", "user=" + sessionUser + " player=" + playerName + " noteId=" + noteId + " ip=" + getClientIp(t));
                                } catch (NumberFormatException ignored) {
                                }
                            }
                        }
                    }

                    case "set_distance" -> {
                        String viewStr = firstNonBlank(params.get("view"), params.get("distance")).trim();
                        String simStr = params.getOrDefault("sim", "").trim();
                        if (!viewStr.isBlank() || !simStr.isBlank()) {
                            try {
                                File propsFile = new File(FabricDash.getServerRootDirectory(), "server.properties");
                                Properties props = new Properties();
                                if (propsFile.exists()) {
                                    try (FileInputStream in = new FileInputStream(propsFile)) {
                                        props.load(in);
                                    }
                                }
                                if (!viewStr.isBlank()) {
                                    int view = Math.max(2, Math.min(32, Integer.parseInt(viewStr)));
                                    try {
                                        server.getPlayerList().setViewDistance(view);
                                    } catch (Exception ignored) {
                                    }
                                    props.setProperty("view-distance", String.valueOf(view));
                                }
                                if (!simStr.isBlank()) {
                                    int sim = Math.max(2, Math.min(32, Integer.parseInt(simStr)));
                                    try {
                                        server.getPlayerList().getClass()
                                                .getMethod("setSimulationDistance", int.class)
                                                .invoke(server.getPlayerList(), sim);
                                    } catch (Exception ignored) {
                                    }
                                    props.setProperty("simulation-distance", String.valueOf(sim));
                                }
                                try (FileOutputStream out = new FileOutputStream(propsFile)) {
                                    props.store(out, "Updated by ForgeDash");
                                }
                                WebActionLogger.log("SET_DISTANCE", "user=" + sessionUser + " view=" + viewStr + " sim=" + simStr + " ip=" + getClientIp(t));
                            } catch (NumberFormatException ignored) {
                            }
                        }
                    }

                    case "give_item" -> {
                        String playerName = params.getOrDefault("player", "").trim();
                        String itemName = firstNonBlank(params.get("item"), params.get("material")).trim();
                        String amountStr = params.getOrDefault("amount", "1").trim();
                        if (!playerName.isBlank() && !itemName.isBlank()) {
                            ServerPlayer player = server.getPlayerList().getPlayerByName(playerName);
                            if (player != null) {
                                String normalizedItem = itemName.toLowerCase().replace(" ", "_");
                                if (!normalizedItem.contains(":")) {
                                    normalizedItem = "minecraft:" + normalizedItem;
                                }
                                Identifier id = Identifier.tryParse(normalizedItem);
                                if (id != null) {
                                    Item item = BuiltInRegistries.ITEM.get(id).map(ref -> ref.value()).orElse(Items.AIR);
                                    if (item != Items.AIR) {
                                        int amount = 1;
                                        try { amount = Math.max(1, Math.min(64, Integer.parseInt(amountStr))); } catch (NumberFormatException ignored) {}
                                        net.minecraft.world.item.ItemStack stack = new net.minecraft.world.item.ItemStack(item, amount);
                                        player.getInventory().add(stack);
                                        WebActionLogger.log("GIVE_ITEM", "user=" + sessionUser + " player=" + playerName + " item=" + itemName + " amount=" + amount + " ip=" + getClientIp(t));
                                    }
                                }
                            }
                        }
                    }

                    case "give_enderchest" -> {
                        String playerName = params.getOrDefault("player", "").trim();
                        String itemName = firstNonBlank(params.get("item"), params.get("material")).trim();
                        String amountStr = params.getOrDefault("amount", "1").trim();
                        if (!playerName.isBlank() && !itemName.isBlank()) {
                            ServerPlayer player = server.getPlayerList().getPlayerByName(playerName);
                            if (player != null) {
                                String normalizedItem = itemName.toLowerCase().replace(" ", "_");
                                if (!normalizedItem.contains(":")) {
                                    normalizedItem = "minecraft:" + normalizedItem;
                                }
                                Identifier id = Identifier.tryParse(normalizedItem);
                                if (id != null) {
                                    Item item = BuiltInRegistries.ITEM.get(id).map(ref -> ref.value()).orElse(Items.AIR);
                                    if (item != Items.AIR) {
                                        int amount = 1;
                                        try { amount = Math.max(1, Math.min(64, Integer.parseInt(amountStr))); } catch (NumberFormatException ignored) {}
                                        net.minecraft.world.item.ItemStack stack = new net.minecraft.world.item.ItemStack(item, amount);
                                        player.getEnderChestInventory().addItem(stack);
                                        WebActionLogger.log("GIVE_ENDERCHEST", "user=" + sessionUser + " player=" + playerName + " item=" + itemName + " amount=" + amount + " ip=" + getClientIp(t));
                                    }
                                }
                            }
                        }
                    }
                }
                if (Set.of("restart", "stop", "ban", "freeze", "whitelist_add", "whitelist_remove",
                        "whitelist_toggle", "plugin_enable", "plugin_disable", "backup_delete", "datapack_delete")
                        .contains(action)) {
                    DashCommand.notifySecurityOperators(action.replace('_', ' '), sessionUser);
                }
            } catch (Exception ex) {
                FabricDash.LOGGER.error("Error executing server action '" + action + "': " + ex.getMessage());
                actionCompletion.completeExceptionally(ex);
            } finally {
                if (!actionCompletion.isDone()) {
                    actionCompletion.complete(null);
                }
            }
        });

        try {
            actionCompletion.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            sendResponseWithStatus(t, 503, "Server action was interrupted. Please retry.");
            return;
        } catch (Exception ex) {
            FabricDash.LOGGER.warn("Web action '" + action + "' failed or timed out.");
            sendResponseWithStatus(t, 500, "Server action failed or timed out.");
            return;
        }

        redirect(t, resolveServerActionRedirect(action, params));
    }

    private String resolveServerActionRedirect(String action, Map<String, String> params) {
        String redirectTarget = switch (action) {
            case "command" -> "/console";
            case "restart", "stop" -> "/";
            case "kick", "ban", "freeze", "tp_to_coords", "tp_player_to_player", "mute",
                 "add_note", "delete_note", "give_item", "give_enderchest" -> {
                String player = params.getOrDefault("player", "");
                yield player.isBlank() ? "/players" : "/players/" + encodeForQuery(player) + "/profile";
            }
            case "chat" -> "/console";
            case "set_motd" -> "/settings?msg=" + encodeForQuery("MOTD updated");
            case "spark_profile" -> "/maintenance?msg=" + encodeForQuery("Spark profiling request processed");
            case "set_distance" -> "/settings?msg=" + encodeForQuery("View distance updated");
            case "backup_create", "backup_delete", "backup_schedule" -> "/settings";
            case "datapack_toggle", "datapack_delete" -> "/settings";
            case "whitelist_add", "whitelist_remove", "whitelist_toggle" -> "/settings";
            case "plugin_enable", "plugin_disable" -> "/plugins";
            default -> "/settings";
        };
        String returnTo = firstNonBlank(params.get("return_to")).trim();
        if (!returnTo.isBlank() && returnTo.startsWith("/") && !returnTo.startsWith("//")
                && !returnTo.contains("\\") && !returnTo.contains("\r") && !returnTo.contains("\n")) {
            redirectTarget = returnTo;
        }
        return redirectTarget;
    }

    private String withActionMessage(String returnTarget, String message) {
        String target = returnTarget == null || returnTarget.isBlank() ? "/" : returnTarget;
        int queryIndex = target.indexOf('?');
        String path = queryIndex < 0 ? target : target.substring(0, queryIndex);
        return path + "?msg=" + encodeForQuery(message == null ? "" : message);
    }

    private void setGameRuleBool(Map<String, String> params, GameRule<Boolean> rule, String name, String sessionUser, HttpExchange t) {
        String valueStr = params.getOrDefault("value", "true").trim();
        boolean value = "true".equalsIgnoreCase(valueStr) || "on".equalsIgnoreCase(valueStr) || "1".equals(valueStr);
        for (ServerLevel world : server.getAllLevels()) {
            world.getGameRules().set(rule, value, server);
        }
        WebActionLogger.log("GAMERULE", "user=" + sessionUser + " rule=" + name + " value=" + value + " ip=" + getClientIp(t));
    }


    private void ensureUpdaterConfigStructure() {
        FabricConfig config = FabricDash.getConfig();
        if (!config.contains("updater.enabled")) {
            config.set("updater.enabled", false);
        }
        if (!config.contains("updater.repo")) {
            config.set("updater.repo", "");
        }
        if (!config.contains("updater.check_interval_minutes")) {
            config.set("updater.check_interval_minutes", 120);
        }
        config.save();
    }

    private String applyPluginSettings(Map<String, String> params, HttpExchange t) {
        FabricConfig config = FabricDash.getConfig();
        String serverIpVal = params.get("server_ip");
        String panelUrlVal = params.get("panel_url");
        String reportUrlVal = params.get("report_url");
        boolean sslEnabled = params.containsKey("ssl_enabled");

        if (sslEnabled
                && (serverIpVal == null || serverIpVal.trim().isBlank()
                || panelUrlVal == null || panelUrlVal.trim().isBlank()
                || reportUrlVal == null || reportUrlVal.trim().isBlank())) {
            return "Error: Server IP, Panel URL and Public Report URL are required when SSL is enabled";
        }

        config.set("ssl-enabled", sslEnabled);

        String submittedBridgeSecret = params.get("bridge_secret");
        if (params.containsKey("bridge_enabled") && submittedBridgeSecret != null && !submittedBridgeSecret.isBlank()
                && (submittedBridgeSecret.trim().length() < 32
                || "your-super-secret-key".equals(submittedBridgeSecret.trim()))) {
            return "Error: Bridge secret must contain at least 32 characters";
        }
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if ((entry.getKey().startsWith("wh_url_")
                    || entry.getKey().startsWith("webhook_") && entry.getKey().endsWith("_url"))
                    && entry.getValue() != null && !entry.getValue().isBlank()
                    && !DiscordWebhookPolicy.isAllowed(entry.getValue())) {
                return "Error: Only Discord HTTPS webhook URLs are allowed";
            }
        }

        if (params.containsKey("bridge_enabled")
                || params.containsKey("bridge_secret")
                || params.containsKey("bridge_master_url")) {
            config.set("bridge.enabled", params.containsKey("bridge_enabled"));
        }

        for (Map.Entry<String, String> entry : params.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if ("action".equals(key) || key.startsWith("wh_url_") || key.startsWith("wh_evt_") || "wh_count".equals(key)) {
                continue;
            }

            if ("bridge_enabled".equals(key)) {
                config.set("bridge.enabled", "on".equalsIgnoreCase(value));
            } else if ("bridge_secret".equals(key)) {
                if (value != null && !value.isBlank()) {
                    config.set("bridge.secret", value.trim());
                }
            } else if ("bridge_master_url".equals(key)) {
                config.set("bridge.master_url", value == null ? "" : value.trim());
            } else if ("panel_url".equals(key)) {
                config.set("panel-url", value == null ? "" : value.trim());
            } else if ("report_url".equals(key)) {
                config.set("report-url", value == null ? "" : value.trim());
            } else if ("server_ip".equals(key)) {
                config.set("server-ip", value == null ? "" : value.trim());
            } else if ("ssl_enabled".equals(key)) {
                config.set("ssl-enabled", true);
            } else if ("web_port".equals(key)) {
                try {
                    int parsed = Integer.parseInt(value);
                    if (parsed >= 1 && parsed <= 65535) {
                        config.set("port", parsed);
                    }
                } catch (NumberFormatException ignored) {
                }
            } else if ("max_backups".equals(key)) {
                try {
                    int parsed = Integer.parseInt(value);
                    config.set("backups.max-backups", Math.max(1, Math.min(100, parsed)));
                } catch (NumberFormatException ignored) {
                }
            } else if ("registration_approval_required".equals(key)) {
                config.set("registration.approval_required", "on".equalsIgnoreCase(value));
            } else if ("audit_enabled".equals(key)) {
                config.set("audit.enabled", "on".equalsIgnoreCase(value));
            } else if ("updater_enabled".equals(key)) {
                config.set("updater.enabled", "on".equalsIgnoreCase(value));
            } else if ("updater_repo".equals(key)) {
                config.set("updater.repo", value);
            } else if (key.startsWith("webhook_") && key.endsWith("_url")) {
                // legacy webhook_* compatibility
                String webhookName = key.substring(8, key.length() - 4);
                config.set("webhooks." + webhookName + ".url", value);
                String enabledKey = "webhook_" + webhookName + "_enabled";
                boolean enabled = "on".equals(params.getOrDefault(enabledKey, "off"));
                config.set("webhooks." + webhookName + ".enabled", enabled);
            } else if (key.startsWith("webhook_") && key.endsWith("_enabled")) {
                // handled with webhook url key
            } else {
                config.set(key, value);
            }
        }

        // New webhook editor format: wh_url_<idx> + wh_evt_<idx>_<event>
        List<DiscordWebhookManager.WebhookEntry> webhookEntries = new ArrayList<>();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith("wh_url_")) {
                continue;
            }
            String idx = key.substring("wh_url_".length()).trim();
            if (idx.isBlank()) {
                continue;
            }
            String url = entry.getValue() == null ? "" : entry.getValue().trim();
            if (url.isBlank()) {
                continue;
            }
            List<String> events = new ArrayList<>();
            for (String evt : DiscordWebhookManager.ALL_EVENTS) {
                String eventKey = "wh_evt_" + idx + "_" + evt;
                if ("on".equalsIgnoreCase(params.get(eventKey))) {
                    events.add(evt);
                }
            }
            if (events.isEmpty()) {
                events.add(DiscordWebhookManager.EVENT_AUDIT);
            }
            webhookEntries.add(new DiscordWebhookManager.WebhookEntry(url, events));
        }
        DiscordWebhookManager webhookManager = FabricDash.getDiscordWebhookManager();
        if (webhookManager != null) {
            webhookManager.saveWebhooks(webhookEntries);
        } else {
            List<Map<String, Object>> serialized = new ArrayList<>();
            for (DiscordWebhookManager.WebhookEntry entry : webhookEntries) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("url", entry.url());
                row.put("events", new ArrayList<>(entry.events()));
                serialized.add(row);
            }
            config.set("discord-webhooks", serialized);
        }

        config.save();
        WebActionLogger.logSettingChange("plugin_settings", "bulk", getClientIp(t));
        return null;
    }

    private class SettingsApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if (!"POST".equalsIgnoreCase(t.getRequestMethod()) && !"PUT".equalsIgnoreCase(t.getRequestMethod())) {
                t.sendResponseHeaders(405, -1);
                t.close();
                return;
            }
            if (!ensurePermission(t, "dash.web.pluginsettings.write", true)) {
                return;
            }

            byte[] requestBody = readRequestBodyOrReject(t, 1024L * 1024L);
            if (requestBody == null) {
                return;
            }
            String body = new String(requestBody, StandardCharsets.UTF_8);
            Map<String, String> params = parseFormData(body);
            String error = applyPluginSettings(params, t);
            t.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            if (error != null) {
                sendResponseWithStatus(t, 400, "{\"success\":false,\"error\":\"" + jsonEscape(error) + "\"}");
                return;
            }
            sendResponseWithStatus(t, 200, "{\"success\":true}");
        }
    }

    private String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return "";
    }

    private boolean redirectToNeoDashRestartIfAvailable(HttpExchange t) throws IOException {
        SessionInfo session = resolveSession(t);
        if (session == null) {
            return false;
        }
        String restartUrl = FabricDash.getConfig().getString("bridge.restart_url", "").trim();
        String lower = restartUrl.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return false;
        }
        redirect(t, restartUrl);
        return true;
    }

    private boolean betaFeaturesEnabled() {
        return FabricDash.getConfig() != null
                && FabricDash.getConfig().getBoolean(BETA_FEATURES_CONFIG_KEY, false);
    }

    private boolean ensureBetaFeatureEnabled(HttpExchange t) throws IOException {
        if (betaFeaturesEnabled()) {
            return true;
        }
        redirect(t, "/settings?msg=" + encodeForQuery("Enable Beta Features in Settings to use this section."));
        return false;
    }

    private boolean isBetaFeatureAction(String action) {
        if (action == null) {
            return false;
        }
        return switch (action) {
            case "spark_profile", "doctor_delete_crash", "doctor_mark_reviewed" -> true;
            default -> false;
        };
    }

    private boolean isSetupTelemetryBypass(HttpExchange t) {
        return auth.isSetupRequired();
    }

    private String requiredPermissionForAction(String action) {
        if (action == null) {
            return null;
        }
        if (action.startsWith("intel_")) {
            return "dash.web.intelligence.write";
        }
        return switch (action) {
            case "command" -> "dash.web.console.command";
            case "restart", "stop" -> "dash.web.server.control";
            case "kick", "ban", "freeze", "mute" -> "dash.web.players.moderate";
            case "tp_to_coords", "tp_player_to_player" -> "dash.web.players.moderate";
            case "chat" -> "dash.web.console.command";
            case "give_item", "give_enderchest" -> "dash.web.players.inventory.write";
            case "gamerule" -> "dash.web.settings.write";
            case "gamerule_keep_inventory", "gamerule_mob_spawning", "gamerule_daylight_cycle",
                 "gamerule_weather_cycle", "gamerule_mob_griefing", "gamerule_fire_tick",
                 "gamerule_natural_regeneration" -> "dash.web.settings.write";
            case "whitelist_add", "whitelist_remove", "whitelist_toggle" -> "dash.web.settings.write";
            case "plugin_enable", "plugin_disable", "plugin_delete", "plugin_browser_install" -> "dash.web.plugins.manage";
            case "set_motd" -> "dash.web.settings.write";
            case "spark_profile" -> "dash.web.tools.spark";
            case "set_distance" -> "dash.web.settings.write";
            case "backup_create" -> "dash.web.backups.create";
            case "backup_delete" -> "dash.web.backups.delete";
            case "backup_schedule" -> "dash.web.backups.schedule";
            case "datapack_toggle", "datapack_delete" -> "dash.web.settings.write";
            case "file_delete", "file_rename" -> "dash.web.files.write";
            case "save_plugin_settings", "save_notification_settings", "test_notification" -> "dash.web.pluginsettings.write";
            case "save_beta_settings" -> "dash.web.settings.write";
            case "save_feature_settings" -> "dash.web.settings.write";
            case "doctor_delete_crash", "doctor_mark_reviewed" -> "dash.web.settings.write";
            case "staff_ticket_create" -> null;
            case "staff_note_create", "staff_ticket_status", "staff_ticket_reply", "staff_ticket_delete", "staff_report_link_create" -> "dash.web.players.moderate";
            case "task_add", "task_toggle", "task_delete" -> "dash.web.tasks.write";
            case "operations_plan_create", "operations_plan_prepare", "operations_plan_status",
                    "operations_incident_create", "operations_incident_close",
                    "operations_handover_create", "operations_handover_ack",
                    "operations_drift_baseline", "operations_restore_drill",
                    "operations_alert_ack", "operations_capacity_sample",
                    "operations_recipe_create", "operations_recipe_toggle" -> "dash.web.settings.write";
            case "invite_generate" -> "dash.web.users.manage";
            case "role_permissions_save", "role_create", "role_set_value", "role_delete" -> "dash.web.users.manage";
            case "user_set_role", "user_make_main_admin", "user_delete" -> "dash.web.users.manage";
            case "registration_approve", "registration_deny" -> "dash.web.users.manage";
            case "bridge_user_allow", "bridge_user_deny" -> "dash.web.users.manage";
            case "owner_2fa_regen" -> "dash.web.settings.write";
            case "add_note", "delete_note" -> "dash.web.players.moderate";
            default -> null;
        };
    }

    private boolean shouldApplyGuardrails(String action) {
        return action != null && !action.isBlank()
                && !action.startsWith("intel_guardrail_")
                && !action.startsWith("intel_approval_");
    }

    private boolean sendGuardrailChallengeIfNeeded(HttpExchange exchange,
                                                    IntelligenceManager.GuardDecision decision,
                                                    Map<String, String> params) throws IOException {
        String reason = params.get("reason");
        if (reason != null && !reason.isBlank()) {
            return false;
        }
        String message = decision.message() == null ? "" : decision.message();
        if (!message.toLowerCase(Locale.ROOT).contains("reason is required")) {
            return false;
        }

        exchange.getResponseHeaders().set("X-Dash-Reason-Required", "1");
        String requestedWith = exchange.getRequestHeaders().getFirst("X-Requested-With");
        if (requestedWith != null && requestedWith.toLowerCase(Locale.ROOT).endsWith("-mutation")) {
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            sendResponseWithStatus(exchange, 428, message);
        } else {
            sendResponseWithStatus(exchange, 428, GuardrailChallengePage.render(message, params));
        }
        return true;
    }

    private void handleIntelligenceAction(HttpExchange t, Map<String, String> params, String actor, String clientIp)
            throws IOException {
        String action = params.getOrDefault("action", "");
        String message;
        try {
            switch (action) {
                case "intel_shadow_start" -> message = intelligenceManager.startShadowBootLab(
                        command -> CompletableFuture.runAsync(command), actor);
                case "intel_state_capture" -> message = intelligenceManager.captureState(params.get("label"), actor);
                case "intel_state_restore" -> message = intelligenceManager.restoreState(params.get("snapshot_id"), actor);
                case "intel_performance_sample" -> {
                    IntelligencePage.RuntimeMetrics live = intelligenceRuntimeMetrics();
                    message = intelligenceManager.recordPerformance(live.tps(), live.mspt(), live.memoryMb(),
                            live.players(), params.get("label"));
                }
                case "intel_safe_quarantine" -> message = intelligenceManager.quarantineArtifact(params.get("artifact"), actor);
                case "intel_safe_restore" -> message = intelligenceManager.restoreQuarantinedArtifact(params.get("quarantine_id"));
                case "intel_backup_restore" -> message = intelligenceManager.restoreBackupEntry(
                        params.get("backup"), params.get("entry"), actor);
                case "intel_guardrail_save" -> message = intelligenceManager.saveGuardrail(
                        params.get("action_pattern"),
                        parseInt(params.get("max_players"), -1),
                        isChecked(params, "require_backup"),
                        parseInt(params.get("quiet_start"), -1),
                        parseInt(params.get("quiet_end"), -1),
                        isChecked(params, "require_reason"),
                        isChecked(params, "dual_control"));
                case "intel_guardrail_delete" -> message = intelligenceManager.deleteGuardrail(params.get("guardrail_id"));
                case "intel_support_create" -> message = intelligenceManager.createSupportCase(
                        params.get("type"), params.get("player"), params.get("subject"), params.get("message"), actor);
                case "intel_support_update" -> message = intelligenceManager.updateSupportCase(
                        params.get("case_id"), params.get("status"), params.get("owner"));
                case "intel_support_reply" -> message = intelligenceManager.addSupportReply(
                        params.get("case_id"), actor, params.get("message"), isChecked(params, "public_reply"));
                case "intel_jit_grant" -> message = intelligenceManager.grantTemporaryAccess(
                        params.get("username"), params.get("permission"), parseInt(params.get("minutes"), 0), actor);
                case "intel_jit_revoke" -> message = intelligenceManager.revokeTemporaryAccess(params.get("grant_id"), actor);
                case "intel_approval_decide" -> {
                    String decision = params.getOrDefault("decision", "");
                    message = Set.of("approve", "reject").contains(decision)
                            ? intelligenceManager.decideApproval(params.get("approval_id"), actor,
                                    "approve".equals(decision))
                            : "Unsupported approval decision.";
                }
                case "intel_retention_save" -> message = intelligenceManager.saveRetentionPolicy(
                        parseInt(params.get("log_days"), 0),
                        parseInt(params.get("backup_days"), 0),
                        parseInt(params.get("crash_days"), 0),
                        parseInt(params.get("keep_min_backups"), 0), actor);
                case "intel_retention_apply" -> message = intelligenceManager.applyRetention(actor);
                case "intel_config_update" -> message = intelligenceManager.updateConfigScalar(
                        params.get("path"), params.get("key"), params.get("value"), actor);
                case "intel_service_sample" -> {
                    IntelligencePage.RuntimeMetrics live = intelligenceRuntimeMetrics();
                    message = intelligenceManager.recordServiceSample(live.online(), live.tps(), live.mspt());
                }
                case "intel_war_create" -> message = intelligenceManager.createWarRoom(
                        params.get("title"), params.get("severity"), params.get("summary"), actor);
                case "intel_war_update" -> message = intelligenceManager.addWarRoomUpdate(
                        params.get("room_id"), actor, params.get("message"), params.get("kind"),
                        isChecked(params, "publish"));
                case "intel_war_close" -> message = intelligenceManager.closeWarRoom(
                        params.get("room_id"), actor, params.get("resolution"));
                case "intel_status_update" -> message = intelligenceManager.updateStatusComponent(
                        params.get("component"), params.get("status"), params.get("message"), actor);
                default -> message = "Unsupported intelligence action.";
            }
        } catch (Exception ex) {
            FabricDash.LOGGER.warn("Intelligence action failed safely: {}", ex.getMessage());
            message = "Intelligence action failed safely: "
                    + (ex.getMessage() == null ? "internal error" : ex.getMessage());
        }
        WebActionLogger.log("INTELLIGENCE", "action=" + action + " actor=" + actor + " ip=" + clientIp);
        redirect(t, intelligenceRedirect(action, message));
    }

    private String intelligenceRedirect(String action, String message) {
        return "/intelligence?tab=" + intelligenceTabForAction(action) + "&msg="
                + encodeForQuery(message == null ? "" : message);
    }

    private String intelligenceTabForAction(String action) {
        if (action == null) return "lab";
        if (action.startsWith("intel_state_") || action.startsWith("intel_performance_")) return "change";
        if (action.startsWith("intel_support_")) return "players";
        if (action.startsWith("intel_guardrail_") || action.startsWith("intel_jit_")
                || action.startsWith("intel_approval_") || action.startsWith("intel_retention_")
                || action.startsWith("intel_config_")) return "policy";
        if (action.startsWith("intel_service_")) return "reliability";
        if (action.startsWith("intel_war_") || action.startsWith("intel_status_")) return "response";
        return "lab";
    }

    private void handleOperationsAction(HttpExchange t, Map<String, String> params, String actor, String clientIp)
            throws IOException {
        String action = params.getOrDefault("action", "");
        String tab = "overview";
        String message;
        try {
            switch (action) {
                case "operations_plan_create" -> {
                    tab = "planner";
                    message = operationsManager.createPlan(params.get("title"), params.get("change_type"),
                            params.get("scheduled_at"), params.get("details"), actor);
                }
                case "operations_plan_prepare" -> {
                    tab = "planner";
                    dash.data.BackupManager backupManager = FabricDash.getBackupManager();
                    if (backupManager == null) {
                        message = "Backup service is unavailable; preflight was not started.";
                    } else {
                        dash.data.BackupManager.BackupResult result = backupManager.createBackupAsync()
                                .get(15, TimeUnit.MINUTES);
                        message = result.success()
                                ? operationsManager.preparePlan(params.get("plan_id"), result.fileName())
                                : "Verified backup failed: " + (result.error() == null ? "unknown error" : result.error());
                    }
                }
                case "operations_plan_status" -> {
                    tab = "planner";
                    message = operationsManager.updatePlanStatus(params.get("plan_id"), params.get("status"));
                }
                case "operations_incident_create" -> {
                    tab = "incidents";
                    message = operationsManager.createIncident(params.get("title"), params.get("severity"),
                            params.get("summary"), actor);
                }
                case "operations_incident_close" -> {
                    tab = "incidents";
                    message = operationsManager.closeIncident(params.get("incident_id"), params.get("resolution"), actor);
                }
                case "operations_handover_create" ->
                        message = operationsManager.createHandover(params.get("summary"), actor);
                case "operations_handover_ack" ->
                        message = operationsManager.acknowledgeHandover(params.get("handover_id"), actor);
                case "operations_drift_baseline" -> {
                    tab = "recovery";
                    message = operationsManager.saveDriftBaseline();
                }
                case "operations_restore_drill" -> {
                    tab = "recovery";
                    message = operationsManager.runRestoreDrill(params.get("backup"));
                }
                case "operations_alert_ack" -> message = operationsManager.acknowledgeAlert(params.get("signature"));
                case "operations_capacity_sample" -> {
                    tab = "recovery";
                    message = operationsManager.recordCapacity(true);
                }
                case "operations_recipe_create" -> {
                    tab = "automation";
                    message = activateOperationsRecipe(params);
                }
                case "operations_recipe_toggle" -> {
                    tab = "automation";
                    message = toggleOperationsRecipe(params);
                }
                default -> message = "Unsupported operations action.";
            }
        } catch (Exception ex) {
            FabricDash.LOGGER.warn("Operations action failed: {}", ex.getMessage());
            message = "Operation failed safely: " + (ex.getMessage() == null ? "internal error" : ex.getMessage());
        }
        WebActionLogger.log("OPERATIONS", "action=" + action + " actor=" + actor + " ip=" + clientIp);
        redirect(t, "/operations?tab=" + tab + "&msg=" + encodeForQuery(message));
    }

    private String activateOperationsRecipe(Map<String, String> params) {
        String recipe = params.getOrDefault("recipe", "").trim();
        int interval;
        try {
            interval = Integer.parseInt(params.getOrDefault("interval", "0"));
        } catch (NumberFormatException ex) {
            return "Interval must be a number between 1 and 10080 minutes.";
        }
        if (interval < 1 || interval > 10080) return "Interval must be between 1 and 10080 minutes.";
        String payload = params.getOrDefault("payload", "").trim();
        int taskId = -1;
        if ("daily_backup".equals(recipe)) {
            if (interval % 60 != 0) return "Backup recipes require a whole-hour interval.";
            dash.data.BackupManager manager = FabricDash.getBackupManager();
            if (manager == null) return "Backup service is unavailable.";
            manager.startSchedule(interval / 60);
        } else {
            dash.data.ScheduledTaskManager manager = FabricDash.getScheduledTaskManager();
            if (manager == null) return "Scheduled task service is unavailable.";
            String taskType = dash.data.ScheduledTaskManager.TYPE_COMMAND;
            String taskPayload;
            switch (recipe) {
                case "nightly_restart" -> taskPayload = "restart";
                case "hourly_save" -> taskPayload = "save-all";
                case "maintenance_notice" -> {
                    taskType = dash.data.ScheduledTaskManager.TYPE_BROADCAST;
                    taskPayload = payload.isBlank() ? "Scheduled maintenance begins soon." : payload;
                }
                default -> { return "Unsupported automation recipe."; }
            }
            taskId = manager.addTask(taskType, interval, taskPayload, true);
            if (taskId < 1) return "Scheduled task could not be created.";
            payload = taskPayload;
        }
        return operationsManager.recordAutomation(recipe, interval, payload, taskId, true);
    }

    private String toggleOperationsRecipe(Map<String, String> params) {
        OperationsManager.Automation automation = operationsManager.findAutomation(params.get("automation_id"))
                .orElse(null);
        if (automation == null) return "Automation recipe not found.";
        boolean enabled = Boolean.parseBoolean(params.getOrDefault("enabled", "false"));
        if ("daily_backup".equals(automation.recipe())) {
            dash.data.BackupManager manager = FabricDash.getBackupManager();
            if (manager == null) return "Backup service is unavailable.";
            if (enabled) manager.startSchedule(Math.max(1, automation.intervalMinutes() / 60));
            else manager.stopSchedule();
        } else {
            dash.data.ScheduledTaskManager manager = FabricDash.getScheduledTaskManager();
            if (manager == null || automation.taskId() < 1) return "Scheduled task is unavailable.";
            manager.setEnabled(automation.taskId(), enabled);
        }
        return operationsManager.setAutomationEnabled(automation.id(), enabled);
    }

    private boolean usesSeparateActionAuthorization(String action) {
        return "staff_ticket_create".equals(action);
    }

    private void saveNotificationSettings(Map<String, String> params) {
        FabricConfig config = FabricDash.getConfig();
        if ("ingame".equals(params.get("settings_scope"))) {
            config.set("notifications.ingame.enabled", params.containsKey("notifications.ingame.enabled"));
            config.set("notifications.ingame.tickets", params.containsKey("notifications.ingame.tickets"));
            config.set("notifications.ingame.security", params.containsKey("notifications.ingame.security"));
            config.set("notifications.ingame.users",
                    splitCsv(params.getOrDefault("notifications.ingame.users", "")));
            config.save();
            return;
        }
        List<String> booleans = List.of(
                "notifications.panel.enabled",
                "notifications.discord.enabled",
                "notifications.email.enabled",
                "notifications.mobile.enabled",
                "notifications.events.startup_log",
                "notifications.events.restart",
                "notifications.events.plugin_updates",
                "cloud-backups.enabled",
                "cloud-backups.encrypt",
                "cloud-backups.pre_restart",
                "cloud-backups.pre_update",
                "backups.pre_restart",
                "backups.pre_update");
        for (String key : booleans) {
            config.set(key, params.containsKey(key));
        }
        List<String> strings = List.of(
                "discord.webhook_url",
                "notifications.email.to",
                "notifications.email.smtp_host",
                "notifications.mobile.webhook_url",
                "notifications.messages.restart",
                "notifications.messages.backup",
                "notifications.messages.crash",
                "cloud-backups.provider",
                "cloud-backups.bucket",
                "cloud-backups.path",
                "cloud-backups.retention_days");
        for (String key : strings) {
            config.set(key, params.getOrDefault(key, ""));
        }
        config.save();
    }

    private String requiredPermissionForGamerule(String action) {
        return "dash.web.settings.write";
    }

    private AiHttpHandler.UserContext aiUserContext(HttpExchange exchange) {
        if (!isAuthenticated(exchange)) return null;
        String username = getSessionUser(exchange);
        WebAuth.UserInfo info = auth.getUsers().get(username);
        return new AiHttpHandler.UserContext(username, info == null ? "USER" : info.role(),
                auth.isMainAdmin(username), effectiveUiPermissions(username));
    }

    private String executeAiReadTool(String tool, JsonObject args, String username) {
        if (!userHasWebPermission(username, aiAgentManager.requiredPermission(tool))) return "Permission denied.";
        try {
            if ("get_server_overview".equals(tool)) {
                StatsCollector.StatsSample sample = FabricDash.getStatsCollector() == null ? null : FabricDash.getStatsCollector().getLatest();
                return "players=" + server.getPlayerList().getPlayers().size() + ", tps="
                        + (sample == null ? "unknown" : sample.tps) + ", mspt=" + (sample == null ? "unknown" : sample.mspt);
            }
            if ("get_recent_logs".equals(tool)) return aiRecentLog(args.has("lines") ? args.get("lines").getAsInt() : 120);
            if ("get_players".equals(tool)) return aiServerCall(() -> server.getPlayerList().getPlayers().stream()
                    .map(player -> player.getGameProfile().name()).limit(100).reduce((a, b) -> a + "\n" + b).orElse("No players online."));
            if ("get_plugins".equals(tool)) {
                Path dir = FabricDash.getServerRootDirectory().toPath().resolve("mods");
                if (!Files.isDirectory(dir)) return "No mods directory.";
                try (var stream = Files.list(dir)) {
                    return stream.filter(Files::isRegularFile).map(path -> path.getFileName().toString()).sorted()
                            .limit(250).reduce((a, b) -> a + "\n" + b).orElse("No mods found.");
                }
            }
            if ("get_backups".equals(tool)) return FabricDash.getBackupManager().listBackups().stream().limit(30)
                    .map(value -> value.name() + " bytes=" + value.size()).reduce((a, b) -> a + "\n" + b).orElse("No backups.");
            if ("read_config".equals(tool)) {
                var document = intelligenceManager.inspectConfig(aiText(args, "path"));
                return "path=" + document.path() + " format=" + document.format() + "\n"
                        + document.fields().stream().limit(150).map(field -> field.key() + "=" + field.value())
                                .reduce((a, b) -> a + "\n" + b).orElse("No scalar fields.");
            }
            if ("get_guardian_summary".equals(tool)) {
                int[] counts = FabricDash.getGuardianDataManager().countLogsSince(System.currentTimeMillis() / 1000L - 86400L);
                return "blocks=" + counts[0] + ", containers=" + counts[1] + ", open_cases="
                        + FabricDash.getGuardianDataManager().listCases("OPEN", null, 50).size();
            }
            if ("get_intelligence_summary".equals(tool)) {
                var root = intelligenceManager.rootCauseReport(); var level = intelligenceManager.serviceLevel();
                return "severity=" + root.severity() + ", summary=" + root.summary() + ", service=" + level.status();
            }
            return "Unsupported read tool.";
        } catch (Exception ex) {
            return "Tool failed safely: " + dash.ai.AiRedactor.redact(ex.getMessage(), 240);
        }
    }

    private AiHttpHandler.Execution executeAiMutation(AiAgentManager.Proposal proposal, String username, String reason) {
        JsonObject args = proposal.arguments();
        try {
            boolean restorePoint = Set.of("restart_server", "quarantine_plugin", "restore_plugin", "apply_config_change",
                    "guardian_rollback", "guardian_restore").contains(proposal.tool());
            if (restorePoint && !FabricDash.getBackupManager().createBackupAsync().get(15, TimeUnit.MINUTES).success())
                return new AiHttpHandler.Execution(false, "Restore point creation failed; action was not run.");
            switch (proposal.tool()) {
                case "create_backup" -> {
                    var result = FabricDash.getBackupManager().createBackupAsync().get(15, TimeUnit.MINUTES);
                    return new AiHttpHandler.Execution(result.success(), result.success() ? "Backup created: " + result.fileName() : "Backup failed safely.");
                }
                case "restart_server" -> {
                    FabricDash.getInstance().getScheduler().schedule(() -> server.halt(false), 2, TimeUnit.SECONDS);
                    return new AiHttpHandler.Execution(true, "Restart scheduled with a restore point.");
                }
                case "kick_player", "ban_player", "unban_player", "whitelist_player" -> {
                    return aiPlayerMutation(proposal.tool(), args, username, reason);
                }
                case "quarantine_plugin" -> { return aiResult(intelligenceManager.quarantineArtifact(aiText(args, "artifact"), username)); }
                case "restore_plugin" -> { return aiResult(intelligenceManager.restoreQuarantinedArtifact(aiText(args, "quarantine_id"))); }
                case "apply_config_change" -> { return aiResult(intelligenceManager.updateConfigScalar(aiText(args, "path"), aiText(args, "key"), aiText(args, "value"), username)); }
                case "guardian_rollback", "guardian_restore" -> { return aiGuardianMutation(proposal.tool(), args); }
                default -> { return new AiHttpHandler.Execution(false, "Unsupported action."); }
            }
        } catch (Exception ex) {
            return new AiHttpHandler.Execution(false, "Action failed safely: " + dash.ai.AiRedactor.redact(ex.getMessage(), 240));
        }
    }

    private AiHttpHandler.Execution aiPlayerMutation(String tool, JsonObject args, String username, String reason) throws Exception {
        String name = aiText(args, "player");
        if (name.isBlank() || name.length() > 32) return new AiHttpHandler.Execution(false, "A valid player is required.");
        return aiServerCall(() -> {
            ServerPlayer online = server.getPlayerList().getPlayerByName(name);
            NameAndId profile = online == null ? NameAndId.createOffline(name) : online.nameAndId();
            if ("kick_player".equals(tool)) {
                if (online == null) return new AiHttpHandler.Execution(false, "Player is not online.");
                online.connection.disconnect(Component.literal(reason));
            } else if ("ban_player".equals(tool)) {
                server.getPlayerList().getBans().add(new UserBanListEntry(profile, null, username, null, reason));
                if (online != null) online.connection.disconnect(Component.literal("Banned: " + reason));
            } else if ("unban_player".equals(tool)) server.getPlayerList().getBans().remove(profile);
            else if ("remove".equalsIgnoreCase(aiText(args, "operation"))) server.getPlayerList().getWhiteList().remove(profile);
            else server.getPlayerList().getWhiteList().add(new UserWhiteListEntry(profile));
            return new AiHttpHandler.Execution(true, "Approved player action completed.");
        });
    }

    private AiHttpHandler.Execution aiGuardianMutation(String tool, JsonObject args) {
        long hours = args.has("hours") ? Math.max(1, Math.min(720, args.get("hours").getAsLong())) : 24L;
        GuardianActionService service = new GuardianActionService();
        GuardianActionService.ActionRequest preview = new GuardianActionService.ActionRequest(aiText(args, "player"), "",
                System.currentTimeMillis() / 1000L - hours * 3600L, null, null, null, 0, "", List.of(), List.of(), 1000, true, true, true);
        GuardianActionService.ActionResult checked = "guardian_restore".equals(tool)
                ? service.restore(FabricDash.getGuardianDataManager(), preview) : service.rollback(FabricDash.getGuardianDataManager(), preview);
        if (!checked.success()) return new AiHttpHandler.Execution(false, checked.message());
        GuardianActionService.ActionRequest apply = new GuardianActionService.ActionRequest(preview.player(), preview.world(),
                preview.fromTime(), preview.x(), preview.y(), preview.z(), preview.radius(), preview.action(),
                preview.include(), preview.exclude(), preview.limit(), false, true, true);
        GuardianActionService.ActionResult result = "guardian_restore".equals(tool)
                ? service.restore(FabricDash.getGuardianDataManager(), apply) : service.rollback(FabricDash.getGuardianDataManager(), apply);
        return new AiHttpHandler.Execution(result.success(), result.message());
    }

    private String aiRecentLog(int requested) throws IOException {
        Path file = FabricDash.getServerRootDirectory().toPath().resolve("logs").resolve("latest.log");
        if (!Files.isRegularFile(file)) return "No current server log.";
        long size = Files.size(file), start = Math.max(0, size - 1024 * 1024L);
        byte[] bytes;
        try (RandomAccessFile input = new RandomAccessFile(file.toFile(), "r")) {
            input.seek(start); bytes = new byte[(int) (size - start)]; input.readFully(bytes);
        }
        String[] lines = new String(bytes, StandardCharsets.UTF_8).split("\\R");
        int count = Math.max(1, Math.min(300, requested));
        return dash.ai.AiRedactor.redact(String.join("\n", Arrays.copyOfRange(lines, Math.max(0, lines.length - count), lines.length)), 24_000);
    }

    private <T> T aiServerCall(java.util.concurrent.Callable<T> callable) throws Exception {
        CompletableFuture<T> future = new CompletableFuture<>();
        server.execute(() -> {
            try { future.complete(callable.call()); } catch (Exception ex) { future.completeExceptionally(ex); }
        });
        return future.get(5, TimeUnit.SECONDS);
    }

    private String aiText(JsonObject args, String key) {
        try { return args.has(key) && !args.get(key).isJsonNull() ? args.get(key).getAsString().trim() : ""; }
        catch (Exception ignored) { return ""; }
    }

    private AiHttpHandler.Execution aiResult(String message) {
        String lower = message == null ? "" : message.toLowerCase(Locale.ROOT);
        boolean success = !(lower.contains("failed") || lower.contains("could not") || lower.contains("unavailable")
                || lower.contains("invalid") || lower.contains("not found") || lower.contains("read-only"));
        return new AiHttpHandler.Execution(success, message == null ? "Action completed without a result." : message);
    }


    static String getQueryParam(String query, String key) {
        if (query == null || query.isBlank()) return null;
        for (String part : query.split("&")) {
            if (part.startsWith(key + "=")) {
                try {
                    return URLDecoder.decode(part.substring(key.length() + 1), StandardCharsets.UTF_8);
                } catch (Exception e) {
                    return part.substring(key.length() + 1);
                }
            }
        }
        return null;
    }

    private List<String> splitCsv(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isBlank()).toList();
    }

    private String humanizeRolePermissionError(String error) {
        if (error == null) return "Unknown error";
        if (error.contains("not_found")) return "Role not found";
        if (error.contains("insufficient_rank")) return "You cannot modify a role with equal or higher rank";
        return error;
    }

    private String humanizeRoleCreationError(String error) {
        if (error == null) return "Unknown error";
        if (error.contains("exists")) return "A role with that name already exists";
        if (error.contains("insufficient_rank")) return "You cannot create a role with equal or higher rank";
        return error;
    }

    static String encodeForQuery(String value) {
        if (value == null) return "";
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private boolean configuredHttps() {
        FabricConfig config = FabricDash.getConfig();
        return config.getBoolean("ssl-enabled", config.getBoolean("ssl.enabled", false));
    }

    private String configuredPublicPanelUrl() {
        return FabricDash.getConfig().getString("panel-url", "");
    }

    private boolean ensureSameOriginMutation(HttpExchange t, boolean jsonResponse) throws IOException {
        if (HttpSecurity.isSameOriginMutation(t, configuredPublicPanelUrl())) {
            return true;
        }
        WebActionLogger.log("CSRF_BLOCKED", "ip=" + getClientIp(t) + " path=" + t.getRequestURI().getPath());
        t.getResponseHeaders().set("Content-Type", jsonResponse ? "application/json" : "text/html; charset=utf-8");
        sendResponseWithStatus(t, 403,
                jsonResponse ? "{\"success\":false,\"error\":\"Cross-site request blocked\"}"
                        : "<html><body><h1>403 Forbidden</h1><p>Cross-site request blocked.</p></body></html>");
        return false;
    }

    private byte[] readRequestBodyOrReject(HttpExchange t, long maxBytes) throws IOException {
        try {
            return HttpSecurity.readRequestBody(t, maxBytes);
        } catch (HttpSecurity.RequestBodyTooLargeException ex) {
            sendResponseWithStatus(t, 413, "Request body too large.");
            return null;
        }
    }

    private byte[] readRequestBodyStrict(HttpExchange t, long maxBytes) throws IOException {
        try {
            return HttpSecurity.readRequestBody(t, maxBytes);
        } catch (HttpSecurity.RequestBodyTooLargeException ex) {
            sendResponseWithStatus(t, 413, "Request body too large.");
            throw new IOException("Request rejected after exceeding the body limit.");
        }
    }

    private void redirect(HttpExchange t, String location) throws IOException {
        HttpSecurity.applyResponseHeaders(t, configuredHttps());
        t.getResponseHeaders().set("Location", location);
        t.sendResponseHeaders(302, -1);
        t.close();
    }

    void sendResponse(HttpExchange t, String response) throws IOException {
        HttpSecurity.applyResponseHeaders(t, configuredHttps());
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        if (!t.getResponseHeaders().containsKey("Content-Type")) {
            t.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        }
        try {
            t.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = t.getResponseBody()) {
                os.write(bytes);
            }
        } catch (IOException e) {
            // stream closed — browser disconnected
        }
    }

    private void serveBundledStyles(HttpExchange t) throws IOException {
        boolean head = "HEAD".equalsIgnoreCase(t.getRequestMethod());
        if (!head && !"GET".equalsIgnoreCase(t.getRequestMethod())) {
            t.getResponseHeaders().set("Allow", "GET, HEAD");
            sendResponseWithStatus(t, 405, "Method not allowed");
            return;
        }
        byte[] css = BundledStyles.css();
        if (css.length == 0) {
            sendResponseWithStatus(t, 500, "Dashboard stylesheet unavailable");
            return;
        }
        HttpSecurity.applyResponseHeaders(t, configuredHttps());
        t.getResponseHeaders().set("Content-Type", "text/css; charset=utf-8");
        t.getResponseHeaders().set("Cache-Control", "public, max-age=31536000, immutable");
        if (head) {
            t.getResponseHeaders().set("Content-Length", Integer.toString(css.length));
            t.sendResponseHeaders(200, -1);
            t.close();
            return;
        }
        t.sendResponseHeaders(200, css.length);
        try (OutputStream output = t.getResponseBody()) {
            output.write(css);
        }
    }

    private void sendResponseWithStatus(HttpExchange t, int status, String response) throws IOException {
        HttpSecurity.applyResponseHeaders(t, configuredHttps());
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        if (!t.getResponseHeaders().containsKey("Content-Type")) {
            t.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        }
        try {
            t.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = t.getResponseBody()) {
                os.write(bytes);
            }
        } catch (IOException e) {
            // stream closed
        }
    }

    private class PluginBrowserSearchHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            t.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            if (!"GET".equalsIgnoreCase(t.getRequestMethod())) {
                sendResponseWithStatus(t, 405, "{\"success\":false,\"error\":\"method_not_allowed\"}");
                return;
            }
            if (!isAuthenticated(t)) {
                sendResponseWithStatus(t, 401, "{\"success\":false,\"error\":\"unauthorized\"}");
                return;
            }
            String query = getQueryParam(t.getRequestURI().getQuery(), "query");
            String sort = getQueryParam(t.getRequestURI().getQuery(), "sort");
            sendResponse(t, PluginBrowserPage.searchJson(query, sort,
                    "[[\"project_type:mod\"],[\"categories:neoforge\"]]",
                    "[\"neoforge\"]"));
        }
    }

    private class UiLanguageHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if (!"POST".equalsIgnoreCase(t.getRequestMethod())) {
                t.sendResponseHeaders(405, -1);
                t.close();
                return;
            }
            if (!isAuthenticated(t)) {
                t.getResponseHeaders().add("Content-Type", "application/json");
                sendResponseWithStatus(t, 401, "{\"success\":false,\"error\":\"unauthorized\"}");
                return;
            }
            String user = getSessionUser(t);
            if (user == null || user.isBlank()) {
                t.getResponseHeaders().add("Content-Type", "application/json");
                sendResponseWithStatus(t, 401, "{\"success\":false,\"error\":\"no_session\"}");
                return;
            }
            String body = new String(readRequestBodyStrict(t, 1024L * 1024L), StandardCharsets.UTF_8);
            Map<String, String> params = parseFormData(body);
            String code = dash.web.I18n.normalize(params.get("language"));
            boolean ok = auth.setUserLanguage(user, code);
            t.getResponseHeaders().add("Content-Type", "application/json");
            if (ok) {
                WebActionLogger.log("UI_LANGUAGE_SET", "user=" + user + " lang=" + code);
                sendResponse(t, "{\"success\":true,\"language\":\"" + code + "\"}");
            } else {
                sendResponseWithStatus(t, 500, "{\"success\":false,\"error\":\"save_failed\"}");
            }
        }
    }

    private Map<String, String> parseFormData(String body) {
        Map<String, String> params = new LinkedHashMap<>();
        if (body == null || body.isBlank()) return params;
        for (String pair : body.split("&")) {
            String[] kv = pair.split("=", 2);
            String key = URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
            String value = kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "";
            params.put(key, value);
        }
        return params;
    }

    private String extractBridgeCommand(String body, String contentType) {
        if (contentType != null && contentType.contains("application/json")) {
            return extractJsonStringField(body, "command");
        }
        Map<String, String> form = parseFormData(body);
        return form.get("command");
    }

    private String formatUptime() {
        long uptimeMs = ManagementFactory.getRuntimeMXBean().getUptime();
        long seconds = uptimeMs / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        long days = hours / 24;

        if (days > 0) return days + "d " + (hours % 24) + "h " + (minutes % 60) + "m";
        if (hours > 0) return hours + "h " + (minutes % 60) + "m";
        return minutes + "m " + (seconds % 60) + "s";
    }

    private int parseInt(String raw, int fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private long parseLong(String raw, long fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private Integer parseOptionalInt(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private List<String> csvList(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        List<String> values = new ArrayList<>();
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isBlank()) values.add(trimmed);
        }
        return values;
    }

    private GuardianActionService.ActionRequest guardianActionRequest(Map<String, String> params) {
        int hours = Math.max(1, Math.min(parseInt(params.get("hours"), 24), 2160));
        String scope = params.getOrDefault("scope", "both");
        return new GuardianActionService.ActionRequest(
                blankToNull(params.get("player")),
                blankToNull(params.get("world")),
                (System.currentTimeMillis() / 1000L) - (hours * 3600L),
                parseOptionalInt(params.get("x")),
                parseOptionalInt(params.get("y")),
                parseOptionalInt(params.get("z")),
                parseOptionalInt(params.get("radius")),
                blankToNull(params.get("action")),
                csvList(params.get("include")),
                csvList(params.get("exclude")),
                Math.max(1, Math.min(parseInt(params.get("limit"), 1000), 10000)),
                "true".equalsIgnoreCase(params.get("preview")) || "on".equalsIgnoreCase(params.get("preview")),
                !"containers".equalsIgnoreCase(scope),
                !"blocks".equalsIgnoreCase(scope));
    }

    private GuardianDataManager.ActionPreviewDiff guardianPreviewDiff(GuardianDataManager guardian, String query) {
        int hours = Math.max(1, Math.min(parseInt(getQueryParam(query, "hours"), 24), 2160));
        String scope = getQueryParam(query, "scope");
        return guardian.buildActionPreviewDiff(
                getQueryParam(query, "player"),
                getQueryParam(query, "world"),
                (System.currentTimeMillis() / 1000L) - (hours * 3600L),
                getQueryParam(query, "action"),
                parseOptionalInt(getQueryParam(query, "x")),
                parseOptionalInt(getQueryParam(query, "y")),
                parseOptionalInt(getQueryParam(query, "z")),
                parseOptionalInt(getQueryParam(query, "radius")),
                csvList(getQueryParam(query, "include")),
                csvList(getQueryParam(query, "exclude")),
                Math.max(1, Math.min(parseInt(getQueryParam(query, "limit"), 1000), 10000)),
                !"containers".equalsIgnoreCase(scope),
                !"blocks".equalsIgnoreCase(scope));
    }

    private String guardianLookupJson(GuardianDataManager guardian, String query, boolean near) {
        int radius = Math.max(0, Math.min(parseInt(getQueryParam(query, "radius"), near ? 5 : 0), 10000));
        Integer x = parseOptionalInt(getQueryParam(query, "x"));
        Integer y = parseOptionalInt(getQueryParam(query, "y"));
        Integer z = parseOptionalInt(getQueryParam(query, "z"));
        String action = getQueryParam(query, "action");
        Long since = sinceFromQuery(query);
        int limit = Math.max(1, Math.min(parseInt(getQueryParam(query, "limit"), 100), 10000));
        List<String> include = csvList(getQueryParam(query, "include"));
        List<String> exclude = csvList(getQueryParam(query, "exclude"));
        List<GuardianDataManager.BlockLogEntry> blocks = guardian.searchBlockLogsAdvanced(
                getQueryParam(query, "player"), getQueryParam(query, "world"), since, null,
                GuardianDataManager.parseBlockAction(action), x, y, z, radius, include, exclude, 1, limit, false);
        List<GuardianDataManager.ContainerLogEntry> containers = guardian.searchContainerLogsAdvanced(
                getQueryParam(query, "player"), getQueryParam(query, "world"), since, null,
                GuardianDataManager.parseContainerAction(action), x, y, z, radius, include, exclude, 1, limit, false);
        GuardianDataManager.QueryCount count = guardian.countAdvanced(getQueryParam(query, "player"),
                getQueryParam(query, "world"), since, null, action, x, y, z, radius, include, exclude);
        return "{\"success\":true,\"count\":{\"blocks\":" + count.blocks() + ",\"containers\":"
                + count.containers() + "},\"blocks\":" + guardianBlockLogsJson(blocks)
                + ",\"containers\":" + guardianContainerLogsJson(containers) + "}";
    }

    private String guardianHasActionJson(GuardianDataManager guardian, String query, boolean placed) {
        Integer x = parseOptionalInt(getQueryParam(query, "x"));
        Integer y = parseOptionalInt(getQueryParam(query, "y"));
        Integer z = parseOptionalInt(getQueryParam(query, "z"));
        String world = getQueryParam(query, "world");
        if (x == null || y == null || z == null || world == null || world.isBlank()) {
            return "{\"success\":false,\"error\":\"world_x_y_z_required\"}";
        }
        long since = sinceFromQuery(query, 24);
        boolean found = guardian.hasBlockAction(getQueryParam(query, "player"), world, x, y, z,
                placed ? GuardianDataManager.ACTION_PLACE : GuardianDataManager.ACTION_BREAK, since,
                Math.max(0, parseInt(getQueryParam(query, "offset"), 0)));
        return "{\"success\":true,\"found\":" + found + "}";
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private boolean isChecked(Map<String, String> params, String name) {
        if (params == null || name == null) {
            return false;
        }
        String value = params.get(name);
        return value != null && ("on".equalsIgnoreCase(value)
                || "true".equalsIgnoreCase(value)
                || "1".equals(value)
                || "yes".equalsIgnoreCase(value));
    }

    private String actorLabel(HttpExchange t) {
        String user = getSessionUser(t);
        return user == null || user.isBlank() ? "bridge" : user;
    }

    private Long sinceFromQuery(String query) {
        String hoursRaw = getQueryParam(query, "hours");
        if (hoursRaw == null || hoursRaw.isBlank()) return null;
        return sinceFromQuery(query, parseInt(hoursRaw, 24));
    }

    private long sinceFromQuery(String query, int fallbackHours) {
        String hoursRaw = getQueryParam(query, "hours");
        int hours = Math.max(1, Math.min(parseInt(hoursRaw, fallbackHours), 2160));
        return (System.currentTimeMillis() / 1000L) - (hours * 3600L);
    }

    private String guardianStatsJson(GuardianDataManager.ServerStats stats) {
        StringBuilder top = new StringBuilder("[");
        for (int i = 0; i < stats.topPlayers.size(); i++) {
            GuardianDataManager.PlayerActivity p = stats.topPlayers.get(i);
            if (i > 0) top.append(',');
            top.append("{\"player\":\"").append(jsonEscape(p.playerName())).append("\",")
                    .append("\"totalActions\":").append(p.totalActions()).append(',')
                    .append("\"blocksBroken\":").append(p.blocksBroken()).append(',')
                    .append("\"blocksPlaced\":").append(p.blocksPlaced()).append('}');
        }
        top.append(']');
        return "{"
                + "\"totalBlocksBroken\":" + stats.totalBlocksBroken + ','
                + "\"totalBlocksPlaced\":" + stats.totalBlocksPlaced + ','
                + "\"totalItemsRemoved\":" + stats.totalItemsRemoved + ','
                + "\"totalItemsAdded\":" + stats.totalItemsAdded + ','
                + "\"uniquePlayers\":" + stats.uniquePlayers + ','
                + "\"topPlayers\":" + top
                + "}";
    }

    private String guardianStatusJson(GuardianDataManager.GuardianStatus status) {
        return "{\"success\":true,\"available\":" + status.available()
                + ",\"databasePath\":\"" + jsonEscape(status.databasePath()) + "\""
                + ",\"databaseBytes\":" + status.databaseBytes()
                + ",\"blockRows\":" + status.blockRows()
                + ",\"containerRows\":" + status.containerRows()
                + ",\"coreProtectRequired\":false}";
    }

    private String guardianActionResultJson(GuardianActionService.ActionResult result) {
        return "{\"success\":" + result.success()
                + ",\"mode\":\"" + jsonEscape(result.mode()) + "\""
                + ",\"preview\":" + result.preview()
                + ",\"matchedBlocks\":" + result.matchedBlocks()
                + ",\"matchedContainers\":" + result.matchedContainers()
                + ",\"changedBlocks\":" + result.changedBlocks()
                + ",\"changedContainers\":" + result.changedContainers()
                + ",\"skipped\":" + result.skipped()
                + ",\"message\":\"" + jsonEscape(result.message()) + "\"}";
    }

    private String guardianBlockLogsJson(List<GuardianDataManager.BlockLogEntry> rows) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < rows.size(); i++) {
            GuardianDataManager.BlockLogEntry row = rows.get(i);
            if (i > 0) json.append(',');
            json.append("{\"id\":").append(row.id())
                    .append(",\"source\":\"").append(jsonEscape(row.source())).append('"')
                    .append(",\"timestamp\":").append(row.timestamp())
                    .append(",\"time\":\"").append(jsonEscape(row.formattedTime())).append('"')
                    .append(",\"player\":\"").append(jsonEscape(row.playerName())).append('"')
                    .append(",\"action\":\"").append(row.actionLabel()).append('"')
                    .append(",\"world\":\"").append(jsonEscape(row.world())).append('"')
                    .append(",\"x\":").append(row.x())
                    .append(",\"y\":").append(row.y())
                    .append(",\"z\":").append(row.z())
                    .append(",\"block\":\"").append(jsonEscape(row.blockType())).append('"')
                    .append(",\"oldBlock\":\"").append(jsonEscape(row.oldBlockType())).append("\"}");
        }
        return json.append(']').toString();
    }

    private String guardianContainerLogsJson(List<GuardianDataManager.ContainerLogEntry> rows) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < rows.size(); i++) {
            GuardianDataManager.ContainerLogEntry row = rows.get(i);
            if (i > 0) json.append(',');
            json.append("{\"id\":").append(row.id())
                    .append(",\"source\":\"").append(jsonEscape(row.source())).append('"')
                    .append(",\"timestamp\":").append(row.timestamp())
                    .append(",\"time\":\"").append(jsonEscape(row.formattedTime())).append('"')
                    .append(",\"player\":\"").append(jsonEscape(row.playerName())).append('"')
                    .append(",\"action\":\"").append(row.actionLabel()).append('"')
                    .append(",\"world\":\"").append(jsonEscape(row.world())).append('"')
                    .append(",\"x\":").append(row.x())
                    .append(",\"y\":").append(row.y())
                    .append(",\"z\":").append(row.z())
                    .append(",\"item\":\"").append(jsonEscape(row.itemMaterial())).append('"')
                    .append(",\"amount\":").append(row.itemAmount()).append('}');
        }
        return json.append(']').toString();
    }

    private String guardianTimelineJson(List<GuardianDataManager.TimelineEntry> rows) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < rows.size(); i++) {
            GuardianDataManager.TimelineEntry row = rows.get(i);
            if (i > 0) json.append(',');
            json.append("{\"timeSlot\":\"").append(jsonEscape(row.timeSlot())).append('"')
                    .append(",\"blockCount\":").append(row.blockCount())
                    .append(",\"containerCount\":").append(row.containerCount()).append('}');
        }
        return json.append(']').toString();
    }

    private String guardianTimelineEventsJson(List<GuardianDataManager.UnifiedTimelineEntry> rows) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < rows.size(); i++) {
            GuardianDataManager.UnifiedTimelineEntry row = rows.get(i);
            if (i > 0) json.append(',');
            json.append("{\"type\":\"").append(jsonEscape(row.eventType())).append('"')
                    .append(",\"id\":").append(row.id())
                    .append(",\"source\":\"").append(jsonEscape(row.source())).append('"')
                    .append(",\"timestamp\":").append(row.timestamp())
                    .append(",\"time\":\"").append(jsonEscape(row.formattedTime())).append('"')
                    .append(",\"player\":\"").append(jsonEscape(row.playerName())).append('"')
                    .append(",\"action\":\"").append(jsonEscape(row.action())).append('"')
                    .append(",\"world\":\"").append(jsonEscape(row.world())).append('"')
                    .append(",\"x\":").append(row.x())
                    .append(",\"y\":").append(row.y())
                    .append(",\"z\":").append(row.z())
                    .append(",\"target\":\"").append(jsonEscape(row.target())).append('"')
                    .append(",\"amount\":").append(row.amount())
                    .append('}');
        }
        return json.append(']').toString();
    }

    private String guardianCasesJson(List<GuardianDataManager.CaseRecord> rows) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) json.append(',');
            json.append(guardianCaseJson(rows.get(i)));
        }
        return json.append(']').toString();
    }

    private String guardianCaseJson(GuardianDataManager.CaseRecord row) {
        if (row == null) {
            return "null";
        }
        return "{\"id\":" + row.id()
                + ",\"title\":\"" + jsonEscape(row.title()) + "\""
                + ",\"status\":\"" + jsonEscape(row.status()) + "\""
                + ",\"priority\":\"" + jsonEscape(row.priority()) + "\""
                + ",\"player\":\"" + jsonEscape(row.playerName()) + "\""
                + ",\"world\":\"" + jsonEscape(row.world()) + "\""
                + ",\"x\":" + nullableNumber(row.x())
                + ",\"y\":" + nullableNumber(row.y())
                + ",\"z\":" + nullableNumber(row.z())
                + ",\"notes\":\"" + jsonEscape(row.notes()) + "\""
                + ",\"createdBy\":\"" + jsonEscape(row.createdBy()) + "\""
                + ",\"createdAt\":" + row.createdAt()
                + ",\"updatedAt\":" + row.updatedAt()
                + ",\"createdTime\":\"" + jsonEscape(row.formattedCreatedAt()) + "\""
                + ",\"updatedTime\":\"" + jsonEscape(row.formattedUpdatedAt()) + "\""
                + ",\"locked\":" + row.locked()
                + "}";
    }

    private String guardianEvidenceJson(List<GuardianDataManager.EvidenceRecord> rows) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < rows.size(); i++) {
            GuardianDataManager.EvidenceRecord row = rows.get(i);
            if (i > 0) json.append(',');
            json.append("{\"id\":").append(row.id())
                    .append(",\"caseId\":").append(row.caseId())
                    .append(",\"eventType\":\"").append(jsonEscape(row.eventType())).append('"')
                    .append(",\"eventId\":").append(row.eventId())
                    .append(",\"label\":\"").append(jsonEscape(row.label())).append('"')
                    .append(",\"addedBy\":\"").append(jsonEscape(row.addedBy())).append('"')
                    .append(",\"createdAt\":").append(row.createdAt())
                    .append(",\"createdTime\":\"").append(jsonEscape(row.formattedCreatedAt())).append("\"}");
        }
        return json.append(']').toString();
    }

    private String guardianFiltersJson(List<GuardianDataManager.SavedFilterRecord> rows) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < rows.size(); i++) {
            GuardianDataManager.SavedFilterRecord row = rows.get(i);
            if (i > 0) json.append(',');
            json.append("{\"id\":").append(row.id())
                    .append(",\"name\":\"").append(jsonEscape(row.name())).append('"')
                    .append(",\"query\":\"").append(jsonEscape(row.query())).append('"')
                    .append(",\"createdBy\":\"").append(jsonEscape(row.createdBy())).append('"')
                    .append(",\"createdAt\":").append(row.createdAt())
                    .append(",\"createdTime\":\"").append(jsonEscape(row.formattedCreatedAt())).append("\"}");
        }
        return json.append(']').toString();
    }

    private String guardianPlayerNotesJson(List<GuardianDataManager.PlayerNoteRecord> rows) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) json.append(',');
            json.append(guardianPlayerNoteJson(rows.get(i)));
        }
        return json.append(']').toString();
    }

    private String guardianPlayerNoteJson(GuardianDataManager.PlayerNoteRecord row) {
        if (row == null) {
            return "null";
        }
        return "{\"player\":\"" + jsonEscape(row.playerName()) + "\""
                + ",\"severity\":\"" + jsonEscape(row.severity()) + "\""
                + ",\"notes\":\"" + jsonEscape(row.notes()) + "\""
                + ",\"createdBy\":\"" + jsonEscape(row.createdBy()) + "\""
                + ",\"createdAt\":" + row.createdAt()
                + ",\"updatedAt\":" + row.updatedAt()
                + ",\"createdTime\":\"" + jsonEscape(row.formattedCreatedAt()) + "\""
                + ",\"updatedTime\":\"" + jsonEscape(row.formattedUpdatedAt()) + "\""
                + "}";
    }

    private String guardianIncidentsJson(List<GuardianDataManager.IncidentRecord> rows) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < rows.size(); i++) {
            GuardianDataManager.IncidentRecord row = rows.get(i);
            if (i > 0) json.append(',');
            json.append("{\"player\":\"").append(jsonEscape(row.playerName())).append('"')
                    .append(",\"world\":\"").append(jsonEscape(row.world())).append('"')
                    .append(",\"chunkX\":").append(row.chunkX())
                    .append(",\"chunkZ\":").append(row.chunkZ())
                    .append(",\"firstAt\":").append(row.firstAt())
                    .append(",\"lastAt\":").append(row.lastAt())
                    .append(",\"firstTime\":\"").append(jsonEscape(row.formattedFirstAt())).append('"')
                    .append(",\"lastTime\":\"").append(jsonEscape(row.formattedLastAt())).append('"')
                    .append(",\"totalActions\":").append(row.totalActions())
                    .append(",\"blockActions\":").append(row.blockActions())
                    .append(",\"containerActions\":").append(row.containerActions())
                    .append(",\"score\":").append(row.score())
                    .append('}');
        }
        return json.append(']').toString();
    }

    private String guardianScoresJson(List<GuardianDataManager.SuspicionScoreRecord> rows) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < rows.size(); i++) {
            GuardianDataManager.SuspicionScoreRecord row = rows.get(i);
            if (i > 0) json.append(',');
            json.append("{\"player\":\"").append(jsonEscape(row.playerName())).append('"')
                    .append(",\"score\":").append(row.score())
                    .append(",\"severity\":\"").append(jsonEscape(row.severity())).append('"')
                    .append(",\"totalActions\":").append(row.totalActions())
                    .append(",\"blockBreaks\":").append(row.blockBreaks())
                    .append(",\"containerRemoves\":").append(row.containerRemoves())
                    .append(",\"rareHits\":").append(row.rareHits())
                    .append(",\"dangerHits\":").append(row.dangerHits())
                    .append(",\"firstAt\":").append(row.firstAt())
                    .append(",\"lastAt\":").append(row.lastAt())
                    .append(",\"firstTime\":\"").append(jsonEscape(row.formattedFirstAt())).append('"')
                    .append(",\"lastTime\":\"").append(jsonEscape(row.formattedLastAt())).append("\"}");
        }
        return json.append(']').toString();
    }

    private String guardianItemAmountsJson(List<GuardianDataManager.ItemAmountRecord> rows) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < rows.size(); i++) {
            GuardianDataManager.ItemAmountRecord row = rows.get(i);
            if (i > 0) json.append(',');
            json.append("{\"item\":\"").append(jsonEscape(row.item())).append('"')
                    .append(",\"amount\":").append(row.amount()).append('}');
        }
        return json.append(']').toString();
    }

    private String guardianPreviewDiffJson(GuardianDataManager.ActionPreviewDiff diff) {
        return "{\"success\":true"
                + ",\"blockRows\":" + diff.blockRows()
                + ",\"containerRows\":" + diff.containerRows()
                + ",\"blockBreaks\":" + diff.blockBreaks()
                + ",\"blockPlaces\":" + diff.blockPlaces()
                + ",\"containerRemovedItems\":" + diff.containerRemovedItems()
                + ",\"containerAddedItems\":" + diff.containerAddedItems()
                + ",\"topTargets\":" + guardianItemAmountsJson(diff.topTargets())
                + "}";
    }

    private String guardianProtectedRegionsJson(List<GuardianDataManager.ProtectedRegionRecord> rows) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) json.append(',');
            json.append(guardianProtectedRegionJson(rows.get(i)));
        }
        return json.append(']').toString();
    }

    private String guardianProtectedRegionJson(GuardianDataManager.ProtectedRegionRecord row) {
        if (row == null) return "null";
        return "{\"id\":" + row.id()
                + ",\"name\":\"" + jsonEscape(row.name()) + "\""
                + ",\"world\":\"" + jsonEscape(row.world()) + "\""
                + ",\"minX\":" + row.minX()
                + ",\"minY\":" + row.minY()
                + ",\"minZ\":" + row.minZ()
                + ",\"maxX\":" + row.maxX()
                + ",\"maxY\":" + row.maxY()
                + ",\"maxZ\":" + row.maxZ()
                + ",\"severity\":\"" + jsonEscape(row.severity()) + "\""
                + ",\"createdBy\":\"" + jsonEscape(row.createdBy()) + "\""
                + ",\"createdAt\":" + row.createdAt()
                + ",\"updatedAt\":" + row.updatedAt()
                + ",\"createdTime\":\"" + jsonEscape(row.formattedCreatedAt()) + "\""
                + ",\"updatedTime\":\"" + jsonEscape(row.formattedUpdatedAt()) + "\""
                + "}";
    }

    private String guardianProtectedRegionHitsJson(List<GuardianDataManager.ProtectedRegionHitRecord> rows) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < rows.size(); i++) {
            GuardianDataManager.ProtectedRegionHitRecord row = rows.get(i);
            if (i > 0) json.append(',');
            json.append("{\"region\":\"").append(jsonEscape(row.regionName())).append('"')
                    .append(",\"severity\":\"").append(jsonEscape(row.severity())).append('"')
                    .append(",\"player\":\"").append(jsonEscape(row.playerName())).append('"')
                    .append(",\"world\":\"").append(jsonEscape(row.world())).append('"')
                    .append(",\"totalActions\":").append(row.totalActions())
                    .append(",\"lastAt\":").append(row.lastAt())
                    .append(",\"lastTime\":\"").append(jsonEscape(row.formattedLastAt())).append("\"}");
        }
        return json.append(']').toString();
    }

    private String guardianAlertRulesJson(List<GuardianDataManager.AlertRuleRecord> rows) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) json.append(',');
            json.append(guardianAlertRuleJson(rows.get(i)));
        }
        return json.append(']').toString();
    }

    private String guardianAlertRuleJson(GuardianDataManager.AlertRuleRecord row) {
        if (row == null) return "null";
        return "{\"id\":" + row.id()
                + ",\"name\":\"" + jsonEscape(row.name()) + "\""
                + ",\"enabled\":" + row.enabled()
                + ",\"windowSeconds\":" + row.windowSeconds()
                + ",\"minActions\":" + row.minActions()
                + ",\"action\":\"" + jsonEscape(row.action()) + "\""
                + ",\"material\":\"" + jsonEscape(row.material()) + "\""
                + ",\"autoCase\":" + row.autoCase()
                + ",\"priority\":\"" + jsonEscape(row.priority()) + "\""
                + ",\"createdBy\":\"" + jsonEscape(row.createdBy()) + "\""
                + ",\"createdAt\":" + row.createdAt()
                + ",\"updatedAt\":" + row.updatedAt()
                + ",\"createdTime\":\"" + jsonEscape(row.formattedCreatedAt()) + "\""
                + ",\"updatedTime\":\"" + jsonEscape(row.formattedUpdatedAt()) + "\""
                + "}";
    }

    private String guardianAlertHitsJson(List<GuardianDataManager.AlertHitRecord> rows) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < rows.size(); i++) {
            GuardianDataManager.AlertHitRecord row = rows.get(i);
            if (i > 0) json.append(',');
            json.append("{\"ruleId\":").append(row.ruleId())
                    .append(",\"rule\":\"").append(jsonEscape(row.ruleName())).append('"')
                    .append(",\"player\":\"").append(jsonEscape(row.playerName())).append('"')
                    .append(",\"count\":").append(row.count())
                    .append(",\"firstAt\":").append(row.firstAt())
                    .append(",\"lastAt\":").append(row.lastAt())
                    .append(",\"firstTime\":\"").append(jsonEscape(row.formattedFirstAt())).append('"')
                    .append(",\"lastTime\":\"").append(jsonEscape(row.formattedLastAt())).append('"')
                    .append(",\"priority\":\"").append(jsonEscape(row.priority())).append('"')
                    .append(",\"autoCase\":").append(row.autoCase())
                    .append('}');
        }
        return json.append(']').toString();
    }

    private String guardianRetentionJson(GuardianDataManager.RetentionPolicyRecord row) {
        return "{\"logDays\":" + row.logDays()
                + ",\"keepCases\":" + row.keepCases()
                + ",\"updatedBy\":\"" + jsonEscape(row.updatedBy()) + "\""
                + ",\"updatedAt\":" + row.updatedAt()
                + ",\"updatedTime\":\"" + jsonEscape(row.formattedUpdatedAt()) + "\""
                + "}";
    }

    private String guardianInboxJson(GuardianDataManager.GuardianInboxRecord inbox) {
        return "{\"openCases\":" + guardianCasesJson(inbox.openCases())
                + ",\"alerts\":" + guardianAlertHitsJson(inbox.alerts())
                + ",\"alertNotes\":" + guardianPlayerNotesJson(inbox.alertNotes())
                + ",\"incidents\":" + guardianIncidentsJson(inbox.incidents())
                + "}";
    }

    private String guardianCaseBundleJson(GuardianDataManager guardian, long caseId) {
        GuardianDataManager.CaseRecord record = guardian.getCase(caseId);
        if (record == null) {
            return "{\"success\":false,\"error\":\"case_not_found\"}";
        }
        return "{\"success\":true,\"case\":" + guardianCaseJson(record)
                + ",\"evidence\":" + guardianEvidenceJson(guardian.listCaseEvidence(caseId))
                + ",\"replay\":" + guardianTimelineEventsJson(guardian.searchTimelineReplay(
                        null, record.playerName(), record.world(), null, null, 80))
                + "}";
    }

    private String guardianActivityJson() {
        var audit = FabricDash.getAuditDataManager();
        if (audit == null) {
            return "[]";
        }
        StringBuilder json = new StringBuilder("[");
        List<dash.data.AuditDataManager.AuditEntry> rows = audit.searchLogs("GUARDIAN", 30);
        for (int i = 0; i < rows.size(); i++) {
            dash.data.AuditDataManager.AuditEntry row = rows.get(i);
            if (i > 0) json.append(',');
            json.append("{\"id\":").append(row.id())
                    .append(",\"timestamp\":").append(row.timestamp())
                    .append(",\"time\":\"").append(jsonEscape(row.getFormattedTime())).append('"')
                    .append(",\"user\":\"").append(jsonEscape(row.username())).append('"')
                    .append(",\"action\":\"").append(jsonEscape(row.action())).append('"')
                    .append(",\"details\":\"").append(jsonEscape(row.details())).append('"')
                    .append(",\"ip\":\"").append(jsonEscape(row.ipAddress())).append("\"}");
        }
        return json.append(']').toString();
    }

    private String nullableNumber(Integer value) {
        return value == null ? "null" : value.toString();
    }

    private String guardianHeatmapJson(List<GuardianDataManager.HeatmapEntry> rows) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < rows.size(); i++) {
            GuardianDataManager.HeatmapEntry row = rows.get(i);
            if (i > 0) json.append(',');
            json.append("{\"world\":\"").append(jsonEscape(row.world())).append('"')
                    .append(",\"chunkX\":").append(row.chunkX())
                    .append(",\"chunkZ\":").append(row.chunkZ())
                    .append(",\"count\":").append(row.count()).append('}');
        }
        return json.append(']').toString();
    }

    private String guardianSuspiciousJson(List<GuardianDataManager.SuspiciousEntry> rows) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < rows.size(); i++) {
            GuardianDataManager.SuspiciousEntry row = rows.get(i);
            if (i > 0) json.append(',');
            json.append("{\"player\":\"").append(jsonEscape(row.playerName())).append('"')
                    .append(",\"totalBroken\":").append(row.totalBroken())
                    .append(",\"diamonds\":").append(row.diamonds())
                    .append(",\"debris\":").append(row.debris()).append('}');
        }
        return json.append(']').toString();
    }

    private String guardianPlayerActivityJson(List<GuardianDataManager.PlayerActivity> rows) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < rows.size(); i++) {
            GuardianDataManager.PlayerActivity row = rows.get(i);
            if (i > 0) json.append(',');
            json.append("{\"player\":\"").append(jsonEscape(row.playerName())).append('"')
                    .append(",\"totalActions\":").append(row.totalActions())
                    .append(",\"blocksBroken\":").append(row.blocksBroken())
                    .append(",\"blocksPlaced\":").append(row.blocksPlaced()).append('}');
        }
        return json.append(']').toString();
    }

    private String guardianCustomStatsJson(GuardianDataManager guardian, long since, String action, int limit) {
        return "{\"since\":" + since
                + ",\"topPlayers\":" + guardianPlayerActivityJson(guardian.getTopPlayersData(since, limit))
                + ",\"peakHours\":" + intIntMapJson(guardian.getPeakHoursData(since))
                + ",\"blockTypes\":" + stringIntMapJson(guardian.getBlockTypesData(since, action, limit))
                + ",\"suspicious\":" + guardianSuspiciousJson(guardian.getSuspiciousPlayers(since))
                + "}";
    }

    private String stringArrayJson(List<String> values) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) json.append(',');
            json.append('"').append(jsonEscape(values.get(i))).append('"');
        }
        return json.append(']').toString();
    }

    private String stringIntMapJson(Map<String, Integer> values) {
        StringBuilder json = new StringBuilder("{");
        int idx = 0;
        for (Map.Entry<String, Integer> entry : values.entrySet()) {
            if (idx++ > 0) json.append(',');
            json.append('"').append(jsonEscape(entry.getKey())).append("\":").append(entry.getValue());
        }
        return json.append('}').toString();
    }

    private String intIntMapJson(Map<Integer, Integer> values) {
        StringBuilder json = new StringBuilder("{");
        int idx = 0;
        for (Map.Entry<Integer, Integer> entry : values.entrySet()) {
            if (idx++ > 0) json.append(',');
            json.append('"').append(entry.getKey()).append("\":").append(entry.getValue());
        }
        return json.append('}').toString();
    }

    private String guardianBlocksCsv(List<GuardianDataManager.BlockLogEntry> rows) {
        StringBuilder csv = new StringBuilder("timestamp,time,source,player,action,block,old_block,world,x,y,z\n");
        for (GuardianDataManager.BlockLogEntry row : rows) {
            csv.append(row.timestamp()).append(',').append(csv(row.formattedTime())).append(',')
                    .append(csv(row.source())).append(',').append(csv(row.playerName())).append(',')
                    .append(row.actionLabel()).append(',').append(csv(row.blockType())).append(',')
                    .append(csv(row.oldBlockType())).append(',').append(csv(row.world())).append(',')
                    .append(row.x()).append(',').append(row.y()).append(',').append(row.z()).append('\n');
        }
        return csv.toString();
    }

    private String guardianContainersCsv(List<GuardianDataManager.ContainerLogEntry> rows) {
        StringBuilder csv = new StringBuilder("timestamp,time,source,player,action,item,amount,world,x,y,z\n");
        for (GuardianDataManager.ContainerLogEntry row : rows) {
            csv.append(row.timestamp()).append(',').append(csv(row.formattedTime())).append(',')
                    .append(csv(row.source())).append(',').append(csv(row.playerName())).append(',')
                    .append(row.actionLabel()).append(',').append(csv(row.itemMaterial())).append(',')
                    .append(row.itemAmount()).append(',').append(csv(row.world())).append(',')
                    .append(row.x()).append(',').append(row.y()).append(',').append(row.z()).append('\n');
        }
        return csv.toString();
    }

    private String csv(String value) {
        if (value == null) return "";
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    static String jsonEscape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private String extractJsonStringField(String json, String field) {
        if (json == null || json.isBlank()) return null;
        String pattern = "\"" + field + "\"\\s*:\\s*\"";
        int idx = json.indexOf("\"" + field + "\"");
        if (idx < 0) return null;
        int colonIdx = json.indexOf(':', idx + field.length() + 2);
        if (colonIdx < 0) return null;
        int quoteStart = json.indexOf('"', colonIdx + 1);
        if (quoteStart < 0) return null;
        int quoteEnd = quoteStart + 1;
        while (quoteEnd < json.length()) {
            if (json.charAt(quoteEnd) == '\\') {
                quoteEnd += 2;
                continue;
            }
            if (json.charAt(quoteEnd) == '"') break;
            quoteEnd++;
        }
        if (quoteEnd >= json.length()) return null;
        return json.substring(quoteStart + 1, quoteEnd)
                .replace("\\\"", "\"")
                .replace("\\n", "\n")
                .replace("\\\\", "\\");
    }

    private class BackupDownloadHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if (!ensurePermission(t, "dash.web.backups.read", true)) {
                return;
            }

            String query = t.getRequestURI().getQuery();
            String backupName = getQueryParam(query, "name");
            if (backupName == null || backupName.isBlank()) {
                t.sendResponseHeaders(400, 0);
                t.close();
                return;
            }

            dash.data.BackupManager bm = FabricDash.getBackupManager();
            if (bm == null) {
                t.sendResponseHeaders(404, 0);
                t.close();
                return;
            }

            File backupFile = bm.getBackupFile(backupName);
            if (backupFile == null || !backupFile.exists()) {
                t.sendResponseHeaders(404, 0);
                t.close();
                return;
            }

            String encodedName = URLEncoder.encode(backupFile.getName(), StandardCharsets.UTF_8).replace("+", "%20");
            t.getResponseHeaders().set("Content-Type", "application/zip");
            t.getResponseHeaders().set("Content-Disposition",
                    "attachment; filename=\"" + backupFile.getName().replace("\"", "_") + "\"; filename*=UTF-8''" + encodedName);
            t.sendResponseHeaders(200, backupFile.length());
            try (OutputStream os = t.getResponseBody();
                 FileInputStream fis = new FileInputStream(backupFile)) {
                byte[] buffer = new byte[8192];
                int len;
                while ((len = fis.read(buffer)) != -1) {
                    os.write(buffer, 0, len);
                }
            }
        }
    }

    private class IconUploadHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if (!ensureAnyPermission(t, true, "dash.web.settings.write", "dash.web.settings.icon.write")) {
                return;
            }
            if (!"POST".equalsIgnoreCase(t.getRequestMethod())) {
                t.sendResponseHeaders(405, -1);
                t.close();
                return;
            }

            try {
                MultipartUpload upload = parseMultipartUpload(t);
                if (upload == null || upload.fileData() == null || upload.fileData().length == 0) {
                    sendJson(t, 400, false, "No file uploaded");
                    return;
                }
                File serverIcon = new File(FabricDash.getServerRootDirectory(), "server-icon.png");
                Files.write(serverIcon.toPath(), upload.fileData());
                WebActionLogger.log("ICON_UPLOAD", "user=" + getSessionUser(t) + " ip=" + getClientIp(t));
                sendJson(t, 200, true, "Server icon updated");
            } catch (Exception e) {
                sendJson(t, 500, false, "Upload failed: " + e.getMessage());
            }
        }
    }

    private class DatapackUploadHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if (!ensureAnyPermission(t, true, "dash.web.settings.write", "dash.web.datapacks.write")) {
                return;
            }
            if (!"POST".equalsIgnoreCase(t.getRequestMethod())) {
                t.sendResponseHeaders(405, -1);
                t.close();
                return;
            }

            try {
                MultipartUpload upload = parseMultipartUpload(t);
                if (upload == null || upload.fileData() == null || upload.fileData().length == 0 || upload.fileName() == null) {
                    sendJson(t, 400, false, "No file uploaded");
                    return;
                }
                String fileName = upload.fileName();
                if (!fileName.toLowerCase(Locale.ROOT).endsWith(".zip")) {
                    sendJson(t, 400, false, "Datapacks must be .zip files");
                    return;
                }
                boolean ok = dash.data.DatapackManager.uploadDatapack(fileName, upload.fileData());
                if (!ok) {
                    sendJson(t, 500, false, "Datapack upload failed");
                    return;
                }
                WebActionLogger.log("DATAPACK_UPLOAD", "user=" + getSessionUser(t) + " file=" + fileName + " ip=" + getClientIp(t));
                sendJson(t, 200, true, "Datapack uploaded: " + fileName);
            } catch (Exception e) {
                sendJson(t, 500, false, "Upload failed: " + e.getMessage());
            }
        }
    }

    private class BridgePlayersHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if (!"GET".equalsIgnoreCase(t.getRequestMethod())) {
                sendResponseWithStatus(t, 405, "Method not allowed");
                return;
            }
            if (!ensureBridgeBearer(t)) return;
            StringBuilder json = new StringBuilder("[");
            boolean first = true;
            Set<String> onlineNames = new HashSet<>();
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (!first) json.append(',');
                first = false;
                String name = player.getName().getString();
                String uuid = player.getStringUUID();
                onlineNames.add(name.toLowerCase(Locale.ROOT));
                WebAuth.UserInfo linked = auth.findLinkedUser(name, uuid);
                String linkedUser = linked == null ? "" : linked.username() + " · " + linked.role();
                json.append("{\"name\":\"").append(jsonEscape(name))
                        .append("\",\"uuid\":\"").append(jsonEscape(uuid))
                        .append("\",\"world\":\"").append(jsonEscape(player.level().dimension().identifier().toString()))
                        .append("\",\"ping\":\"").append(player.connection.latency()).append(" ms")
                        .append("\",\"linkedUser\":\"").append(jsonEscape(linkedUser)).append("\",\"online\":true}");
            }
            for (WebAuth.UserInfo user : auth.getUsers().values()) {
                String linkedPlayer = user.linkedPlayer();
                if (linkedPlayer == null || linkedPlayer.isBlank() || "N/A".equalsIgnoreCase(linkedPlayer)
                        || onlineNames.contains(linkedPlayer.toLowerCase(Locale.ROOT))) continue;
                if (!first) json.append(',');
                first = false;
                json.append("{\"name\":\"").append(jsonEscape(linkedPlayer))
                        .append("\",\"uuid\":\"\",\"world\":\"Offline\",\"ping\":\"-\",\"linkedUser\":\"")
                        .append(jsonEscape(user.username() + " · " + user.role())).append("\",\"online\":false}");
            }
            json.append(']');
            t.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            sendResponse(t, json.toString());
        }
    }

    private class PlayerProfileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if (!ensurePermission(t, "dash.web.players.read", true)) {
                return;
            }

            String query = t.getRequestURI().getQuery();
            String playerName = getQueryParam(query, "name");
            if (playerName == null || playerName.isBlank()) {
                t.getResponseHeaders().add("Content-Type", "application/json");
                sendResponse(t, "{\"error\":\"Missing player name\"}");
                return;
            }

            ServerPlayer player = server.getPlayerList().getPlayerByName(playerName);
            String uuid = "";
            boolean online = false;
            double x = 0, y = 0, z = 0;
            String world = "";
            double health = 0;
            int food = 0;
            int xpLevel = 0;

            if (player != null) {
                online = true;
                uuid = player.getUUID().toString();
                x = player.getX();
                y = player.getY();
                z = player.getZ();
                world = player.level().dimension().identifier().toString();
                health = player.getHealth();
                food = player.getFoodData().getFoodLevel();
                xpLevel = player.experienceLevel;
            } else {
                ServerPlayer profilePlayer = server.getPlayerList().getPlayerByName(playerName);
                if (profilePlayer != null) {
                    uuid = profilePlayer.getUUID().toString();
                }
            }

            String json = String.format(
                    "{\"name\":\"%s\",\"uuid\":\"%s\",\"online\":%s,\"x\":%.2f,\"y\":%.2f,\"z\":%.2f,\"world\":\"%s\",\"health\":%.1f,\"food\":%d,\"xpLevel\":%d}",
                    jsonEscape(playerName), jsonEscape(uuid), online, x, y, z, jsonEscape(world), health, food, xpLevel);
            t.getResponseHeaders().add("Content-Type", "application/json");
            sendResponse(t, json);
        }
    }

    private MultipartUpload parseMultipartUpload(HttpExchange t) throws IOException {
        String contentType = t.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.contains("multipart/form-data")) {
            return null;
        }

        String boundary = null;
        for (String part : contentType.split(";")) {
            part = part.trim();
            if (part.startsWith("boundary=")) {
                boundary = part.substring(9);
                if (boundary.startsWith("\"") && boundary.endsWith("\"")) {
                    boundary = boundary.substring(1, boundary.length() - 1);
                }
            }
        }
        if (boundary == null) return null;

        byte[] bodyBytes = HttpSecurity.readRequestBody(t, 64L * 1024L * 1024L);
        String body = new String(bodyBytes, StandardCharsets.ISO_8859_1);
        String delimiter = "--" + boundary;
        String[] parts = body.split(Pattern.quote(delimiter));

        Map<String, String> fields = new LinkedHashMap<>();
        byte[] fileData = null;
        String fileName = null;

        for (String part : parts) {
            if (!part.contains("Content-Disposition")) {
                continue;
            }
            int headerEnd = part.indexOf("\r\n\r\n");
            if (headerEnd < 0) {
                continue;
            }
            String headers = part.substring(0, headerEnd);
            String content = part.substring(headerEnd + 4);
            if (content.endsWith("\r\n")) {
                content = content.substring(0, content.length() - 2);
            }

            Matcher nameMatcher = Pattern.compile("name=\"([^\"]+)\"").matcher(headers);
            String fieldName = nameMatcher.find() ? nameMatcher.group(1) : null;
            if (fieldName == null) {
                continue;
            }

            Matcher fileMatcher = Pattern.compile("filename=\"([^\"]*)\"").matcher(headers);
            if (fileMatcher.find()) {
                String parsedName = fileMatcher.group(1);
                if (parsedName != null && !parsedName.isBlank()) {
                    parsedName = parsedName.replace("\\", "/");
                    int slash = parsedName.lastIndexOf('/');
                    fileName = slash >= 0 ? parsedName.substring(slash + 1) : parsedName;
                }
                fileData = content.getBytes(StandardCharsets.ISO_8859_1);
            } else {
                fields.put(fieldName, content);
            }
        }
        if (fileData == null) {
            return null;
        }
        return new MultipartUpload(fileName, fileData, fields);
    }

    private record MultipartUpload(String fileName, byte[] fileData, Map<String, String> fields) {
    }

    private void sendJson(HttpExchange t, int status, boolean success, String message) throws IOException {
        t.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        StringBuilder response = new StringBuilder();
        response.append("{\"success\":").append(success);
        if (message != null) {
            if (success) {
                response.append(",\"message\":\"").append(jsonEscape(message)).append("\"");
            } else {
                response.append(",\"error\":\"").append(jsonEscape(message)).append("\"");
            }
        }
        response.append("}");
        sendResponseWithStatus(t, status, response.toString());
    }

    private class FileUploadHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if (!ensurePermission(t, "dash.web.files.write", true)) {
                return;
            }
            if (!"POST".equalsIgnoreCase(t.getRequestMethod())) {
                t.sendResponseHeaders(405, -1);
                t.close();
                return;
            }

            try {
                MultipartUpload upload = parseMultipartUpload(t);
                if (upload == null || upload.fileData() == null || upload.fileName() == null) {
                    sendJson(t, 400, false, "Invalid upload");
                    return;
                }

                String fullPath = firstNonBlank(upload.fields().get("fullpath"), upload.fields().get("path"));
                if (fullPath == null) {
                    fullPath = "";
                }
                fullPath = fullPath.replace("\\", "/").trim();
                while (fullPath.startsWith("/")) {
                    fullPath = fullPath.substring(1);
                }
                if (fullPath.isBlank()) {
                    fullPath = upload.fileName();
                }

                File serverDir = FabricDash.getServerRootDirectory();
                File targetFile = new File(serverDir, fullPath);
                if (isProtectedLockFile(targetFile)) {
                    sendJson(t, 400, false, "Protected lock files cannot be uploaded");
                    return;
                }

                if (!targetFile.getCanonicalFile().toPath().startsWith(serverDir.getCanonicalFile().toPath())) {
                    sendJson(t, 403, false, "Access denied");
                    return;
                }

                File targetDir = targetFile.getParentFile();
                targetDir.mkdirs();
                Files.write(targetFile.toPath(), upload.fileData());
                WebActionLogger.log("FILE_UPLOAD", "user=" + getSessionUser(t) + " file=" + upload.fileName() + " path=" + fullPath + " ip=" + getClientIp(t));
                sendJson(t, 200, true, "File uploaded: " + upload.fileName());
            } catch (Exception e) {
                sendJson(t, 500, false, "Upload failed: " + e.getMessage());
            }
        }
    }

    private class PluginUploadHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if (!ensurePermission(t, "dash.web.plugins.manage", true)) {
                return;
            }
            if (!"POST".equalsIgnoreCase(t.getRequestMethod())) {
                t.sendResponseHeaders(405, -1);
                t.close();
                return;
            }

            try {
                MultipartUpload upload = parseMultipartUpload(t);
                if (upload == null || upload.fileData() == null || upload.fileName() == null) {
                    sendJson(t, 400, false, "No file uploaded");
                    return;
                }

                String fileName = upload.fileName();
                fileName = fileName.replace('\\', '/');
                fileName = fileName.substring(fileName.lastIndexOf('/') + 1)
                        .replaceAll("[^a-zA-Z0-9._-]", "_");
                if (fileName.isBlank() || !fileName.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                    sendJson(t, 400, false, "Only .jar files are accepted");
                    return;
                }

                java.nio.file.Path modsDir = FabricDash.getServerRootDirectory().toPath().resolve("mods");
                dash.security.JarUploadSecurity.validateArchive(
                        upload.fileData(), Set.of("META-INF/neoforge.mods.toml", "META-INF/mods.toml"));
                dash.security.JarUploadSecurity.writeAtomically(modsDir, fileName, upload.fileData());
                WebActionLogger.log("MOD_UPLOAD", "user=" + getSessionUser(t) + " file=" + fileName + " ip=" + getClientIp(t));
                sendJson(t, 200, true, "Mod uploaded: " + fileName + " (restart required)");
            } catch (Exception e) {
                sendJson(t, 500, false, "Upload failed: " + e.getMessage());
            }
        }
    }

    private String extractFormField(String body, String boundary, String fieldName) {
        String delimiter = "--" + boundary;
        String[] parts = body.split(Pattern.quote(delimiter));
        for (String part : parts) {
            if (part.contains("name=\"" + fieldName + "\"") && !part.contains("filename=")) {
                int headerEnd = part.indexOf("\r\n\r\n");
                if (headerEnd >= 0) {
                    String value = part.substring(headerEnd + 4).trim();
                    if (value.endsWith("\r\n")) {
                        value = value.substring(0, value.length() - 2);
                    }
                    return value;
                }
            }
        }
        return null;
    }

    private boolean isProtectedLockFile(File file) {
        String name = file.getName().toLowerCase();
        return name.equals("session.lock") || name.equals("server.lock") || name.endsWith(".lck");
    }

    private void deleteRecursively(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        file.delete();
    }

    public WebAuth getAuth() {
        return auth;
    }
}
