package dash.data;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.sql.*;

public class GlobalSettingsManager {

    private static final Logger logger = LoggerFactory.getLogger(GlobalSettingsManager.class);
    private final Path dataFolder;
    private Connection connection;

    public GlobalSettingsManager(Path dataFolder) {
        this.dataFolder = dataFolder;
        initDatabase();
    }

    private void initDatabase() {
        try {
            connection = SqliteConnections.open(dataFolder, "globaldata.db");

            try (Statement stmt = connection.createStatement()) {
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS global_settings (
                        key TEXT PRIMARY KEY,
                        value TEXT NOT NULL
                    )
                """);
            }
        } catch (SQLException e) {
            logger.error("GlobalSettings DB init failed: {}", e.getMessage());
        }
    }

    public String getGlobalSetting(String key, String defaultValue) {
        try (PreparedStatement stmt = connection.prepareStatement(
                "SELECT value FROM global_settings WHERE key = ?")) {
            stmt.setString(1, key);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("value");
                }
            }
        } catch (SQLException e) {
            logger.warn("Failed to get global setting '{}': {}", key, e.getMessage());
        }
        return defaultValue;
    }

    public void setGlobalSetting(String key, String value) {
        try (PreparedStatement stmt = connection.prepareStatement(
                "INSERT OR REPLACE INTO global_settings (key, value) VALUES (?, ?)")) {
            stmt.setString(1, key);
            stmt.setString(2, value);
            stmt.executeUpdate();
        } catch (SQLException e) {
            logger.warn("Failed to set global setting '{}': {}", key, e.getMessage());
        }
    }

    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException ignored) {
        }
    }
}
