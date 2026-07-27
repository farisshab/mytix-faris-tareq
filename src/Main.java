import db.DBConfig;
import db.DBConnection;
import menu.MenuController;

import java.sql.Connection;

// This acts as an entry point. It will load the DB config, opens one connection for the session, and closes it on exit
public class Main {
    public static void main(String[] args) {
        String configPath = "config.properties";
        if (args.length > 0) {
            configPath = args[0];
        }

        try {
            DBConfig config = new DBConfig(configPath);
            try (Connection conn = DBConnection.connect(config)) {
                System.out.println("Connected to MyTix database,");
                new MenuController(conn).run();
            }
        } catch (Exception e) {
            System.err.println("Fatal error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}