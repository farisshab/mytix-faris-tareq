package db;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

// Create a configuration class to allow for easy connection to the database
public class DBConfig {
    private final String url;
    private final String user;
    private final String password;

    public DBConfig(String propertiesPath) throws IOException {
        Properties props = new Properties();
        try (InputStream in = new FileInputStream(propertiesPath)) {
            props.load(in);
        }
        this.url = require(props, "db.url");
        this.user = require(props, "db.user");
        this.password = props.getProperty("db.password", "");
    }

    private static String require(Properties props, String key) {
        String value = props.getProperty(key);
        if (value == null) {
            throw new IllegalStateException("Missing required config key: " + key);
        }
        return value;
    }

    public String getUrl() {
        return url;
    }

    public String getUser() {
        return user;
    }

    public String getPassword() {
        return password;
    }
}
