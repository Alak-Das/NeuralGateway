/**
 * UI model for the "Active Model" KPI card.
 * Derived from ModelStatus entries streamed from the backend
 * (which now carry provider and priority alongside the model name).
 */
export interface ActiveModelUI {
  main: string;
  sub: string;
  provider: string;
  priority: number;
}

/** Shape of the backend ModelStatus fields this module needs. */
export interface ModelStatusLike {
  model: string;
  activeConnections: number;
  provider: string;
  priority: number;
}

/**
 * Build the ActiveModelUI display model.
 * Prefers the first model with active connections; falls back to the first model.
 */
export const getActiveModelUI = (models: ModelStatusLike[]): ActiveModelUI | null => {
  if (!models || models.length === 0) return null;

  const active = models.find(m => (m.activeConnections || 0) > 0) ?? models[0];

  return {
    main: active.model,
    provider: active.provider,
    priority: active.priority,
    sub: `${active.provider} (Priority: ${active.priority})`
  };
};

/**
 * Build the ActiveModelUI display model for a specific model id.
 */
export const getModelDisplayInfo = (models: ModelStatusLike[], modelId: string): ActiveModelUI | null => {
  const model = models.find(m => m.model === modelId);
  if (!model) return null;

  return {
    main: model.model,
    provider: model.provider,
    priority: model.priority,
    sub: `${model.provider} (Priority: ${model.priority})`
  };
};