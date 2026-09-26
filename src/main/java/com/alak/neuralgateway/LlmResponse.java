package com.alak.neuralgateway;

public record LlmResponse(String transactionId, String modelUsed, String text) {}
