package ops;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

import session.Session;
import util.InputUtil;

public class BookingOps {

    public static void bookTickets(Connection conn, Scanner scanner, Session session) throws SQLException {
        if(!session.isCustomer()) {
            System.out.println("Only customer accounts can book tickets.");
            return;
        }

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
                   "JOIN price_tier pt ON pst.performance_id = pt.performance_id AND pst.tier_id = pt.tier_id" +
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
                System.out.printf("[%d] %s (GA) - Tier %s - $%.2f - capacity %d%n", s.sectionId(), s.sectionName(), s.tierCode(), s.price(), s.gaCapacity());
            } else {
                System.out.printf("[%d] %s (Reserved) - Tier %s - $%.2f%n", s.sectionId(), s.sectionName(), s.tierCode(), s.price());
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
        Integer quantity = InputUtil.promptInt(scanner, "How many seats? > ");
        if (quantity == null || quantity <= 0) {
            throw new BookingAbortedException("Invalid quantity.");
        }

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
    
    private static String lookupPerformanceStatus(Connection conn, int performanceId) throws SQLException {
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
            try (ResultSet keys = stmt.getGeneratedKeys()) {
                keys.next();
                return keys.getInt(1);
            }
        } 
    }

    private static class BookingAbortedException extends RuntimeException {
        BookingAbortedException(String message) {
            super(message);
        }
    }

}
