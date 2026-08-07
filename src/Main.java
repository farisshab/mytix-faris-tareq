import db.DBConfig;
import db.DBConnection;
import java.sql.Connection;
import java.util.Scanner;

import ops.AuthOps;
import session.Session;
import util.ConsoleUtil;

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

                // One Scanner for the whole session, shared by auth and every menu.
                // Creating a second Scanner on System.in would lose buffered input,
                // which breaks piped/redirected input the moment control changes hands.
                Scanner scanner = new Scanner(System.in);
                boolean continueToSignIn = true;
                while (continueToSignIn) {
                    ConsoleUtil.clear();
                    Session session = AuthOps.authenticate(conn, scanner);

                    // HomeMenu returns true only when the signed-in user just deleted their own account
                    // In that case, we loop back to the sign-in screen
                    continueToSignIn = HomeMenu.listMenu(conn, scanner, session);
                }
            }
        } catch (java.util.NoSuchElementException e) {
            // stdin reached end-of-input (piped or redirected input ran out); exit cleanly
            System.out.println("\nEnd of input, exiting. Goodbye.");
        } catch (Exception e) {
            System.err.println("Fatal error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}