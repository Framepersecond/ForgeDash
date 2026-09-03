package dash.data;

import dash.FabricConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class BackupManager {

    private final Logger logger = LoggerFactory.getLogger(BackupManager.class);
    private final Path dataFolder;
    private final Path serverRoot;
    private final FabricConfig config;
    private final File backupDir;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ForgeDash-Backup");
        t.setDaemon(true);
        return t;
    });
    private ScheduledFuture<?> scheduledTask;
    private int scheduleHours = 0;

    public BackupManager(Path dataFolder, Path serverRoot, FabricConfig config) {
        this.dataFolder = dataFolder;
        this.serverRoot = serverRoot;
        this.config = config;
        this.backupDir = dataFolder.resolve("backups").toFile();
        if (!backupDir.exists())
            backupDir.mkdirs();

        scheduleHours = config.getInt("backup-schedule-hours", 0);
        if (scheduleHours > 0) {
            startSchedule(scheduleHours);
        }
    }

    public void stop() {
        if (scheduledTask != null) {
            scheduledTask.cancel(false);
        }
        executor.shutdown();
    }

    public void startSchedule(int hours) {
        this.scheduleHours = hours;
        config.set("backup-schedule-hours", hours);
        config.save();

        if (scheduledTask != null) {
            scheduledTask.cancel(false);
        }

        if (hours > 0) {
            scheduledTask = executor.scheduleAtFixedRate(this::createBackup, hours, hours, TimeUnit.HOURS);
        }
    }

    public void stopSchedule() {
        scheduleHours = 0;
        config.set("backup-schedule-hours", 0);
        config.save();

        if (scheduledTask != null) {
            scheduledTask.cancel(false);
            scheduledTask = null;
        }
    }

    public int getScheduleHours() {
        return scheduleHours;
    }

    public CompletableFuture<BackupResult> createBackupAsync() {
        if (executor.isShutdown()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Backup service is stopped."));
        }
        return CompletableFuture.supplyAsync(this::createBackup, executor);
    }

    public synchronized BackupResult createBackup() {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS"));
        String fileName = "backup_" + timestamp + ".zip";
        Path backupRoot = backupDir.toPath().toAbsolutePath().normalize();
        Path archive = backupRoot.resolve(fileName);
        Path partial = backupRoot.resolve(fileName + ".part");
        boolean committed = false;

        try {
            Files.createDirectories(backupRoot);
            String levelName = resolveLevelName(serverRoot.toFile());
            LinkedHashSet<String> toBackup = new LinkedHashSet<>(Arrays.asList(
                    "mods",
                    "config",
                    "server.properties",
                    "ops.json",
                    "whitelist.json",
                    "banned-players.json",
                    "banned-ips.json",
                    "world",
                    "world_nether",
                    "world_the_end"));
            if (levelName != null && !levelName.isBlank()) {
                toBackup.add(levelName);
                toBackup.add(levelName + "_nether");
                toBackup.add(levelName + "_the_end");
            }
            int entriesWritten = writeBackupArchive(serverRoot, backupRoot, partial, toBackup);
            if (entriesWritten == 0) {
                throw new IOException("No backup files were found in server root: " + serverRoot);
            }
            moveAtomically(partial, archive);
            committed = true;
            verifyBackupArchive(archive, entriesWritten);
            int maxBackups = Math.max(1, config.getInt("backups.max-backups", 10));
            cleanOldBackups(maxBackups);
            return new BackupResult(true, fileName, Files.size(archive), null);
        } catch (Exception e) {
            try {
                Files.deleteIfExists(partial);
                if (committed) {
                    Files.deleteIfExists(archive);
                }
            } catch (IOException ignored) {
            }
            logger.warn("Backup creation failed: {}", e.getMessage());
            return new BackupResult(false, null, 0, e.getMessage());
        }
    }

    public static int writeBackupArchive(Path serverRoot, Path backupRoot, Path archive,
            Collection<String> roots) throws IOException {
        Path normalizedServerRoot = serverRoot.toAbsolutePath().normalize();
        Path normalizedBackupRoot = backupRoot.toAbsolutePath().normalize();
        Path normalizedArchive = archive.toAbsolutePath().normalize();
        if (!normalizedArchive.getParent().equals(normalizedBackupRoot)) {
            throw new IOException("Backup archive must be created inside the backup directory.");
        }
        if (Files.isSymbolicLink(normalizedBackupRoot)) {
            throw new IOException("Symbolic-link backup directories are not supported.");
        }
        int written = 0;
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(normalizedArchive,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))) {
            for (String rootName : roots) {
                if (rootName == null || rootName.isBlank()) {
                    continue;
                }
                Path source = normalizedServerRoot.resolve(rootName).normalize();
                if (!source.startsWith(normalizedServerRoot)) {
                    throw new IOException("Backup source escapes the server root.");
                }
                written += addToArchive(source, rootName.replace('\\', '/'), normalizedBackupRoot, output);
            }
        }
        return written;
    }

    private static int addToArchive(Path source, String entryName, Path backupRoot,
            ZipOutputStream output) throws IOException {
        Path normalized = source.toAbsolutePath().normalize();
        if (normalized.startsWith(backupRoot) || Files.isSymbolicLink(normalized)
                || !Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            return 0;
        }
        if (Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
            int written = 0;
            try (var children = Files.list(normalized)) {
                for (Path child : children.sorted().toList()) {
                    written += addToArchive(child, entryName + "/" + child.getFileName(), backupRoot, output);
                }
            }
            return written;
        }
        if (!Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS) || skipBackupFile(normalized)) {
            return 0;
        }
        ZipEntry entry = new ZipEntry(entryName);
        entry.setTime(Files.getLastModifiedTime(normalized, LinkOption.NOFOLLOW_LINKS).toMillis());
        output.putNextEntry(entry);
        Files.copy(normalized, output);
        output.closeEntry();
        return 1;
    }

    private static boolean skipBackupFile(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.equals("session.lock") || name.endsWith(".lock") || name.endsWith(".lck")
                || name.endsWith(".pid") || name.endsWith(".log") || name.endsWith(".db-journal")
                || name.endsWith(".part");
    }

    private static void verifyBackupArchive(Path archive, int expectedEntries) throws IOException {
        int actualEntries = 0;
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(archive.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                entries.nextElement();
                actualEntries++;
            }
        }
        if (actualEntries != expectedEntries) {
            throw new IOException("Backup verification failed.");
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target);
        }
    }

    private void cleanOldBackups(int keep) {
        File[] backups = backupDir.listFiles((dir, name) -> name.startsWith("backup_") && name.endsWith(".zip"));
        if (backups == null || backups.length <= keep)
            return;

        Arrays.sort(backups, Comparator.comparingLong(File::lastModified).reversed());

        for (int i = keep; i < backups.length; i++) {
            backups[i].delete();
        }
    }

    private String resolveLevelName(File serverDir) {
        File propsFile = new File(serverDir, "server.properties");
        if (!propsFile.exists() || !propsFile.isFile()) {
            return "world";
        }
        Properties props = new Properties();
        try (FileInputStream in = new FileInputStream(propsFile)) {
            props.load(in);
            String levelName = props.getProperty("level-name", "world").trim();
            return levelName.isBlank() ? "world" : levelName;
        } catch (Exception ex) {
            return "world";
        }
    }

    public List<BackupInfo> listBackups() {
        List<BackupInfo> list = new ArrayList<>();
        File[] backups = backupDir.listFiles((dir, name) -> name.startsWith("backup_") && name.endsWith(".zip"));
        if (backups == null)
            return list;

        Arrays.sort(backups, Comparator.comparingLong(File::lastModified).reversed());

        for (File backup : backups) {
            list.add(new BackupInfo(
                    backup.getName(),
                    backup.lastModified(),
                    backup.length()));
        }

        return list;
    }

    public File getBackupFile(String name) {
        if (name.contains("..") || name.contains("/") || name.contains("\\")) {
            return null;
        }
        File file = new File(backupDir, name);
        return file.exists() ? file : null;
    }

    public boolean deleteBackup(String name) {
        File file = getBackupFile(name);
        return file != null && file.delete();
    }

    public boolean restoreBackup(String name) {
        File backup = getBackupFile(name);
        if (backup == null || Files.isSymbolicLink(backup.toPath()))
            return false;

        File restoreDir = new File(backupDir, "restore_" + System.currentTimeMillis());
        Path restoreRoot = restoreDir.toPath().toAbsolutePath().normalize();

        try (java.util.zip.ZipFile zipFile = new java.util.zip.ZipFile(backup)) {
            long totalBytes = 0L;
            int entries = 0;
            var zipEntries = zipFile.entries();
            while (zipEntries.hasMoreElements()) {
                var entry = zipEntries.nextElement();
                if (++entries > 500_000) throw new IOException("Backup contains too many entries.");
                Path destinationPath = restoreRoot.resolve(entry.getName()).normalize();
                if (!destinationPath.startsWith(restoreRoot) || entry.getName().indexOf('\0') >= 0) {
                    throw new IOException("Invalid backup entry.");
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(destinationPath);
                    continue;
                }
                if (Files.exists(destinationPath, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Backup contains duplicate entries.");
                Files.createDirectories(destinationPath.getParent());
                long entryBytes = 0L;
                try (InputStream input = zipFile.getInputStream(entry); OutputStream output = Files.newOutputStream(destinationPath)) {
                    byte[] buffer = new byte[16 * 1024];
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        entryBytes += read;
                        totalBytes += read;
                        if (entryBytes > 8L * 1024L * 1024L * 1024L || totalBytes > 64L * 1024L * 1024L * 1024L) throw new IOException("Backup expands beyond the restore limit.");
                        output.write(buffer, 0, read);
                    }
                }
            }
            return true;
        } catch (Exception e) {
            deleteRestoreDirectory(restoreRoot);
            return false;
        }
    }

    private void deleteRestoreDirectory(Path root) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }

    public record BackupInfo(String name, long timestamp, long size) {
        public String getFormattedSize() {
            if (size < 1024)
                return size + " B";
            if (size < 1024 * 1024)
                return String.format("%.1f KB", size / 1024.0);
            if (size < 1024 * 1024 * 1024)
                return String.format("%.1f MB", size / (1024.0 * 1024.0));
            return String.format("%.2f GB", size / (1024.0 * 1024.0 * 1024.0));
        }

        public String getFormattedDate() {
            return LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(timestamp), java.time.ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        }
    }

    public record BackupResult(boolean success, String fileName, long size, String error) {
    }
}
