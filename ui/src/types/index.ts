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
}

export interface RequesterStatus {
  requester: string;
  count: number;
}
