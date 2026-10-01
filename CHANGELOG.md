# Changelog

All notable changes to Neural Gateway are documented in this file.

---

## [Unreleased] — 2026-01-10

### Fixed

- **UI Build Errors** - Fixed TypeScript/build errors in React components:
  - Removed unused variables and imports from `Charts.tsx` (`latencyLineColor`, `latencyFillColor`, `errorLineColor`, `errorFillColor`, `backgroundProbeColor`, `backgroundProbeFillColor`)
  - Removed unused imports and variables from `KpiGrid.tsx` (`formatTimeAgo`, `themeColors`, `fifteenMinsAgo`, `trippedCount`)
  - Fixed React import issues in `App.tsx`, `KpiGrid.tsx`, `RequestersTable.tsx`, `StatusTable.tsx` (removed unused default React imports)
  - Fixed `isUnchecked` unused function in `StatusTable.tsx`
  - Used `lastUpdated` prop in `KpiGrid.tsx` to display "Updated X ago" timestamp
  - Added `formatTimeAgo` import to `KpiGrid.tsx`

### Changed

- **Model JavaBean enabled flag support** — `Model.java` now exposes `isEnabled()` as a bean property (public getter), enabling Stream filtering by method reference.
- **ModelRegistry respects enabled flag** — `getModelsByPipeline()` and other query methods filter out disabled models, preventing them from being selected as routing candidates.
- **HealthCheckService skips disabled models** — Both the main health sweep and the recovery sweep skip models that are not enabled.
- **LlmProvidersProperties uses `Boolean.TRUE.equals()`** for the `enabled` field default, so absent/null YAML values are treated as disabled.
- **ModelStatusService** now marks disabled models as DOWN in the model status and emits SSE updates for their transition.
- **RoutingService** filters out disabled models from routing candidates.
- **Health checks enabled by default** — `llm.health-check.enabled` now defaults to `true` (override via `LLM_HEALTH_CHECK_ENABLED=false`). Previously, models that went down were never re-probed, leaving stale statuses and stale OPEN circuits in the dashboard.
- **Faster NVIDIA recovery pacing** — NVIDIA `health-check-interval-ms` lowered from 30 min to 2 min and `health-check-max-backoff-ms` capped at 10 min, so a down model is re-probed within minutes instead of waiting up to an hour.

### Added

- **Unit tests** for the disabled-model behavior:
  - `ModelRegistryDisabledTest` — verifies `getModelsByPipeline()` excludes disabled models, tests `getEnabledModels()` and `findById()`.
  - `ApplicationYamlEnabledFlagTest` — verifies that `enabled: false` in `application.yml` correctly disables specific models (e.g. `explabs/gpt-6-luna`), while models without the flag default to disabled.
- **`CircuitBreakerService.markHealthy()`** — force-closes OPEN/HALF_OPEN/FORCED_OPEN breakers after a verified healthy probe and syncs the CLOSED state (`circuitOpen=false`, `consecutiveErrors=0`) to Redis even when the breaker is already CLOSED, preventing Redis/breaker divergence. Wired into `HealthCheckService.updateModelStatusFromResult()` on probe success.
- **Dashboard `UP · BLOCKED` badge** — the status table shows an amber warning badge when a model's probe succeeds (`isUp`) but its circuit is OPEN (`circuitOpen`), clarifying that the model is healthy but requests are still blocked.
- **Unit tests** — `testMarkHealthyClosesOpenCircuitAndSyncsRedis` and `testMarkHealthyOnClosedCircuitStillSyncsRedisWithoutError` in `CircuitBreakerServiceTest`; `ApplicationYamlEnabledFlagTest` updated to assert the 11 enabled / 2 intentionally disabled (explabs) model split.

---

## [Unreleased] — 2026-09-30

### Fixed

- **`noEligibleProvider()` now filters by `Model::isEnabled`** (`LlmGatewayFacade.java` line 412).  
  Previously, `getModelsByPipeline()` collected all models assigned to a pipeline, including disabled ones. When a model (e.g. an explabs model) was disabled **after** its provider had been marked `QUOTA_EXHAUSTED` in Redis, the stale state was still included in the error message, making it appear that an enabled provider was failing.  
  **Fix:** Added `.filter(Model::isEnabled)` so the error message only reports providers that have at least one **enabled** model for the pipeline.

- **Docker Compose Redis env var mismatch** (`docker-compose.yml`).  
  Changed `SPRING_DATA_REDIS_HOST` to `REDIS_HOST` to match the actual property key read in `application.yml`. The old key was silently ignored, causing the app to try connecting to `localhost` instead of the `redis` container.
