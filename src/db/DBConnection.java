package db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

// Utilize DBConfig.java to connect to the database
public class DBConnection {
    public static Connection connect(DBConfig config) throws SQLException {
        return DriverManager.getConnection(config.getUrl(), config.getUser(), config.getPassword());
    }
}
