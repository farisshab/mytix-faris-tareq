package CustomerMenu;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Scanner;

import ops.BookingOps;
import ops.ResaleOps;
import ops.ReviewOps;
import session.Session;
import util.ConsoleUtil;

public class CustomerMenu {
    public static void listMenu(Connection conn, Scanner scanner, Session session) throws SQLException {
        boolean back = false;
        while (!back) {
            printMenu();
            String choice = scanner.nextLine().trim();
            ConsoleUtil.clear();
            switch (choice) {
                case "1" -> BookingOps.bookTickets(conn, scanner, session);
                case "2" -> BookingOps.cancelTickets(conn, scanner, session);
                case "3" -> ResaleOps.listForResale(conn, scanner, session);
                case "4" -> ResaleOps.withdrawListing(conn, scanner, session);
                case "5" -> ResaleOps.buyListing(conn, scanner, session);
                case "6" -> ReviewOps.submitReview(conn, scanner, session);
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
            > """);
    }
}
