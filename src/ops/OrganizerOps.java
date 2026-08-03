package ops;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

import session.Session;
import util.InputUtil;

// Organizer write operations. Every op is checked against the signed-in organizer:
// a statement can only ever touch an event that user owns (organizer_id = their id),
// so nobody can reprice, block, or cancel someone else's show.
public class OrganizerOps {

    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final BigDecimal MIN_CAP = new BigDecimal("100");

    // 1) Create Event
    public static void createEvent(Connection conn, Scanner scanner, Session session) throws SQLException {
        printGenres(conn);
        Integer genreId = InputUtil.promptInt(scanner, "Genre ID > ");
        if (genreId == null) return;
        System.out.print("Event title > ");
        String title = scanner.nextLine().trim();
        if (title.isEmpty()) { System.out.println("Title can't be empty."); return; }
        BigDecimal cap = promptDecimal(scanner, "Resale cap % (blank for 120) > ", new BigDecimal("120"));
        if (cap == null) return;
        if (cap.compareTo(MIN_CAP) < 0) { System.out.println("Cap must be at least 100%."); return; }

        String sql = "INSERT INTO event (organizer_id, title, genre_id, resale_cap_pct) VALUES (?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, session.userId());
            ps.setString(2, title);
            ps.setInt(3, genreId);
            ps.setBigDecimal(4, cap);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                System.out.printf("Event created (event_id=%d).%n", keys.getInt(1));
            }
        } catch (SQLIntegrityConstraintViolationException e) {
            System.out.println("Couldn't create the event - is that a valid genre?");
        }
    }

    // 2) Add Performance to Event
    public static void addPerformance(Connection conn, Scanner scanner, Session session) throws SQLException {
        printMyEvents(conn, session.userId());
        Integer eventId = InputUtil.promptInt(scanner, "Event ID > ");
        if (eventId == null) return;
        printVenues(conn);
        Integer venueId = InputUtil.promptInt(scanner, "Venue ID > ");
        if (venueId == null) return;
        LocalDateTime when = promptDateTime(scanner, "Date & time (yyyy-MM-dd HH:mm) > ");
        if (when == null) return;

        // INSERT ... SELECT so the row only lands if the event is theirs
        String sql = "INSERT INTO performance (event_id, venue_id, performance_datetime) " +
                     "SELECT ?, ?, ? FROM event e WHERE e.event_id=? AND e.organizer_id=?";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, eventId); ps.setInt(2, venueId); ps.setObject(3, when);
            ps.setInt(4, eventId); ps.setInt(5, session.userId());
            int n = ps.executeUpdate();
            if (n == 0) { System.out.println("That event isn't yours (or doesn't exist)."); return; }
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                System.out.printf("Performance added (performance_id=%d). Define its tiers and assign sections next.%n", keys.getInt(1));
            }
        } catch (SQLIntegrityConstraintViolationException e) {
            System.out.println("Couldn't add the performance - is that a valid venue?");
        }
    }

    // 3) Define Price Tiers
    public static void definePriceTiers(Connection conn, Scanner scanner, Session session) throws SQLException {
        printMyPerformances(conn, session.userId(), false);
        Integer perfId = InputUtil.promptInt(scanner, "Performance ID > ");
        if (perfId == null) return;
        if (!ownsPerformance(conn, session.userId(), perfId)) {
            System.out.println("That performance isn't yours (or doesn't exist).");
            return;
        }
        printTiers(conn, perfId);

        System.out.println("Add tiers one at a time (blank code to stop). Two or more is expected.");
        String sql = "INSERT INTO price_tier (performance_id, tier_code, price) VALUES (?, ?, ?)";
        int added = 0;
        while (true) {
            System.out.print("Tier code (e.g. P1), blank to stop > ");
            String code = scanner.nextLine().trim();
            if (code.isEmpty()) break;
            BigDecimal price = promptDecimal(scanner, "Price > ", null);
            if (price == null) continue;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, perfId); ps.setString(2, code); ps.setBigDecimal(3, price);
                ps.executeUpdate();
                added++;
                System.out.printf("Added tier %s ($%.2f).%n", code, price);
            } catch (SQLIntegrityConstraintViolationException e) {
                System.out.printf("Tier %s already exists for this performance.%n", code);
            }
        }
        System.out.printf("Done. %d tier(s) added.%n", added);
    }

    // 4) Assign Sections to Tiers
    public static void assignSectionsToTiers(Connection conn, Scanner scanner, Session session) throws SQLException {
        printMyPerformances(conn, session.userId(), false);
        Integer perfId = InputUtil.promptInt(scanner, "Performance ID > ");
        if (perfId == null) return;
        if (!ownsPerformance(conn, session.userId(), perfId)) {
            System.out.println("That performance is not yours, or does not exist.");
            return;
        }
        printTiers(conn, perfId);

        List<SectionRow> sections = loadSections(conn, perfId);
        System.out.println("\n--- Sections ---");
        for (SectionRow s : sections) {
            System.out.printf("[section %d] %s (%s) - %s%n",
                s.sectionId(), s.name(), s.type(), s.tierId() == null ? "UNASSIGNED" : "tier_id " + s.tierId());
        }

        String sql = "INSERT INTO performance_section_tier (performance_id, section_id, tier_id) VALUES (?, ?, ?)";
        for (SectionRow s : sections) {
            if (s.tierId() != null) continue; // already assigned, leave it
            Integer tierId = InputUtil.promptInt(scanner, "tier_id for section '" + s.name() + "' (0 to skip) > ");
            if (tierId == null || tierId == 0) continue;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, perfId); ps.setInt(2, s.sectionId()); ps.setInt(3, tierId);
                ps.executeUpdate();
                System.out.printf("Assigned %s.%n", s.name());
            } catch (SQLIntegrityConstraintViolationException e) {
                // the composite FK rejects a tier that isn't a tier of THIS performance
                System.out.printf("Couldn't assign %s - is tier_id %d a tier of this performance?%n", s.name(), tierId);
            }
        }

        int remaining = countUnassignedSections(conn, perfId);
        if (remaining == 0) System.out.println("All sections priced - this performance is fully sellable.");
        else System.out.printf("Heads up: %d section(s) still have no tier and can't be sold until you assign them.%n", remaining);
    }

    // 5) Set Resale Cap
    public static void setResaleCap(Connection conn, Scanner scanner, Session session) throws SQLException {
        printMyEvents(conn, session.userId());
        Integer eventId = InputUtil.promptInt(scanner, "Event ID > ");
        if (eventId == null) return;
        BigDecimal cap = promptDecimal(scanner, "New resale cap % > ", null);
        if (cap == null) return;
        if (cap.compareTo(MIN_CAP) < 0) { System.out.println("Cap must be at least 100%."); return; }

        String sql = "UPDATE event SET resale_cap_pct=? WHERE event_id=? AND organizer_id=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setBigDecimal(1, cap); ps.setInt(2, eventId); ps.setInt(3, session.userId());
            if (ps.executeUpdate() == 1) System.out.printf("Resale cap set to %.0f%%.%n", cap);
            else System.out.println("That event isn't yours (or doesn't exist).");
        }
    }

    // 6) Update Tier Price (refused if anything has sold in the tier)
    public static void updateTierPrice(Connection conn, Scanner scanner, Session session) throws SQLException {
        printMyPerformances(conn, session.userId(), true);
        Integer perfId = InputUtil.promptInt(scanner, "Performance ID > ");
        if (perfId == null) return;
        printTiers(conn, perfId);
        Integer tierId = InputUtil.promptInt(scanner, "tier_id to reprice > ");
        if (tierId == null) return;
        BigDecimal newPrice = promptDecimal(scanner, "New price > ", null);
        if (newPrice == null) return;

        String sql =
            "UPDATE price_tier pt " +
            "JOIN performance p ON p.performance_id=pt.performance_id " +
            "JOIN event e ON e.event_id=p.event_id " +
            "SET pt.price=? " +
            "WHERE pt.tier_id=? AND e.organizer_id=? AND p.performance_datetime>NOW() AND NOT (" +
            "  EXISTS (SELECT 1 FROM seat_hold sh JOIN seat se ON se.seat_id=sh.seat_id JOIN seat_row r ON r.row_id=se.row_id " +
            "          JOIN performance_section_tier pst ON pst.section_id=r.section_id AND pst.performance_id=sh.performance_id " +
            "          WHERE sh.performance_id=pt.performance_id AND sh.hold_type='SOLD' AND pst.tier_id=pt.tier_id) " +
            "  OR EXISTS (SELECT 1 FROM ticket t JOIN orders o ON o.order_id=t.order_id " +
            "          JOIN performance_section_tier pst ON pst.section_id=t.ga_section_id AND pst.performance_id=o.performance_id " +
            "          WHERE o.performance_id=pt.performance_id AND t.status='ACTIVE' AND pst.tier_id=pt.tier_id))";
        int n;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setBigDecimal(1, newPrice); ps.setInt(2, tierId); ps.setInt(3, session.userId());
            n = ps.executeUpdate();
        }
        if (n == 1) System.out.printf("Price updated to $%.2f.%n", newPrice);
        else System.out.println("Price change refused: " + diagnoseTierUpdate(conn, session.userId(), tierId));
    }

    // 7) Block Seat
    public static void blockSeat(Connection conn, Scanner scanner, Session session) throws SQLException {
        printMyPerformances(conn, session.userId(), true);
        Integer perfId = InputUtil.promptInt(scanner, "Performance ID > ");
        if (perfId == null) return;
        Integer seatId = promptSeat(conn, scanner, perfId);
        if (seatId == null) return;

        String sql = "INSERT INTO seat_hold (performance_id, seat_id, hold_type, ticket_id) " +
                     "SELECT ?, ?, 'BLOCKED', NULL FROM performance p JOIN event e ON e.event_id=p.event_id " +
                     "WHERE p.performance_id=? AND e.organizer_id=? AND p.status='SCHEDULED' AND p.performance_datetime>NOW()";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, perfId); ps.setInt(2, seatId); ps.setInt(3, perfId); ps.setInt(4, session.userId());
            int n = ps.executeUpdate();
            if (n == 1) System.out.println("Seat blocked.");
            else System.out.println("That performance isn't yours, or isn't a scheduled, future show.");
        } catch (SQLIntegrityConstraintViolationException e) {
            // the seat_hold PK rejects a seat that already has a SOLD or BLOCKED row
            System.out.println("Can't block that seat - it's already sold or blocked.");
        }
    }

    // 8) Unblock Seat
    public static void unblockSeat(Connection conn, Scanner scanner, Session session) throws SQLException {
        printMyPerformances(conn, session.userId(), true);
        Integer perfId = InputUtil.promptInt(scanner, "Performance ID > ");
        if (perfId == null) return;
        Integer seatId = promptSeat(conn, scanner, perfId);
        if (seatId == null) return;

        // scoped to BLOCKED, so a sold seat can never be freed this way
        String sql = "DELETE sh FROM seat_hold sh " +
                     "JOIN performance p ON p.performance_id=sh.performance_id " +
                     "JOIN event e ON e.event_id=p.event_id " +
                     "WHERE sh.performance_id=? AND sh.seat_id=? AND sh.hold_type='BLOCKED' AND e.organizer_id=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, perfId); ps.setInt(2, seatId); ps.setInt(3, session.userId());
            if (ps.executeUpdate() == 1) System.out.println("Seat unblocked.");
            else System.out.println("Nothing to unblock there (not blocked, or not your event).");
        }
    }

    // 9) Cancel a Performance (full cascade in one transaction)
    public static void cancelPerformance(Connection conn, Scanner scanner, Session session) throws SQLException {
        printMyPerformances(conn, session.userId(), true);
        Integer perfId = InputUtil.promptInt(scanner, "Performance ID to cancel > ");
        if (perfId == null) return;
        System.out.print("Cancel this performance? Every sold ticket is refunded. (Y/N) > ");
        if (!scanner.nextLine().trim().equalsIgnoreCase("Y")) { System.out.println("Not cancelled."); return; }

        boolean autoCommit = conn.getAutoCommit();
        try {
            conn.setAutoCommit(false);

            // step 1 is the gate: owner + not already cancelled
            int flipped;
            try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE performance p JOIN event e ON e.event_id=p.event_id " +
                "SET p.status='CANCELLED', p.cancelled_at=? " +
                "WHERE p.performance_id=? AND e.organizer_id=? AND p.status='SCHEDULED'")) {
                ps.setObject(1, LocalDateTime.now()); ps.setInt(2, perfId); ps.setInt(3, session.userId());
                flipped = ps.executeUpdate();
            }
            if (flipped == 0) {
                conn.rollback();
                System.out.println("Can't cancel: not your performance, or it's already cancelled.");
                return;
            }

            int refunded;
            try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE ticket t JOIN orders o ON o.order_id=t.order_id " +
                "SET t.status='CANCELLED', t.cancel_type='PERFORMANCE', t.cancelled_at=? " +
                "WHERE o.performance_id=? AND t.status='ACTIVE'")) {
                ps.setObject(1, LocalDateTime.now()); ps.setInt(2, perfId);
                refunded = ps.executeUpdate();
            }

            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM seat_hold WHERE performance_id=?")) {
                ps.setInt(1, perfId); ps.executeUpdate();
            }

            try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE listing l JOIN ticket t ON t.ticket_id=l.ticket_id JOIN orders o ON o.order_id=t.order_id " +
                "SET l.status='WITHDRAWN', l.closed_at=? WHERE o.performance_id=? AND l.status='ACTIVE'")) {
                ps.setObject(1, LocalDateTime.now()); ps.setInt(2, perfId); ps.executeUpdate();
            }

            conn.commit();
            System.out.printf("Performance cancelled. %d ticket(s) refunded.%n", refunded);
        } catch (SQLException e) {
            conn.rollback();
            System.out.println("Cancellation failed, rolled back: " + e.getMessage());
        } finally {
            conn.setAutoCommit(autoCommit);
        }
    }

    // helpers

    private record SectionRow(int sectionId, String name, String type, Integer tierId) {}

    private static List<SectionRow> loadSections(Connection conn, int perfId) throws SQLException {
        String sql = "SELECT s.section_id, s.section_name, s.section_type, " +
                     "(SELECT pst.tier_id FROM performance_section_tier pst " +
                     " WHERE pst.performance_id=? AND pst.section_id=s.section_id) AS tier_id " +
                     "FROM section s JOIN performance p ON p.venue_id=s.venue_id " +
                     "WHERE p.performance_id=? ORDER BY s.section_name";
        List<SectionRow> rows = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, perfId); ps.setInt(2, perfId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new SectionRow(rs.getInt("section_id"), rs.getString("section_name"),
                        rs.getString("section_type"), (Integer) rs.getObject("tier_id")));
                }
            }
        }
        return rows;
    }

    private static int countUnassignedSections(Connection conn, int perfId) throws SQLException {
        String sql = "SELECT COUNT(*) FROM section s JOIN performance p ON p.venue_id=s.venue_id " +
                     "WHERE p.performance_id=? AND NOT EXISTS " +
                     "(SELECT 1 FROM performance_section_tier pst WHERE pst.performance_id=? AND pst.section_id=s.section_id)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, perfId); ps.setInt(2, perfId);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getInt(1); }
        }
    }

    private static boolean ownsPerformance(Connection conn, int organizerId, int perfId) throws SQLException {
        String sql = "SELECT 1 FROM performance p JOIN event e ON e.event_id=p.event_id " +
                     "WHERE p.performance_id=? AND e.organizer_id=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, perfId); ps.setInt(2, organizerId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    private static String diagnoseTierUpdate(Connection conn, int organizerId, int tierId) throws SQLException {
        String sql = "SELECT (e.organizer_id=?) AS owned, (p.performance_datetime>NOW()) AS future, (" +
            "  EXISTS (SELECT 1 FROM seat_hold sh JOIN seat se ON se.seat_id=sh.seat_id JOIN seat_row r ON r.row_id=se.row_id " +
            "          JOIN performance_section_tier pst ON pst.section_id=r.section_id AND pst.performance_id=sh.performance_id " +
            "          WHERE sh.performance_id=pt.performance_id AND sh.hold_type='SOLD' AND pst.tier_id=pt.tier_id) " +
            "  OR EXISTS (SELECT 1 FROM ticket t JOIN orders o ON o.order_id=t.order_id " +
            "          JOIN performance_section_tier pst ON pst.section_id=t.ga_section_id AND pst.performance_id=o.performance_id " +
            "          WHERE o.performance_id=pt.performance_id AND t.status='ACTIVE' AND pst.tier_id=pt.tier_id)) AS has_sales " +
            "FROM price_tier pt JOIN performance p ON p.performance_id=pt.performance_id JOIN event e ON e.event_id=p.event_id " +
            "WHERE pt.tier_id=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, organizerId); ps.setInt(2, tierId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return "no such tier.";
                if (!rs.getBoolean("owned")) return "that performance isn't yours.";
                if (!rs.getBoolean("future")) return "that performance has already passed.";
                if (rs.getBoolean("has_sales")) return "tickets have already sold in that tier.";
                return "unknown reason.";
            }
        }
    }

    private static Integer promptSeat(Connection conn, Scanner scanner, int perfId) throws SQLException {
        System.out.print("Section name > ");
        String section = scanner.nextLine().trim();
        System.out.print("Row name > ");
        String row = scanner.nextLine().trim();
        Integer seatNum = InputUtil.promptInt(scanner, "Seat number > ");
        if (seatNum == null) return null;
        String sql = "SELECT se.seat_id FROM seat se JOIN seat_row r ON r.row_id=se.row_id " +
                     "JOIN section s ON s.section_id=r.section_id JOIN performance p ON p.venue_id=s.venue_id " +
                     "WHERE p.performance_id=? AND s.section_name=? AND r.row_name=? AND se.seat_number=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, perfId); ps.setString(2, section); ps.setString(3, row); ps.setInt(4, seatNum);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) { System.out.println("No such seat in this performance's venue."); return null; }
                return rs.getInt("seat_id");
            }
        }
    }

    private static void printGenres(Connection conn) throws SQLException {
        String sql = "SELECT g.genre_id, seg.segment_name, g.genre_name FROM genre g " +
                     "JOIN segment seg ON seg.segment_id=g.segment_id ORDER BY seg.segment_name, g.genre_name";
        System.out.println("\n--- Genres ---");
        try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) System.out.printf("[ID: %d] %s / %s%n",
                rs.getInt("genre_id"), rs.getString("segment_name"), rs.getString("genre_name"));
        }
    }

    private static void printVenues(Connection conn) throws SQLException {
        String sql = "SELECT venue_id, name, city, country FROM venue ORDER BY name";
        System.out.println("\n--- Venues ---");
        try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) System.out.printf("[ID: %d] %s - %s, %s%n",
                rs.getInt("venue_id"), rs.getString("name"), rs.getString("city"), rs.getString("country"));
        }
    }

    private static void printMyEvents(Connection conn, int organizerId) throws SQLException {
        String sql = "SELECT e.event_id, e.title, g.genre_name, e.resale_cap_pct " +
                     "FROM event e JOIN genre g ON g.genre_id=e.genre_id WHERE e.organizer_id=? ORDER BY e.event_id";
        System.out.println("\n--- Your Events ---");
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, organizerId);
            try (ResultSet rs = ps.executeQuery()) {
                boolean any = false;
                while (rs.next()) {
                    any = true;
                    System.out.printf("[ID: %d] %s (%s) - resale cap %.0f%%%n",
                        rs.getInt("event_id"), rs.getString("title"), rs.getString("genre_name"), rs.getBigDecimal("resale_cap_pct"));
                }
                if (!any) System.out.println("(none yet)");
            }
        }
    }

    private static void printMyPerformances(Connection conn, int organizerId, boolean futureOnly) throws SQLException {
        String sql = "SELECT p.performance_id, e.title, v.name AS venue, p.performance_datetime, p.status " +
                     "FROM performance p JOIN event e ON e.event_id=p.event_id JOIN venue v ON v.venue_id=p.venue_id " +
                     "WHERE e.organizer_id=? " +
                     (futureOnly ? "AND p.performance_datetime>NOW() AND p.status='SCHEDULED' " : "") +
                     "ORDER BY p.performance_datetime";
        System.out.println("\n--- Your Performances ---");
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, organizerId);
            try (ResultSet rs = ps.executeQuery()) {
                boolean any = false;
                while (rs.next()) {
                    any = true;
                    LocalDateTime dt = rs.getObject("performance_datetime", LocalDateTime.class);
                    System.out.printf("[ID: %d] %s @ %s - %s (%s)%n",
                        rs.getInt("performance_id"), rs.getString("title"), rs.getString("venue"), dt.format(DT), rs.getString("status"));
                }
                if (!any) System.out.println("(none)");
            }
        }
    }

    private static void printTiers(Connection conn, int perfId) throws SQLException {
        String sql = "SELECT tier_id, tier_code, price FROM price_tier WHERE performance_id=? ORDER BY tier_code";
        System.out.println("\n--- Tiers ---");
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, perfId);
            try (ResultSet rs = ps.executeQuery()) {
                boolean any = false;
                while (rs.next()) {
                    any = true;
                    System.out.printf("[tier_id %d] %s - $%.2f%n", rs.getInt("tier_id"), rs.getString("tier_code"), rs.getBigDecimal("price"));
                }
                if (!any) System.out.println("(none yet)");
            }
        }
    }

    private static BigDecimal promptDecimal(Scanner scanner, String prompt, BigDecimal dflt) {
        System.out.print(prompt);
        String in = scanner.nextLine().trim();
        if (in.isEmpty() && dflt != null) return dflt;
        try {
            return new BigDecimal(in);
        } catch (NumberFormatException e) {
            System.out.printf("\"%s\" is not a valid number.%n", in);
            return null;
        }
    }

    private static LocalDateTime promptDateTime(Scanner scanner, String prompt) {
        System.out.print(prompt);
        String in = scanner.nextLine().trim();
        try {
            return LocalDateTime.parse(in, DT);
        } catch (DateTimeParseException e) {
            System.out.println("Use the format yyyy-MM-dd HH:mm, e.g. 2026-09-01 20:00");
            return null;
        }
    }
}
