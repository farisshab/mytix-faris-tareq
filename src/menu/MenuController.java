package menu;

import ops.VenueOps;

import java.sql.Connection;
import java.util.Scanner;

public class MenuController {
    private final Connection conn;
    private final Scanner scanner = new Scanner(System.in);

    public MenuController(Connection conn) {
        this.conn = conn;
    }

    public void run() {
        boolean running = true;
        while (running) {
            printMenu();
            String choice = scanner.nextLine().trim();
            try {
                switch (choice) {
                    case "1" -> VenueOps.listVenues(conn);
                    case "0" -> running = false;
                    default -> System.out.println("Unrecognized option.");
                }
            } catch (Exception e) {
                System.out.println("Error: " + e.getMessage());
            }
        }
        System.out.println("Goodbye.");
    }

    private void printMenu() {
        System.out.print("===== MyTix =====\n1) List venues\n0) Exit\n================\n>");
    }
}
