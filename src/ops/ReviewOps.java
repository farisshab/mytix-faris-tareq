package ops;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

import session.Session;
import util.InputUtil;

public class ReviewOps {

    public static void submitReview(Connection conn, Scanner scanner, Session session) throws SQLException {
        List <ReviewablePerformance> performances = listReviewablePerformances(conn, session.userId());
        if (performances.isEmpty()) {
            System.out.println("You have no performances eligible for review");
            System.out.println("For a performance to be eligible for review, you must have attended it within the past year and not already reviewed it.");
            return;
        }
        printReviewablePerformances(performances);

        Integer performanceId = InputUtil.promptInt(scanner, "Performance ID to review > ");
        if (performanceId == null) {
            return;
        }

        ReviewablePerformance chosen = performances.stream()
            .filter(p -> p.performanceId() == performanceId)
            .findFirst()
            .orElse(null);
        if (chosen == null) {
            System.out.println("That performance isn't eligible for review right now.");
            return;
        }

        Integer eventRating = promptRating(scanner, "Event");
        if (eventRating == null) {
            return;
        }
        Integer venueRating = promptRating(scanner , "Venue");
        if (venueRating == null) {
            return;
        }

        System.out.print("Comment (optional, press Enter to skip) > ");
        String comment = scanner.nextLine().trim();
        String commentToStore;

        if (comment.isEmpty()) {
            commentToStore = null;
        } else {
            commentToStore = comment;
        }

        System.out.printf("Submit review for \"%s\" @ %s - Event: %d/5, Venue: %d/5? (Y/N) > ", chosen.eventTitle(), chosen.venueName(), eventRating, venueRating);
        String confirm = scanner.nextLine().trim();
        if (!confirm.equalsIgnoreCase("Y")) {
            System.out.println("Not submitted.");
            return;
        }

        try {
            insertReview(conn, session.userId(), chosen.performanceId(), eventRating, venueRating, commentToStore);
            System.out.println("Review submitted. Thank you!");
        } catch (SQLIntegrityConstraintViolationException e) {
            // A review for  this performance already exists -- race condition
            System.out.println("You've already reviewed this performance.");
        }
    }

    private record ReviewablePerformance(int performanceId, String eventTitle, String venueName, LocalDateTime performanceDatetime) {}

    private static List<ReviewablePerformance> listReviewablePerformances(Connection conn, int customerId) throws SQLException {
        // DISTINCT is important, so we do not produce duplicate rows when someone owns 2 tickets to the same performance
        String sql = "SELECT DISTINCT p.performance_id, e.title, v.name AS venue_name, p.performance_datetime " +
                     "FROM ticket t " +
                     "JOIN orders o ON t.order_id = o.order_id " +
                     "JOIN performance p ON o.performance_id = p.performance_id " +
                     "JOIN event e ON p.event_id = e.event_id " +
                     "JOIN venue v ON p.venue_id = v.venue_id " +
                     "JOIN ticket_ownership tow ON tow.ticket_id = t.ticket_id " +
                     "WHERE t.status = 'ACTIVE' " +
                     "AND tow.owner_id = ? " +
                     "AND tow.acquired_at = (SELECT MAX(tow2.acquired_at) FROM ticket_ownership tow2 WHERE tow2.ticket_id = t.ticket_id) " +
                     "AND p.performance_datetime < NOW() " +
                     "AND p.performance_datetime >= NOW() - INTERVAL 365 DAY " +
                     "AND NOT EXISTS (SELECT 1 FROM review r WHERE r.customer_id = ? AND r.performance_id = p.performance_id) " +
                     "ORDER BY p.performance_datetime DESC";

        List<ReviewablePerformance> performances = new ArrayList<>();
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, customerId);
            stmt.setInt(2, customerId);
            try (ResultSet rs = stmt.executeQuery()) {
                while(rs.next()) {
                    performances.add(new ReviewablePerformance(
                        rs.getInt("performance_id"),
                        rs.getString("title"),
                        rs.getString("venue_name"),
                        rs.getObject("performance_datetime", LocalDateTime.class)
                    ));
                }
            }
        }
        return performances;
    }

    private static void printReviewablePerformances(List<ReviewablePerformance> performances) {
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
        System.out.println("\n--- Performances You Can Review ---");
        for (ReviewablePerformance p : performances) {
            System.out.printf("[ID: %d] %s @ %s - %s%n", p.performanceId(), p.eventTitle(), p.venueName(), p.performanceDatetime().format(fmt));
        }
    }
    
    private static Integer promptRating(Scanner scanner, String label) {
        Integer rating = InputUtil.promptInt(scanner, label + " rating (1-5) > ");
        if (rating == null) {
            return null;
        }
        if (rating < 1 || rating > 5) {
            System.out.println(label + " rating must be between 1 and 5.");
            return null;
        }
        return rating;
    }

    private static void insertReview(Connection conn, int customerId, int performanceId, int eventRating, int venueRating, String comment) throws SQLException {
        String sql = "INSERT INTO review (customer_id, performance_id, event_rating, venue_rating, comment_text, created_at) " +
                     "VALUES (?, ?, ?, ?, ?, ?)";
                     try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                        stmt.setInt(1, customerId);
                        stmt.setInt(2, performanceId);
                        stmt.setInt(3, eventRating);
                        stmt.setInt(4, venueRating);
                        stmt.setString(5, comment);
                        stmt.setObject(6, LocalDateTime.now());
                        stmt.executeUpdate();
                     }
    }
}
