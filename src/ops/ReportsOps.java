package ops;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;

import util.InputUtil;

// Reports R4, R7, R8. SQL reference and the assumptions behind each one
// are in sql/reports.sql and docs/report_design.md. A rule that runs through all of
// them: "sold" and revenue count active tickets only, since a cancelled ticket was
// refunded and its seat is back in the pool.

public class ReportsOps {

    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    // R1: Total no. of tickets sold and gross revnue in a specific date range by city or by venue
    public static void r1(Connection conn, Scanner scanner) throws SQLException {
        System.out.print("""
                
            === R1 Revenue ===
            1) By city
            2) By venue within a city
            > """);
        String choice = scanner.nextLine().trim();
        switch (choice) {
            case "1" -> r1ByCity(conn, scanner);
            case "2" -> r1ByVenue(conn, scanner);
            default -> System.out.println("Unrecognized option.");
        }
    }

    private static void r1ByCity(Connection conn, Scanner scanner) throws SQLException {
        LocalDateTime from = promptDateTime(scanner, "From date (yyyy-MM-dd HH:mm) > ");
        if (from == null) {
            return;
        }
        LocalDateTime to = promptDateTime(scanner, "To date (yyyy-MM-dd HH:mm) > ");
        if (to == null) {
            return;
        }

        String sql =
            "SELECT v.city, COUNT(*) AS tickets_sold, SUM(t.face_value) AS gross_revenue " +
            "FROM ticket t " +
            "JOIN orders o ON o.order_id=t.order_id " +
            "JOIN performance p ON p.performance_id=o.performance_id " +
            "JOIN venue v ON v.venue_id=p.venue_id " +
            "WHERE t.status='ACTIVE' AND o.order_datetime >= ? AND o.order_datetime < ? " +
            "GROUP BY v.city ORDER BY gross_revenue DESC";
        
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setObject(1, from);
                stmt.setObject(2, to);
                try (ResultSet rs = stmt.executeQuery()) {
                    System.out.println("\n--- Tickets sold & revenue by city ---");
                    boolean any = false;
                    while (rs.next()) {
                        any = true;
                        System.out.printf("%-18s %5d tickets   $%.2f%n",
                            rs.getString("city"),
                            rs.getInt("tickets_sold"),
                            rs.getBigDecimal("gross_revenue")
                        );
                    }
                    if (!any) {
                        System.out.println("(no active tickets sold in that range)");
                    }
                }
            }
    }

    private static void r1ByVenue(Connection conn, Scanner scanner) throws SQLException {
        System.out.print("City > ");
        String city = scanner.nextLine().trim();
        LocalDateTime from = promptDateTime(scanner, "From date (yyyy-MM-dd HH:mm) > ");
        if (from == null) {
            return;
        }
        LocalDateTime to = promptDateTime(scanner, "To date (yyyy-MM-dd HH:mm) > ");
        if (to == null) {
            return;
        }

        String sql =
            "SELECT v.venue_id, v.name, COUNT(*) AS tickets_sold, SUM(t.face_value) AS gross_revenue " +
            "FROM ticket t " +
            "JOIN orders o ON o.order_id=t.order_id " +
            "JOIN performance p ON p.performance_id=o.performance_id " +
            "JOIN venue v ON v.venue_id=p.venue_id " +
            "WHERE t.status='ACTIVE' AND v.city=? AND o.order_datetime >= ? AND o.order_datetime < ? " +
            "GROUP BY v.venue_id, v.name ORDER BY gross_revenue DESC";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, city);
            stmt.setObject(2, from);
            stmt.setObject(3, to);
            try (ResultSet rs = stmt.executeQuery()) {
                System.out.println("\n--- Tickets sold & revenue by venue in " + city + " ---");
                boolean any = false;
                while (rs.next()) {
                    any = true;
                    System.out.printf("[%d] %-28s %5d tickets   $%.2f%n",
                        rs.getInt("venue_id"),
                        rs.getString("name"),
                        rs.getInt("tickets_sold"),
                        rs.getBigDecimal("gross_revenue")
                    );
                }
                if (!any) {
                    System.out.println("(no active tickets sold there in that range)");
                }
            }
        }
    }

    // R2: Total no. of events and performances per segment and genre, per country, per country and city, as well as per country, city, and venue
    public static void r2(Connection conn, Scanner scanner) throws SQLException {
        System.out.print("""
                
            === R2 Event/performance counts ===
            1) Per segment & genre
            2) Per country
            3) Per country & city
            4) Per country, city, & venue
            > """);
        
        String choice = scanner.nextLine().trim();
        switch (choice) {
            case "1" -> r2BySegmentGenre(conn);
            case "2" -> r2ByCountry(conn);
            case "3" -> r2ByCountryCity(conn);
            case "4" -> r2ByCountryCityVenue(conn);
            default -> System.out.println("Unrecognized option.");
        }
    }

    private static void r2BySegmentGenre(Connection conn) throws SQLException {
        String sql =
            "SELECT sg.segment_name, g.genre_name, COUNT(DISTINCT e.event_id) AS events, COUNT(p.performance_id) AS performances " +
            "FROM event e " +
            "JOIN genre g ON g.genre_id=e.genre_id " +
            "JOIN segment sg ON sg.segment_id=g.segment_id " +
            "LEFT JOIN performance p ON p.event_id=e.event_id " +
            "GROUP BY sg.segment_name, g.genre_name ORDER BY sg.segment_name, g.genre_name";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            try (ResultSet rs = stmt.executeQuery()) {
                System.out.println("\n--- Events & performances per segment/genre ---");
                boolean any = false;
                while (rs.next()) {
                    any = true;
                    System.out.printf("%-12s %-18s %4d events   %5d performances%n",
                        rs.getString("segment_name"),
                        rs.getString("genre_name"),
                        rs.getInt("events"),
                        rs.getInt("performances")
                    );
                }
                if (!any) {
                    System.out.println("(no events found)");
                }
            }
        }
    }

    private static void r2ByCountry(Connection conn) throws SQLException {
        String sql =
            "SELECT v.country, COUNT(DISTINCT e.event_id) AS events, COUNT(p.performance_id) AS performances " +
            "FROM performance p JOIN venue v ON v.venue_id=p.venue_id JOIN event e ON e.event_id=p.event_id " +
            "GROUP BY v.country ORDER BY v.country";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            try (ResultSet rs = stmt.executeQuery()) {
                System.out.println("\n--- Events & performances per country ---");
                boolean any = false;
                while (rs.next()) {
                    any = true;
                    System.out.printf("%-10s %4d events   %5d performances%n",
                        rs.getString("country"),
                        rs.getInt("events"),
                        rs.getInt("performances")
                    );
                }
                if (!any) {
                    System.out.println("(no performances found)");
                }
            }
        }
    }

    private static void r2ByCountryCity(Connection conn) throws SQLException {
        String sql =
            "SELECT v.country, v.city, COUNT(DISTINCT e.event_id) AS events, COUNT(p.performance_id) AS performances " +
            "FROM performance p JOIN venue v ON v.venue_id=p.venue_id JOIN event e ON e.event_id=p.event_id " +
            "GROUP BY v.country, v.city ORDER BY v.country, v.city";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            try (ResultSet rs = stmt.executeQuery()) {
                System.out.println("\n--- Events & performances per country/city ---");
                boolean any = false;
                while (rs.next()) {
                    any = true;
                    System.out.printf("%-10s %-18s %4d events   %5d performances%n",
                        rs.getString("country"),
                        rs.getString("city"),
                        rs.getInt("events"),
                        rs.getInt("performances")
                    );
                }
                if (!any) {
                    System.out.println("(no performances found)");
                }
            }
        }
    }

    private static void r2ByCountryCityVenue(Connection conn) throws SQLException {
        String sql =
            "SELECT v.country, v.city, v.venue_id, v.name, COUNT(DISTINCT e.event_id) AS events, COUNT(p.performance_id) AS performances " +
            "FROM performance p JOIN venue v ON v.venue_id=p.venue_id JOIN event e ON e.event_id=p.event_id " +
            "GROUP BY v.country, v.city, v.venue_id, v.name ORDER BY v.country, v.city, v.name";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            try (ResultSet rs = stmt.executeQuery()) {
                System.out.println("\n--- Events & performances per country/city/venue ---");
                boolean any = false;
                while (rs.next()) {
                    any = true;
                    System.out.printf("%-10s %-16s [%d] %-24s %4d events   %5d performances%n",
                        rs.getString("country"),
                        rs.getString("city"),
                        rs.getInt("venue_id"),
                        rs.getString("name"),
                        rs.getInt("events"),
                        rs.getInt("performances")
                    );
                }
                if (!any) {
                    System.out.println("(no performances found)");
                }
            }
        }
    }

    // R3: Rank organizers by gross revenue overall and per country (and refine it by city)
    public static void r3(Connection conn, Scanner scanner) throws SQLException {
        System.out.print("""
                
            === R3 Organizer revenue ranking ===
            1) Overall
            2) Per country
            3) One city
            > """);
        
        String choice = scanner.nextLine().trim();
        switch (choice) {
            case "1" -> r3Overall(conn);
            case "2" -> r3PerCountry(conn);
            case "3" -> r3ByCity(conn, scanner);
            default -> System.out.println("Unrecognized option.");
        }
    }

    private static void r3Overall(Connection conn) throws SQLException {
        String sql =
            "SELECT u.user_id AS organizer_id, u.full_name, SUM(t.face_value) AS gross_revenue, " +
            "       DENSE_RANK() OVER (ORDER BY SUM(t.face_value) DESC) AS rnk " +
            "FROM ticket t " +
            "JOIN orders o ON o.order_id=t.order_id " +
            "JOIN performance p ON p.performance_id=o.performance_id " +
            "JOIN event e ON e.event_id=p.event_id " +
            "JOIN users u ON u.user_id=e.organizer_id " +
            "WHERE t.status='ACTIVE' " +
            "GROUP BY u.user_id, u.full_name ORDER BY rnk, organizer_id";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            try (ResultSet rs = stmt.executeQuery()) {
                System.out.println("\n--- Organizers by gross revenue ---");
                boolean any = false;
                while (rs.next()) {
                    any = true;
                    System.out.printf("%2d. [%d] %-24s $%,.2f%n",
                        rs.getInt("rnk"),
                        rs.getInt("organizer_id"),
                        rs.getString("full_name"),
                        rs.getBigDecimal("gross_revenue")
                    );
                }
                if (!any) {
                    System.out.println("(no revenue yet)");
                }
            }
        }
    }

    private static void r3PerCountry(Connection conn) throws SQLException {
        String sql =
            "SELECT v.country, u.user_id AS organizer_id, u.full_name, SUM(t.face_value) AS gross_revenue, " +
            "       DENSE_RANK() OVER (PARTITION BY v.country ORDER BY SUM(t.face_value) DESC) AS rnk " +
            "FROM ticket t " +
            "JOIN orders o ON o.order_id=t.order_id " +
            "JOIN performance p ON p.performance_id=o.performance_id " +
            "JOIN venue v ON v.venue_id=p.venue_id " +
            "JOIN event e ON e.event_id=p.event_id " +
            "JOIN users u ON u.user_id=e.organizer_id " +
            "WHERE t.status='ACTIVE' " +
            "GROUP BY v.country, u.user_id, u.full_name ORDER BY v.country, rnk, organizer_id";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            try (ResultSet rs = stmt.executeQuery()) {
                System.out.println("\n--- Organizers by gross revenue, per country ---");
                boolean any = false;
                while (rs.next()) {
                    any = true;
                    System.out.printf("%-10s %2d. [%d] %-24s $%,.2f%n",
                        rs.getString("country"),
                        rs.getInt("rnk"),
                        rs.getInt("organizer_id"),
                        rs.getString("full_name"),
                        rs.getBigDecimal("gross_revenue")
                    );
                }
                if (!any) {
                    System.out.println("(no revenue yet)");
                }
            }
        }
    }

    private static void r3ByCity(Connection conn, Scanner scanner) throws SQLException {
        System.out.print("City > ");
        String city = scanner.nextLine().trim();
        String sql =
            "SELECT u.user_id AS organizer_id, u.full_name, SUM(t.face_value) AS gross_revenue, " +
            "       DENSE_RANK() OVER (ORDER BY SUM(t.face_value) DESC) AS rnk " +
            "FROM ticket t " +
            "JOIN orders o ON o.order_id=t.order_id " +
            "JOIN performance p ON p.performance_id=o.performance_id " +
            "JOIN venue v ON v.venue_id=p.venue_id " +
            "JOIN event e ON e.event_id=p.event_id " +
            "JOIN users u ON u.user_id=e.organizer_id " +
            "WHERE t.status='ACTIVE' AND v.city=? " +
            "GROUP BY u.user_id, u.full_name ORDER BY rnk, organizer_id";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, city);
            try (ResultSet rs = stmt.executeQuery()) {
                System.out.println("\n--- Organizers by gross revenue in " + city + " ---");
                boolean any = false;
                while (rs.next()) {
                    any = true;
                    System.out.printf("%2d. [%d] %-24s $%,.2f%n",
                        rs.getInt("rnk"),
                        rs.getInt("organizer_id"),
                        rs.getString("full_name"),
                        rs.getBigDecimal("gross_revenue")
                    );
                }
                if (!any) {
                    System.out.println("(no revenue yet)");
                }
            }
        }
    }

    // R4: scalper flag (global thresholds, reported per city)
    public static void r4(Connection conn, Scanner scanner) throws SQLException {
        String sql =
            "WITH bought AS (" +
            "  SELECT o.customer_id, v.city, t.ticket_id, " +
            "         EXISTS (SELECT 1 FROM listing l WHERE l.ticket_id=t.ticket_id AND l.seller_id=o.customer_id) AS listed " +
            "  FROM ticket t " +
            "  JOIN orders o ON o.order_id=t.order_id " +
            "  JOIN performance p ON p.performance_id=o.performance_id " +
            "  JOIN venue v ON v.venue_id=p.venue_id " +
            "  WHERE o.order_datetime >= NOW() - INTERVAL 1 YEAR), " +
            "scalper AS (" +
            "  SELECT customer_id, COUNT(*) AS bought_total, SUM(listed) AS listed_total " +
            "  FROM bought GROUP BY customer_id " +
            "  HAVING bought_total >= 10 AND listed_total > bought_total/2) " +
            "SELECT b.city, b.customer_id, u.full_name, s.bought_total, s.listed_total, " +
            "       COUNT(*) AS bought_in_city, SUM(b.listed) AS listed_in_city " +
            "FROM bought b JOIN scalper s ON s.customer_id=b.customer_id JOIN users u ON u.user_id=b.customer_id " +
            "GROUP BY b.city, b.customer_id, u.full_name, s.bought_total, s.listed_total " +
            "ORDER BY b.city, s.listed_total DESC, b.customer_id";
        try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            System.out.println("\n--- R4: Possible scalpers, by city ---");
            boolean any = false;
            while (rs.next()) {
                any = true;
                System.out.printf("%-12s [%d] %-22s bought %d / listed %d overall  (here: %d bought, %d listed)%n",
                    rs.getString("city"), rs.getInt("customer_id"), rs.getString("full_name"),
                    rs.getInt("bought_total"), rs.getInt("listed_total"),
                    rs.getInt("bought_in_city"), rs.getInt("listed_in_city"));
            }
            if (!any) System.out.println("(no customers cross the scalper thresholds)");
        }
    }

    // R5: Rank customers by no. of orders in specific time period, and rank by no. of orders per city (for customers who have placed 2+ orders in the year)
    public static void r5(Connection conn, Scanner scanner) throws SQLException {
        System.out.print("""
                
            === R5 Customer order ranking ===
            1) Overall, in a date range
            2) Per city, past year, customers with >= 2 orders
            > """);
        
        String choice = scanner.nextLine().trim();
        switch (choice) {
            case "1" -> r5Overall(conn, scanner);
            case "2" -> r5PerCity(conn);
            default -> System.out.println("Unrecognized option.");
        }
    }

    private static void r5Overall(Connection conn, Scanner scanner) throws SQLException {
        LocalDateTime from = promptDateTime(scanner, "From date (yyyy-MM-dd HH:mm) > ");
        if (from == null) {
            return;
        }
        LocalDateTime to = promptDateTime(scanner, "To date (yyyy-MM-dd HH:mm) > ");
        if (to == null) {
            return;
        }

        String sql =
            "SELECT o.customer_id, u.full_name, COUNT(*) AS order_count, " +
            "       DENSE_RANK() OVER (ORDER BY COUNT(*) DESC) AS rnk " +
            "FROM orders o JOIN users u ON u.user_id=o.customer_id " +
            "WHERE o.order_datetime >= ? AND o.order_datetime < ? " +
            "GROUP BY o.customer_id, u.full_name ORDER BY rnk, o.customer_id";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, from);
            stmt.setObject(2, to);
            try (ResultSet rs = stmt.executeQuery()) {
                System.out.println("\n--- Customers by order count ---");
                boolean any = false;
                while (rs.next()) {
                    any = true;
                    System.out.printf("%2d. [%d] %-24s %d orders%n",
                        rs.getInt("rnk"),
                        rs.getInt("customer_id"),
                        rs.getString("full_name"),
                        rs.getInt("order_count")
                    );
                }
                if (!any) {
                    System.out.println("(no orders in that range)");
                }
            }
        }
    }

    private static void r5PerCity(Connection conn) throws SQLException {
        String sql =
            "SELECT v.city, o.customer_id, u.full_name, COUNT(*) AS order_count, " +
            "       DENSE_RANK() OVER (PARTITION BY v.city ORDER BY COUNT(*) DESC) AS rnk " +
            "FROM orders o " +
            "JOIN performance p ON p.performance_id=o.performance_id " +
            "JOIN venue v ON v.venue_id=p.venue_id " +
            "JOIN users u ON u.user_id=o.customer_id " +
            "WHERE o.order_datetime >= NOW() - INTERVAL 1 YEAR " +
            "GROUP BY v.city, o.customer_id, u.full_name " +
            "HAVING COUNT(*) >= 2 " +
            "ORDER BY v.city, rnk, o.customer_id";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            try (ResultSet rs = stmt.executeQuery()) {
                System.out.println("\n--- Customers by order count, per city (past year, >=2 orders) ---");
                boolean any = false;
                while (rs.next()) {
                    any = true;
                    System.out.printf("%-14s %2d. [%d] %-24s %d orders%n",
                        rs.getString("city"),
                        rs.getInt("rnk"),
                        rs.getInt("customer_id"),
                        rs.getString("full_name"),
                        rs.getInt("order_count")
                    );
                }
                if (!any) {
                    System.out.println("(no customers meet the threshold)");
                }
            }
        }
    }

    // R6: Report the customers with the largest number of cancelled tickets and the organizers with the largest number of cancelled performances within a year
    public static void r6(Connection conn, Scanner scanner) throws SQLException {
        System.out.print("""
                
            === R6 Cancellations (past year) ===
            1) Customers with most cancelled tickets
            2) Organizers with most cancelled performances
            > """);
        String choice = scanner.nextLine().trim();
        switch (choice) {
            case "1" -> r6Customers(conn);
            case "2" -> r6Organizers(conn);
            default -> System.out.println("Unrecognized option.");
        }
    }

    private static void r6Customers(Connection conn) throws SQLException {
        // Follows the CURRENT owner, not the original purchaser
        String sql =
            "SELECT tow.owner_id AS customer_id, u.full_name, COUNT(*) AS cancelled_count, " +
            "       DENSE_RANK() OVER (ORDER BY COUNT(*) DESC) AS rnk " +
            "FROM ticket t " +
            "JOIN ticket_ownership tow ON tow.ticket_id=t.ticket_id " +
            "JOIN users u ON u.user_id=tow.owner_id " +
            "WHERE t.status='CANCELLED' AND t.cancel_type='CUSTOMER' " +
            "  AND t.cancelled_at >= NOW() - INTERVAL 1 YEAR " +
            "  AND tow.acquired_at = (SELECT MAX(tow2.acquired_at) FROM ticket_ownership tow2 WHERE tow2.ticket_id=t.ticket_id) " +
            "GROUP BY tow.owner_id, u.full_name ORDER BY rnk, customer_id";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            try (ResultSet rs = stmt.executeQuery()) {
                System.out.println("\n--- Customers with most cancelled tickets (past year) ---");
                boolean any = false;
                while (rs.next()) {
                    any = true;
                    System.out.printf("%2d. [%d] %-24s %d cancelled%n",
                        rs.getInt("rnk"),
                        rs.getInt("customer_id"),
                        rs.getString("full_name"),
                        rs.getInt("cancelled_count")
                    );
                }
                if (!any) {
                    System.out.println("(no customer cancellations in the past year)");
                }
            }
        }
    }

    private static void r6Organizers(Connection conn) throws SQLException {
        String sql =
            "SELECT e.organizer_id, u.full_name, COUNT(*) AS cancelled_count, " +
            "       DENSE_RANK() OVER (ORDER BY COUNT(*) DESC) AS rnk " +
            "FROM performance p " +
            "JOIN event e ON e.event_id=p.event_id " +
            "JOIN users u ON u.user_id=e.organizer_id " +
            "WHERE p.status='CANCELLED' AND p.cancelled_at >= NOW() - INTERVAL 1 YEAR " +
            "GROUP BY e.organizer_id, u.full_name ORDER BY rnk, organizer_id";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            try (ResultSet rs = stmt.executeQuery()) {
                System.out.println("\n--- Organizers with most cancelled performances (past year) ---");
                boolean any = false;
                while (rs.next()) {
                    any = true;
                    System.out.printf("%2d. [%d] %-24s %d cancelled%n",
                        rs.getInt("rnk"),
                        rs.getInt("organizer_id"),
                        rs.getString("full_name"),
                        rs.getInt("cancelled_count")
                    );
                }
                if (!any) {
                    System.out.println("(no performance cancellations in the past year)");
                }
            }
        }
    }

    // The CTE bundle R7 sits on: sellable and sold per (performance, section, tier).
    private static final String SECTION_LEVEL =
        "WITH assigned AS (" +
        "  SELECT pst.performance_id, pst.section_id, pst.tier_id, s.section_type, s.ga_capacity " +
        "  FROM performance_section_tier pst JOIN section s ON s.section_id=pst.section_id), " +
        "sec_seats AS (" +
        "  SELECT sr.section_id, COUNT(*) AS seats FROM seat_row sr JOIN seat st ON st.row_id=sr.row_id GROUP BY sr.section_id), " +
        "res_holds AS (" +
        "  SELECT sh.performance_id, sr.section_id, SUM(sh.hold_type='SOLD') AS sold, SUM(sh.hold_type='BLOCKED') AS blocked " +
        "  FROM seat_hold sh JOIN seat st ON st.seat_id=sh.seat_id JOIN seat_row sr ON sr.row_id=st.row_id " +
        "  GROUP BY sh.performance_id, sr.section_id), " +
        "ga_sold AS (" +
        "  SELECT o.performance_id, t.ga_section_id AS section_id, COUNT(*) AS sold " +
        "  FROM ticket t JOIN orders o ON o.order_id=t.order_id " +
        "  WHERE t.status='ACTIVE' AND t.ga_section_id IS NOT NULL GROUP BY o.performance_id, t.ga_section_id), " +
        "section_level AS (" +
        "  SELECT a.performance_id, a.section_id, a.tier_id, " +
        "         CASE WHEN a.section_type='RESERVED' THEN COALESCE(ss.seats,0)-COALESCE(rh.blocked,0) ELSE a.ga_capacity END AS sellable, " +
        "         CASE WHEN a.section_type='RESERVED' THEN COALESCE(rh.sold,0) ELSE COALESCE(gs.sold,0) END AS sold " +
        "  FROM assigned a " +
        "  LEFT JOIN sec_seats ss ON ss.section_id=a.section_id " +
        "  LEFT JOIN res_holds rh ON rh.performance_id=a.performance_id AND rh.section_id=a.section_id " +
        "  LEFT JOIN ga_sold gs ON gs.performance_id=a.performance_id AND gs.section_id=a.section_id) ";

    // R7: sell-through (per performance, per tier, or monthly by city)
    public static void r7(Connection conn, Scanner scanner) throws SQLException {
        System.out.print("""

            === R7 Sell-through ===
            1) Per performance
            2) Per tier of one performance
            3) Sold-out / under-quarter by city, for a month
            > """);
        String choice = scanner.nextLine().trim();
        switch (choice) {
            case "1" -> r7PerPerformance(conn);
            case "2" -> r7PerTier(conn, scanner);
            case "3" -> r7MonthlyByCity(conn, scanner);
            default -> System.out.println("Unrecognized option.");
        }
    }

    private static void r7PerPerformance(Connection conn) throws SQLException {
        String sql = SECTION_LEVEL +
            "SELECT p.performance_id, e.title, v.name AS venue, v.city, p.performance_datetime, " +
            "       SUM(sl.sellable) AS sellable, SUM(sl.sold) AS sold, " +
            "       SUM(sl.sold)/NULLIF(SUM(sl.sellable),0) AS sell_through " +
            "FROM section_level sl " +
            "JOIN performance p ON p.performance_id=sl.performance_id " +
            "JOIN event e ON e.event_id=p.event_id JOIN venue v ON v.venue_id=p.venue_id " +
            "GROUP BY p.performance_id, e.title, v.name, v.city, p.performance_datetime " +
            "ORDER BY sell_through DESC";
        try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            System.out.println("\n--- Sell-through per performance ---");
            boolean any = false;
            while (rs.next()) {
                any = true;
                LocalDateTime dt = rs.getObject("performance_datetime", LocalDateTime.class);
                System.out.printf("[%d] %-32s %-12s %s  %d/%d sold (%.1f%%)%n",
                    rs.getInt("performance_id"), rs.getString("title"), rs.getString("city"),
                    dt.format(DT), rs.getInt("sold"), rs.getInt("sellable"), rs.getDouble("sell_through") * 100);
            }
            if (!any) System.out.println("(no performances with sellable capacity)");
        }
    }

    private static void r7PerTier(Connection conn, Scanner scanner) throws SQLException {
        Integer perfId = InputUtil.promptInt(scanner, "Performance ID > ");
        if (perfId == null) return;
        String sql = SECTION_LEVEL +
            "SELECT pt.tier_code, pt.price, SUM(sl.sellable) AS sellable, SUM(sl.sold) AS sold, " +
            "       SUM(sl.sold)/NULLIF(SUM(sl.sellable),0) AS sell_through " +
            "FROM section_level sl JOIN price_tier pt ON pt.tier_id=sl.tier_id " +
            "WHERE sl.performance_id=? " +
            "GROUP BY pt.tier_id, pt.tier_code, pt.price ORDER BY pt.price DESC";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, perfId);
            try (ResultSet rs = ps.executeQuery()) {
                System.out.println("\n--- Sell-through per tier, performance " + perfId + " ---");
                boolean any = false;
                while (rs.next()) {
                    any = true;
                    System.out.printf("%-4s $%-8.2f %d/%d sold (%.1f%%)%n",
                        rs.getString("tier_code"), rs.getBigDecimal("price"),
                        rs.getInt("sold"), rs.getInt("sellable"), rs.getDouble("sell_through") * 100);
                }
                if (!any) System.out.println("(no priced sections for that performance)");
            }
        }
    }

    private static void r7MonthlyByCity(Connection conn, Scanner scanner) throws SQLException {
        Integer year = InputUtil.promptInt(scanner, "Year (e.g. 2026) > ");
        if (year == null) return;
        Integer month = InputUtil.promptInt(scanner, "Month (1-12) > ");
        if (month == null || month < 1 || month > 12) { System.out.println("Month must be 1-12."); return; }
        String sql = SECTION_LEVEL +
            ", perf_rate AS (" +
            "  SELECT sl.performance_id, SUM(sl.sellable) AS sellable, SUM(sl.sold) AS sold, " +
            "         SUM(sl.sold)/NULLIF(SUM(sl.sellable),0) AS rate " +
            "  FROM section_level sl GROUP BY sl.performance_id HAVING SUM(sl.sellable) > 0) " +
            "SELECT v.city, pr.performance_id, e.title, p.performance_datetime, pr.sellable, pr.sold, pr.rate, " +
            "       CASE WHEN pr.rate >= 1 THEN 'SOLD OUT' ELSE 'UNDER QUARTER' END AS flag " +
            "FROM perf_rate pr " +
            "JOIN performance p ON p.performance_id=pr.performance_id " +
            "JOIN venue v ON v.venue_id=p.venue_id JOIN event e ON e.event_id=p.event_id " +
            "WHERE YEAR(p.performance_datetime)=? AND MONTH(p.performance_datetime)=? " +
            "  AND (pr.rate >= 1 OR pr.rate < 0.25) " +
            "ORDER BY v.city, flag, pr.rate";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, year); ps.setInt(2, month);
            try (ResultSet rs = ps.executeQuery()) {
                System.out.printf("%n--- Sold-out / under-quarter by city, %04d-%02d ---%n", year, month);
                boolean any = false;
                while (rs.next()) {
                    any = true;
                    LocalDateTime dt = rs.getObject("performance_datetime", LocalDateTime.class);
                    System.out.printf("%-12s %-13s [%d] %-30s %s  %d/%d (%.1f%%)%n",
                        rs.getString("city"), rs.getString("flag"), rs.getInt("performance_id"),
                        rs.getString("title"), dt.format(DT), rs.getInt("sold"), rs.getInt("sellable"),
                        rs.getDouble("rate") * 100);
                }
                if (!any) System.out.println("(nothing sold out or under a quarter that month)");
            }
        }
    }

    // R8: resale report (per event, or top 10 by volume over a period)
    public static void r8(Connection conn, Scanner scanner) throws SQLException {
        System.out.print("""

            === R8 Resale report ===
            1) Per event (all time)
            2) Top 10 events by resale volume (date range)
            > """);
        String choice = scanner.nextLine().trim();
        switch (choice) {
            case "1" -> r8PerEvent(conn);
            case "2" -> r8TopByVolume(conn, scanner);
            default -> System.out.println("Unrecognized option.");
        }
    }

    private static void r8PerEvent(Connection conn) throws SQLException {
        String sql =
            "SELECT e.event_id, e.title, COUNT(*) AS completed_resales, " +
            "       ROUND(AVG(l.list_price - t.face_value),2) AS avg_markup, " +
            "       ROUND(SUM(ROUND(l.list_price,2)=ROUND(t.face_value*e.resale_cap_pct/100,2))/COUNT(*),4) AS frac_at_cap " +
            "FROM listing l " +
            "JOIN ticket t ON t.ticket_id=l.ticket_id " +
            "JOIN orders o ON o.order_id=t.order_id " +
            "JOIN performance p ON p.performance_id=o.performance_id " +
            "JOIN event e ON e.event_id=p.event_id " +
            "WHERE l.status='SOLD' " +
            "GROUP BY e.event_id, e.title ORDER BY completed_resales DESC, e.event_id";
        try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            System.out.println("\n--- Resale stats per event ---");
            boolean any = false;
            while (rs.next()) {
                any = true;
                System.out.printf("[%d] %-34s %d resales, avg markup $%.2f, %.1f%% at cap%n",
                    rs.getInt("event_id"), rs.getString("title"), rs.getInt("completed_resales"),
                    rs.getBigDecimal("avg_markup"), rs.getDouble("frac_at_cap") * 100);
            }
            if (!any) System.out.println("(no completed resales yet)");
        }
    }

    private static void r8TopByVolume(Connection conn, Scanner scanner) throws SQLException {
        LocalDateTime from = promptDateTime(scanner, "From date (yyyy-MM-dd HH:mm) > ");
        if (from == null) return;
        LocalDateTime to = promptDateTime(scanner, "To date (yyyy-MM-dd HH:mm) > ");
        if (to == null) return;
        String sql =
            "SELECT e.event_id, e.title, COUNT(*) AS resale_volume " +
            "FROM listing l " +
            "JOIN ticket t ON t.ticket_id=l.ticket_id " +
            "JOIN orders o ON o.order_id=t.order_id " +
            "JOIN performance p ON p.performance_id=o.performance_id " +
            "JOIN event e ON e.event_id=p.event_id " +
            "WHERE l.status='SOLD' AND l.closed_at >= ? AND l.closed_at < ? " +
            "GROUP BY e.event_id, e.title ORDER BY resale_volume DESC, e.event_id LIMIT 10";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, from); ps.setObject(2, to);
            try (ResultSet rs = ps.executeQuery()) {
                System.out.println("\n--- Top events by resale volume ---");
                boolean any = false;
                int rank = 0;
                while (rs.next()) {
                    any = true;
                    System.out.printf("%2d. [%d] %-34s %d resales%n",
                        ++rank, rs.getInt("event_id"), rs.getString("title"), rs.getInt("resale_volume"));
                }
                if (!any) System.out.println("(no completed resales in that range)");
            }
        }
    }
    
    // R9: for each event, the most popular noun phrases from its review comments.
    // With no part-of-speech (POS) tagger, we approximate noun phrases as frequent
    // runs of content words (non-stopwords), which in review prose are mostly nouns
    // and their modifiers. We count 1-to-3 word phrases, rank by popularity (longer
    // phrases bumped up), and drop any already covered by a longer phrase above.
    private static final Set<String> STOPWORDS = new HashSet<>(Arrays.asList(
        "a","an","the","and","or","but","of","to","in","on","at","for","with","was","were","is","are","be","been",
        "being","it","its","our","we","us","my","your","their","they","them","he","she","his","her","this","that",
        "these","those","up","out","off","down","before","after","near","still","so","as","had","has","have","having",
        "made","make","get","got","than","then","there","here","very","just","really","too","also","from","by","into",
        "over","under","about","all","any","some","more","most","no","not","did","do","does","would","could","should",
        "will","can","if","when","while","which","who","what","how","because","though","although","been","were","what",
        "never","surprisingly","warmed","stole","sat","worth","kept"));

    public static void r9(Connection conn, Scanner scanner) throws SQLException {
        String sql =
            "SELECT p.event_id, e.title, r.comment_text " +
            "FROM review r " +
            "JOIN performance p ON p.performance_id = r.performance_id " +
            "JOIN event e ON e.event_id = p.event_id " +
            "WHERE r.comment_text IS NOT NULL AND r.comment_text <> '' " +
            "ORDER BY p.event_id";

        Map<Integer, String> titles = new LinkedHashMap<>();
        Map<Integer, Map<String, Integer>> phrasesByEvent = new LinkedHashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                int eventId = rs.getInt("event_id");
                titles.putIfAbsent(eventId, rs.getString("title"));
                extractPhrases(rs.getString("comment_text"),
                    phrasesByEvent.computeIfAbsent(eventId, k -> new HashMap<>()));
            }
        }

        if (phrasesByEvent.isEmpty()) {
            System.out.println("\n(no review comments to analyze)");
            return;
        }

        System.out.println("\n--- R9: Most popular noun phrases per event ---");
        for (Map.Entry<Integer, Map<String, Integer>> ev : phrasesByEvent.entrySet()) {
            List<Map.Entry<String, Integer>> ranked = new ArrayList<>(ev.getValue().entrySet());
            ranked.sort((x, y) -> {
                int sx = x.getValue() * wordCount(x.getKey());   // popularity, weighted by length
                int sy = y.getValue() * wordCount(y.getKey());
                if (sx != sy) return Integer.compare(sy, sx);
                return x.getKey().compareTo(y.getKey());
            });
            System.out.printf("%n[%d] %s%n", ev.getKey(), titles.get(ev.getKey()));
            List<String> shown = new ArrayList<>();
            for (Map.Entry<String, Integer> pe : ranked) {
                String phrase = pe.getKey();
                boolean covered = false;                          // skip if a shown phrase already contains it
                for (String s : shown) {
                    if ((" " + s + " ").contains(" " + phrase + " ")) { covered = true; break; }
                }
                if (covered) continue;
                System.out.printf("   %-30s (%d)%n", phrase, pe.getValue());
                shown.add(phrase);
                if (shown.size() >= 8) break;
            }
        }
    }

    private static int wordCount(String phrase) {
        return (int) phrase.chars().filter(c -> c == ' ').count() + 1;
    }

    // Count every 1-to-3 word phrase that falls inside a run of content words.
    // We split on sentence and clause punctuation first so a phrase never spans a
    // period or comma, then break runs on stopwords within each clause.
    private static void extractPhrases(String text, Map<String, Integer> counts) {
        if (text == null) return;
        for (String clause : text.toLowerCase().split("[.,;:!?]+")) {
            String[] tokens = clause.replaceAll("[^a-z]+", " ").trim().split("\\s+");
            List<String> run = new ArrayList<>();
            for (int i = 0; i <= tokens.length; i++) {
                boolean isContent = i < tokens.length && tokens[i].length() >= 3 && !STOPWORDS.contains(tokens[i]);
                if (isContent) {
                    run.add(tokens[i]);
                } else {
                    for (int len = 1; len <= 3; len++) {
                        for (int start = 0; start + len <= run.size(); start++) {
                            counts.merge(String.join(" ", run.subList(start, start + len)), 1, Integer::sum);
                        }
                    }
                    run.clear();
                }
            }
        }
    }

    private static LocalDateTime promptDateTime(Scanner scanner, String prompt) {
        System.out.print(prompt);
        String s = scanner.nextLine().trim();
        try { return LocalDateTime.parse(s, DT); }
        catch (DateTimeParseException e) { System.out.println("Use the format yyyy-MM-dd HH:mm."); return null; }
    }
}
