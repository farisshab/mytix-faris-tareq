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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.stream.Collectors;

import session.Session;
import util.ConsoleUtil;
import util.InputUtil;

public class BookingOps {

    /*
    NOTE: THE FUNCTIONS IN THIS SECTION ARE ALL RELATED TO BOOKING TICKETS
     */

    public static void bookTickets(Connection conn, Scanner scanner, Session session) throws SQLException {

        printUpcomingPerformances(conn);

        Integer performanceId = InputUtil.promptInt(scanner, "Performance ID > ");
        if (performanceId == null) {
            return;
        }

        String status = lookupPerformanceStatus(conn, performanceId);
        if (status == null) {
            System.out.println("No performance with that ID.");
            return;
        }
        if (status.equals("CANCELLED")) {
            System.out.println("That performance has been cancelled and can't be booked.");
            return;
        }

        List<SectionTierRow> sections = listSectionsForPerformance(conn, performanceId);
        if (sections.isEmpty()) {
            System.out.println("No sections/pricing configured for this performance yet.");
            return;
        }

        ConsoleUtil.clear();
        printSections(sections);

        Integer sectionId = InputUtil.promptInt(scanner, "Section ID to book from > ");
        if (sectionId == null) {
            return;
        }
        SectionTierRow chosen = sections.stream().filter(s -> s.sectionId() == sectionId).findFirst().orElse(null);
        if (chosen == null) {
            System.out.println("That section isn't part of this performance.");
            return;
        }
        
        CardOnFile card = lookupCustomerCard(conn, session.userId());
        if (card == null) {
            System.out.println("No credit card on file - add one before booking.");
            return;
        }

        boolean autoCommit = conn.getAutoCommit();
        try {
            conn.setAutoCommit(false);

            int orderId = insertOrder(conn, session.userId(), performanceId, card);

            if (chosen.sectionType().equals("GA")) {
                bookGaTickets(conn, scanner, orderId, performanceId, session.userId(), chosen);
            } else {
                bookReservedSeats(conn, scanner, orderId, performanceId, session.userId(), chosen);
            }

            conn.commit();
            System.out.printf("Order placed (order_id=%d).%n", orderId);
        } catch (BookingAbortedException e) {
            conn.rollback();
            System.out.println("Booking cancelled: " + e.getMessage());
        } catch (SQLException e) {
            conn.rollback();
            System.out.println("Booking failed, rolled back: " + e.getMessage());
        } finally {
            conn.setAutoCommit(autoCommit);
        }
    }

    // SECTION/TIER LISTING

    private record SectionTierRow(int sectionId, String sectionName, String sectionType, String tierCode, BigDecimal price, Integer gaCapacity) {}

    private static List<SectionTierRow> listSectionsForPerformance(Connection conn, int performanceId) throws SQLException {
        String sql = "SELECT s.section_id, s.section_name, s.section_type, s.ga_capacity, " +
                   "pt.tier_code, pt.price " +
                   "FROM performance_section_tier pst " +
                   "JOIN section s ON pst.section_id = s.section_id " +
                   "JOIN price_tier pt ON pst.performance_id = pt.performance_id AND pst.tier_id = pt.tier_id " +
                   "WHERE pst.performance_id = ? " +
                   "ORDER BY s.section_name";
        List<SectionTierRow> rows = new ArrayList<>();
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, performanceId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Integer gaCapacity = (Integer) rs.getObject("ga_capacity");
                    rows.add(new SectionTierRow(
                        rs.getInt("section_id"),
                        rs.getString("section_name"),
                        rs.getString("section_type"),
                        rs.getString("tier_code"),
                        rs.getBigDecimal("price"),
                        gaCapacity));
                }
            }
        }
        return rows;
    }

    private static void printSections(List<SectionTierRow> sections) {
        System.out.println("\n=== Sections & Pricing ===");
        for (SectionTierRow s : sections) {
            if (s.sectionType.equals("GA")) {
                System.out.printf("[ID: %d] %s (GA) - Tier %s - $%.2f - capacity %d%n", s.sectionId(), s.sectionName(), s.tierCode(), s.price(), s.gaCapacity());
            } else {
                System.out.printf("[ID: %d] %s (Reserved) - Tier %s - $%.2f%n", s.sectionId(), s.sectionName(), s.tierCode(), s.price());
            }
        }
    }

    // GENERAL ADMISSION BOOKING

    private static void bookGaTickets(Connection conn, Scanner scanner, int orderId, int performanceId, int customerId, SectionTierRow section) throws SQLException {
        Integer quantity = InputUtil.promptInt(scanner, "How many GA tickets? > ");
        if (quantity == null || quantity <= 0) {
            throw new BookingAbortedException("Invalid quantity.");
        }

        // Locks the section for the current transaction's duration, so that GA cannot be overbooked
        // E.g. if 5 seats are available, and 2 people want to book 3 seats, we will end up overbooking
        // We will make it so the 2nd transaction must wait for the first transaction to finish
        int capacity = lockSectionCapacity(conn, section.sectionId());
        int sold = countSoldGaTickets(conn, section.sectionId(), performanceId);
        int remaining = capacity - sold;
        if (quantity > remaining) {
            throw new BookingAbortedException("Only " + remaining + " GA ticket(s) remaining in that section.");
        }

        for (int i = 0; i < quantity; i++) {
            int ticketId = insertGaTicket(conn, orderId, section.sectionId(), section.price());
            insertOwnership(conn, ticketId, customerId);
        }
    }

    private static int lockSectionCapacity(Connection conn, int sectionId) throws SQLException {
        String sql = "SELECT ga_capacity FROM section WHERE section_id = ? FOR UPDATE";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, sectionId);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getInt("ga_capacity");
            }
        }
    }

    private static int countSoldGaTickets(Connection conn, int sectionId, int performanceId) throws SQLException {
        String sql = "SELECT COUNT(*) AS sold_count FROM ticket t " +
                     "JOIN orders o ON t.order_id = o.order_id " +
                     "WHERE t.ga_section_id = ? AND o.performance_id = ? AND t.status = 'ACTIVE'";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, sectionId);
            stmt.setInt(2, performanceId);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getInt("sold_count");
            }
        }
    }

    private static int insertGaTicket(Connection conn, int orderId, int gaSectionId, BigDecimal faceValue) throws SQLException {
        String sql = "INSERT INTO ticket (order_id, ga_section_id, face_value, status) VALUES (?, ?, ?, 'ACTIVE')";
        try (PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setInt(1, orderId);
            stmt.setInt(2, gaSectionId);
            stmt.setBigDecimal(3, faceValue);
            stmt.executeUpdate();
            try (ResultSet keys = stmt.getGeneratedKeys()) {
                keys.next();
                return keys.getInt(1);
            }
        }
    }


    // RESERVED SEAT BOOKING

    private static void bookReservedSeats(Connection conn, Scanner scanner, int orderId, int performanceId, int customerId, SectionTierRow section) throws SQLException {
        ConsoleUtil.clear();
        printAvailableSeats(conn, section.sectionId(), performanceId);
        
        Integer quantity = InputUtil.promptInt(scanner, "How many seats? > ");
        if (quantity == null || quantity <= 0) {
            throw new BookingAbortedException("Invalid quantity.");
        }

        System.out.println();
        for (int i = 1; i <= quantity; i++) {
            System.out.printf("--- Seat %d of %d ---%n", i, quantity);
            System.out.print("Row name > ");
            String rowName = scanner.nextLine().trim();
            Integer seatNumber = InputUtil.promptInt(scanner, "Seat number > ");
            if (seatNumber == null) {
                throw new BookingAbortedException("Invalid seat number.");
            }

            Integer seatId = lookupSeatId(conn, section.sectionId(), rowName, seatNumber);
            if (seatId == null) {
                throw new BookingAbortedException("No seat " + rowName + seatNumber + " in that section");
            }

            if (isSeatTaken(conn, performanceId, seatId)) {
                throw new BookingAbortedException("Seat " + rowName + seatNumber + " is no longer available.");
            }

            int ticketId = insertReservedTicket(conn, orderId, seatId, section.price());

            try {
                insertSeatHold(conn, performanceId, seatId, ticketId);
            } catch (SQLIntegrityConstraintViolationException e) {
                // Someone else booked this seat between our check above and this insert
                // The seat_hold PK is what catches this race condition
                throw new BookingAbortedException("Seat " + rowName + seatNumber + " was just taken by someone else.");
            }

            insertOwnership(conn, ticketId, customerId);
        }
    }

    private static void printAvailableSeats(Connection conn, int sectionId, int performanceId) throws SQLException {
        String sql = "SELECT sr.row_name, se.seat_number FROM seat se " +
                     "JOIN seat_row sr ON se.row_id = sr.row_id " +
                     "WHERE sr.section_id = ? " +
                     "AND NOT EXISTS (SELECT 1 FROM seat_hold sh WHERE sh.performance_id = ? AND sh.seat_id = se.seat_id) " +
                     "ORDER BY sr.row_name, se.seat_number";

        Map<String, List<Integer>> byRow = new LinkedHashMap<>();
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, sectionId);
            stmt.setInt(2, performanceId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    byRow.computeIfAbsent(rs.getString("row_name"), k -> new ArrayList<>()).add(rs.getInt("seat_number"));
                }
            }
        }

        System.out.println("\n--- Available Seats ---");
        if (byRow.isEmpty()) {
            System.out.println("This section has no available seats");
            return;
        }
        for (Map.Entry<String, List<Integer>> entry : byRow.entrySet()) {
            String seatList = entry.getValue().stream().map(String::valueOf).collect(Collectors.joining(", "));
            System.out.printf("Row %s: %s%n", entry.getKey(), seatList);
        }
    }

    private static Integer lookupSeatId(Connection conn, int sectionId, String rowName, int seatNumber) throws SQLException {
        String sql = "SELECT se.seat_id FROM seat se " +
                     "JOIN seat_row sr ON se.row_id = sr.row_id " +
                     "WHERE sr.section_id = ? AND sr.row_name = ? AND se.seat_number = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, sectionId);
            stmt.setString(2, rowName);
            stmt.setInt(3, seatNumber);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return rs.getInt("seat_id");
            }
        }
    }

    private static boolean isSeatTaken(Connection conn, int performanceId, int seatId) throws SQLException {
        String sql = "SELECT 1 FROM seat_hold WHERE performance_id = ? AND seat_id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, performanceId);
            stmt.setInt(2, seatId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static int insertReservedTicket(Connection conn, int orderId, int seatId, BigDecimal faceValue) throws SQLException {
        String sql = "INSERT INTO ticket (order_id, seat_id, face_value, status) VALUES (?, ?, ?, 'ACTIVE')";
        try (PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setInt(1, orderId);
            stmt.setInt(2, seatId);
            stmt.setBigDecimal(3, faceValue);
            stmt.executeUpdate();
            try (ResultSet keys = stmt.getGeneratedKeys()) {
                keys.next();
                return keys.getInt(1);
            }
        }
    }

    private static void insertSeatHold(Connection conn, int performanceId, int seatId, int ticketId) throws SQLException {
        String sql = "INSERT INTO seat_hold (performance_id, seat_id, hold_type, ticket_id) VALUES (?, ?, 'SOLD', ?)";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, performanceId);
            stmt.setInt(2, seatId);
            stmt.setInt(3, ticketId);
            stmt.executeUpdate();
        }
    }

    // ADDITIONAL HELPERS

    private static void insertOwnership(Connection conn, int ticketId, int ownerId) throws SQLException {
        // The first ownership row is the original purchase
        // Future rows will represent resales
        String sql = "INSERT INTO ticket_ownership (ticket_id, owner_id, acquired_at, acquired_via) " + "VALUES (?, ?, ?, 'PURCHASE')";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, ticketId);
            stmt.setInt(2, ownerId);
            stmt.setObject(3, LocalDateTime.now());
            stmt.executeUpdate();
        }
    }

        // The next two functions are package-private so that they can be reused in ResaleOps.java

    static void printUpcomingPerformances(Connection conn) throws SQLException {
        String sql = "SELECT p.performance_id, e.title, v.name AS venue_name, v.city, p.performance_datetime " +
                     "FROM performance p " +
                     "JOIN event e ON p.event_id = e.event_id " +
                     "JOIN venue v ON p.venue_id = v.venue_id " +
                     "WHERE p.status = 'SCHEDULED' AND p.performance_datetime > NOW() " +
                     "ORDER BY p.performance_datetime";
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:MM");

        System.out.println("\n--- Upcoming Performances ---");
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            ResultSet rs = stmt.executeQuery();
            boolean any = false;
            while (rs.next()) {
                any = true;
                LocalDateTime dt = rs.getObject("performance_datetime", LocalDateTime.class);
                System.out.printf("[ID: %d] %s @ %s, %s - %s%n",
                    rs.getInt("performance_id"),
                    rs.getString("title"),
                    rs.getString("venue_name"),
                    rs.getString("city"),
                    dt.format(fmt));
            }
            if (!any) {
                System.out.println("No upcoming performances found");
            }
        }
    }
    
    static String lookupPerformanceStatus(Connection conn, int performanceId) throws SQLException {
        String sql = "SELECT status FROM performance WHERE performance_id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, performanceId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return rs.getString("status");
             }
        }
    }

    private record CardOnFile(String cardNumber, String cardholderName, int expiryMonth, int expiryYear) {}

    private static CardOnFile lookupCustomerCard(Connection conn, int customerId) throws SQLException {
        String sql = "SELECT card_number, cardholder_name, expiry_month, expiry_year " + "FROM credit_card WHERE customer_id = ? LIMIT 1";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, customerId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new CardOnFile(
                    rs.getString("card_number"),
                    rs.getString("cardholder_name"),
                    rs.getInt("expiry_month"),
                    rs.getInt("expiry_year")
                );
            }
        }
    }

    private static int insertOrder(Connection conn, int customerId, int performanceId, CardOnFile card) throws SQLException {
        String payExpiry = String.format("%02d/%d", card.expiryMonth(), card.expiryYear());
        String sql = "INSERT INTO orders (customer_id, performance_id, order_datetime, pay_card_number, pay_cardholder, pay_expiry) " +
                     "VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setInt(1, customerId);
            stmt.setInt(2, performanceId);
            stmt.setObject(3, LocalDateTime.now());
            stmt.setString(4, card.cardNumber());
            stmt.setString(5, card.cardholderName());
            stmt.setString(6, payExpiry);
            stmt.executeUpdate();
            try (ResultSet keys = stmt.getGeneratedKeys()) {
                keys.next();
                return keys.getInt(1);
            }
        } 
    }

    /*
    NOTE: THE FUNCTIONS IN THIS SECTION ARE ALL RELATED TO CANCELLING TICKETS
     */

    public static void cancelTickets(Connection conn, Scanner scanner, Session session) throws SQLException {
        // First, create a list of all tickets that CAN be cancelled
        List<CancellableTicket> tickets = listCancellableTickets(conn, session.userId());
        if (tickets.isEmpty()) {
            System.out.println("You currently have no tickets eligible for cancellation.");
            System.out.println("(For a ticket to be eligible, you must own it, and it must be >= 7 days before the performance.)");
            return;
        }
        printCancellableTickets(tickets);

        Integer ticketId = InputUtil.promptInt(scanner, "\nTicket ID to cancel > ");
        if (ticketId == null) {
            return;
        }

        CancellableTicket chosen = tickets.stream()
            .filter(t -> t.ticketId() == ticketId)
            .findFirst()
            .orElse(null);
        if (chosen == null) {
            System.out.println("That ticket isn't eligible for cancellation right now.");
            return;
        }

        System.out.printf("Cancel ticket #%d ($%.2f, \"%s\")? This issues a full refund. (Y/N) > ", chosen.ticketId(), chosen.faceValue(), chosen.eventTitle());
        String confirm = scanner.nextLine().trim();
        if (!confirm.equalsIgnoreCase("Y")) {
            System.out.println("Not cancelled.");
            return;
        }

        boolean autoCommit = conn.getAutoCommit();
        try {
            conn.setAutoCommit(false);

            boolean wasCancelled = markTicketCancelled(conn, chosen.ticketId());

            if (!wasCancelled) {
                // Someone else already cancelled this ticket (by duplicate/concurrent request)
                conn.rollback();
                System.out.println("That ticket was already cancelled. No refund issues.");
                return;
            }

            if (chosen.seatId() != null) {
                // This is a reserved seat
                // We cancel it by deleting its row in seat_hold
                // GA tickets have no row in seat_hold
                releaseSeatHold(conn, chosen.performanceId(), chosen.seatId());
            }

            // A cancelled ticket cannot remain purchaseable via resale
            // Close any listing still active for it
            closeActiveListingForTicket(conn, chosen.ticketId());

            conn.commit();
            System.out.printf("Ticket #%d cancelled. Full refund of $%.2f issued.%n", chosen.ticketId(), chosen.faceValue());
        } catch (SQLException e) {
            conn.rollback();
            System.out.println("Cancellation failed, rolled back: " + e.getMessage());
        } finally {
            conn.setAutoCommit(autoCommit);
        }
    }

    private record CancellableTicket(int ticketId, Integer seatId, BigDecimal faceValue, int performanceId, String eventTitle, LocalDateTime performanceDatetime, String sectionName, String rowName, Integer seatNumber) {
        boolean isReserved() {
            return rowName != null;
        }
    }

    private static List<CancellableTicket> listCancellableTickets(Connection conn, int customerId) throws SQLException {
        /*
        Checks that:
            - The "current owner" is the LATEST row in ticket_ownership, sorted by acquired_at for that ticket
            - or the original PURCHASE if it's never been resold
            or the latest RESALE row
        */
       String sql = "SELECT t.ticket_id, t.seat_id, t.face_value, o.performance_id, e.title, p.performance_datetime, " +
                    "COALESCE(s_res.section_name, s_ga.section_name) AS section_name, sr.row_name, se.seat_number " +
                    "FROM ticket t " +
                    "JOIN orders o ON t.order_id = o.order_id " +
                    "JOIN performance p ON o.performance_id = p.performance_id " +
                    "JOIN event e ON p.event_id = e.event_id " +
                    "JOIN ticket_ownership tow ON tow.ticket_id = t.ticket_id " +
                    "LEFT JOIN seat se ON t.seat_id = se.seat_id " +
                    "LEFT JOIN seat_row sr ON se.row_id = sr.row_id " +
                    "LEFT JOIN section s_res ON sr.section_id = s_res.section_id " +
                    "LEFT JOIN section s_ga ON t.ga_section_id = s_ga.section_id " +
                    "WHERE t.status = 'ACTIVE' " +
                    "AND tow.owner_id = ? " +
                    "AND tow.acquired_at = (SELECT MAX(tow2.acquired_at) FROM ticket_ownership tow2 WHERE tow2.ticket_id = t.ticket_id) " +
                    "AND p.performance_datetime >= NOW() + INTERVAL 7 DAY " +
                    "ORDER BY p.performance_datetime";

        List<CancellableTicket> tickets = new ArrayList<>();
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, customerId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Integer seatId = (Integer) rs.getObject("seat_id");
                    tickets.add(new CancellableTicket(
                        rs.getInt("ticket_id"),
                        seatId,
                        rs.getBigDecimal("face_value"),
                        rs.getInt("performance_id"),
                        rs.getString("title"),
                        rs.getObject("performance_datetime", LocalDateTime.class),
                        rs.getString("section_name"),
                        rs.getString("row_name"),
                        (Integer) rs.getObject("seat_number")
                    ));
                }
            }
        }
        return tickets;
    }

    private static void printCancellableTickets(List<CancellableTicket> tickets) {
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

        Map<Integer, List<CancellableTicket>> byPerformance = new LinkedHashMap<>();
        for (CancellableTicket t : tickets) {
            byPerformance.computeIfAbsent(t.performanceId(), k -> new ArrayList<>()).add(t);
        }

        System.out.println("--- Your Cancellable Tickets ---");
        for (List<CancellableTicket> group : byPerformance.values()) {
            CancellableTicket first = group.get(0);
            System.out.printf("%n%s @ %s%n", first.eventTitle(), first.performanceDatetime().format(fmt));
            for (CancellableTicket t : group) {
                String seatInfo;
                if (t.isReserved()) {
                    seatInfo = String.format("%s, Row %s, Seat %d", t.sectionName(), t.rowName(), t.seatNumber());
                } else {
                    seatInfo = String.format("%s", t.sectionName());
                }
                System.out.printf("   [ID: %d] %s - $%.2f%n", t.ticketId(), seatInfo, t.faceValue());
            }
        }
    }

    private static boolean markTicketCancelled(Connection conn, int ticketId) throws SQLException {
        // status/cancelled_at/cancel_type must all change together since the schema's CHECK constaint requires all three or none
        String sql = "UPDATE ticket SET status = 'CANCELLED', cancelled_at = ?, cancel_type = 'CUSTOMER' " +
                     "WHERE ticket_id = ? AND status = 'ACTIVE'";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, LocalDateTime.now());
            stmt.setInt(2, ticketId);
            return stmt.executeUpdate() == 1;
        }
    }

    private static void releaseSeatHold(Connection conn, int performanceId, int seatId) throws SQLException {
        String sql = "DELETE FROM seat_hold WHERE performance_id = ? AND seat_id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, performanceId);
            stmt.setInt(2, seatId);
            stmt.executeUpdate();
        }
    }

    private static void closeActiveListingForTicket(Connection conn, int ticketId) throws SQLException {
        String sql = "UPDATE listing SET status = 'WITHDRAWN', closed_at = ? WHERE ticket_id = ? AND status = 'ACTIVE'";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, LocalDateTime.now());
            stmt.setInt(2, ticketId);
            stmt.executeUpdate();
        }
    }

    private static class BookingAbortedException extends RuntimeException {
        BookingAbortedException(String message) {
            super(message);
        }
    }

}
