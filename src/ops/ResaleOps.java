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

import session.Session;
import util.ConsoleUtil;
import util.InputUtil;

public class ResaleOps {

    // CREATING A LISTING

    /**
     * @param conn
     * @param scanner
     * @param session
     * @throws SQLException
     */
    public static void listForResale(Connection conn, Scanner scanner, Session session) throws SQLException {
        List<ListableTicket> tickets = listListableTickets(conn, session.userId());
        if (tickets.isEmpty()) {
            System.out.println("You have no eligible tickets for resale.");
            System.out.println("An eligible ticket is one that is currently owned by you, active, and not already listed for sale.");
            return;
        }
        printListableTickets(tickets);

        Integer ticketId = InputUtil.promptInt(scanner, "Ticket ID to list > ");
        if (ticketId == null) {
            return;
        }

        ListableTicket chosen = tickets.stream()
            .filter(t -> t.ticketId() == ticketId)
            .findFirst()
            .orElse(null);
        if (chosen == null) {
            System.out.println("That ticket isn't available for resale right now.");
            return;
        }

        System.out.printf("Face value: $%.2f. Maximum resale price: $%.2f.%n", chosen.faceValue(), chosen.resaleCap());
        BigDecimal price = promptResalePrice(scanner, chosen.resaleCap());
        if (price == null) {
            return;
        }

        String seatInfo = describeSeat(chosen.sectionName(), chosen.rowName(), chosen.seatNumber());
        System.out.printf("List %s for $%.2f? (Y/N) > ", seatInfo, price);
        String confirm = scanner.nextLine().trim();
        if (!confirm.equalsIgnoreCase("Y")) {
            System.out.println("Not listed.");
            return;
        }

        try {
            int listingId = insertListing(conn, chosen.ticketId(), session.userId(), price);
            System.out.printf("Listing created (listing_id=%d) at $%.2f.%n", listingId, price);
        } catch (SQLIntegrityConstraintViolationException e) {
            // Race condition, someone else listed this ticket first
            System.out.println("This ticket already has an active listing.");
        }
    }

    /**
     * ListableTicket
     * @param ticketId
     * @param faceValue
     * @param resaleCap
     * @param performanceId
     * @param eventTitle
     * @param performanceDatetime
     * @param sectionName
     * @param rowName
     * @param seatNumber
     */
    private record ListableTicket(int ticketId, BigDecimal faceValue, BigDecimal resaleCap, int performanceId, String eventTitle, LocalDateTime performanceDatetime, String sectionName, String rowName, Integer seatNumber) {}

    /**
     * @param conn
     * @param customerId
     * @return
     * @throws SQLException
     */
    private static List<ListableTicket> listListableTickets(Connection conn, int customerId) throws SQLException {
        // Get's the owner via the latest row in ticket_ownership.
        // Works very similarly to BookingOps.listCancellableTickets, without the 7-day constraint
        // but with a NOT EXISTS to exclude tickets that already have an active listing
        String sql = "SELECT t.ticket_id, t.face_value, (t.face_value * e.resale_cap_pct / 100) AS resale_cap, " +
                     "o.performance_id, e.title, p.performance_datetime, " +
                     "COALESCE(s_res.section_name, s_ga.section_name) AS section_name, " +
                     "sr.row_name, se.seat_number " +
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
                     "AND p.status = 'SCHEDULED' " +
                     "AND tow.owner_id = ? " +
                     "AND tow.acquired_at = (SELECT MAX(tow2.acquired_at) FROM ticket_ownership tow2 WHERE tow2.ticket_id = t.ticket_id) " +
                     "AND NOT EXISTS (SELECT 1 FROM listing l where l.ticket_id = t.ticket_id AND l.status = 'ACTIVE') " +
                     "ORDER BY p.performance_datetime, section_name, sr.row_name, se.seat_number";

        List<ListableTicket> tickets = new ArrayList<>();
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, customerId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    tickets.add(new ListableTicket(
                        rs.getInt("ticket_id"),
                        rs.getBigDecimal("face_value"),
                        rs.getBigDecimal("resale_cap"),
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

    /**
     * @param tickets
     */
    private static void printListableTickets(List<ListableTicket> tickets) {
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
        Map<Integer, List<ListableTicket>> byPerformance = new LinkedHashMap<>();
        for (ListableTicket t : tickets) {
            byPerformance.computeIfAbsent(t.performanceId(), k -> new ArrayList<>()).add(t);
        }

        System.out.println("\n--- Your Tickets Eligible for Resale ---");
        for (List<ListableTicket> group : byPerformance.values()) {
            ListableTicket first = group.get(0);
            System.out.printf("%n%s @ %s%n", first.eventTitle(), first.performanceDatetime().format(fmt));
            for (ListableTicket t : group) {
                String seatInfo = describeSeat(t.sectionName(), t.rowName(), t.seatNumber());
                System.out.printf("   [ID: %d] %s - Face value $%.2f - Max resale $%.2f%n", t.ticketId(), seatInfo, t.faceValue(), t.resaleCap());
            }
        }
    }

    /**
     * @param scanner
     * @param cap
     * @return
     */
    private static BigDecimal promptResalePrice(Scanner scanner, BigDecimal cap) {
        System.out.print("List price > $");
        String input = scanner.nextLine().trim();
        BigDecimal price;
        try {
            price = new BigDecimal(input);
        } catch (NumberFormatException e) {
            System.out.printf("\"%s\" is not a valid price.%n", input);
            return null;
        }

        if (price.compareTo(BigDecimal.ZERO) < 0) {
            System.out.println("Price cannot be negative.");
            return null;
        }

        if (price.compareTo(cap) > 0) {
            System.out.printf("Price exceeds the resale cap of $%.2f.%n", cap);
            return null;
        }
        return price;
    }

    /**
     * @param conn
     * @param ticketId
     * @param sellerId
     * @param listPrice
     * @return
     * @throws SQLException
     */
    private static int insertListing(Connection conn, int ticketId, int sellerId, BigDecimal listPrice) throws SQLException {
        String sql = "INSERT INTO listing (ticket_id, seller_id, list_price, status, created_at) " +
                     "VALUES (?, ?, ?, 'ACTIVE', ?)";
        try (PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setInt(1, ticketId);
            stmt.setInt(2, sellerId);
            stmt.setBigDecimal(3, listPrice);
            stmt.setObject(4, LocalDateTime.now());
            stmt.executeUpdate();
            try (ResultSet keys = stmt.getGeneratedKeys()) {
                keys.next();
                return keys.getInt(1);
            }
        }
    }

    // WITHDRAWING A LISTING

    /**
     * @param conn
     * @param scanner
     * @param session
     * @throws SQLException
     */
    public static void withdrawListing(Connection conn, Scanner scanner, Session session) throws SQLException {
        List<OwnedListing> listings = listActiveListingsForSeller(conn, session.userId());
        if (listings.isEmpty()) {
            System.out.println("You have no active listings to withdraw.");
            return;
        }
        printOwnedListings(listings);

        Integer listingId = InputUtil.promptInt(scanner, "Listing ID to withdraw > ");
        if (listingId == null) {
            return;
        }

        OwnedListing chosen = listings.stream()
            .filter(l -> l.listingId() == listingId)
            .findFirst()
            .orElse(null);
        if (chosen == null) {
            System.out.println("You are unable to withdraw that listing.");
            return;
        }

        String seatInfo = describeSeat(chosen.sectionName(), chosen.rowName(), chosen.seatNumber());
        System.out.printf("Withdraw listing for %s at $%.2f? (Y/N) > ", seatInfo, chosen.listPrice());
        String confirm = scanner.nextLine().trim();
        if (!confirm.equalsIgnoreCase("Y")) {
            System.out.println("Not withdrawn.");
            return;
        }

        withdraw(conn, chosen.listingId());
        System.out.printf("Listing #%d withdrawn.%n", chosen.listingId());
    }

    /**
     * OwnedListing
     * @param listingId
     * @param listPrice
     * @param createdAt
     * @param eventTitle
     * @param sectionName
     * @param rowName
     * @param seatNumber
     */
    private record OwnedListing(int listingId, BigDecimal listPrice, LocalDateTime createdAt, String eventTitle, String sectionName, String rowName, Integer seatNumber) {}

    /**
     * @param conn
     * @param sellerId
     * @return
     * @throws SQLException
     */
    private static List<OwnedListing> listActiveListingsForSeller(Connection conn, int sellerId) throws SQLException {
        String sql = "SELECT l.listing_id, l.list_price, l.created_at, e.title, " +
                     "COALESCE(s_res.section_name, s_ga.section_name) AS section_name, " +
                     "sr.row_name, se.seat_number " +
                     "FROM listing l " +
                     "JOIN ticket t ON l.ticket_id = t.ticket_id " +
                     "JOIN orders o ON t.order_id = o.order_id " +
                     "JOIN performance p ON o.performance_id = p.performance_id " +
                     "JOIN event e ON p.event_id = e.event_id " +
                     "LEFT JOIN seat se ON t.seat_id = se.seat_id " +
                     "LEFT JOIN seat_row sr ON se.row_id = sr.row_id " +
                     "LEFT JOIN section s_res ON sr.section_id = s_res.section_id " +
                     "LEFT JOIN section s_ga ON t.ga_section_id = s_ga.section_id " +
                     "WHERE l.seller_id = ? AND l.status = 'ACTIVE' " +
                     "ORDER BY l.created_at";

        List<OwnedListing> listings = new ArrayList<>();
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, sellerId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    listings.add(new OwnedListing(
                        rs.getInt("listing_id"),
                        rs.getBigDecimal("list_price"),
                        rs.getObject("created_at", LocalDateTime.class),
                        rs.getString("title"),
                        rs.getString("section_name"),
                        rs.getString("row_name"),
                        (Integer) rs.getObject("seat_number")
                    ));
                }
            }
        }
        return listings;
    }

    /**
     * @param listings
     */
    private static void printOwnedListings(List<OwnedListing> listings) {
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:MM");
        System.out.println("\n--- Your Active Listings ---");
        for (OwnedListing l : listings) {
            String seatInfo = describeSeat(l.sectionName(), l.rowName(), l.seatNumber());
            System.out.printf("[ID: %d] %s - %s - $%.2f - listed %s%n",
                l.listingId(),
                l.eventTitle(),
                seatInfo,
                l.listPrice(),
                l.createdAt().format(fmt)
            );
        }
    }

    /**
     * @param conn
     * @param listingId
     * @throws SQLException
     */
    private static void withdraw(Connection conn, int listingId) throws SQLException {
        String sql = "UPDATE listing SET status = 'WITHDRAWN', closed_at = ? WHERE listing_id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, LocalDateTime.now());
            stmt.setInt(2, listingId);
            stmt.executeUpdate();
        }
    }

    // BUYING A LISTING

    /**
     * @param conn
     * @param scanner
     * @param session
     * @throws SQLException
     */
    public static void buyListing(Connection conn, Scanner scanner, Session session) throws SQLException {
        if (!hasCardOnFile(conn, session.userId())) {
            System.out.println("No credit card on file - add one from Account Management before buying.");
            return;
        }

        BookingOps.printUpcomingPerformances(conn);
        Integer performanceId = InputUtil.promptInt(scanner, "Performance ID > ");
        if (performanceId == null) {
            return;
        }

        String status = BookingOps.lookupPerformanceStatus(conn, performanceId);
        if (status == null) {
            System.out.println("No performance with that ID.");
            return;
        }

        if (status.equals("CANCELLED")) {
            System.out.println("That performance has been cancelled - no listings can be bought for it.");
            return;
        }

        List<BrowsableListing> listings = listBrowsableListings(conn, performanceId, session.userId());
        if (listings.isEmpty()) {
            ConsoleUtil.clear();
            System.out.println("No active listings for this performance.");
            return;
        }
        printBrowsableListings(listings);

        Integer listingId = InputUtil.promptInt(scanner, "Listing ID to buy > ");
        if (listingId == null) {
            return;
        }

        BrowsableListing chosen = listings.stream()
            .filter(l -> l.listingId() == listingId)
            .findFirst()
            .orElse(null);
        if (chosen == null) {
            System.out.println("That listing isn't available.");
            return;
        }

        String seatInfo = describeSeat(chosen.sectionName(), chosen.rowName(), chosen.seatNumber());
        System.out.printf("Buy %s for $%.2f? (Y/N) > ", seatInfo, chosen.listPrice());
        String confirm = scanner.nextLine().trim();
        if (!confirm.equalsIgnoreCase("Y")) {
            System.out.println("Not putchased.");
            return;
        }

        boolean autoCommit = conn.getAutoCommit();
        try {
            conn.setAutoCommit(false);
            purchaseListing(conn, chosen.listingId(), chosen.ticketId(), session.userId());
            conn.commit();
            System.out.printf("Purchased %s for $%.2f. Ownership transferred.%n", seatInfo, chosen.listPrice());
        } catch (SQLException e) {
            conn.rollback();
            System.out.println("Purchase failed, rolled back: " + e.getMessage());
        } finally {
            conn.setAutoCommit(autoCommit);
        }
    }

    /**
     * BrowsableListing
     * @param listingId
     * @param ticketId
     * @param listPrice
     * @param sectionName
     * @param rowName
     * @param seatNumber
     */
    private record BrowsableListing(int listingId, int ticketId, BigDecimal listPrice, String sectionName, String rowName, Integer seatNumber) {}

    /**
     * @param conn
     * @param customerId
     * @return
     * @throws SQLException
     */
    private static boolean hasCardOnFile(Connection conn, int customerId) throws SQLException {
        String sql = "SELECT 1 FROM credit_card WHERE customer_id = ? LIMIT 1";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, customerId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * @param conn
     * @param performanceId
     * @param excludingCustomerId
     * @return
     * @throws SQLException
     */
    private static List<BrowsableListing> listBrowsableListings(Connection conn, int performanceId, int excludingCustomerId) throws SQLException {
        String sql = "SELECT l.listing_id, l.ticket_id, l.list_price, " +
                     "COALESCE(s_res.section_name, s_ga.section_name) AS section_name, " +
                     "sr.row_name, se.seat_number " +
                     "FROM listing l " +
                     "JOIN ticket t ON l.ticket_id = t.ticket_id " +
                     "JOIN orders o ON t.order_id = o.order_id " +
                     "LEFT JOIN seat se ON t.seat_id = se.seat_id " +
                     "LEFT JOIN seat_row sr ON se.row_id = sr.row_id " +
                     "LEFT JOIN section s_res ON sr.section_id = s_res.section_id " +
                     "LEFT JOIN section s_ga ON t.ga_section_id = s_ga.section_id " +
                     "WHERE l.status = 'ACTIVE' " +
                     "AND t.status = 'ACTIVE' " +
                     "AND o.performance_id = ? " +
                     "AND l.seller_id != ? " +
                     "ORDER BY l.list_price";
        
        List<BrowsableListing> listings = new ArrayList<>();
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
        stmt.setInt(1, performanceId);
        stmt.setInt(2, excludingCustomerId);
        try (ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                listings.add(new BrowsableListing(
                    rs.getInt("listing_id"),
                    rs.getInt("ticket_id"),
                    rs.getBigDecimal("list_price"),
                    rs.getString("section_name"),
                    rs.getString("row_name"),
                    (Integer) rs.getObject("seat_number")
                ));
            }
        }
        }
        return listings;
    }

    /**
     * @param listings
     */
    private static void printBrowsableListings(List<BrowsableListing> listings) {
        System.out.println("\n--- Available Listings ---");
        for (BrowsableListing l : listings) {
            String seatInfo = describeSeat(l.sectionName(), l.rowName(), l.seatNumber());
            System.out.printf("[ID: %d] %s - $%.2f%n", l.listingId(), seatInfo, l.listPrice());
        }
    }

    /**
     * @param conn
     * @param listingId
     * @param ticketId
     * @param buyerId
     * @throws SQLException
     */
    private static void purchaseListing(Connection conn, int listingId, int ticketId, int buyerId) throws SQLException {
        String updateSql = "UPDATE listing SET status = 'SOLD', buyer_id = ?, closed_at = ? WHERE listing_id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(updateSql)) {
            stmt.setInt(1, buyerId);
            stmt.setObject(2, LocalDateTime.now());
            stmt.setInt(3, listingId);
            stmt.executeUpdate();
        }

        // Appends a NEW ownership row RATHER than overwriting the old one (per decision 15)
        String ownershipSql = "INSERT INTO ticket_ownership (ticket_id, owner_id, acquired_at, acquired_via) " +
                              "VALUES (?, ?, ?, 'RESALE')";
        try (PreparedStatement stmt = conn.prepareStatement(ownershipSql)) {
            stmt.setInt(1, ticketId);
            stmt.setInt(2, buyerId);
            stmt.setObject(3, LocalDateTime.now());
            stmt.executeUpdate();
        }
    }

    // SHARED HELPERS

    /**
     * @param sectionName
     * @param rowName
     * @param seatNumber
     * @return
     */
    private static String describeSeat(String sectionName, String rowName, Integer seatNumber) {
        if (rowName != null) {
            return String.format("%s, Row %s, Seat %d", sectionName, rowName, seatNumber);
        } else {
            return String.format("%s (General Admission)", sectionName);
        }
    }
}
