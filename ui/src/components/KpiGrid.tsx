import React, { useEffect, useState } from 'react';
import { ModelStatus } from '../types';

interface KpiGridProps {
  data: ModelStatus[];
  lastUpdated: Date | null;
}

export default function KpiGrid({ data, lastUpdated }: KpiGridProps) {
  const [activeRotationIndex, setActiveRotationIndex] = useState(0);

  const totalModels = data.length;
  const upModels = data.filter(d => d.isUp && !d.circuitOpen).length;
  const trippedCount = data.filter(d => d.circuitOpen).length;
  const realDownCount = data.filter(d => !d.isUp && !d.circuitOpen).length;

  const totalTps = data.reduce((sum, d) => sum + d.tps, 0);
  const totalConns = data.reduce((sum, d) => sum + d.activeConnections, 0);
  
  const upLatencies = data.filter(d => d.isUp && !d.circuitOpen && d.latencyMs > 0).map(d => d.latencyMs);
  const avgLatencyMs = upLatencies.length > 0 ? upLatencies.reduce((a, b) => a + b, 0) / upLatencies.length : 0;
  
  const currentActiveModels = data.filter(d => d.activeConnections > 0).map(d => d.model);

  useEffect(() => {
    const interval = setInterval(() => {
      setActiveRotationIndex(prev => prev + 1);
    }, 1800);
    return () => clearInterval(interval);
  }, []);

  const getActiveModelUI = () => {
    if (currentActiveModels.length === 0) {
      return {
        main: <span className="text-muted font-monospace" style={{ fontSize: '1.8rem', letterSpacing: '3px' }}>______</span>,
        sub: <span className="text-muted" style={{ fontSize: '0.85rem' }}>Idle &bull; No active streams</span>
      };
    }

    const idx = activeRotationIndex % currentActiveModels.length;
    const currentModel = currentActiveModels[idx];
    const parts = currentModel.split('/');
    const org = parts.length > 1 ? parts[0] + '/' : '';
    const modelName = parts.length > 1 ? parts[1] : parts[0];

    const isMultiple = currentActiveModels.length > 1;

    return {
      main: (
        <div className="text-truncate w-100" title={currentModel}>
            <div className="text-muted fw-normal" style={{ fontSize: '0.72rem', lineHeight: '1', letterSpacing: '0.5px' }}>{org}</div>
            <span className="text-primary fw-bolder" style={{ fontSize: '1.2rem' }}>{modelName}</span>
        </div>
      ),
      sub: isMultiple ? (
        <span className="badge bg-warning bg-opacity-10 text-warning border border-warning border-opacity-25" style={{ fontSize: '0.75rem' }}>
            <i className="bi bi-arrow-repeat me-1"></i>{idx + 1} of {currentActiveModels.length} active (cycling)
        </span>
      ) : (
        <span className="badge bg-success bg-opacity-10 text-success border border-success border-opacity-25" style={{ fontSize: '0.75rem' }}>
            <span className="spinner-grow spinner-grow-sm me-1" style={{ width: '0.5rem', height: '0.5rem' }}></span>In use
        </span>
      )
    };
  };

  const activeModelUI = getActiveModelUI();

  return (
    <div className="row g-4 mb-4" id="kpiGrid">
      <div className="col-12 col-sm-6 col-lg-4 col-xl">
        <div className="card border-0 shadow-sm rounded-4 p-4 h-100 position-relative overflow-hidden">
          <div className="text-secondary fw-semibold mb-2" style={{ fontSize: '0.85rem', letterSpacing: '0.5px', textTransform: 'uppercase' }}>
            <i className="bi bi-server me-2"></i>Fleet Health
          </div>
          <div className="fw-bolder d-flex align-items-baseline" style={{ fontSize: '2rem' }}>
            <span className={upModels === totalModels ? 'text-success' : (upModels === 0 ? 'text-danger' : 'text-warning')}>{upModels}</span>
            <span className="text-muted ms-2" style={{ fontSize: '1.25rem' }}>/ {totalModels}</span>
          </div>
          <div className="mt-2">
            {trippedCount > 0 ? (
              <span className="badge bg-warning text-dark"><i className="bi bi-exclamation-triangle me-1"></i> {trippedCount} Tripped</span>
            ) : realDownCount > 0 ? (
              <span className="badge bg-danger"><i className="bi bi-x-circle me-1"></i> {realDownCount} Down</span>
            ) : (
              <span className="badge bg-success bg-opacity-10 text-success border border-success border-opacity-25"><i className="bi bi-check-circle me-1"></i> All Systems Go</span>
            )}
          </div>
        </div>
      </div>

      <div className="col-12 col-sm-6 col-lg-4 col-xl">
        <div className="card border-0 shadow-sm rounded-4 p-4 h-100">
          <div className="text-secondary fw-semibold mb-2" style={{ fontSize: '0.85rem', letterSpacing: '0.5px', textTransform: 'uppercase' }}>
            <i className="bi bi-activity text-primary me-2"></i>Global TPS
          </div>
          <div className="fw-bolder d-flex align-items-baseline text-main" style={{ fontSize: '2.5rem', letterSpacing: '-1px' }}>
            {totalTps.toFixed(1)} <span className="text-muted ms-1 fw-medium" style={{ fontSize: '1rem', letterSpacing: '0' }}>req/s</span>
          </div>
          <div className="text-muted mt-2" style={{ fontSize: '0.8rem' }}>Combined throughput</div>
        </div>
      </div>

      <div className="col-12 col-sm-6 col-lg-4 col-xl">
        <div className="card border-0 shadow-sm rounded-4 p-4 h-100 bg-surface-hover border-primary border-opacity-25" style={{ transition: 'all 0.3s' }}>
          <div className="text-secondary fw-semibold mb-2 d-flex justify-content-between align-items-center" style={{ fontSize: '0.85rem', letterSpacing: '0.5px', textTransform: 'uppercase' }}>
            <span><i className="bi bi-cpu text-warning me-2"></i>Active Model</span>
            {currentActiveModels.length > 0 && <span className="spinner-grow text-warning" style={{ width: '0.5rem', height: '0.5rem' }}></span>}
          </div>
          <div className="fw-bolder d-flex align-items-center my-auto" style={{ minHeight: '40px' }}>
            {activeModelUI.main}
          </div>
          <div className="mt-2">
            {activeModelUI.sub}
          </div>
        </div>
      </div>

      <div className="col-12 col-sm-6 col-lg-4 col-xl">
        <div className="card border-0 shadow-sm rounded-4 p-4 h-100">
          <div className="text-secondary fw-semibold mb-2" style={{ fontSize: '0.85rem', letterSpacing: '0.5px', textTransform: 'uppercase' }}>
            <i className="bi bi-diagram-3 text-info me-2"></i>Active Connections
          </div>
          <div className="fw-bolder text-main" style={{ fontSize: '2.5rem', letterSpacing: '-1px' }}>
            {totalConns}
          </div>
          <div className="text-muted mt-2" style={{ fontSize: '0.8rem' }}>Real-time concurrent streams</div>
        </div>
      </div>

      <div className="col-12 col-sm-12 col-lg-4 col-xl">
        <div className="card border-0 shadow-sm rounded-4 p-4 h-100">
          <div className="text-secondary fw-semibold mb-2" style={{ fontSize: '0.85rem', letterSpacing: '0.5px', textTransform: 'uppercase' }}>
            <i className="bi bi-stopwatch text-success me-2"></i>Avg Fleet Latency
          </div>
          <div className="fw-bolder d-flex align-items-baseline text-main" style={{ fontSize: '2.5rem', letterSpacing: '-1px' }}>
            {avgLatencyMs >= 1000 ? (avgLatencyMs / 1000).toFixed(2) : Math.round(avgLatencyMs)}
            <span className="text-muted ms-1 fw-medium" style={{ fontSize: '1rem', letterSpacing: '0' }}>{avgLatencyMs >= 1000 ? 's' : 'ms'}</span>
          </div>
          <div className="text-muted mt-2" style={{ fontSize: '0.8rem' }}>Time to first token (TTFT)</div>
        </div>
      </div>
    </div>
  );
}
