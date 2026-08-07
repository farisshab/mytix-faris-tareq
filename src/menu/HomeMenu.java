import java.sql.Connection;
import java.util.Scanner;

import CustomerMenu.CustomerMenu;
import OrganizerMenu.OrganizerMenu;
import OrganizerToolkitMenu.OrganizerToolkitMenu;
import ReportsMenu.ReportsMenu;
import SearchMenu.QueriesMenu;
import session.Session;
import util.ConsoleUtil;

public class HomeMenu {
    public static boolean listMenu(Connection conn, Session session) {
        Scanner scanner = new Scanner(System.in);
        boolean running = true;
        while (running) {
            printMenu();
            String choice = scanner.nextLine().trim();
            ConsoleUtil.clear();
            try {
                switch (choice) {
                    case "1" -> {
                        boolean deletedSelf = AccountMenu.listMenu(conn, scanner, session);
                        if (deletedSelf) {
                            return true;
                        }
                    }
                    case "2" ->  {
                        if (session.isOrganizer()) {
                            System.out.println("This option is unavailable for organizers.");
                        } else {
                            CustomerMenu.listMenu(conn, scanner, session);
                        }
                    }
                    case "3" -> {
                        if (session.isCustomer()) {
                            System.out.println("This option is unavailable for customers.");
                        } else {
                            OrganizerMenu.listMenu(conn, scanner, session);
                        }
                    }
                    case "4" -> QueriesMenu.listMenu(conn, scanner);
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
        return false;
    }

    private static void printMenu() {
        System.out.print("""
            
            ===== MyTix =====
            1) Account Management
            2) Customer Operations
            3) Organizer Operations
            4) Queries
            5) Reports
            6) Organizer Toolkit
            0) Exit
            ================
            > """);
    }
}
