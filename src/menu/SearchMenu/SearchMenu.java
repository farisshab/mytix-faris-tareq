package SearchMenu;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Scanner;

import util.ConsoleUtil;

public class SearchMenu {
    public static void listMenu(Connection conn, Scanner scanner) throws SQLException {
        boolean back = false;
        while (!back) {
            printMenu();
            String choice = scanner.nextLine().trim();
            ConsoleUtil.clear();
            switch (choice) {
                case "1", "2", "3", "4", "5", "6", "7" -> System.out.println("TODO: Q" + choice);
                case "8" -> listVenues(conn);
                case "0" -> back = true;
                default -> System.out.println("Unrecognized option.");
            }
        }
    }

    private static void printMenu() {
        System.out.print("""
                
            === Search ===
            1) Q1
            2) Q2
            3) Q3
            4) Q4
            5) Q5
            6) Q6
            7) Q7
            8) List venues (temporary)
            0) Go Back
            =======================
            >\s""");
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
