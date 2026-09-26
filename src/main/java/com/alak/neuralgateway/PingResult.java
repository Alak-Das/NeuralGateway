package com.alak.neuralgateway;

import java.time.Instant;

public record PingResult(Instant timestamp, boolean isUp, long latencyMs) {}
