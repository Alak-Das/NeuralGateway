import { RequesterStatus } from '../types';
import { formatNumber } from '../utils/formatters';

interface RequestersTableProps {
  requesters: RequesterStatus[];
}

export default function RequestersTable({ requesters }: RequestersTableProps) {
  return (
    <div className="card border-0 shadow-sm rounded-4 overflow-hidden">
      <div className="card-header bg-transparent border-bottom d-flex justify-content-between align-items-center p-3 px-4">
        <h5 className="mb-0 fw-bold d-flex align-items-center">
          <i className="bi bi-people text-info me-2"></i>Requester Telemetry
        </h5>
        <span className="badge bg-secondary text-white px-2 py-1">
          {requesters.length} {requesters.length === 1 ? 'Requester' : 'Requesters'}
        </span>
      </div>
      <div className="table-responsive">
        <table className="table table-hover align-middle">
          <thead>
            <tr>
              <th className="px-4">Requester Identity (X-Requester)</th>
              <th className="px-4">Total Requests</th>
              <th className="px-4">Errors</th>
              <th className="px-4">Avg Latency (ms)</th>
              <th className="px-4">Total Tokens Used</th>
            </tr>
          </thead>
          <tbody>
            {requesters.length === 0 ? (
              <tr>
                <td colSpan={5}>
                  <div className="text-center py-5">
                    <i className="bi bi-people text-muted opacity-50" style={{ fontSize: '4rem' }}></i>
                    <h5 className="fw-bold mt-3 text-secondary">No requester data available</h5>
                    <p className="text-muted">Requesters will appear here when traffic is routed through the service.</p>
                  </div>
                </td>
              </tr>
            ) : (
              requesters.map((r, i) => (
                <tr key={r.requester || i}>
                  <td className="py-3 px-4">
                    <div className="d-flex align-items-center">
                      <span className="badge bg-primary text-white px-3 py-2 font-monospace" style={{ fontSize: '0.9rem' }}>
                        <i className="bi bi-person-badge me-2"></i>{r.requester}
                      </span>
                    </div>
                  </td>
                  <td className="py-3 px-4">
                    <span className="fw-bold text-main">{formatNumber(r.requestCount || 0)}</span>
                  </td>
                  <td className="py-3 px-4">
                    <span className={`fw-bold ${(r.errorCount || 0) > 0 ? 'text-danger' : 'text-main'}`}>
                      {formatNumber(r.errorCount || 0)}
                    </span>
                  </td>
                  <td className="py-3 px-4">
                    <span className="fw-bold text-main">{formatNumber(r.avgLatencyMs || 0)}</span>
                  </td>
                  <td className="py-3 px-4">
                    <span className="fw-bold font-monospace text-main" style={{ fontSize: '1.05rem' }}>{formatNumber(r.tokenCount || r.count)}</span>
                    <span className="text-muted ms-2 small">({((r.tokenCount !== undefined ? r.tokenCount : r.count) || 0).toLocaleString()} tokens)</span>
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}