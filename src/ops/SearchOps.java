package ops;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Scanner;

import util.InputUtil;

// Search queries Q4-Q7 (read-only). Q4 and Q5 both need a per-performance view of
// how many tickets are open and the cheapest open ticket, so that math is built
// once as the perf_avail CTE below (the same body as the performance_availability
// view in sql/queries.sql, inlined here so the app needs no extra schema object).
public class SearchOps {

    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private static final String PERF_AVAIL =
        "WITH perf_avail AS (" +
        "  SELECT performance_id, SUM(available) AS available_count, " +
        "         MIN(CASE WHEN available>0 THEN price END) AS cheapest_price, " +
        "         MAX(section_type='RESERVED' AND available>0) AS has_reserved_avail, " +
        "         MAX(section_type='GA' AND available>0) AS has_ga_avail " +
        "  FROM (" +
        "    SELECT pst.performance_id, s.section_type, pt.price, " +
        "           sc.total_seats - COALESCE(hc.sold,0) - COALESCE(hc.blocked,0) AS available " +
        "    FROM performance_section_tier pst " +
        "    JOIN section s ON s.section_id=pst.section_id AND s.section_type='RESERVED' " +
        "    JOIN price_tier pt ON pt.tier_id=pst.tier_id " +
        "    JOIN (SELECT r.section_id, COUNT(*) total_seats FROM seat se JOIN seat_row r ON r.row_id=se.row_id GROUP BY r.section_id) sc " +
        "      ON sc.section_id=s.section_id " +
        "    LEFT JOIN (SELECT sh.performance_id, r.section_id, SUM(sh.hold_type='SOLD') sold, SUM(sh.hold_type='BLOCKED') blocked " +
        "               FROM seat_hold sh JOIN seat se ON se.seat_id=sh.seat_id JOIN seat_row r ON r.row_id=se.row_id " +
        "               GROUP BY sh.performance_id, r.section_id) hc " +
        "      ON hc.performance_id=pst.performance_id AND hc.section_id=s.section_id " +
        "    UNION ALL " +
        "    SELECT pst.performance_id, s.section_type, pt.price, s.ga_capacity - COALESCE(g.sold,0) AS available " +
        "    FROM performance_section_tier pst " +
        "    JOIN section s ON s.section_id=pst.section_id AND s.section_type='GA' " +
        "    JOIN price_tier pt ON pt.tier_id=pst.tier_id " +
        "    LEFT JOIN (SELECT o.performance_id, t.ga_section_id section_id, COUNT(*) sold " +
        "               FROM ticket t JOIN orders o ON o.order_id=t.order_id " +
        "               WHERE t.status='ACTIVE' AND t.ga_section_id IS NOT NULL " +
        "               GROUP BY o.performance_id, t.ga_section_id) g " +
        "      ON g.performance_id=pst.performance_id AND g.section_id=s.section_id " +
        "  ) section_avail GROUP BY performance_id" +
        ")";

    // ---- Q4: performances in a date range with at least N tickets available ----
    // (The Q1-Q3 geo predicate ANDs in here once Faris' location searches exist.)
    public static void q4(Connection conn, Scanner scanner) throws SQLException {
        LocalDateTime from = promptDateTime(scanner, "From date (yyyy-MM-dd HH:mm) > ");
        if (from == null) return;
        LocalDateTime to = promptDateTime(scanner, "To date (yyyy-MM-dd HH:mm) > ");
        if (to == null) return;
        Integer minAvail = InputUtil.promptInt(scanner, "Minimum available tickets > ");
        if (minAvail == null) return;

        String sql = PERF_AVAIL +
            " SELECT p.performance_id, e.title, v.name AS venue, v.city, p.performance_datetime, pa.available_count " +
            " FROM performance p JOIN perf_avail pa ON pa.performance_id=p.performance_id " +
            " JOIN event e ON e.event_id=p.event_id JOIN venue v ON v.venue_id=p.venue_id " +
            " WHERE p.status='SCHEDULED' AND p.performance_datetime BETWEEN ? AND ? AND pa.available_count>=? " +
            " ORDER BY p.performance_datetime";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, from); ps.setObject(2, to); ps.setInt(3, minAvail);
            printPerformances(ps);
        }
    }

    // ---- Q5: fully combinable filters (any subset) ----
    public static void q5(Connection conn, Scanner scanner) throws SQLException {
        System.out.println("Leave any filter blank to skip it.");
        String city = promptString(scanner, "City > ");
        String segment = promptString(scanner, "Segment (Music / Arts & Theatre / Sports) > ");
        String genre = promptString(scanner, "Genre > ");
        LocalDateTime from = promptOptionalDateTime(scanner, "From date (yyyy-MM-dd HH:mm) > ");
        LocalDateTime to = promptOptionalDateTime(scanner, "To date (yyyy-MM-dd HH:mm) > ");
        BigDecimal priceLo = promptOptionalDecimal(scanner, "Min cheapest price > ");
        BigDecimal priceHi = promptOptionalDecimal(scanner, "Max cheapest price > ");
        Integer minAvail = promptOptionalInt(scanner, "Minimum available tickets > ");
        String stype = promptString(scanner, "Section type (RESERVED / GA) > ");

        String sql = PERF_AVAIL +
            " SELECT p.performance_id, e.title, v.name AS venue, v.city, seg.segment_name, g.genre_name, " +
            "        p.performance_datetime, pa.available_count, pa.cheapest_price " +
            " FROM performance p JOIN perf_avail pa ON pa.performance_id=p.performance_id " +
            " JOIN event e ON e.event_id=p.event_id JOIN venue v ON v.venue_id=p.venue_id " +
            " JOIN genre g ON g.genre_id=e.genre_id JOIN segment seg ON seg.segment_id=g.segment_id " +
            " WHERE p.status='SCHEDULED' AND p.performance_datetime>=NOW() " +
            "   AND (? IS NULL OR v.city=?) " +
            "   AND (? IS NULL OR seg.segment_name=?) " +
            "   AND (? IS NULL OR g.genre_name=?) " +
            "   AND (? IS NULL OR p.performance_datetime>=?) " +
            "   AND (? IS NULL OR p.performance_datetime<=?) " +
            "   AND (? IS NULL OR pa.cheapest_price>=?) " +
            "   AND (? IS NULL OR pa.cheapest_price<=?) " +
            "   AND (? IS NULL OR pa.available_count>=?) " +
            "   AND (? IS NULL OR (?='RESERVED' AND pa.has_reserved_avail=1) OR (?='GA' AND pa.has_ga_avail=1)) " +
            " ORDER BY p.performance_datetime";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int i = 1;
            i = bind(ps, i, city, Types.VARCHAR, 2);
            i = bind(ps, i, segment, Types.VARCHAR, 2);
            i = bind(ps, i, genre, Types.VARCHAR, 2);
            i = bind(ps, i, from, Types.TIMESTAMP, 2);
            i = bind(ps, i, to, Types.TIMESTAMP, 2);
            i = bind(ps, i, priceLo, Types.DECIMAL, 2);
            i = bind(ps, i, priceHi, Types.DECIMAL, 2);
            i = bind(ps, i, minAvail, Types.INTEGER, 2);
            bind(ps, i, stype, Types.VARCHAR, 3);
            printFilterResults(ps);
        }
    }

    // ---- Q6: seat-map summary for one performance ----
    public static void q6(Connection conn, Scanner scanner) throws SQLException {
        Integer perfId = InputUtil.promptInt(scanner, "Performance ID > ");
        if (perfId == null) return;

        String sql =
            "SELECT s.section_name, s.section_type, pt.tier_code, pt.price, " +
            "       sc.total_seats - COALESCE(hc.sold,0) - COALESCE(hc.blocked,0) AS available, " +
            "       COALESCE(hc.sold,0) AS sold, COALESCE(hc.blocked,0) AS blocked " +
            "FROM section s " +
            "JOIN performance_section_tier pst ON pst.performance_id=? AND pst.section_id=s.section_id " +
            "JOIN price_tier pt ON pt.tier_id=pst.tier_id " +
            "JOIN (SELECT r.section_id, COUNT(*) total_seats FROM seat se JOIN seat_row r ON r.row_id=se.row_id GROUP BY r.section_id) sc " +
            "     ON sc.section_id=s.section_id " +
            "LEFT JOIN (SELECT r.section_id, SUM(sh.hold_type='SOLD') sold, SUM(sh.hold_type='BLOCKED') blocked " +
            "           FROM seat_hold sh JOIN seat se ON se.seat_id=sh.seat_id JOIN seat_row r ON r.row_id=se.row_id " +
            "           WHERE sh.performance_id=? GROUP BY r.section_id) hc ON hc.section_id=s.section_id " +
            "WHERE s.section_type='RESERVED' " +
            "UNION ALL " +
            "SELECT s.section_name, s.section_type, pt.tier_code, pt.price, " +
            "       s.ga_capacity - COALESCE(gc.sold,0) AS available, COALESCE(gc.sold,0) AS sold, 0 AS blocked " +
            "FROM section s " +
            "JOIN performance_section_tier pst ON pst.performance_id=? AND pst.section_id=s.section_id " +
            "JOIN price_tier pt ON pt.tier_id=pst.tier_id " +
            "LEFT JOIN (SELECT t.ga_section_id section_id, COUNT(*) sold FROM ticket t JOIN orders o ON o.order_id=t.order_id " +
            "           WHERE o.performance_id=? AND t.status='ACTIVE' AND t.ga_section_id IS NOT NULL GROUP BY t.ga_section_id) gc " +
            "     ON gc.section_id=s.section_id " +
            "WHERE s.section_type='GA' " +
            "ORDER BY section_name";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, perfId); ps.setInt(2, perfId); ps.setInt(3, perfId); ps.setInt(4, perfId);
            try (ResultSet rs = ps.executeQuery()) {
                System.out.println("\n--- Seat Map ---");
                boolean any = false;
                while (rs.next()) {
                    any = true;
                    System.out.printf("%-22s %-9s %-4s $%-8.2f avail %-5d sold %-4d blocked %d%n",
                        rs.getString("section_name"), rs.getString("section_type"), rs.getString("tier_code"),
                        rs.getBigDecimal("price"), rs.getInt("available"), rs.getInt("sold"), rs.getInt("blocked"));
                }
                if (!any) System.out.println("(no sections priced for that performance, or no such performance)");
            }
        }
    }

    // ---- Q7: best available block of q consecutive seats ----
    public static void q7(Connection conn, Scanner scanner) throws SQLException {
        Integer perfId = InputUtil.promptInt(scanner, "Performance ID > ");
        if (perfId == null) return;
        Integer q = InputUtil.promptInt(scanner, "How many consecutive seats? > ");
        if (q == null || q <= 0) { System.out.println("Enter a positive number of seats."); return; }
        BigDecimal budget = promptOptionalDecimal(scanner, "Budget (blank for none) > ");

        String sql =
            "WITH available_seats AS (" +
            "  SELECT se.seat_number, r.row_id, r.row_name, s.section_name, pt.price " +
            "  FROM seat se JOIN seat_row r ON r.row_id=se.row_id JOIN section s ON s.section_id=r.section_id " +
            "  JOIN performance_section_tier pst ON pst.performance_id=? AND pst.section_id=s.section_id " +
            "  JOIN price_tier pt ON pt.tier_id=pst.tier_id " +
            "  WHERE s.section_type='RESERVED' " +
            "    AND NOT EXISTS (SELECT 1 FROM seat_hold sh WHERE sh.performance_id=? AND sh.seat_id=se.seat_id)), " +
            "islands AS (" +
            "  SELECT row_id, row_name, section_name, price, seat_number, " +
            "         seat_number - ROW_NUMBER() OVER (PARTITION BY row_id ORDER BY seat_number) AS island_key " +
            "  FROM available_seats), " +
            "runs AS (" +
            "  SELECT section_name, row_name, price, MIN(seat_number) AS start_seat " +
            "  FROM islands GROUP BY row_id, row_name, section_name, price, island_key HAVING COUNT(*) >= ?) " +
            "SELECT section_name, row_name, start_seat, start_seat + ? - 1 AS end_seat, price AS price_each, ? * price AS total_price " +
            "FROM runs WHERE (? IS NULL OR ? >= (? * price)) " +
            "ORDER BY total_price, section_name, row_name, start_seat LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, perfId); ps.setInt(2, perfId);
            ps.setInt(3, q); ps.setInt(4, q); ps.setInt(5, q);
            if (budget == null) { ps.setNull(6, Types.DECIMAL); ps.setNull(7, Types.DECIMAL); }
            else { ps.setBigDecimal(6, budget); ps.setBigDecimal(7, budget); }
            ps.setInt(8, q);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    System.out.printf("%nBest block: %s Row %s, seats %d-%d (%d seats) at $%.2f each = $%.2f total.%n",
                        rs.getString("section_name"), rs.getString("row_name"),
                        rs.getInt("start_seat"), rs.getInt("end_seat"), q,
                        rs.getBigDecimal("price_each"), rs.getBigDecimal("total_price"));
                } else {
                    System.out.println("\nNo block of " + q + " consecutive seats found" + (budget != null ? " within budget." : "."));
                }
            }
        }
    }

    // ==================== helpers ====================

    // Bind the same value (or SQL NULL) into `count` consecutive placeholders.
    private static int bind(PreparedStatement ps, int i, Object val, int sqlType, int count) throws SQLException {
        for (int k = 0; k < count; k++) {
            if (val == null) ps.setNull(i, sqlType);
            else ps.setObject(i, val);
            i++;
        }
        return i;
    }

    private static void printPerformances(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            System.out.println("\n--- Performances ---");
            boolean any = false;
            while (rs.next()) {
                any = true;
                LocalDateTime dt = rs.getObject("performance_datetime", LocalDateTime.class);
                System.out.printf("[%d] %s @ %s, %s - %s (%d available)%n",
                    rs.getInt("performance_id"), rs.getString("title"), rs.getString("venue"),
                    rs.getString("city"), dt.format(DT), rs.getInt("available_count"));
            }
            if (!any) System.out.println("(no matching performances)");
        }
    }

    private static void printFilterResults(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            System.out.println("\n--- Results ---");
            boolean any = false;
            while (rs.next()) {
                any = true;
                LocalDateTime dt = rs.getObject("performance_datetime", LocalDateTime.class);
                System.out.printf("[%d] %s (%s / %s) @ %s, %s - %s | %d avail, cheapest $%.2f%n",
                    rs.getInt("performance_id"), rs.getString("title"), rs.getString("segment_name"),
                    rs.getString("genre_name"), rs.getString("venue"), rs.getString("city"),
                    dt.format(DT), rs.getInt("available_count"), rs.getBigDecimal("cheapest_price"));
            }
            if (!any) System.out.println("(no matching performances)");
        }
    }

    private static String promptString(Scanner scanner, String prompt) {
        System.out.print(prompt);
        String s = scanner.nextLine().trim();
        return s.isEmpty() ? null : s;
    }

    private static Integer promptOptionalInt(Scanner scanner, String prompt) {
        String s = promptString(scanner, prompt);
        if (s == null) return null;
        try { return Integer.parseInt(s); }
        catch (NumberFormatException e) { System.out.println("Not a number, skipping that filter."); return null; }
    }

    private static BigDecimal promptOptionalDecimal(Scanner scanner, String prompt) {
        String s = promptString(scanner, prompt);
        if (s == null) return null;
        try { return new BigDecimal(s); }
        catch (NumberFormatException e) { System.out.println("Not a number, skipping."); return null; }
    }

    private static LocalDateTime promptOptionalDateTime(Scanner scanner, String prompt) {
        String s = promptString(scanner, prompt);
        if (s == null) return null;
        try { return LocalDateTime.parse(s, DT); }
        catch (DateTimeParseException e) { System.out.println("Bad date format, skipping."); return null; }
    }

    private static LocalDateTime promptDateTime(Scanner scanner, String prompt) {
        System.out.print(prompt);
        String s = scanner.nextLine().trim();
        try { return LocalDateTime.parse(s, DT); }
        catch (DateTimeParseException e) { System.out.println("Use the format yyyy-MM-dd HH:mm."); return null; }
    }
}
