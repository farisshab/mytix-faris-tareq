package ops;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Scanner;

import util.InputUtil;

// Organizer toolkit: suggest a tier structure and prices for a new performance,
// based on comparable past performances (same genre, similar venue capacity, same
// city, recent). The strategy and its assumptions are in docs/report_design.md.
// The extra credit revenue-change estimate is a separate piece, to be added later.
public class ToolkitOps {

    // How many comparables we want before we stop widening the search.
    private static final int TARGET_COMPARABLES = 5;
    // Tier count is clamped to this range whatever the comparables say.
    private static final int MIN_TIERS = 1, MAX_TIERS = 4;

    // One rung of the widening ladder. Genre is never relaxed; we loosen the
    // geography first, then the capacity band, then how far back we look.
    private record Rung(String scope, int capBandPct, int months, String describe) {}

    private static final Rung[] LADDER = {
        new Rung("CITY",    40, 24, "same city, within 40% capacity, last 24 months"),
        new Rung("COUNTRY", 40, 24, "same country, within 40% capacity, last 24 months"),
        new Rung("ANY",     40, 24, "anywhere, within 40% capacity, last 24 months"),
        new Rung("ANY",     60, 24, "anywhere, within 60% capacity, last 24 months"),
        new Rung("ANY",    100, 24, "anywhere, within 100% capacity, last 24 months"),
        new Rung("ANY",    100, 36, "anywhere, within 100% capacity, last 36 months"),
    };

    // Venue total capacity: reserved seats plus GA capacity. Reused a few times below.
    private static final String VENUE_CAP =
        "SELECT v.venue_id, COALESCE(res.seats,0) + COALESCE(ga.cap,0) AS capacity FROM venue v " +
        "LEFT JOIN (SELECT s.venue_id, COUNT(*) seats FROM section s " +
        "           JOIN seat_row r ON r.section_id=s.section_id JOIN seat se ON se.row_id=r.row_id " +
        "           WHERE s.section_type='RESERVED' GROUP BY s.venue_id) res ON res.venue_id=v.venue_id " +
        "LEFT JOIN (SELECT venue_id, SUM(ga_capacity) cap FROM section WHERE section_type='GA' GROUP BY venue_id) ga " +
        "  ON ga.venue_id=v.venue_id";

    public static void suggest(Connection conn, Scanner scanner) throws SQLException {
        Integer eventId = InputUtil.promptInt(scanner, "Event ID (for its genre) > ");
        if (eventId == null) return;
        Integer venueId = InputUtil.promptInt(scanner, "Venue ID (where the performance will run) > ");
        if (venueId == null) return;

        // Genre of the event, and the target venue's city + capacity.
        int genreId; String genreName, city; int targetCap; String country;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT g.genre_id, g.genre_name FROM event e JOIN genre g ON g.genre_id=e.genre_id WHERE e.event_id=?")) {
            ps.setInt(1, eventId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) { System.out.println("No such event."); return; }
                genreId = rs.getInt("genre_id");
                genreName = rs.getString("genre_name");
            }
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT v.city, v.country, cap.capacity FROM venue v " +
                "JOIN (" + VENUE_CAP + ") cap ON cap.venue_id=v.venue_id WHERE v.venue_id=?")) {
            ps.setInt(1, venueId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) { System.out.println("No such venue."); return; }
                city = rs.getString("city");
                country = rs.getString("country");
                targetCap = rs.getInt("capacity");
            }
        }
        if (targetCap <= 0) { System.out.println("That venue has no sections/seats to price against."); return; }

        // Walk the ladder until we clear the target count; keep the widest if we never do.
        List<Integer> comparables = List.of();
        String rungUsed = null;
        for (Rung r : LADDER) {
            List<Integer> found = findComparables(conn, genreId, targetCap, city, country, r);
            if (found.size() >= comparables.size()) { comparables = found; rungUsed = r.describe(); }
            if (found.size() >= TARGET_COMPARABLES) { comparables = found; rungUsed = r.describe(); break; }
        }

        boolean marketDefault = comparables.isEmpty();
        if (marketDefault) rungUsed = "no close comparables, market-wide default";

        // Tier count: median across comparables, clamped. Default 3 when we have nothing.
        int k = marketDefault ? 3 : clampTiers(median(tierCounts(conn, comparables)));

        // Prices: percentile ladder over pooled sold-ticket face values. When there are
        // no comparables we fall back to the whole market's sold tickets.
        List<Double> faceValues = soldFaceValues(conn, comparables);
        double[] prices = priceLadder(faceValues, k);

        // Capacity shares: averaged over the comparables that use exactly k tiers.
        // If none match (or market default), use a simple front-loaded default shape.
        double[] shares = marketDefault ? defaultShares(k) : capacityShares(conn, comparables, k);
        if (shares == null) shares = defaultShares(k);

        printSuggestion(genreName, city, targetCap, comparables.size(), rungUsed, k, prices, shares);
    }

    // Comparable performance ids for one rung. Genre and "past, non-cancelled" are fixed.
    private static List<Integer> findComparables(Connection conn, int genreId, int targetCap,
                                                 String city, String country, Rung r) throws SQLException {
        String geo = switch (r.scope()) {
            case "CITY" -> " AND v.city = ? ";
            case "COUNTRY" -> " AND v.country = ? ";
            default -> " ";
        };
        String sql =
            "WITH venue_cap AS (" + VENUE_CAP + ") " +
            "SELECT p.performance_id FROM performance p " +
            "JOIN event e ON e.event_id=p.event_id " +
            "JOIN venue v ON v.venue_id=p.venue_id " +
            "JOIN venue_cap vc ON vc.venue_id=v.venue_id " +
            "WHERE e.genre_id=? AND p.status='SCHEDULED' AND p.performance_datetime < NOW() " +
            "  AND p.performance_datetime >= NOW() - INTERVAL ? MONTH " +
            "  AND vc.capacity BETWEEN ? AND ? " + geo;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int i = 1;
            ps.setInt(i++, genreId);
            ps.setInt(i++, r.months());
            ps.setDouble(i++, targetCap * (1 - r.capBandPct() / 100.0));
            ps.setDouble(i++, targetCap * (1 + r.capBandPct() / 100.0));
            if (r.scope().equals("CITY")) ps.setString(i++, city);
            else if (r.scope().equals("COUNTRY")) ps.setString(i++, country);
            List<Integer> ids = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) ids.add(rs.getInt(1));
            }
            return ids;
        }
    }

    // Tier count means tiers that actually have sections assigned, the same structure
    // the capacity shares are built from, so the two never disagree.
    // A price tier with no section assigned yet is not a usable tier for this suggestion.
    private static List<Integer> tierCounts(Connection conn, List<Integer> perfIds) throws SQLException {
        List<Integer> counts = new ArrayList<>();
        String sql = "SELECT COUNT(DISTINCT tier_id) c FROM performance_section_tier WHERE performance_id IN (" +
                     placeholders(perfIds.size()) + ") GROUP BY performance_id";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bindInts(ps, perfIds);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) counts.add(rs.getInt("c"));
            }
        }
        return counts;
    }

    private static List<Double> soldFaceValues(Connection conn, List<Integer> perfIds) throws SQLException {
        List<Double> values = new ArrayList<>();
        String where = perfIds.isEmpty()
            ? "1=1"                                   // market-wide fallback: all past sold tickets
            : "o.performance_id IN (" + placeholders(perfIds.size()) + ")";
        String sql = "SELECT t.face_value FROM ticket t JOIN orders o ON o.order_id=t.order_id " +
                     "WHERE t.status='ACTIVE' AND " + where + " ORDER BY t.face_value";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            if (!perfIds.isEmpty()) bindInts(ps, perfIds);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) values.add(rs.getDouble(1));
            }
        }
        return values;
    }

    // Average per-rank capacity share over the comparables that use exactly k tiers.
    // Returns null when no comparable matches k.
    private static double[] capacityShares(Connection conn, List<Integer> perfIds, int k) throws SQLException {
        String sql =
            "SELECT pt.performance_id, pt.price, " +
            "       SUM(CASE WHEN s.section_type='RESERVED' THEN sc.seats ELSE s.ga_capacity END) AS tier_capacity " +
            "FROM price_tier pt " +
            "JOIN performance_section_tier pst ON pst.performance_id=pt.performance_id AND pst.tier_id=pt.tier_id " +
            "JOIN section s ON s.section_id=pst.section_id " +
            "LEFT JOIN (SELECT r.section_id, COUNT(*) seats FROM seat se JOIN seat_row r ON r.row_id=se.row_id GROUP BY r.section_id) sc " +
            "  ON sc.section_id=s.section_id " +
            "WHERE pt.performance_id IN (" + placeholders(perfIds.size()) + ") " +
            "GROUP BY pt.performance_id, pt.tier_id, pt.price";

        // Collect per performance: list of (price, capacity).
        java.util.Map<Integer, List<double[]>> perfTiers = new java.util.HashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bindInts(ps, perfIds);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int pid = rs.getInt("performance_id");
                    perfTiers.computeIfAbsent(pid, x -> new ArrayList<>())
                             .add(new double[]{ rs.getDouble("price"), rs.getDouble("tier_capacity") });
                }
            }
        }

        double[] sum = new double[k];
        int used = 0;
        for (List<double[]> tiers : perfTiers.values()) {
            if (tiers.size() != k) continue;                 // only average like-structured comparables
            tiers.sort((a, b) -> Double.compare(b[0], a[0])); // price descending: rank 0 = premium
            double total = 0;
            for (double[] t : tiers) total += t[1];
            if (total <= 0) continue;
            for (int i = 0; i < k; i++) sum[i] += tiers.get(i)[1] / total;
            used++;
        }
        if (used == 0) return null;
        for (int i = 0; i < k; i++) sum[i] /= used;
        return sum;
    }

    // helper functions

    // Price for each tier from evenly spaced percentile bands of the pooled face values.
    // Tier 0 is the premium (top band). Rounded to the nearest $5.
    private static double[] priceLadder(List<Double> sortedValues, int k) {
        double[] prices = new double[k];
        if (sortedValues.isEmpty()) return prices;
        int n = sortedValues.size();
        for (int tier = 0; tier < k; tier++) {
            // tier 0 = premium = highest band, so read from the top down
            int band = k - 1 - tier;
            double p = (band + 0.5) / k; // band midpoint percentile
            int idx = (int) Math.floor(p * (n - 1));
            prices[tier] = Math.round(sortedValues.get(idx) / 5.0) * 5.0;
        }
        // Keep tiers strictly descending: if the bands collapsed (e.g. most tickets sold at one price),
        // nudge each lower tier below the one above it.
        for (int tier = 1; tier < k; tier++) {
            if (prices[tier] >= prices[tier - 1]) prices[tier] = Math.max(0, prices[tier - 1] - 5);
        }
        return prices;
    }

    private static double[] defaultShares(int k) {
        // Front-loaded but not extreme: a bit more room in the middle/value tiers.
        if (k == 1) return new double[]{1.0};
        if (k == 2) return new double[]{0.4, 0.6};
        if (k == 3) return new double[]{0.25, 0.45, 0.30};
        return new double[]{0.15, 0.30, 0.35, 0.20};
    }

    private static int clampTiers(int k) { return Math.max(MIN_TIERS, Math.min(MAX_TIERS, k)); }

    private static int median(List<Integer> xs) {
        if (xs.isEmpty()) return 3;
        Collections.sort(xs);
        return xs.get(xs.size() / 2);
    }

    private static String placeholders(int n) {
        return String.join(",", Collections.nCopies(n, "?"));
    }

    private static void bindInts(PreparedStatement ps, List<Integer> ids) throws SQLException {
        for (int i = 0; i < ids.size(); i++) ps.setInt(i + 1, ids.get(i));
    }

    private static void printSuggestion(String genre, String city, int targetCap, int nComparables,
                                        String rung, int k, double[] prices, double[] shares) {
        System.out.printf("%n--- Pricing suggestion: %s in %s (capacity %d) ---%n", genre, city, targetCap);
        if (nComparables == 0) {
            System.out.println("No close comparables found, so this appears to be a market-wide default. Treat it as a rough starting point.");
        } else {
            System.out.printf("Based on %d comparable %s performance(s): %s.%n", nComparables, genre, rung);
        }
        System.out.printf("Suggested structure: %d tier(s)%n", k);
        for (int i = 0; i < k; i++) {
            int seats = (int) Math.round(shares[i] * targetCap);
            String label = (i == 0 ? "premium" : i == k - 1 ? "value" : "mid");
            System.out.printf("  Tier %d (%-7s)  $%-7.0f  ~%2.0f%% of capacity  (~%d seats)%n",
                i + 1, label, prices[i], shares[i] * 100, seats);
        }
    }
}
