package dash;

import dash.security.ReleaseAssetVerifier;
import dash.security.SafeHttpDownloads;

import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GithubUpdater {

    private static final String DEFAULT_REPO = "Framepersecond/ForgeDash";
    private static final String DEFAULT_ASSET_KEYWORD = "forgedash";
    private static final long RELEASE_CACHE_TTL_MS = 300_000L;
    private static final long RATE_LIMIT_BACKOFF_MS = 24L * 60L * 60L * 1000L;

    private final Path dataFolder;
    private final Logger logger;
    private final String currentVersion;
    private final String repoFullName;
    private final String assetKeyword;
    private final String releasesLatestApi;
    private final boolean enabled;
    private volatile String lastKnownLatestTag;
    private volatile String cachedLatestReleaseJson;
    private volatile long cachedLatestReleaseFetchedAt;
    private volatile long rateLimitBackoffUntilMs;
    private volatile boolean warnedRateLimited;

    public GithubUpdater(Path dataFolder, Logger logger, String currentVersion) {
        this.dataFolder = dataFolder;
        this.logger = logger;
        this.currentVersion = currentVersion != null ? currentVersion : "unknown";
        this.repoFullName = resolveConfiguredRepo();
        this.assetKeyword = resolveConfiguredAssetKeyword();
        this.releasesLatestApi = "https://api.github.com/repos/" + repoFullName + "/releases/latest";
        this.enabled = true;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isUpdateAvailable() {
        if (!enabled) {
            return false;
        }

        String latestTag = getLatestVersion();
        if (latestTag == null || latestTag.isBlank()) {
            return false;
        }

        String latestVersion = normalizeVersion(latestTag);
        String current = normalizeVersion(this.currentVersion);
        return compareVersions(latestVersion, current) > 0;
    }

    public String getCurrentVersion() {
        return currentVersion;
    }

    public String getLatestVersion() {
        if (!enabled) {
            return getCurrentVersion();
        }
        String latestTag = fetchLatestTag();
        if (latestTag != null && !latestTag.isBlank()) {
            lastKnownLatestTag = latestTag;
            return latestTag;
        }
        return lastKnownLatestTag != null ? lastKnownLatestTag : getCurrentVersion();
    }

    /**
     * Forces a fresh GitHub release check by clearing the cache, then returns the latest version.
     */
    public String checkForUpdates() {
        cachedLatestReleaseJson = null;
        cachedLatestReleaseFetchedAt = 0L;
        // Manual scan should be allowed to bypass any scheduled backoff window.
        rateLimitBackoffUntilMs = 0L;
        warnedRateLimited = false;
        return getLatestVersion();
    }

    private static final String STAGED_UPDATE_FILENAME = "ForgeDash-update.jar";
    private static final String FINAL_UPDATE_FILENAME = "ForgeDash.jar";

    public boolean isUpdatePrepared() {
        File staged = resolveStagedUpdateFile();
        return staged != null && staged.exists();
    }

    public boolean downloadUpdate() {
        if (!enabled) {
            return false;
        }

        ReleaseAsset asset = fetchLatestJarAsset();
        if (asset == null || asset.downloadUrl().isBlank()) {
            logger.warn("[Updater] No release JAR asset found.");
            return false;
        }

        File output = resolveStagedUpdateFile();
        if (output == null) {
            logger.warn("[Updater] Failed to resolve staged update file path.");
            return false;
        }

        File tempOutput = new File(output.getParentFile(), STAGED_UPDATE_FILENAME + ".tmp");
        try {
            downloadAssetToFile(asset.downloadUrl(), tempOutput);
            ReleaseAssetVerifier.verifySha256(tempOutput.toPath(), asset.digest());
            if (output.exists() && !output.delete()) {
                logger.warn("[Updater] Could not remove previous staged update — overwriting.");
            }
            if (!tempOutput.renameTo(output)) {
                logger.warn("[Updater] Failed to finalise staged update file.");
                tempOutput.delete();
                return false;
            }
            logger.info("[Updater] Update downloaded successfully and staged as {}. It will be applied on next restart.",
                    STAGED_UPDATE_FILENAME);
            return true;
        } catch (IOException ex) {
            logger.warn("[Updater] Failed to download update: {}", ex.getMessage());
            tempOutput.delete();
            return false;
        }
    }

    /**
     * Called on shutdown: replaces the currently running jar with the
     * staged update so the new version loads on the next server start.
     *
     * @return {@code true} if the update was applied, {@code false} otherwise
     */
    public boolean applyUpdateOnShutdown() {
        File staged = resolveStagedUpdateFile();
        if (staged == null || !staged.exists()) {
            return false;
        }

        File modsFolder = staged.getParentFile();

        try {
            File currentJar = new File(
                    GithubUpdater.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            String targetJarName = (currentJar.getName().toLowerCase().endsWith(".jar"))
                    ? currentJar.getName()
                    : FINAL_UPDATE_FILENAME;

            if (currentJar.exists() && !currentJar.getName().equalsIgnoreCase(targetJarName)) {
                if (!currentJar.delete()) {
                    logger.warn("[Updater] Could not delete running jar '{}'. Manual cleanup may be needed.",
                            currentJar.getName());
                }
            }

            File finalJar = new File(modsFolder, targetJarName);
            if (finalJar.exists() && !finalJar.delete()) {
                logger.warn("[Updater] Could not remove existing {}. Update rename may fail.",
                        targetJarName);
            }

            if (staged.renameTo(finalJar)) {
                logger.info("[Updater] Update applied! '{}' will be loaded on the next server start.",
                        targetJarName);
                return true;
            } else {
                logger.warn("[Updater] Failed to rename staged update to '{}'. The file is still at: {}",
                        targetJarName, staged.getAbsolutePath());
                return false;
            }
        } catch (Exception ex) {
            logger.warn("[Updater] Failed to apply update on shutdown: {}", ex.getMessage());
            return false;
        }
    }

    private File resolveStagedUpdateFile() {
        File modsFolder = resolveModsFolder();
        if (modsFolder == null) {
            return null;
        }
        if (!modsFolder.exists()) {
            modsFolder.mkdirs();
        }
        return new File(modsFolder, STAGED_UPDATE_FILENAME);
    }

    private File resolveModsFolder() {
        try {
            File currentJar = new File(GithubUpdater.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            File parent = currentJar.getParentFile();
            if (parent != null && parent.isDirectory() && "mods".equalsIgnoreCase(parent.getName())) {
                return parent;
            }
        } catch (Exception ignored) {
        }

        File serverRoot = FabricDash.getServerRootDirectory();
        File modsDir = new File(serverRoot, "mods");
        if (modsDir.exists() || modsDir.mkdirs()) {
            return modsDir;
        }

        File fallback = dataFolder.resolve("updates").toFile();
        if (fallback.exists() || fallback.mkdirs()) {
            return fallback;
        }
        return fallback;
    }

    private String fetchLatestTag() {
        try {
            String json = fetchLatestReleaseJson();
            Matcher tagMatcher = Pattern.compile("\"tag_name\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
            if (tagMatcher.find()) {
                return tagMatcher.group(1);
            }
        } catch (IOException ex) {
            if (System.currentTimeMillis() >= rateLimitBackoffUntilMs) {
                logger.warn("[Updater] Failed to check latest release: {}", ex.getMessage());
            }
        }
        return null;
    }

    private ReleaseAsset fetchLatestJarAsset() {
        try {
            String json = fetchLatestReleaseJson();
            String assetsArray = extractJsonArray(json, "assets");
            if (assetsArray == null || assetsArray.isBlank()) {
                return null;
            }

            ReleaseAsset fallbackJar = null;
            for (String assetObject : splitTopLevelObjects(assetsArray)) {
                String name = extractJsonStringField(assetObject, "name");
                if (name == null || !name.toLowerCase().endsWith(".jar")) {
                    continue;
                }

                String browserDownloadUrl = extractJsonStringField(assetObject, "browser_download_url");
                if (browserDownloadUrl == null || browserDownloadUrl.isBlank()) {
                    continue;
                }

                String digest = extractJsonStringField(assetObject, "digest");
                ReleaseAsset asset = new ReleaseAsset(name, browserDownloadUrl, digest);
                if (fallbackJar == null) {
                    fallbackJar = asset;
                }
                if (assetKeyword.isBlank() || name.toLowerCase().contains(assetKeyword)) {
                    return asset;
                }
            }
            return fallbackJar;
        } catch (IOException ex) {
            if (System.currentTimeMillis() >= rateLimitBackoffUntilMs) {
                logger.warn("[Updater] Failed to resolve update asset: {}", ex.getMessage());
            }
        }
        return null;
    }

    private String resolveConfiguredRepo() {
        String configured = null;
        try {
            if (FabricDash.getConfig() != null) {
                configured = FabricDash.getConfig().getString("updater.github-repo", DEFAULT_REPO);
            }
        } catch (Exception ignored) {
        }
        String repo = configured == null || configured.isBlank() ? DEFAULT_REPO : configured.trim();
        repo = repo.replace("https://github.com/", "").replace("http://github.com/", "");
        while (repo.startsWith("/")) {
            repo = repo.substring(1);
        }
        while (repo.endsWith("/")) {
            repo = repo.substring(0, repo.length() - 1);
        }
        if (!repo.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) {
            logger.warn("[Updater] Invalid updater.github-repo '{}'; falling back to {}.", repo, DEFAULT_REPO);
            return DEFAULT_REPO;
        }
        return repo;
    }

    private String resolveConfiguredAssetKeyword() {
        String configured = null;
        try {
            if (FabricDash.getConfig() != null) {
                configured = FabricDash.getConfig().getString("updater.asset-keyword", DEFAULT_ASSET_KEYWORD);
            }
        } catch (Exception ignored) {
        }
        return configured == null ? DEFAULT_ASSET_KEYWORD : configured.trim().toLowerCase();
    }

    private String fetchLatestReleaseJson() throws IOException {
        long now = System.currentTimeMillis();

        String cached = cachedLatestReleaseJson;
        if (cached != null && (now - cachedLatestReleaseFetchedAt) < RELEASE_CACHE_TTL_MS) {
            return cached;
        }

        if (now < rateLimitBackoffUntilMs) {
            if (cached != null) {
                return cached;
            }
            throw new IOException("GitHub API rate limit active until epoch-second " + (rateLimitBackoffUntilMs / 1000));
        }

        try {
            String json = fetchJson(releasesLatestApi);
            cachedLatestReleaseJson = json;
            cachedLatestReleaseFetchedAt = now;
            rateLimitBackoffUntilMs = 0L;
            warnedRateLimited = false;
            return json;
        } catch (HttpStatusException ex) {
            markRateLimitBackoff(ex, now);
            throw ex;
        }
    }

    private String extractJsonArray(String json, String fieldName) {
        String marker = "\"" + fieldName + "\"";
        int markerIndex = json.indexOf(marker);
        if (markerIndex < 0) {
            return null;
        }

        int arrayStart = json.indexOf('[', markerIndex);
        if (arrayStart < 0) {
            return null;
        }

        boolean inString = false;
        boolean escaped = false;
        int depth = 0;
        for (int i = arrayStart; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }

            if (c == '"') {
                inString = true;
                continue;
            }
            if (c == '[') {
                depth++;
                continue;
            }
            if (c == ']') {
                depth--;
                if (depth == 0) {
                    return json.substring(arrayStart, i + 1);
                }
            }
        }
        return null;
    }

    private java.util.List<String> splitTopLevelObjects(String jsonArray) {
        java.util.List<String> objects = new java.util.ArrayList<>();
        if (jsonArray == null || jsonArray.length() < 2) {
            return objects;
        }

        boolean inString = false;
        boolean escaped = false;
        int depth = 0;
        int objectStart = -1;

        for (int i = 0; i < jsonArray.length(); i++) {
            char c = jsonArray.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }

            if (c == '"') {
                inString = true;
                continue;
            }

            if (c == '{') {
                if (depth == 0) {
                    objectStart = i;
                }
                depth++;
                continue;
            }

            if (c == '}') {
                depth--;
                if (depth == 0 && objectStart >= 0) {
                    objects.add(jsonArray.substring(objectStart, i + 1));
                    objectStart = -1;
                }
            }
        }

        return objects;
    }

    private String extractJsonStringField(String jsonObject, String fieldName) {
        Pattern pattern = Pattern.compile("\\\"" + Pattern.quote(fieldName) + "\\\"\\s*:\\s*\\\"((?:\\\\\\\"|\\\\\\\\|[^\\\"])*)\\\"");
        Matcher matcher = pattern.matcher(jsonObject);
        if (!matcher.find()) {
            return null;
        }
        return matcher.group(1)
                .replace("\\\\", "\\")
                .replace("\\\"", "\"")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t");
    }

    private String fetchJson(String endpoint) throws IOException {
        return fetchJsonInternal(endpoint);
    }

    private String fetchJsonInternal(String endpoint) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(20_000);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        connection.setRequestProperty("User-Agent", "ForgeDash-Updater");

        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) {
            String body = readResponseBody(connection);
            throw new HttpStatusException(
                    buildHttpErrorMessage("GitHub API", endpoint, code, body, connection),
                    code,
                    connection.getHeaderField("X-RateLimit-Remaining"),
                    connection.getHeaderField("X-RateLimit-Reset"));
        }

        try (InputStream in = connection.getInputStream()) {
            return readBoundedUtf8(in, 16 * 1024 * 1024);
        } finally {
            connection.disconnect();
        }
    }

    private void downloadAssetToFile(String assetApiUrl, File targetFile) throws IOException {
        SafeHttpDownloads.downloadPublicHttpsJar(assetApiUrl, targetFile.toPath());
    }

    private String normalizeVersion(String version) {
        if (version == null) {
            return "0";
        }
        String normalized = version.trim().toLowerCase();
        if (normalized.startsWith("v")) {
            normalized = normalized.substring(1);
        }
        int dash = normalized.indexOf('-');
        if (dash >= 0) {
            normalized = normalized.substring(0, dash);
        }
        return normalized;
    }

    private int compareVersions(String a, String b) {
        String[] aParts = a.split("\\.");
        String[] bParts = b.split("\\.");
        int max = Math.max(aParts.length, bParts.length);
        for (int i = 0; i < max; i++) {
            int av = parseIntPart(aParts, i);
            int bv = parseIntPart(bParts, i);
            if (av != bv) {
                return Integer.compare(av, bv);
            }
        }
        return 0;
    }

    private int parseIntPart(String[] parts, int index) {
        if (index >= parts.length) {
            return 0;
        }
        String clean = parts[index].replaceAll("[^0-9]", "");
        if (clean.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(clean);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private boolean isRateLimited(HttpStatusException ex) {
        if (ex == null || ex.statusCode != 403) {
            return false;
        }
        if ("0".equals(ex.rateLimitRemaining)) {
            return true;
        }
        String msg = ex.getMessage();
        return msg != null && msg.toLowerCase().contains("rate limit");
    }

    private void markRateLimitBackoff(HttpStatusException ex, long nowMs) {
        if (!isRateLimited(ex)) {
            return;
        }

        long resetMs = nowMs + RATE_LIMIT_BACKOFF_MS;
        rateLimitBackoffUntilMs = Math.max(rateLimitBackoffUntilMs, resetMs);
        if (!warnedRateLimited) {
            warnedRateLimited = true;
            long hours = Math.max(1L, (rateLimitBackoffUntilMs - nowMs) / (60L * 60L * 1000L));
            logger.warn("[Updater] GitHub rate limit reached. Pausing automatic update checks for ~{}h (until epoch-second {}). Manual 'Scan for Updates' can still force a retry.",
                    hours, rateLimitBackoffUntilMs / 1000L);
        }
    }

    private String readResponseBody(HttpURLConnection connection) {
        try (InputStream err = connection.getErrorStream()) {
            if (err == null) {
                return "";
            }
            return new String(err.readNBytes(4096), StandardCharsets.UTF_8).trim();
        } catch (IOException ignored) {
            return "";
        }
    }

    private String readBoundedUtf8(InputStream input, int maxBytes) throws IOException {
        byte[] bytes = input.readNBytes(maxBytes + 1);
        if (bytes.length > maxBytes) {
            throw new IOException("GitHub response exceeds the configured size limit.");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private String buildHttpErrorMessage(String prefix, String endpoint, int statusCode, String responseBody,
            HttpURLConnection connection) {
        StringBuilder sb = new StringBuilder();
        sb.append(prefix).append(" HTTP ").append(statusCode).append(" on ").append(endpoint);

        String authHeader = connection.getHeaderField("WWW-Authenticate");
        if (authHeader != null && !authHeader.isBlank()) {
            sb.append(" | WWW-Authenticate: ").append(authHeader);
        }

        String remaining = connection.getHeaderField("X-RateLimit-Remaining");
        String reset = connection.getHeaderField("X-RateLimit-Reset");
        if (remaining != null && !remaining.isBlank()) {
            sb.append(" | X-RateLimit-Remaining: ").append(remaining);
        }
        if (reset != null && !reset.isBlank()) {
            sb.append(" | X-RateLimit-Reset: ").append(reset);
        }

        if (responseBody != null && !responseBody.isBlank()) {
            sb.append(" | Response: ").append(responseBody);
        }

        return sb.toString();
    }


    private static final class HttpStatusException extends IOException {
        private final int statusCode;
        private final String rateLimitRemaining;
        private final String rateLimitReset;

        private HttpStatusException(String message, int statusCode, String rateLimitRemaining, String rateLimitReset) {
            super(message);
            this.statusCode = statusCode;
            this.rateLimitRemaining = rateLimitRemaining;
            this.rateLimitReset = rateLimitReset;
        }
    }

    private record ReleaseAsset(String name, String downloadUrl, String digest) {
    }
}
