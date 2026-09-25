package com.example.llmservice;

import java.time.Instant;
import java.util.List;

public record ModelStatus(
    String model, 
    List<String> categories,
    boolean isUp, 
    long latencyMs, 
    Instant lastChecked, 
    String errorMessage, 
    List<PingResult> history, 
    long totalUses,
    int activeConnections,
    double tps,
    boolean circuitOpen
) {}
