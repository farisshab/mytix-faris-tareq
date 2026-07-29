import util.ConsoleUtil;

import java.sql.Connection;
import java.util.Scanner;

public class AccountMenu {
    public static void listMenu(Connection conn, Scanner scanner) {
        boolean back = false;
        while (!back) {
            printMenu();
            String choice = scanner.nextLine().trim();
            ConsoleUtil.clear();
            switch (choice) {
                case "1" -> System.out.println("TODO: Create Account");
                case "2" -> System.out.println("TODO: Delete Account");
                case "0" -> back = true;
                default -> System.out.println("Unrecognized option.");
            }
        }
    }

    private static void printMenu() {
        System.out.print("""
            
            === Account Management ===
            1) Create Account
            2) Delete Account
            0) Back
            >\s""");
    }
}
