# Glossary of Terms

This glossary defines key terms used throughout the Neural Gateway project.

## A

**API Key**: A secret token used to authenticate with LLM provider APIs (NVIDIA NIM, Experiential Labs, Antseed).

**Auto Pipeline**: A routing pipeline (`model: "auto"`) that automatically determines the appropriate pipeline (reasoning, coding, vision) based on request content analysis.

## C

**Circuit Breaker**: A pattern used to detect failures and prevent cascading failures. When a model fails repeatedly, its circuit breaker trips to OPEN, preventing further requests for a cooldown period.

**Context Window**: The maximum number of tokens a language model can process in a single request. Exceeding this limit typically results in truncation or errors.

## E

**EMA (Exponential Moving Average)**: A type of moving average that gives more weight to recent data points, used in Neural Gateway for latency calculation.

## H

**Health Check**: A periodic probe sent to LLM providers to verify their availability and responsiveness. Results are used to update model status.

## L

**LLM (Large Language Model)**: An AI model capable of understanding and generating human-like text, such as those provided by NVIDIA NIM, Experiential Labs, and Antseed.

**Latency**: The time delay between sending a request and receiving a response, typically measured in milliseconds.

## M

**Model Registry**: A service that maintains the catalog of available models, their properties (pipelines, priority, enabled status), and their current health status.

**Pipeline**: A group of models specialized for a particular type of task (reasoning, coding, vision). Requests are routed to models within the specified pipeline.

## P

**Provider**: A company or service that offers access to LLM APIs (e.g., NVIDIA NIM, Experiential Labs, Antseed).

**Priority**: A numeric value assigned to models within a pipeline that determines their selection order when multiple models are available. Higher numbers indicate higher priority.

## R

**Reasoning Pipeline**: A routing pipeline (`model: "reasoning"`) optimized for complex multi-step reasoning tasks.

**Routing Score**: A calculated value used to select the best available model for a request. Combines EMA latency with an active connection penalty: `score = emaLatency + (activeConnections * 300ms)`.

## S

**SSE (Server-Sent Events)**: A technology used for pushing real-time updates from the server to clients, used in Neural Gateway for model status updates.

**StatusFresh**: A boolean indicator showing whether a model's health status has been checked within the freshness window (default 24 hours).

## T

**Telemetry**: Data collected about system performance, usage, and behavior, including latency, error rates, and request patterns.

**Throughput**: The number of requests processed successfully per unit of time, typically measured in requests per second (RPS).

## V

**Vision Pipeline**: A routing pipeline (`model: "vision"`) optimized for multimodal tasks involving both text and image inputs.

## W

**WSL Relay**: A process on Windows systems running Docker Desktop with WSL 2 that can interfere with localhost connections. Requires using `127.0.0.1` instead of `localhost` for proper connectivity.