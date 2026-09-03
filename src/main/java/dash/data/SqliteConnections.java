package dash.data;

import org.sqlite.JDBC;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.util.Properties;

final class SqliteConnections {
    private static final Driver DRIVER = new JDBC();

    private SqliteConnections() {
    }

    static Connection open(Path dataFolder, String dbName) throws SQLException {
        try {
            Files.createDirectories(dataFolder);
        } catch (IOException e) {
            throw new SQLException("Could not create data directory " + dataFolder, e);
        }

        Connection connection = DRIVER.connect("jdbc:sqlite:" + dataFolder.resolve(dbName).toAbsolutePath(), new Properties());
        if (connection == null) {
            throw new SQLException("SQLite JDBC driver did not accept database URL");
        }
        return connection;
    }
}
