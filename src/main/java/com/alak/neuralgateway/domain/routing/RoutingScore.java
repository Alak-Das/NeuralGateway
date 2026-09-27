package com.alak.neuralgateway.domain.routing;

/**
 * Value object representing the routing score used for model selection.
 * Lower scores are better (lower latency + connection penalty).
 */
public class RoutingScore implements Comparable<RoutingScore> {
    private final double emaLatencyMs;
    private final int activeConnections;
    private final double connectionPenaltyPerConnection;
    private final int priority;
    private final double calculatedScore;

    public RoutingScore(double emaLatencyMs, int activeConnections, double connectionPenaltyPerConnection, int priority) {
        this.emaLatencyMs = emaLatencyMs;
        this.activeConnections = activeConnections;
        this.connectionPenaltyPerConnection = connectionPenaltyPerConnection;
        this.priority = priority > 0 ? priority : 1;
        this.calculatedScore = (emaLatencyMs + (activeConnections * connectionPenaltyPerConnection)) / this.priority;
    }

    public double getEmaLatencyMs() {
        return emaLatencyMs;
    }

    public int getActiveConnections() {
        return activeConnections;
    }

    public double getConnectionPenaltyPerConnection() {
        return connectionPenaltyPerConnection;
    }

    public double getCalculatedScore() {
        return calculatedScore;
    }

    @Override
    public String toString() {
        return "RoutingScore{" +
                "emaLatencyMs=" + emaLatencyMs +
                ", activeConnections=" + activeConnections +
                ", connectionPenaltyPerConnection=" + connectionPenaltyPerConnection +
                ", calculatedScore=" + calculatedScore +
                '}';
    }

    /**
     * Comparator for sorting by score (ascending - lower is better).
     */

    @Override
    public int compareTo(RoutingScore other) {
        // 1. Highest Priority wins (Descending)
        int priorityCompare = Integer.compare(other.priority, this.priority);
        if (priorityCompare != 0) return priorityCompare;

        // 2. Lowest Active Connections wins (Load Balancing) (Ascending)
        int connectionCompare = Integer.compare(this.activeConnections, other.activeConnections);
        if (connectionCompare != 0) return connectionCompare;

        // 3. Lowest Latency wins (Speed Tie-breaker) (Ascending)
        return Double.compare(this.emaLatencyMs, other.emaLatencyMs);
    }
}