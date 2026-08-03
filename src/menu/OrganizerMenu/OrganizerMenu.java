package OrganizerMenu;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Scanner;

import ops.OrganizerOps;
import session.Session;
import util.ConsoleUtil;

public class OrganizerMenu {
        public static void listMenu(Connection conn, Scanner scanner, Session session) throws SQLException {
            boolean back = false;
            while (!back) {
                printMenu();
                String choice = scanner.nextLine().trim();
                ConsoleUtil.clear();
                switch (choice) {
                    case "1" -> OrganizerOps.createEvent(conn, scanner, session);
                    case "2" -> OrganizerOps.addPerformance(conn, scanner, session);
                    case "3" -> OrganizerOps.definePriceTiers(conn, scanner, session);
                    case "4" -> OrganizerOps.assignSectionsToTiers(conn, scanner, session);
                    case "5" -> OrganizerOps.setResaleCap(conn, scanner, session);
                    case "6" -> OrganizerOps.updateTierPrice(conn, scanner, session);
                    case "7" -> OrganizerOps.blockSeat(conn, scanner, session);
                    case "8" -> OrganizerOps.unblockSeat(conn, scanner, session);
                    case "9" -> OrganizerOps.cancelPerformance(conn, scanner, session);
                    case "0" -> back = true;
                    default -> System.out.println("Unrecognized option.");
                }
            }
    }

    private static void printMenu() {
        System.out.print("""

            === Organizer Operations ===
            1) Create Event
            2) Add Performance to Event
            3) Define Price Tiers
            4) Assign Sections to Tiers
            5) Set Resale Cap
            6) Update Tier Price
            7) Block Seat
            8) Unblock Seat
            9) Cancel a Performance
            0) Go Back
            =======================
            > """);
    }
}
