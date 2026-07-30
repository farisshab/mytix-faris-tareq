package ops;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.Period;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.Scanner;


import session.Session;

public class UserOps {
    private static final int MINIMUM_AGE = 18;

    // OPERATIONS

    public static void createUser(Connection conn, Scanner scanner) throws SQLException {
        System.out.print("Email > ");
        String email = scanner.nextLine().trim();

        EmailLookup existing = lookupEmail(conn, email);

        if (existing.userId() != null && !existing.isDeleted()) {
            System.out.println("That email is already registered.");
            return;
        }
 
        if (existing.userId() != null) {
            // User was soft delete, so we re-activate
            System.out.print("An account with this email was previously deleted.\nReactivate it with your previous information? (Y/N) > ");
            String confirm = scanner.nextLine().trim();
            if (!confirm.equalsIgnoreCase("Y")) {
                System.out.println("Aborting - that email is tied to a deleted account.");
                return;
            }
            reactivateUser(conn, existing.userId());
            System.out.printf("Account reactivated (user_id=%d) with your previous information.%n", existing.userId());
            return;
        }

        String role = promptRole(scanner);
        if (role == null) {
            System.out.println("Invalid role selection. Aborting...");
            return;
        }

        System.out.print("Full name > ");
        String fullName = scanner.nextLine().trim();

        System.out.print("Address > ");
        String address = scanner.nextLine().trim();

        LocalDate dob = promptValidDateOfBirth(scanner);
        if (dob == null) {
            return;
        }

        CreditCardInfo card = null;
        if (role.equals("CUSTOMER")) {
            card = promptCreditCardDetails(scanner);
            if (card == null) {
                System.out.println("Abording account creation...");
                return;
            }
        }

        boolean autoCommit = conn.getAutoCommit();
        try {
            conn.setAutoCommit(false);

            int userId = insertUser(conn, fullName, address, email, dob, role);

            if (card != null) {
                insertCreditCard(conn, userId, card);
            }

            conn.commit();
            System.out.printf("Account created (user_id=%d, role=%s).%n", userId, role);
        } catch (SQLException e) {
            conn.rollback();
            System.out.println("Account creation failed, rolled back: " + e.getMessage());
        } finally {
            conn.setAutoCommit(autoCommit);
        }
    }

    public static boolean deleteUser(Connection conn, Scanner scanner, Session session) throws SQLException {
        System.out.print("Are you sure you want to delete your account? This cannot be undone. (Y/N) > ");
        String confirm = scanner.nextLine().trim();
        if (!confirm.equalsIgnoreCase(("Y"))) {
            System.out.println("Cancelled - your account was not deleted.");
            return false;
        }

        int userId = session.userId();

        // If any children still point to that user_id, we would get an error, so we define soft delete and hard delete (soft if children exist, hard if otherwise)
        if (hasHistory(conn, userId)) {
            softDelete(conn, userId);
            System.out.println("User has existing history - soft-deleted. History preserved.");
        } else {
            hardDelete(conn, userId);
            System.out.println("User had no history - permanently deleted.");
        }
        return true;
    }

    // PROMPT/INPUT HELPER FUNCTIONS

    private static String promptRole(Scanner scanner) {
        System.out.print("""
            Role?
            - (1) Customer
            - (2) Organizer?
            > """);
        String choice = scanner.nextLine().trim();
        return switch (choice) {
            case "1" -> "CUSTOMER";
            case "2" -> "ORGANIZER";
            default -> null;
        };
    }

    private static Integer promptInt(Scanner scanner, String prompt) {
        System.out.print(prompt);
        String input = scanner.nextLine().trim();
        try {
            return Integer.parseInt(input);
        } catch (NumberFormatException e) {
            System.out.printf("\"%s\" is not a valid integer.%n", input);
            return null;
        }
    }

    // EMAIL HELPERS

    private record EmailLookup(Integer userId, boolean isDeleted) {}

    private static EmailLookup lookupEmail(Connection conn, String email) throws SQLException {
        String sql = "SELECT user_id, is_deleted FROM users where EMAIL = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, email);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return new EmailLookup(null, false);
                }
                return new EmailLookup(rs.getInt("user_id"), rs.getBoolean("is_deleted"));
            }
        }
    }

    // DATE HELPERS

    private static LocalDate promptValidDateOfBirth(Scanner scanner) {
        System.out.print("Date of birth (YYYY-MM-DD) > ");
        String input = scanner.nextLine().trim();
        LocalDate dob;
        try {
            dob = LocalDate.parse(input);
        } catch (DateTimeParseException e) {
            System.out.println("Invalid date format, expected YYYY-MM-DD.");
            return null;
        }

        // Must enforce age >= 18

        int age = Period.between(dob, LocalDate.now()).getYears();
        if (age < MINIMUM_AGE) {
            System.out.println("Must be at least " + MINIMUM_AGE + " years old to register.");
            return null;
        }
        return dob;
    }

    // CREDIT CARD HELPERS

    private record CreditCardInfo(String cardNumber, String cardholderName, int expiryMonth, int expiryYear) {}

    private static CreditCardInfo promptCreditCardDetails(Scanner scanner) {
        System.out.print("Card number > ");
        String cardNumber = scanner.nextLine().trim();

        System.out.print("Cardholder name > ");
        String cardholderName = scanner.nextLine().trim();

        Integer month = promptInt(scanner, "Expiry month (1-12) > ");
        if (month == null) {
            return null;
        }
        if (month < 1 || month > 12) {
            System.out.println("Expiry month must be between 1 and 12.");
            return null;
        }

        Integer year = promptInt(scanner, "Expiry year (e.g. 2026) > ");
        if (year == null) {
            return null;
        }

        // Reject any expired card
        YearMonth expiry = YearMonth.of(year, month);
        if (expiry.isBefore(YearMonth.now())) {
            System.out.printf("That card is already expired (%d/%d).%n", month, year);
            return null;
        }

        return new CreditCardInfo(cardNumber, cardholderName, month, year);
    }

    private static void insertCreditCard(Connection conn, int userId, CreditCardInfo card) throws SQLException {
        String sql = "INSERT INTO credit_card (customer_id, card_number, cardholder_name, expiry_month, expiry_year) " + "VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, userId);
            stmt.setString(2, card.cardNumber);
            stmt.setString(3, card.cardholderName);
            stmt.setInt(4, card.expiryMonth);
            stmt.setInt(5, card.expiryYear);
            stmt.executeUpdate();
        }
    }

    // ADDITIONAL HELPERS

    private static int insertUser(Connection conn, String fullName, String address, String email, LocalDate dob, String role) throws SQLException {
        String sql = "INSERT INTO users (full_name, address, email, date_of_birth, role, is_deleted) " + "VALUES (?, ?, ?, ?, ?, FALSE)";

        try (PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setString(1, fullName);
            stmt.setString(2, address);
            stmt.setString(3, email);
            stmt.setObject(4, dob);
            stmt.setString(5, role);
            stmt.executeUpdate();
            try (ResultSet keys = stmt.getGeneratedKeys()) {
                keys.next();
                return keys.getInt(1);
            }
        }
    }

    private static void reactivateUser(Connection conn, int userId) throws SQLException {
        String sql = "UPDATE users SET is_deleted = FALSE WHERE user_id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, userId);
            stmt.executeUpdate();
        }
    }

    // DELETION HELPERS

        // The function below will query all tables where user_id is a foreign key
    private static boolean hasHistory(Connection conn, int userId) throws SQLException {
        if (existsWhere(conn, "SELECT 1 FROM credit_card WHERE customer_id = ?", userId)) return true;
        if (existsWhere(conn, "SELECT 1 FROM event WHERE organizer_id = ?", userId)) return true;
        if (existsWhere(conn, "SELECT 1 FROM orders WHERE customer_id = ?", userId)) return true;
        if (existsWhere(conn, "SELECT 1 FROM ticket_ownership WHERE owner_id = ?", userId)) return true;
        if (existsWhereEither(conn, "SELECT 1 FROM listing WHERE seller_id = ? OR buyer_id = ?", userId)) return true;
        return existsWhere(conn, "SELECT 1 FROM review WHERE customer_id = ?", userId);
    }

    private static boolean existsWhere(Connection conn, String sql, int userId) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static boolean existsWhereEither(Connection conn, String sql, int userId) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, userId);
            stmt.setInt(2, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static void softDelete(Connection conn, int userId) throws SQLException {
        String sql = "UPDATE users SET is_deleted = TRUE WHERE user_id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, userId);
            stmt.executeUpdate();
        }
    }

    private static void hardDelete(Connection conn, int userId) throws SQLException {
        String sql = "DELETE FROM users WHERE user_id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, userId);
            stmt.executeUpdate();
        }
    }
}
