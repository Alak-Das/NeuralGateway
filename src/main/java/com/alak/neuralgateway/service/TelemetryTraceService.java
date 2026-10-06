package com.alak.neuralgateway.service;

import org.springframework.stereotype.Service;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.List;
import java.util.ArrayList;

@Service
public class TelemetryTraceService {

    public record TraceLog(
            long timestamp,
            String requester,
            String model,
            long latencyMs,
            boolean success,
            String pipeline,
            long tokenCount,
            int httpStatus,
            String endpoint
    ) {}

    private final ConcurrentLinkedDeque<TraceLog> traces = new ConcurrentLinkedDeque<>();
    private static final int MAX_TRACES = 100;

    public void recordTrace(String requester, String model, long latencyMs, boolean success, String pipeline, long tokenCount, int httpStatus, String endpoint) {
        if (requester == null) requester = "System";
        traces.addFirst(new TraceLog(System.currentTimeMillis(), requester, model, latencyMs, success, pipeline, tokenCount, httpStatus, endpoint));
        while (traces.size() > MAX_TRACES) {
            traces.pollLast();
        }
    }

    // Overloaded method for backward compatibility
    public void recordTrace(String requester, String model, long latencyMs, boolean success, String pipeline) {
        recordTrace(requester, model, latencyMs, success, pipeline, 0, 0, "");
    }

    public List<TraceLog> getLatestTraces() {
        return new ArrayList<>(traces);
    }
}