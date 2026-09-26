export interface HealthCheckResult {
  model: string | null;
  latencyMs: number;
  timestamp: string;
  errorMessage: string | null;
  up: boolean;
  isUp?: boolean; // fallback
}

export interface ModelStatus {
  model: string;
  categories: string[];
  isUp: boolean;
  latencyMs: number;
  lastChecked: string;
  errorMessage: string | null;
  history: HealthCheckResult[];
  totalUses: number;
  activeConnections: number;
  tps: number;
  circuitOpen: boolean;
}

export interface RequesterStatus {
  requester: string;
  count: number;
}
