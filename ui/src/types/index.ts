export interface HealthCheckResult {
  model: string | null;
  latencyMs: number;
  timestamp: string;
  errorMessage: string | null;
  up: boolean;
  isUp?: boolean; // fallback
  isBackgroundProbe?: boolean;
}

export interface ModelStatus {
  model: string;
  categories: string[];
  isUp: boolean;
  latencyMs: number;
  lastChecked: string | null;
  /** Optional for compatibility with older gateway instances. */
  statusFresh?: boolean;
  errorMessage: string | null;
  history: HealthCheckResult[];
  totalUses: number;
  activeConnections: number;
  tps: number;
  circuitOpen: boolean;
  provider: string;
  priority: number;
  enabled?: boolean;
}

export interface RequesterStatus {
  requester: string;
  count: number;
  tokenCount?: number;
  requestCount?: number;
  errorCount?: number;
  avgLatencyMs?: number;
  models?: Record<string, number>;
  pipelines?: Record<string, number>;
}
