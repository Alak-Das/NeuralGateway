# Neural Gateway Runbook

Operational procedures for running and maintaining Neural Gateway in production.

## Overview

Neural Gateway is a Spring Boot application that provides intelligent LLM routing with health checking, circuit breaking, and failover capabilities.

## Architecture Components

- **API Layer**: REST endpoints (`/v1/chat/completions`, `/v1/models`, etc.)
- **Service Layer**: Business logic (routing, health checking, circuit breaking)
- **Infrastructure Layer**: Redis for state persistence, HTTP clients for LLM providers
- **Cross-cutting Concerns**: Logging, monitoring, telemetry
- **External Integrations**: NVIDIA NIM, Experiential Labs, Antseed APIs

## Deployment

### Development Environment
```bash
# Start all services
docker-compose up -d

# View logs
docker-compose logs -f

# Stop all services
docker-compose down
```

### Production Deployment Considerations

#### Resource Requirements
- **Memory**: Minimum 512MB, recommended 1GB+
- **CPU**: 2+ cores recommended for production load
- **Storage**: Minimal for application, Redis persistence requires disk space
- **Network**: Outbound connectivity to LLM provider APIs

#### Configuration
- Use environment variables for secrets (never commit to version control)
- Tune health check intervals based on provider rate limits
- Adjust circuit breaker thresholds based on traffic patterns
- Set appropriate Redis persistence settings

#### Scaling
- Horizontal scaling is supported via Redis for state sharing
- Ensure sticky sessions or shared state for WebSocket connections if used
- Load balancer should support health checks to `/api/models/status`

## Monitoring

### Key Metrics to Watch

#### Application Metrics
- **Request Rate**: Requests per second to `/v1/chat/completions`
- **Error Rate**: Percentage of non-2xx responses
- **Latency**: P50, P95, P99 response times
- **Throughput**: Successful responses per second

#### Model Health Metrics
- **Model Availability**: Percentage of models showing AVAILABLE
- **Circuit Breaker State**: Number of OPEN/HALF_OPEN circuits
- **Health Check Success Rate**: Percentage of successful health probes
- **Failover Frequency**: How often requests are redirected due to failures

#### Infrastructure Metrics
- **Redis**: Memory usage, connection count, hit/miss ratio
- **System**: CPU usage, memory usage, disk I/O, network throughput
- **Provider APIs**: Latency, error rates, rate limit hits

### Health Check Endpoints

```bash
# Basic health
curl http://localhost:9090/actuator/health

# Detailed health
curl http://localhost:9090/actuator/health/detail

# Model status
curl http://localhost:9090/api/models/status

# Metrics (Prometheus format if enabled)
curl http://localhost:9090/actuator/prometheus
```

### Alerting Rules

#### Critical Alerts
- **Gateway Down**: HTTP 5xx or connection refused for > 2 minutes
- **All Models Unavailable**: 0% model availability in any pipeline for > 5 minutes
- **High Error Rate**: > 5% error rate for > 10 minutes
- **Circuit Breaker Storm**: > 50% of models in OPEN state for > 5 minutes

#### Warning Alerts
- **Elevated Latency**: P95 latency > 2x baseline for > 10 minutes
- **Degraded Performance**: Success rate < 95% for > 15 minutes
- **Resource Pressure**: CPU > 80% or Memory > 85% for > 10 minutes
- **Health Check Issues**: Health check success rate < 90% for > 20 minutes

## Routine Operations

### Daily Checks
1. Verify gateway is responding to health checks
2. Check dashboard for model availability and circuit states
3. Review error rates and latency metrics
4. Confirm Redis is persisting data correctly
5. Review logs for unusual patterns

### Weekly Checks
1. Review trend graphs for resource usage
2. Check for any models consistently showing issues
3. Verify backup of Redis data (if applicable)
4. Review security logs and access patterns
5. Check for available dependency updates

### Monthly Checks
1. Capacity planning review
2. Security audit of configurations and access
3. Performance tuning review
4. Disaster recovery procedure test
5. Documentation review and updates

## Incident Response

### Incident Classification

#### Severity 1 (Critical)
- Complete gateway outage affecting all users
- Data loss or corruption
- Security breach

#### Severity 2 (High)
- Significant degradation (> 50% error rate or latency increase)
- Partial outage affecting specific pipelines/features
- Repeated failures requiring manual intervention

#### Severity 3 (Medium)
- Minor degradation noticeable to power users
- Isolated issues affecting small user subset
- Performance below SLO but not impacting core functionality

#### Severity 4 (Low)
- Cosmetic issues
- Documentation errors
- Minor usability improvements

### Response Procedure

#### 1. Detection
- Monitoring alerts trigger
- User reports received
- Manual health checks reveal issues

#### 2. Initial Response (First 15 minutes)
- Acknowledge incident
- Assign incident commander
- Begin impact assessment
- Check basic health endpoints
- Verify infrastructure status

#### 3. Investigation (15-60 minutes)
- Collect logs and metrics
- Check model status and circuit breakers
- Review recent changes/deployments
- Check provider status pages
- Identify root cause hypothesis

#### 4. Mitigation (60-120 minutes)
- Implement workaround or fix
- Roll back recent changes if applicable
- Failover to backup systems if configured
- Apply temporary configuration changes
- Monitor for improvement

#### 5. Resolution (+120 minutes)
- Implement permanent fix
- Verify solution resolves root cause
- Return to normal operations
- Conduct post-incident review

### Communication Plan

#### Internal
- Status updates every 30 minutes during active incident
- Use designated communication channels (Slack, email, etc.)
- Maintain incident timeline in shared document
- Notify stakeholders per severity level

#### External (if applicable)
- Status page updates per incident severity
- Follow communication timing guidelines
- Provide transparent information without compromising security
- Offer compensation or credits per SLA if applicable

## Backup and Disaster Recovery

### Backup Procedures
Neural Gateway state is primarily stored in Redis:
- Application configuration: Stored in environment variables and application.yml
- Model status and circuit breaker state: Stored in Redis
- Telemetry data: Stored in Redis with TTL
- Persistent user data: None (stateless design)

#### Redis Backup
```bash
# Manual backup
docker-compose exec redis redis-cli SAVE

# Or copy the dump file
cp ./redis-data/dump.rdb ./backups/redis-dump-$(date +%Y%m%d-%H%M%S).rdb
```

#### Automated Backup (Example Cron Job)
```bash
# Daily backup at 2 AM
0 2 * * * cp /path/to/redis-data/dump.rdb /path/to/backups/redis-dump-$(date +\%Y\%m\%d).rdb
```

### Recovery Procedures

#### From Redis Backup
1. Stop gateway: `docker-compose stop neural-gateway`
2. Stop Redis: `docker-compose stop redis`
3. Replace dump.rdb: `cp ./backups/redis-dump-YYYYMMDD.rdb ./redis-data/dump.rdb`
4. Start Redis: `docker-compose start redis`
5. Start gateway: `docker-compose start neural-gateway`
6. Verify model status and telemetry

#### Complete Environment Recovery
1. Recreate infrastructure (network, volumes)
2. Pull latest code: `git pull`
3. Build application: `./mvnw package -DskipTests`
4. Start Redis: `docker-compose up -d redis`
5. Start gateway: `docker-compose up -d neural-gateway`
6. Verify functionality with health checks

## Performance Tuning

### JVM Settings
Adjust in `docker-compose.yml` or startup script:
```bash
# Example JVM options
JAVA_OPTS="-Xms512m -Xmx1024m -XX:+UseG1GC -XX:MaxGCPauseMillis=50"
```

### Redis Optimization
- Monitor memory usage with `redis-cli INFO memory`
- Consider Redis clustering for high availability
- Tune save points based on data criticality
- Monitor persistence performance

### Application-Level Tuning
#### Health Check Settings
- `llm.health-check.intervalMs`: Balance between freshness and provider load
- `llm.health-check.max-models-per-sweep`: Prevent sweep from taking too long
- Provider-specific intervals: Adjust based on provider rate limits

#### Circuit Breaker Settings
- Failure threshold: Number of failures before opening
- Timeout: Duration in OPEN state before trying HALF_OPEN
- Sliding window: Size of window for failure counting
- Minimum number of calls: Minimum calls needed to calculate failure rate

#### Routing Optimization
- Context window validation: Prevents wasted requests on models with insufficient context
- Priority tuning: Adjust based on model performance and cost
- Load balancing formula: `score = emaLatency + (activeConnections * penalty)`

## Security Operations

### Secret Management
- API keys stored in environment variables or .env file
- Consider using HashiCorp Vault, AWS Secrets Manager, or similar in production
- Rotate keys periodically
- Audit key usage via provider dashboards

### Network Security
- Restrict outbound traffic to only required LLM provider endpoints
- Use firewalls to limit inbound traffic to application ports
- Consider API gateway or service mesh for additional security
- Enable HTTPS/TLS for all external communications

### Monitoring and Logging
- Enable audit logging for sensitive operations
- Monitor for brute force or abuse patterns
- Log and alert on configuration changes
- Regularly review access logs for anomalies

### Vulnerability Management
- Monitor dependency vulnerabilities via tools like Dependabot
- Apply security patches promptly
- Conduct periodic security reviews
- Follow responsible disclosure process for reporting vulnerabilities

## Troubleshooting References

For detailed troubleshooting procedures, see [TROUBLESHOOTING.md](TROUBLESHOOTING.md)

## Change Management

### Deployment Process
1. Code review and approval
2. Automated testing (unit, integration, benchmark)
3. Staging environment validation
4. Production deployment during low-traffic window
5. Smoke test verification
6. Monitoring for anomalies
7. Rollback plan ready

### Rollback Procedure
1. Identify problematic deployment
2. Retrieve previous known-good version
3. Deploy previous version: `docker-compose up -d --no-deps --build neural-gateway`
4. Verify functionality
5. Investigate root cause of failed deployment
6. Schedule fixed version deployment

## Contact Information

### Primary Contacts
- Platform Team: [platform-team@example.com](mailto:platform-team@example.com)
- On-Call Engineer: [See rotation schedule]
- Application Owner: [app-owner@example.com](mailto:app-owner@example.com)

### Emergency Contacts
- Production Emergency: [prod-emergency@example.com](mailto:prod-emergency@example.com)
- Security Emergency: [security@example.com](mailto:security@example.com)

### External Dependencies
- NVIDIA NIM Status: [status.nvidia.com/nim](https://status.nvidia.com/nim)
- Experiential Labs Status: [status.experientiallabs.ai](https://status.experientiallabs.ai)
- Antseed Status: [Check provider dashboard]
- Redis Documentation: [redis.io/documentation](https://redis.io/documentation)