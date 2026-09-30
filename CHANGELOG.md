# Changelog

All notable changes to Neural Gateway are documented in this file.

---

## [Unreleased] — 2026-09-30

### Fixed

- **`noEligibleProvider()` now filters by `Model::isEnabled`** (`LlmGatewayFacade.java` line 412).  
  Previously, `getModelsByPipeline()` collected all models assigned to a pipeline, including disabled ones. When a model (e.g. an explabs model) was disabled **after** its provider had been marked `QUOTA_EXHAUSTED` in Redis, the stale state was still included in the error message, making it appear that an enabled provider was failing.  
  **Fix:** Added `.filter(Model::isEnabled)` so the error message only reports providers that have at least one **enabled** model for the pipeline.

- **Docker Compose Redis env var mismatch** (`docker-compose.yml`).  
  Changed `SPRING_DATA_REDIS_HOST` to `REDIS_HOST` to match the actual property key read in `application.yml`. The old key was silently ignored, causing the app to try connecting to `localhost` instead of the `redis` container.

### Changed

- **Model JavaBean enabled flag support** — `Model.java` now exposes `isEnabled()` as a bean property (public getter), enabling Stream filtering by method reference.
- **ModelRegistry respects enabled flag** — `getModelsByPipeline()` and other query methods filter out disabled models, preventing them from being selected as routing candidates.
- **HealthCheckService skips disabled models** — Both the main health sweep and the recovery sweep skip models that are not enabled.
- **LlmProvidersProperties uses `Boolean.TRUE.equals()`** for the `enabled` field default, so absent/null YAML values are treated as disabled.
- **ModelStatusService** now marks disabled models as DOWN in the model status and emits SSE updates for their transition.
- **RoutingService** filters out disabled models from routing candidates.

### Added

- **Unit tests** for the disabled-model behavior:
  - `ModelRegistryDisabledTest` — verifies `getModelsByPipeline()` excludes disabled models, tests `getEnabledModels()` and `findById()`.
  - `ApplicationYamlEnabledFlagTest` — verifies that `enabled: false` in `application.yml` correctly disables specific models (e.g. `explabs/gpt-6-luna`), while models without the flag default to disabled.
