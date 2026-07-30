package ops;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Scanner;

import session.Session;
import util.ConsoleUtil;

public class AuthOps {
    public static Session authenticate(Connection conn, Scanner scanner) throws SQLException {
        while (true) {
            printWelcome();
            String choice = scanner.nextLine().trim();
            ConsoleUtil.clear();
            switch (choice) {
                case "1" -> {
                    Session session = signIn(conn,  scanner);
                    if (session != null) {
                        return session;
                    }
                }
                case "2" -> UserOps.createUser(conn, scanner);
                default -> System.out.println("Unrecognized option.");
            }
        }
    }

    private static void printWelcome() {
        System.out.print("""
                
            === Welcome to MyTix ===
            1) Sign in
            2) Create an account
            >\s""");
    }


    // Looks up a user by email ONLY.
    private static Session signIn(Connection conn, Scanner scanner) throws SQLException {
        System.out.print("Email > ");
        String email = scanner.nextLine().trim();

        String sql = "SELECT user_id, full_name, role FROM users WHERE email = ? AND is_deleted = FALSE";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, email);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    System.out.println("No account found with that email.");
                    return null;
                }
                int userId = rs.getInt("user_id");
                String fullName = rs.getString("full_name");
                String role = rs.getString("role");
                System.out.printf("Welcome back, %s (%s): (user_id = %d).%n", fullName, role, userId);
                return new Session(userId, fullName, role);
            }
        }
    }
}
