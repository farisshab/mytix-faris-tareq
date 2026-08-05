package OrganizerToolkitMenu;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Scanner;

import ops.ToolkitOps;
import util.ConsoleUtil;

public class OrganizerToolkitMenu {
    public static void listMenu(Connection conn, Scanner scanner) throws SQLException {
        boolean back = false;
        while (!back) {
            printMenu();
            String choice = scanner.nextLine().trim();
            ConsoleUtil.clear();
            switch (choice) {
                case "1" -> ToolkitOps.suggest(conn, scanner);
                case "0" -> back = true;
                default -> System.out.println("Unrecognized option.");
            }
        }
    }

    private static void printMenu() {
        System.out.print("""

            === Organizer Toolkit ===
            1) Suggest pricing & tier structure for a new performance
            0) Go Back
            =======================
            > """);
    }
}
