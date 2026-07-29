package OrganizerToolkitMenu;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Scanner;

import util.ConsoleUtil;

public class OrganizerToolkitMenu {
    public static void listMenu(Connection conn, Scanner scanner) throws SQLException {
        ConsoleUtil.clear();
        System.out.print("""

            === Organizer Toolkit ===
            (Work in Progress...)
            Press Enter to go back.\s""");
        scanner.nextLine();
    }
}
