package com.example.llmservice.domain.routing;

/**
 * Value object representing the routing score used for model selection.
 * Lower scores are better (lower latency + connection penalty).
 */
public class RoutingScore {
    private final double emaLatencyMs;
    private final int activeConnections;
    private final double connectionPenaltyPerConnection;
    private final double calculatedScore;

    public RoutingScore(double emaLatencyMs, int activeConnections, double connectionPenaltyPerConnection) {
        this.emaLatencyMs = emaLatencyMs;
        this.activeConnections = activeConnections;
        this.connectionPenaltyPerConnection = connectionPenaltyPerConnection;
        this.calculatedScore = emaLatencyMs + (activeConnections * connectionPenaltyPerConnection);
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
    public static final java.util.Comparator<RoutingScore> BY_SCORE_ASC = 
            java.util.Comparator.comparingDouble(RoutingScore::getCalculatedScore);
}