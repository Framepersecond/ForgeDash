package dash;

import dash.bridge.ConsoleCatcher;
import dash.bridge.BridgeSecurity;
import dash.data.AuditDataManager;
import dash.data.BackupManager;
import dash.data.GlobalSettingsManager;
import dash.data.GuardianDataManager;
import dash.data.PlayerDataManager;
import dash.data.ScheduledTaskManager;
import dash.security.FilePermissions;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

@Mod(FabricDash.MOD_ID)
public class FabricDash {

    public static final String MOD_ID = "forgedash";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static FabricDash instance;
    private static MinecraftServer server;
    private static FabricConfig config;

    private AdminWebServer webServer;
    private static StatsCollector statsCollector;
    private static PlayerDataManager playerDataManager;
    private static BackupManager backupManager;
    private static RegistrationManager registrationManager;
    private static RegistrationApprovalManager registrationApprovalManager;
    private static DiscordWebhookManager discordWebhookManager;
    private static AuditDataManager auditDataManager;
    private static ScheduledTaskManager scheduledTaskManager;
    private static GithubUpdater githubUpdater;
    private static GlobalSettingsManager globalSettingsManager;
    private static GuardianDataManager guardianDataManager;
    private static int webPort;

    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> updaterFuture;

    public FabricDash() {
        instance = this;

        // Load config
        config = new FabricConfig(getConfigDir().resolve("config.yml"));
        config.saveDefaultConfig();
        config.reload();

        // Set defaults if missing
        setDefaultIfAbsent("bridge.enabled", "true");
        setDefaultIfAbsent("bridge.secret", "");
        setDefaultIfAbsent("bridge.master_url", "");
        setDefaultIfAbsent("beta.enabled", "false");
        setDefaultIfAbsent("server-ip", "");
        setDefaultIfAbsent("ssl-enabled", "false");
        setDefaultIfAbsent("panel-url", "");
        setDefaultIfAbsent("report-url", "");
        setDefaultIfAbsent("proxy.trust-forwarded-headers", "false");
        setDefaultIfAbsent("discord.webhook_url", "");
        setDefaultIfAbsent("updater.github-repo", "Framepersecond/ForgeDash");
        setDefaultIfAbsent("updater.asset-keyword", "forgedash");
        migrateDefaultValue("updater.github-repo",
                List.of("Framepersecond/FabricDash", "Framepersecond/NeoDash"),
                "Framepersecond/ForgeDash");
        migrateDefaultValue("updater.asset-keyword",
                List.of("fabricdash", "neoforge"),
                "forgedash");
        ensureSecureBridgeSecret();
        config.save();
        FilePermissions.ownerReadWrite(getConfigDir().resolve("config.yml"));

        webPort = config.getInt("port", 8080);
        WebActionLogger.init();

        if (config.getBoolean("bridge.enabled", true)) {
            ConsoleCatcher.register();
            LOGGER.info("NeoBridge mode enabled.");
        }

        logStartupBetaBanner();

        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(this::onServerStartedEvent);
        NeoForge.EVENT_BUS.addListener(this::onServerStoppingEvent);
        NeoForge.EVENT_BUS.addListener(this::onServerTick);
        NeoForge.EVENT_BUS.addListener(this::onPlayerJoin);
        NeoForge.EVENT_BUS.addListener(this::onPlayerQuit);
        NeoForge.EVENT_BUS.addListener(GuardianForgeEvents::onBlockBreak);
        NeoForge.EVENT_BUS.addListener(GuardianForgeEvents::onBlockPlace);
        NeoForge.EVENT_BUS.addListener(GuardianForgeEvents::onContainerOpen);
        NeoForge.EVENT_BUS.addListener(GuardianForgeEvents::onContainerClose);
    }

    private void ensureSecureBridgeSecret() {
        String secret = config.getString("bridge.secret", "").trim();
        if (secret.isBlank() || "your-super-secret-key".equals(secret)) {
            config.set("bridge.secret", BridgeSecurity.generateSecret());
            LOGGER.warn("Generated a new random NeoBridge secret because the configured value was empty or insecure.");
        }
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        DashCommand.register(event.getDispatcher());
    }

    private void onServerStartedEvent(ServerStartedEvent event) {
        onServerStarted(event.getServer());
    }

    private void onServerStoppingEvent(ServerStoppingEvent event) {
        onServerStopping(event.getServer());
    }

    private void onServerTick(ServerTickEvent.Post event) {
        FreezeManager.handleTick(event.getServer());
    }

    private void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (playerDataManager != null && event.getEntity() instanceof ServerPlayer player) {
            playerDataManager.onPlayerJoin(
                    player.getUUID(),
                    player.getName().getString(),
                    extractPlayerIp(player.connection));
        }
    }

    private void onPlayerQuit(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            if (playerDataManager != null) {
                playerDataManager.onPlayerQuit(
                        player.getUUID(),
                        player.getName().getString());
            }
            FreezeManager.onDisconnect(player.getUUID());
        }
    }

    private void onServerStarted(MinecraftServer minecraftServer) {
        server = minecraftServer;
        scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "ForgeDash-Scheduler");
            t.setDaemon(true);
            return t;
        });

        playerDataManager = new PlayerDataManager(getDataDir());
        backupManager = new BackupManager(getDataDir(), getServerRootDirectory().toPath(), config);
        registrationManager = new RegistrationManager();
        registrationApprovalManager = new RegistrationApprovalManager();
        auditDataManager = new AuditDataManager(getDataDir());
        discordWebhookManager = new DiscordWebhookManager(config, LOGGER);
        scheduledTaskManager = new ScheduledTaskManager(minecraftServer, getDataDir());
        githubUpdater = new GithubUpdater(getDataDir(), LOGGER, getModVersion());
        globalSettingsManager = new GlobalSettingsManager(getDataDir());
        guardianDataManager = new GuardianDataManager(getDataDir(), command -> CompletableFuture.runAsync(command),
                message -> LOGGER.warn(message));

        for (var p : minecraftServer.getPlayerList().getPlayers()) {
            playerDataManager.onPlayerJoin(
                    p.getUUID(),
                    p.getName().getString(),
                    p.connection != null ? extractPlayerIp(p.connection) : "Unknown");
        }

        WebActionLogger.setAuditManager(auditDataManager);
        WebActionLogger.setDiscordWebhookManager(discordWebhookManager);

        statsCollector = new StatsCollector();
        statsCollector.start();

        webServer = new AdminWebServer(minecraftServer, webPort);
        webServer.start();

        startUpdaterSchedule();

        if (!webServer.hasPortBindFailed()) {
            LOGGER.info("ForgeDash Admin started on port {}", webPort);
        }

        if (discordWebhookManager != null) {
            discordWebhookManager.dispatchEmbed(
                    DiscordWebhookManager.EVENT_SERVER_START_STOP,
                    "Server Started", "The Minecraft server is now online.", 0x10B981);
        }
    }

    private void onServerStopping(MinecraftServer minecraftServer) {
        if (scheduledTaskManager != null) scheduledTaskManager.close();
        if (auditDataManager != null) auditDataManager.close();
        if (globalSettingsManager != null) globalSettingsManager.close();
        if (guardianDataManager != null) guardianDataManager.close();
        if (backupManager != null) backupManager.stop();
        if (playerDataManager != null) playerDataManager.close();
        if (statsCollector != null) statsCollector.stop();
        if (webServer != null) webServer.stop();

        stopUpdaterSchedule();
        if (scheduler != null) {
            scheduler.shutdownNow();
        }

        if (githubUpdater != null) {
            githubUpdater.applyUpdateOnShutdown();
        }

        if (discordWebhookManager != null) {
            discordWebhookManager.dispatchEmbed(
                    DiscordWebhookManager.EVENT_SERVER_START_STOP,
                    "Server Stopped", "The Minecraft server has been shut down.", 0xF43F5E);
        }

        LOGGER.info("ForgeDash Admin stopped");
        server = null;
    }

    private void logStartupBetaBanner() {
        LOGGER.info("=======================");
        LOGGER.info(" 222222         11     ");
        LOGGER.info("      22       111     ");
        LOGGER.info("  22222   ..    11     ");
        LOGGER.info(" 22       ..    11     ");
        LOGGER.info(" 2222222      111111   ");
        LOGGER.info("=======================");
    }

    private void setDefaultIfAbsent(String path, String value) {
        if (config.getString(path, null) == null) {
            config.set(path, value);
        }
    }

    private void migrateDefaultValue(String path, List<String> oldValues, String newValue) {
        String current = config.getString(path, null);
        if (current == null) {
            return;
        }
        for (String oldValue : oldValues) {
            if (oldValue.equalsIgnoreCase(current.trim())) {
                config.set(path, newValue);
                return;
            }
        }
    }

    // --- Updater scheduling ---

    private void startUpdaterSchedule() {
        stopUpdaterSchedule();
        if (githubUpdater == null || !githubUpdater.isEnabled()) return;

        long intervalMinutes = 120L;
        if (globalSettingsManager != null) {
            String raw = globalSettingsManager.getGlobalSetting("update_interval_minutes", "120");
            try {
                intervalMinutes = Long.parseLong(raw);
            } catch (NumberFormatException ignored) {
            }
        }
        intervalMinutes = Math.max(20L, Math.min(10080L, intervalMinutes));

        updaterFuture = scheduler.scheduleAtFixedRate(this::runUpdaterScan,
                intervalMinutes, intervalMinutes, TimeUnit.MINUTES);
        LOGGER.info("[Updater] Scheduled update scan every {} minutes.", intervalMinutes);
    }

    public void rescheduleUpdater() {
        startUpdaterSchedule();
    }

    private void stopUpdaterSchedule() {
        if (updaterFuture != null) {
            updaterFuture.cancel(false);
            updaterFuture = null;
        }
    }

    private void runUpdaterScan() {
        try {
            if (githubUpdater == null || !githubUpdater.isEnabled()) return;
            if (!githubUpdater.isUpdateAvailable()) return;
            if (githubUpdater.isUpdatePrepared()) return;
            LOGGER.info("[ForgeDash] New version available. Auto-downloading update in the background...");
            boolean ok = githubUpdater.downloadUpdate();
            if (!ok) {
                LOGGER.warn("[ForgeDash] Auto-download failed. You can retry from the admin panel under Updates.");
            }
        } catch (Exception ex) {
            LOGGER.warn("[Updater] Scheduled update scan failed: {}", ex.getMessage());
        }
    }

    private static String extractPlayerIp(ServerGamePacketListenerImpl handler) {
        if (handler == null || handler.getConnection() == null || handler.getConnection().getRemoteAddress() == null) {
            return "Unknown";
        }
        String raw = handler.getConnection().getRemoteAddress().toString();
        if (raw == null || raw.isBlank()) {
            return "Unknown";
        }
        String ip = raw;
        if (ip.startsWith("/")) {
            ip = ip.substring(1);
        }
        int colon = ip.lastIndexOf(':');
        if (colon > 0) {
            String maybePort = ip.substring(colon + 1);
            if (maybePort.chars().allMatch(Character::isDigit)) {
                ip = ip.substring(0, colon);
            }
        }
        return ip;
    }

    // --- Static getters ---

    public static FabricDash getInstance() {
        return instance;
    }

    public static MinecraftServer getServer() {
        return server;
    }

    public static FabricConfig getConfig() {
        return config;
    }

    public static Path getConfigDir() {
        Path configRoot = FMLPaths.CONFIGDIR.get();
        Path current = configRoot.resolve(MOD_ID);
        Path legacy = configRoot.resolve("fabricdash");
        if (!Files.exists(current) && Files.exists(legacy)) {
            return legacy;
        }
        return current;
    }

    public static Path getDataDir() {
        return getConfigDir();
    }

    public static File getServerRootDirectory() {
        MinecraftServer activeServer = server;
        File fallback = new File(".").getAbsoluteFile();
        if (activeServer == null) {
            return fallback;
        }

        List<File> candidates = new ArrayList<>();
        try {
            candidates.add(activeServer.getServerDirectory().toFile());
        } catch (Exception ignored) {
        }
        try {
            Path worldRoot = activeServer.getWorldPath(LevelResource.ROOT);
            if (worldRoot != null) {
                File worldRootFile = worldRoot.toFile();
                if (worldRootFile != null) {
                    File parent = worldRootFile.getParentFile();
                    if (parent != null) {
                        candidates.add(parent);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        candidates.add(fallback);

        File best = fallback;
        int bestScore = Integer.MIN_VALUE;
        for (File candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            File absolute = candidate.getAbsoluteFile();
            int score = scoreServerRootCandidate(absolute);
            if (score > bestScore) {
                bestScore = score;
                best = absolute;
            }
        }
        return best;
    }

    private static int scoreServerRootCandidate(File dir) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) {
            return Integer.MIN_VALUE;
        }
        int score = 0;
        if (new File(dir, "server.properties").isFile()) {
            score += 50;
        }
        if (new File(dir, "mods").isDirectory()) {
            score += 25;
        }
        if (new File(dir, "config").isDirectory()) {
            score += 12;
        }
        if (new File(dir, "world").isDirectory()) {
            score += 10;
        }
        if (new File(dir, "ops.json").isFile()) {
            score += 6;
        }
        if (new File(dir, "whitelist.json").isFile()) {
            score += 4;
        }
        String[] children = dir.list();
        if (children != null) {
            score += Math.min(20, children.length);
        }
        return score;
    }

    public static String getModVersion() {
        return ModList.get().getModContainerById(MOD_ID)
                .map(c -> c.getModInfo().getVersion().toString())
                .orElse("unknown");
    }

    public ScheduledExecutorService getScheduler() {
        return scheduler;
    }

    public static StatsCollector getStatsCollector() {
        return statsCollector;
    }

    public static PlayerDataManager getPlayerDataManager() {
        return playerDataManager;
    }

    public static BackupManager getBackupManager() {
        return backupManager;
    }

    public static RegistrationManager getRegistrationManager() {
        return registrationManager;
    }

    public static RegistrationApprovalManager getRegistrationApprovalManager() {
        return registrationApprovalManager;
    }

    public static DiscordWebhookManager getDiscordWebhookManager() {
        return discordWebhookManager;
    }

    public static AuditDataManager getAuditDataManager() {
        return auditDataManager;
    }

    public static ScheduledTaskManager getScheduledTaskManager() {
        return scheduledTaskManager;
    }

    public static GithubUpdater getGithubUpdater() {
        return githubUpdater;
    }

    public static GlobalSettingsManager getGlobalSettingsManager() {
        return globalSettingsManager;
    }

    public static GuardianDataManager getGuardianDataManager() {
        return guardianDataManager;
    }

    public static int getWebPort() {
        return webPort;
    }

    public AdminWebServer getWebServer() {
        return webServer;
    }

    /** Called by /dash port command to change the port at runtime. */
    public void changeWebPort(int newPort) {
        webPort = newPort;
        config.set("port", String.valueOf(newPort));
        config.save();
        if (webServer != null) {
            webServer.rebindToPort(newPort);
        }
    }
}
