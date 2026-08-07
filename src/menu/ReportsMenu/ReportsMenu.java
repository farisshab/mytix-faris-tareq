package ReportsMenu;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Scanner;

import ops.ReportsOps;
import util.ConsoleUtil;

public class ReportsMenu {
    public static void listMenu(Connection conn, Scanner scanner) throws SQLException {
        boolean back = false;
        while (!back) {
            printMenu();
            String choice = scanner.nextLine().trim();
            ConsoleUtil.clear();
            switch (choice) {
                case "1" -> ReportsOps.r1(conn, scanner);
                case "2" -> ReportsOps.r2(conn, scanner);
                case "3" -> ReportsOps.r3(conn, scanner);
                case "4" -> ReportsOps.r4(conn, scanner);
                case "5" -> ReportsOps.r5(conn, scanner);
                case "6" -> ReportsOps.r6(conn, scanner);
                case "7" -> ReportsOps.r7(conn, scanner);
                case "8" -> ReportsOps.r8(conn, scanner);
                case "9" -> System.out.println("TODO: R" + choice);
                case "0" -> back = true;
                default -> System.out.println("Unrecognized option.");
            }
        }
    }

    private static void printMenu() {
        System.out.print("""

            === Reports ===
            1) Tickets sold & revenue by city (R1)
            2) Event & performance counts (R2)
            3) Organizer revenue ranking (R3)
            4) Possible scalpers by city (R4)
            5) Customer order ranking (R5)
            6) Most cancellations (R6)
            7) Sell-through (R7)
            8) Resale report (R8)
            9) Event noun phrases (R9)
            0) Go Back
            =======================
            > """);
    }
}
