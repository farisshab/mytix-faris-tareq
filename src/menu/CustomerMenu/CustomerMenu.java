package CustomerMenu;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Scanner;

import util.ConsoleUtil;

public class CustomerMenu {
    public static void listMenu(Connection conn, Scanner scanner) throws SQLException {
        boolean back = false;
        while (!back) {
            printMenu();
            String choice = scanner.nextLine().trim();
            ConsoleUtil.clear();
            switch (choice) {
                case "1" -> System.out.println("TODO: Book Tickets");
                case "2" -> System.out.println("TODO: Cancel Tickets");
                case "3" -> System.out.println("TODO: List a Ticket for Resale");
                case "4" -> System.out.println("TODO: Withdraw a Listing");
                case "5" -> System.out.println("TODO: Buy a Listing");
                case "6" -> System.out.println("TODO: Submit a Review");
                case "0" -> back = true;
                default -> System.out.println("Unrecognized option.");
            }
        }
    }

    private static void printMenu() {
        System.out.print("""
            
            === Customer Operations ===
            1) Book Tickets
            2) Cancel Tickets
            3) List a Ticket for Resale
            4) Withdraw a Listing
            5) Buy a Listing
            6) Submit a Review
            0) Go Back
            =======================
            >\s""");
    }
}
