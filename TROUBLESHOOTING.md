# Troubleshooting Guide

This guide helps you diagnose and resolve common issues with Neural Gateway.

## Common Issues

### 1. Connection Problems

#### Symptoms
- Gateway fails to start
- Cannot connect to Redis
- API endpoints return 502/503 errors

#### Solutions
- **Redis Connection Failed**
  ```bash
  # Check if Redis is running
  docker ps | grep redis
  
  # If not running, start it
  docker-compose up -d redis
  
  # Test connection
  redis-cli ping
  # Should return PONG
  ```
  
- **Port Already in Use**
  ```bash
  # Check what's using port 8080
  netstat -ano | findstr :8080
  
  # Either stop the conflicting process or change the port in application.yml
  ```

#### Symptoms
- Slow response times
- High latency metrics in dashboard

#### Solutions
- **Check Health Check Frequency**
  - Health checks every 2 minutes for NVIDIA models are normal
  - If seeing latency spikes, check if they align with health check times
  
- **Circuit Breaker Tripping**
  ```bash
  # Check dashboard for OPEN circuits
  # If many models show OPEN circuits, upstream providers may be experiencing issues
  ```
  
- **Insufficient Resources**
  ```bash
  # Check memory usage
  # Ensure JVM has enough memory allocated
  ```

### 2. Model Routing Issues

#### Symptoms
- Requests failing with "All models in requested category are unavailable"
- Getting 400 Bad Request errors
- Receiving unexpected model responses

#### Solutions
- **Model Unavailable**
  ```bash
  # Check /api/models/status endpoint
  # Verify models in requested pipeline are AVAILABLE
  # Check health check logs for probing failures
  ```
  
- **400 Bad Request**
  ```bash
  # Verify request format matches OpenAPI specification
  # Check if model parameter is valid for the pipeline
  # Ensure max_tokens doesn't exceed model context window
  ```
  
- **Wrong Model Selected**
  ```bash
  # Check routing logs
  # Verify PipelineResolverService is correctly categorizing requests
  # Check that model priorities are set as expected
  ```

### 3. Configuration Issues

#### Symptoms
- Application fails to start with configuration errors
- Environment variables not being picked up
- Unexpected behavior despite configuration changes

#### Solutions
- **Missing Environment Variables**
  ```bash
  # Check .env file exists and is formatted correctly
  # Verify variable names match those in application.yml
  # Example: NVIDIA_API_KEY_1 should match ${NVIDIA_API_KEY_1:}
  ```
  
- **Configuration Not Reloaded**
  ```bash
  # Remember that changes to application.yml require restart
  # Changes to .env file require restart unless using spring-cloud-config
  ```
  
- **Invalid YAML**
  ```bash
  # Validate application.yml syntax
  # Use online YAML validator or IDE plugin
  ```

### 4. Performance Issues

#### Symptoms
- High CPU usage
- Memory leaks
- Degraded throughput over time

#### Solutions
- **High CPU**
  ```bash
  # Check if health check threads are running too frequently
  # Review health-check-interval-ms settings
  # Check for infinite loops in custom code
  ```
  
- **Memory Leaks**
  ```bash
  # Monitor memory usage over time
  # Check if telemetry data retention is working
  # Verify Redis persistence service is cleaning old data
  ```
  
- **Degraded Throughput**
  ```bash
  # Check for blocked threads
  # Review circuit breaker states
  # Check Redis connection pool exhaustion
  ```

### 5. Docker/Docker-Compose Issues

#### Symptoms
- Containers failing to start
- Volume mounting issues
- Network connectivity problems between containers

#### Solutions
- **Container Won't Start**
  ```bash
  # Check container logs
  docker-compose logs neural-gateway
  
  # Common issues:
  # - Missing environment variables
  # - Port conflicts
  # - Invalid configuration
  ```
  
- **Network Issues**
  ```bash
  # Verify containers are on same network
  docker-compose ps
  
  # Test connectivity between containers
  docker-compose exec neural-gateway ping redis
  ```

## Diagnostic Commands

### Health Check Endpoints
```bash
# Get model status
curl http://localhost:9090/api/models/status

# Get detailed health info
curl http://localhost:9090/actuator/health

# Get metrics
curl http://localhost:9090/actuator/metrics
```

### Logging
```bash
# Enable debug logging temporarily
# Set logging.level.com.alak.neuralgateway=DEBUG in application.yml

# View logs
docker-compose logs -f neural-gateway
```

### Redis Inspection
```bash
# Connect to Redis
docker-compose exec redis redis-cli

# Check gateway-related keys
KEYS neuralgateway*

# Check specific model status
HGETALL neuralgateway:model:nvidia/nemotron-3-super-120b-a12b:status
```

## When to Seek Help

If you've tried the above solutions and still experience issues:

1. Check the [GitHub Issues](https://github.com/yourusername/neural-gateway/issues) for similar problems
2. Gather:
   - Version of Neural Gateway
   - Environment details (OS, Java, Docker versions)
   - Relevant logs (from application and Docker containers)
   - Steps to reproduce the issue
   - Configuration files (with secrets redacted)
3. Open a new issue with this information

## Emergency Procedures

### Complete Gateway Failure
1. Check if Redis is running: `docker-compose ps redis`
2. Restart the gateway: `docker-compose restart neural-gateway`
3. Check logs for startup errors: `docker-compose logs neural-gateway`
4. If persistent, restore from backup or redeploy

### Data Loss/Corruption
1. Neural Gateway is designed to be stateless except for Redis
2. Redis persistence is configured to save to disk (`./redis-data`)
3. To restore: Ensure `redis-data` volume is mounted and contains latest dump.rdb
4. Restart Redis then gateway

## 6. Telemetry/Tracing Issues

#### Symptoms
- Live Traces tab shows no data or "No traces available"
- Missing trace data in `/api/telemetry/traces` endpoint
- Dashboard fails to load trace data
- High memory usage related to trace storage

#### Solutions
- **Check Telemetry Endpoint**
  ```bash
  # Verify the telemetry endpoint is accessible
  curl http://localhost:9090/api/telemetry/traces
  # Should return JSON array (may be empty if no traces)
  ```

- **Check Frontend Console**
  - Open browser developer tools (F12)
  - Check for JavaScript errors in the console
  - Verify that the LiveLogs component is making requests to `/api/telemetry/traces`

- **Verify Backend Telemetry Collection**
  ```bash
  # Check if telemetry collection is enabled
  # Look for telemetry-related logs in the application
  docker-compose logs -f neural-gateway | grep -i telemet
  ```

- **Check Memory Usage for Traces**
  ```bash
  # Monitor memory usage over time
  # If memory grows unbounded, trace cleanup may not be working
  # Check trace retention settings in application.yml
  ```

## FAQ

**Q: Why am I getting "All models in requested category are unavailable" even though models show as AVAILABLE?**
A: This can happen when:
- All AVAILABLE models have OPEN circuit breakers (recent failures)
- Models are AVAILABLE but disabled via `enabled: false` in configuration
- Health check shows AVAILABLE but routing logic filters for other reasons (context window, etc.)

**Q: How do I update API keys without downtime?**
1. Update the values in your .env file
2. Restart the gateway: `docker-compose restart neural-gateway`
3. The gateway will pick up new keys on startup
4. For zero-downtime, you would need to implement hot-reloading (not currently supported)

**Q: Can I change the health check interval?**
Yes, adjust `llm.health-check.intervalMs` in application.yml or via environment variable `LLM_HEALTH_CHECK_INTERVAL_MS`.
Lower values increase upstream load; higher values mean slower failure detection.