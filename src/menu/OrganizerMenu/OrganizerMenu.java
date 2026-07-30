package OrganizerMenu;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Scanner;

import util.ConsoleUtil;

public class OrganizerMenu {
        public static void listMenu(Connection conn, Scanner scanner) throws SQLException {
            boolean back = false;
            while (!back) {
                printMenu();
                String choice = scanner.nextLine().trim();
                ConsoleUtil.clear();
                switch (choice) {
                    case "1" -> System.out.println("TODO: Create Event");
                    case "2" -> System.out.println("TODO: Add Performance to Event");
                    case "3" -> System.out.println("TODO: Define Price Tiers");
                    case "4" -> System.out.println("TODO: Assign Sections to Tiers");
                    case "5" -> System.out.println("TODO: Set Resale Cap");
                    case "6" -> System.out.println("TODO: Update Tier Price");
                    case "7" -> System.out.println("TODO: Block Seat");
                    case "8" -> System.out.println("TODO: Unblock Seat");
                    case "9" -> System.out.println("TODO: Cancel a Performance");
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
