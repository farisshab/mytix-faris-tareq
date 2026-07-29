import java.sql.Connection;
import java.util.Scanner;

import CustomerMenu.CustomerMenu;
import OrganizerMenu.OrganizerMenu;
import OrganizerToolkitMenu.OrganizerToolkitMenu;
import ReportsMenu.ReportsMenu;
import SearchMenu.SearchMenu;
import util.ConsoleUtil;

public class HomeMenu {
    public static void listMenu(Connection conn) {
        Scanner scanner = new Scanner(System.in);
        boolean running = true;
        while (running) {
            printMenu();
            String choice = scanner.nextLine().trim();
            ConsoleUtil.clear();
            try {
                switch (choice) {
                    case "1" -> AccountMenu.listMenu(conn, scanner);
                    case "2" -> CustomerMenu.listMenu(conn, scanner);
                    case "3" -> OrganizerMenu.listMenu(conn, scanner);
                    case "4" -> SearchMenu.listMenu(conn, scanner);
                    case "5" -> ReportsMenu.listMenu(conn, scanner);
                    case "6" -> OrganizerToolkitMenu.listMenu(conn, scanner);
                    case "0" -> running = false;
                    default -> System.out.println("Unrecognized option.");
                }
            } catch (Exception e) {
                System.out.println("Error: " + e.getMessage());
            }
        }
        ConsoleUtil.clear();
        System.out.println("Thank you for using MyTix. We hope to see you again soon!");
    }

    private static void printMenu() {
        System.out.print("""
            
            ===== MyTix =====
            1) Account Management
            2) Customer Operations
            3) Organizer Operations
            4) Search
            5) Reports
            6) Organizer Toolkit
            0) Exit
            ================
            >\s""");
    }
}
