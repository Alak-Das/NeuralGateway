import React from 'react';
import { RequesterStatus } from '../types';

interface RequestersTableProps {
  requesters: RequesterStatus[];
}

export default function RequestersTable({ requesters }: RequestersTableProps) {
  const formatNumber = (num: number | null | undefined) => {
    if (num == null || isNaN(num)) return '0';
    if (num >= 1000000000) return (num / 1000000000).toFixed(2) + 'B';
    if (num >= 1000000) return (num / 1000000).toFixed(2) + 'M';
    if (num >= 1000) return (num / 1000).toFixed(1) + 'K';
    return num.toLocaleString();
  };

  return (
    <div className="card border-0 shadow-sm rounded-4 overflow-hidden">
      <div className="card-header bg-transparent border-bottom d-flex justify-content-between align-items-center p-3 px-4">
        <h5 className="mb-0 fw-bold d-flex align-items-center">
          <i className="bi bi-people text-info me-2"></i>Requester Telemetry
        </h5>
        <span className="badge bg-secondary bg-opacity-10 text-secondary border border-secondary border-opacity-25 px-2 py-1">
          {requesters.length} {requesters.length === 1 ? 'Requester' : 'Requesters'}
        </span>
      </div>
      <div className="table-responsive">
        <table className="table table-hover align-middle">
          <thead>
            <tr>
              <th className="px-4">Requester Identity (X-Requester)</th>
              <th className="px-4">Total Tokens Used</th>
            </tr>
          </thead>
          <tbody>
            {requesters.length === 0 ? (
              <tr>
                <td colSpan={2}>
                  <div className="text-center py-5">
                    <i className="bi bi-people text-muted opacity-50" style={{ fontSize: '4rem' }}></i>
                    <h5 className="fw-bold mt-3 text-secondary">No requester data available</h5>
                    <p className="text-muted">Requesters will appear here when traffic is routed through the service.</p>
                  </div>
                </td>
              </tr>
            ) : (
              requesters.map((r, i) => (
                <tr key={i}>
                  <td className="py-3 px-4">
                    <div className="d-flex align-items-center">
                      <span className="badge bg-primary bg-opacity-10 text-primary border border-primary border-opacity-25 px-3 py-2 font-monospace" style={{ fontSize: '0.9rem' }}>
                        <i className="bi bi-person-badge me-2"></i>{r.requester}
                      </span>
                    </div>
                  </td>
                  <td className="py-3 px-4">
                    <span className="fw-bold font-monospace text-main" style={{ fontSize: '1.05rem' }}>{formatNumber(r.count)}</span>
                    <span className="text-muted ms-2 small">({(r.count || 0).toLocaleString()} tokens)</span>
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

