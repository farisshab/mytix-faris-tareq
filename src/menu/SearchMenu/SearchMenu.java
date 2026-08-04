package SearchMenu;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Scanner;

import ops.SearchOps;
import util.ConsoleUtil;

public class SearchMenu {
    public static void listMenu(Connection conn, Scanner scanner) throws SQLException {
        boolean back = false;
        while (!back) {
            printMenu();
            String choice = scanner.nextLine().trim();
            ConsoleUtil.clear();
            switch (choice) {
                case "1", "2", "3" -> System.out.println("TODO: Q" + choice);
                case "4" -> SearchOps.q4(conn, scanner);
                case "5" -> SearchOps.q5(conn, scanner);
                case "6" -> SearchOps.q6(conn, scanner);
                case "7" -> SearchOps.q7(conn, scanner);
                case "0" -> back = true;
                default -> System.out.println("Unrecognized option.");
            }
        }
    }

    private static void printMenu() {
        System.out.print("""
                
            === Search ===
            1) Performances near a location (Q1)
            2) Search by postal code (Q2)
            3) Search by exact address (Q3)
            4) By date range & availability (Q4)
            5) Advanced filters (Q5)
            6) Seat map for a performance (Q6)
            7) Best available seats (Q7)
            0) Go Back
            =======================
            > """);
    }

    public static void listVenues(Connection conn) throws SQLException {
        String sql = "SELECT venue_id, name, city, country FROM venue ORDER BY name";

        try (PreparedStatement stmt = conn.prepareStatement(sql); ResultSet rs = stmt.executeQuery()) {
            System.out.println("\n--- Venues ---");
            boolean any = false;
            while (rs.next()) {
                any = true;
                System.out.printf("[%d] %s - %s, %s%n",
                    rs.getInt("venue_id"),
                    rs.getString("name"),
                rs.getString("city"),
                rs.getString("country"));
            }
            if (!any) {
                System.out.println("(no venues yet - load sample data first.");
            }
        }
    }
}
