package ReportsMenu;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Scanner;

import util.ConsoleUtil;

public class ReportsMenu {
    public static void listMenu(Connection conn, Scanner scanner) throws SQLException {
        boolean back = false;
        while (!back) {
            printMenu();
            String choice = scanner.nextLine().trim();
            ConsoleUtil.clear();
            switch (choice) {
                case "1", "2", "3", "4", "5", "6", "7", "8", "9" ->
                    System.out.println("TODO: R" + choice);
                case "0" -> back = true;
                default -> System.out.println("Unrecognized option.");
            }
        }
    }

    private static void printMenu() {
        System.out.print("""

            === Reports ===
            1) R1
            2) R2
            3) R3
            4) R4
            5) R5
            6) R6
            7) R7
            8) R8
            9) R9
            0) Go Back
            =======================
            > """);
    }
}
