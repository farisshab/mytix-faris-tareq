import util.ConsoleUtil;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Scanner;

import ops.UserOps;
import session.Session;

public class AccountMenu {
    public static boolean listMenu(Connection conn, Scanner scanner, Session session) throws SQLException {
        boolean back = false;
        while (!back) {
            printMenu();
            String choice = scanner.nextLine().trim();
            ConsoleUtil.clear();
            switch (choice) {
                case "1" -> UserOps.viewAccountInfo(conn, session);
                case "2" -> UserOps.viewCreditCardInfo(conn, session);
                case "3" -> UserOps.updateEmail(conn, scanner, session);
                case "4" -> UserOps.updateAddress(conn, scanner, session);
                case "5" -> UserOps.updateCreditCardInfo(conn, scanner, session);
                case "6" -> {
                    boolean deleted = UserOps.deleteUser(conn, scanner, session);
                    if (deleted) {
                        return true;
                    }
                }
                case "0" -> back = true;
                default -> System.out.println("Unrecognized option.");
            }
        }
        return false;
    }

    private static void printMenu() {
        System.out.print("""
            
            === Account Management ===
            1) View Account Information
            2) View Card Information
            3) Update Email
            4) Update Address
            5) Update Credit Card
            6) Delete Account
            0) Back
            > """);
    }
}