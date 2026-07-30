package session;

// Holds information about the currently signed-in user of the app.
// Exists so that user_id, full_name, and role only need to be stored once and does not require
// us to query the database everytime we want to perform an operation.

public record Session(int userId, String fullName, String role) {
    public boolean isCustomer() {
        return "CUSTOMER".equals(role);
    }

    public boolean isOrganizer() {
        return "ORGANIZER".equals(role);
    }
}
