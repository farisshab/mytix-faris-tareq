package ops;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class VenueOps {
    public static void listVenues(Connection conn) throws SQLException {
        String sql = "SELECT venue_id, name, city, country FROM venue ORDER BY name";

        try (PreparedStatement stmt = conn.prepareStatement(sql); ResultSet rs = stmt.executeQuery()) {
            System.out.println("\n--- Venues ---");
            boolean any = false;
            while (rs.next()) {
                any = true;
                System.out.printf("[%d] %s - %s, %s%n",
                    rs.getInt("venue_id"),
                    rs.getString("name"),
                rs.getString("city"),
                rs.getString("country"));
            }
            if (!any) {
                System.out.println("(no venues yet - load sample data first.");
            }
        }
    }
}
