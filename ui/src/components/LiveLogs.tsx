import { useEffect, useState } from 'react';

export interface TraceLog {
  timestamp: number;
  requester: string;
  model: string;
  latencyMs: number;
  success: boolean;
  pipeline: string;
}

export default function LiveLogs() {
  const [logs, setLogs] = useState<TraceLog[]>([]);

  useEffect(() => {
    let disposed = false;
    let timeoutId: number;

    const fetchLogs = async () => {
      try {
        const response = await fetch('/api/telemetry/traces');
        if (response.ok) {
          const data: TraceLog[] = await response.json();
          if (!disposed) {
            // Sort by newest first
            const sorted = [...data].sort((a, b) => b.timestamp - a.timestamp);
            setLogs(sorted);
          }
        }
      } catch (error) {
        console.error('Failed to fetch telemetry traces', error);
      } finally {
        if (!disposed) {
          timeoutId = window.setTimeout(fetchLogs, 2000);
        }
      }
    };

    fetchLogs();

    return () => {
      disposed = true;
      window.clearTimeout(timeoutId);
    };
  }, []);

  return (
    <div className="card shadow-sm border-0 mb-4">
      <div className="card-header bg-surface border-bottom py-3">
        <h5 className="card-title mb-0">Live Traces</h5>
      </div>
      <div className="card-body p-0">
        <div className="table-responsive" style={{ maxHeight: '500px', overflowY: 'auto' }}>
          <table className="table table-hover mb-0 align-middle">
            <thead className="table-light sticky-top">
              <tr>
                <th>Timestamp</th>
                <th>Requester</th>
                <th>Pipeline</th>
                <th>Model</th>
                <th>Latency (ms)</th>
                <th>Status</th>
              </tr>
            </thead>
            <tbody>
              {logs.length === 0 ? (
                <tr>
                  <td colSpan={6} className="text-center py-4 text-muted">
                    No traces available.
                  </td>
                </tr>
              ) : (
                logs.map((log, index) => (
                  <tr key={`${log.timestamp}-${index}`}>
                    <td className="text-muted" style={{ fontSize: '0.875rem' }}>
                      {new Date(log.timestamp).toLocaleString()}
                    </td>
                    <td><span className="badge bg-secondary bg-opacity-10 text-secondary">{log.requester}</span></td>
                    <td>{log.pipeline}</td>
                    <td>{log.model}</td>
                    <td>{log.latencyMs}</td>
                    <td>
                      {log.success ? (
                        <span className="badge bg-success">Success</span>
                      ) : (
                        <span className="badge bg-danger">Failed</span>
                      )}
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
}
