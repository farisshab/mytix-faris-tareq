package ops;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Scanner;

import util.InputUtil;

// Reports R4, R7, R8. SQL reference and the assumptions behind each one
// are in sql/reports.sql and docs/report_design.md. A rule that runs through all of
// them: "sold" and revenue count active tickets only, since a cancelled ticket was
// refunded and its seat is back in the pool.

public class ReportsOps {

    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

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

    private static LocalDateTime promptDateTime(Scanner scanner, String prompt) {
        System.out.print(prompt);
        String s = scanner.nextLine().trim();
        try { return LocalDateTime.parse(s, DT); }
        catch (DateTimeParseException e) { System.out.println("Use the format yyyy-MM-dd HH:mm."); return null; }
    }
}
