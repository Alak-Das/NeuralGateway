import { useEffect, useState, useContext } from 'react';
import { ModelStatus } from '../types';
import { Line } from 'react-chartjs-2';
import { Chart as ChartJS, CategoryScale, LinearScale, PointElement, LineElement, Filler, Tooltip, Legend } from 'chart.js';
import { ThemeContext } from '../theme/ThemeContext';
import { formatNumber, formatTimeAgo } from '../utils/formatters';

ChartJS.register(CategoryScale, LinearScale, PointElement, LineElement, Filler, Tooltip, Legend);

interface KpiGridProps {
  data: ModelStatus[];
  lastUpdated: Date | null;
}

const getIsUp = (status: ModelStatus): boolean => {
  const isUp = (status as any).up !== undefined ? (status as any).up : status.isUp;
  return isUp ?? false;
};

export default function KpiGrid({ data, lastUpdated }: KpiGridProps) {
  const [activeRotationIndex, setActiveRotationIndex] = useState(0);
  const theme = useContext(ThemeContext);
  const isDark = theme === 'dark';

  // Theme-aware sparkline colors
  const usageLineColor = isDark ? '#06b6d4' : '#0891b2';
  const usageFillColor = isDark ? 'rgba(6, 182, 212, 0.2)' : 'rgba(8, 145, 178, 0.15)';
  const latencyLineColor = isDark ? '#f59e0b' : '#d97706';
  const latencyFillColor = isDark ? 'rgba(245, 158, 11, 0.2)' : 'rgba(217, 119, 6, 0.15)';

  const totalModels = data.length;
  const upModels = data.filter(d => getIsUp(d)).length;
  const downModels = totalModels - upModels;
  const disabledModels = data.filter(d => d.enabled === false).length;
  const enabledModels = totalModels - disabledModels;
  const enabledUp = data.filter(d => d.enabled !== false && getIsUp(d)).length;

  const healthColor = totalModels === 0
    ? 'text-muted'
    : upModels === totalModels || (enabledModels > 0 && enabledUp === enabledModels)
      ? 'text-success'
      : upModels === 0
        ? 'text-danger'
        : 'text-warning';

  const totalConns = data.reduce((sum, d) => sum + (d.activeConnections || 0), 0);
  
  const upLatencies = data.filter(d => getIsUp(d) && d.latencyMs > 0).map(d => d.latencyMs);
  const avgLatencyMs = upLatencies.length > 0 ? upLatencies.reduce((a, b) => a + b, 0) / upLatencies.length : 0;
  
  const currentActiveModels = data.filter(d => (d.activeConnections || 0) > 0);

  useEffect(() => {
    const interval = setInterval(() => {
      setActiveRotationIndex(prev => prev + 1);
    }, 1800);
    return () => clearInterval(interval);
  }, []);

  // Calculate Sparkline Data and Global RPM
  const now = new Date();
  const oneMinCutoff = now.getTime() - 60 * 1000;
  const fiveMinCutoff = now.getTime() - 5 * 60 * 1000;
  let reqsLast1Min = 0;
  let reqsLast5Min = 0;

  const sparklineData = Array.from({ length: 15 }, (_, i) => {
    const d = new Date(now.getTime() - (14 - i) * 60 * 1000);
    d.setSeconds(0, 0);
    return {
      time: d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }),
      timestamp: d.getTime(),
      health: 100,
      rpm: 0,
      latency: 0,
      usage: 0
    };
  });

  const timeMap = new Map<number, typeof sparklineData[0]>();
  sparklineData.forEach(d => timeMap.set(d.timestamp, d));

  const latencyByMin: Record<number, { totalMs: number, count: number }> = {};
  const usageByMin: Record<number, number> = {};

  data.forEach(d => {
    if (d.history) {
      d.history.forEach(h => {
        const date = new Date(h.timestamp);
        const tRaw = date.getTime();
        date.setSeconds(0, 0);
        const t = date.getTime();
        
        if (h.isBackgroundProbe === false) {
          if (tRaw >= oneMinCutoff) reqsLast1Min++;
          if (tRaw >= fiveMinCutoff) reqsLast5Min++;
        }

        if (timeMap.has(t)) {
          if (h.isBackgroundProbe === false) {
            usageByMin[t] = (usageByMin[t] || 0) + 1;
          }

          const isUp = h.up ?? h.isUp ?? false;
          if (isUp && h.latencyMs > 0) {
            if (!latencyByMin[t]) latencyByMin[t] = { totalMs: 0, count: 0 };
            latencyByMin[t].totalMs += h.latencyMs;
            latencyByMin[t].count++;
          }
        }
      });
    }
  });

  sparklineData.forEach(d => {
    const t = d.timestamp;
    let readyCountAtMin = 0;
    const bucketEnd = t + 59999;
    data.forEach(m => {
      let latestEvent: any = null;
      if (m.history && Array.isArray(m.history) && m.history.length > 0) {
        for (let i = m.history.length - 1; i >= 0; i--) {
          const h = m.history[i];
          const hTime = new Date(h.timestamp).getTime();
          if (hTime <= bucketEnd) {
            latestEvent = h;
            break;
          }
        }
      }
      const isUp = latestEvent
        ? ((latestEvent.up !== undefined ? latestEvent.up : latestEvent.isUp) ?? false)
        : getIsUp(m);
      if (isUp) {
        readyCountAtMin++;
      }
    });

    d.health = totalModels > 0 ? (readyCountAtMin / totalModels) * 100 : 0;

    if (usageByMin[t]) {
      d.rpm = usageByMin[t];
      d.usage = usageByMin[t];
    }
    if (latencyByMin[t] && latencyByMin[t].count > 0) {
      d.latency = latencyByMin[t].totalMs / latencyByMin[t].count;
    }
  });

  const total15mReqs = sparklineData.reduce((sum, d) => sum + d.rpm, 0);
  const peakRpm = Math.max(0, ...sparklineData.map(d => d.rpm));
  const currentRpm = reqsLast1Min > 0 ? reqsLast1Min : (reqsLast5Min > 0 ? Number((reqsLast5Min / 5).toFixed(1)) : 0);

  const sparklineOptions = {
    responsive: true,
    maintainAspectRatio: false,
    plugins: { legend: { display: false }, tooltip: { enabled: false } },
    scales: { x: { display: false }, y: { display: false } },
    elements: { point: { radius: 0, hitRadius: 0, hoverRadius: 0 }, line: { tension: 0.4, borderWidth: 2 } },
    animation: false as const
  };

  const getActiveModelUI = () => {
    if (currentActiveModels.length === 0) {
      return {
        main: <span className="text-muted font-monospace" style={{ fontSize: '1.8rem', letterSpacing: '3px' }}>______</span>,
        sub: <span className="text-muted" style={{ fontSize: '0.85rem' }}>Idle &bull; No active streams</span>
      };
    }

    const idx = activeRotationIndex % currentActiveModels.length;
    const activeModel = currentActiveModels[idx];
    const currentModel = activeModel.model;
    const parts = currentModel.split('/');
    const org = parts.length > 1 ? parts[0] + '/' : '';
    const modelName = parts.length > 1 ? parts[1] : parts[0];

    const isMultiple = currentActiveModels.length > 1;

    return {
      main: (
        <div className="text-truncate w-100" title={currentModel}>
            <div className="text-muted fw-normal" style={{ fontSize: '0.72rem', lineHeight: '1', letterSpacing: '0.5px' }}>{org}</div>
            <div className="d-flex align-items-center gap-2 flex-wrap">
              <span className="text-primary fw-bolder" style={{ fontSize: '1.2rem' }}>{modelName}</span>
              {activeModel.provider && (
                <span className="badge bg-info bg-opacity-10 text-info border border-info border-opacity-25 fw-medium" style={{ fontSize: '0.65rem' }}>
                  <i className="bi bi-cloud me-1"></i>{activeModel.provider}
                </span>
              )}
              <span className="badge bg-secondary bg-opacity-10 text-secondary border border-secondary border-opacity-25 fw-medium" style={{ fontSize: '0.65rem' }}>
                <i className="bi bi-sort-numeric-up me-1"></i>Priority: {activeModel.priority}
              </span>
            </div>
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
      {/* Routing readiness */}
      <div className="col-12 col-sm-6 col-lg-4 col-xl">
        <div className="card border-0 shadow-sm rounded-4 h-100 position-relative overflow-hidden d-flex flex-column p-0">
          <div className="p-3 pb-0 d-flex flex-column" style={{ zIndex: 2 }}>
            <div className="text-secondary fw-semibold mb-1" style={{ fontSize: '0.85rem', letterSpacing: '0.5px', textTransform: 'uppercase' }}>
              <i className="bi bi-server me-2"></i>Routing Ready
            </div>
            <div className="fw-bolder d-flex align-items-baseline" style={{ fontSize: '2rem', lineHeight: '1.1' }}>
              <span className={healthColor} title="Models currently reporting UP in the gateway">
                {totalModels > 0 ? upModels : '—'}
              </span>
              <span className="text-muted ms-2" style={{ fontSize: '1.1rem' }}>/ {totalModels} ready</span>
            </div>
            <div className="text-muted mt-1" style={{ fontSize: '0.75rem' }}>
              {downModels > 0 ? `${downModels} down` : 'All UP'}
              {disabledModels > 0 ? ` · ${disabledModels} disabled` : ''}
              {lastUpdated ? ` · Updated ${formatTimeAgo(lastUpdated.toISOString())}` : ''}
            </div>
          </div>
          <div className="flex-grow-1 w-100 mt-2 position-relative" style={{ minHeight: '60px' }}>
            <div className="position-absolute w-100 h-100 px-3 pb-3">
              <Line 
                data={{ 
                  labels: sparklineData.map(d => d.time), 
                  datasets: [{ data: sparklineData.map(d => d.health), borderColor: '#10b981', backgroundColor: 'rgba(16, 185, 129, 0.2)', fill: true, pointRadius: 0, tension: 0.4 }] 
                }} 
                options={{ ...sparklineOptions, maintainAspectRatio: false, layout: { padding: 0 }, scales: { x: { display: false }, y: { display: false, min: -1, max: 100 } } }} 
              />
            </div>
          </div>
        </div>
      </div>
      {/* Global Throughput (RPM) */}
      <div className="col-12 col-sm-6 col-lg-4 col-xl">
        <div className="card border-0 shadow-sm rounded-4 h-100 position-relative overflow-hidden d-flex flex-column p-0">
          <div className="p-3 pb-0 d-flex flex-column" style={{ zIndex: 2 }}>
            <div className="d-flex justify-content-between align-items-center mb-1">
              <span className="text-secondary fw-semibold" style={{ fontSize: '0.85rem', letterSpacing: '0.5px', textTransform: 'uppercase' }}>
                <i className="bi bi-activity text-primary me-2"></i>Global RPM
              </span>
              {peakRpm > 0 && (
                <span className="badge bg-primary bg-opacity-10 text-primary border border-primary border-opacity-25" style={{ fontSize: '0.68rem' }} title="Peak RPM in last 15 minutes">
                  Peak: {peakRpm}
                </span>
              )}
            </div>
            <div className="fw-bolder d-flex align-items-baseline text-main" style={{ fontSize: '2.2rem', letterSpacing: '-1px', lineHeight: '1.1' }}>
              {currentRpm} <span className="text-muted ms-1 fw-medium" style={{ fontSize: '0.9rem', letterSpacing: '0' }}>req/min</span>
            </div>
            <div className="text-muted small mt-1" style={{ fontSize: '0.75rem' }}>
              {total15mReqs > 0 ? `${total15mReqs} reqs in last 15m` : 'No active traffic in window'}
            </div>
          </div>
          <div className="flex-grow-1 w-100 mt-2 position-relative" style={{ minHeight: '60px' }}>
            <div className="position-absolute w-100 h-100 px-3 pb-3">
              <Line 
                data={{ 
                  labels: sparklineData.map(d => d.time), 
                  datasets: [{ data: sparklineData.map(d => d.rpm), borderColor: '#3b82f6', backgroundColor: 'rgba(59, 130, 246, 0.2)', fill: true, pointRadius: 0, tension: 0.4 }] 
                }} 
                options={{ ...sparklineOptions, maintainAspectRatio: false, layout: { padding: 0 }, scales: { x: { display: false }, y: { display: false, min: -0.5 } } }} 
              />
            </div>
          </div>
        </div>
      </div>

      {/* Active Model */}
      <div className="col-12 col-sm-6 col-lg-4 col-xl">
        <div className="card border-0 shadow-sm rounded-4 p-3 h-100 bg-surface-hover border-primary border-opacity-25 position-relative overflow-hidden d-flex flex-column" style={{ transition: 'all 0.3s' }}>
          <div className="text-secondary fw-semibold mb-1 d-flex justify-content-between align-items-center" style={{ fontSize: '0.85rem', letterSpacing: '0.5px', textTransform: 'uppercase' }}>
            <span><i className="bi bi-cpu text-warning me-2"></i>Active Model</span>
            {currentActiveModels.length > 0 && <span className="spinner-grow text-warning" style={{ width: '0.5rem', height: '0.5rem' }}></span>}
          </div>
            <div className="fw-bolder d-flex align-items-center flex-grow-1">
              {activeModelUI.main}
            </div>
            <div className="mt-2" style={{ zIndex: 2 }}>
              <div className="text-muted small fw-medium">
                {activeModelUI.sub}
              </div>
            </div>
          </div>
        </div>

      {/* Active Connections */}
      <div className="col-12 col-sm-6 col-lg-4 col-xl">
        <div className="card border-0 shadow-sm rounded-4 h-100 position-relative overflow-hidden d-flex flex-column p-0">
          <div className="p-3 pb-0 d-flex flex-column" style={{ zIndex: 2 }}>
            <div className="text-secondary fw-semibold mb-1" style={{ fontSize: '0.85rem', letterSpacing: '0.5px', textTransform: 'uppercase' }}>
              <i className="bi bi-diagram-3 text-info me-2"></i>Active Conns
            </div>
            <div className="fw-bolder text-main" style={{ fontSize: '2.5rem', letterSpacing: '-1px', lineHeight: '1.1' }}>
              {formatNumber(totalConns)}
            </div>
          </div>
          <div className="flex-grow-1 w-100 mt-2 position-relative" style={{ minHeight: '60px' }}>
            <div className="position-absolute w-100 h-100 px-3 pb-3">
              <Line 
                data={{ 
                  labels: sparklineData.map(d => d.time), 
                  datasets: [{ data: sparklineData.map(d => d.usage), borderColor: usageLineColor, backgroundColor: usageFillColor, fill: true, stepped: true, pointRadius: 0 }] 
                }} 
                options={{ ...sparklineOptions, maintainAspectRatio: false, layout: { padding: 0 }, scales: { x: { display: false }, y: { display: false, min: -0.5 } } }} 
              />
            </div>
          </div>
        </div>
      </div>

      {/* Avg Fleet Latency */}
      <div className="col-12 col-sm-12 col-lg-4 col-xl">
        <div className="card border-0 shadow-sm rounded-4 h-100 position-relative overflow-hidden d-flex flex-column p-0">
          <div className="p-3 pb-0 d-flex flex-column" style={{ zIndex: 2 }}>
            <div className="text-secondary fw-semibold mb-1" style={{ fontSize: '0.85rem', letterSpacing: '0.5px', textTransform: 'uppercase' }}>
              <i className="bi bi-stopwatch text-success me-2"></i>Avg Latency
            </div>
            <div className="fw-bolder d-flex align-items-baseline text-main" style={{ fontSize: '2.2rem', letterSpacing: '-1px', lineHeight: '1.1' }}>
              {avgLatencyMs >= 1000 ? (avgLatencyMs / 1000).toFixed(2) : Math.round(avgLatencyMs)}
              <span className="text-muted ms-1 fw-medium" style={{ fontSize: '0.9rem', letterSpacing: '0' }}>{avgLatencyMs >= 1000 ? 's' : 'ms'}</span>
            </div>
          </div>
          <div className="flex-grow-1 w-100 mt-2 position-relative" style={{ minHeight: '60px' }}>
            <div className="position-absolute w-100 h-100 px-3 pb-3">
              <Line 
                data={{ 
                  labels: sparklineData.map(d => d.time), 
                  datasets: [{ data: sparklineData.map(d => d.latency), borderColor: latencyLineColor, backgroundColor: latencyFillColor, fill: true, pointRadius: 0, tension: 0.4 }] 
                }} 
                options={{ ...sparklineOptions, maintainAspectRatio: false, layout: { padding: 0 }, scales: { x: { display: false }, y: { display: false, min: -10 } } }} 
              />
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
