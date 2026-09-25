package com.example.llmservice;

public record LlmResponse(String transactionId, String modelUsed, String text) {}
