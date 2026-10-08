import { useState, useMemo, useContext } from 'react';
import { ModelStatus } from '../types';
import { formatNumber, formatTimeAgo, formatTimeOnly, formatShort } from '../utils/formatters';
import { ThemeContext } from '../theme/ThemeContext';
import { buildModelColorMap, getModelColor } from '../theme/colors';

interface StatusTableProps {
  data: ModelStatus[];
  onRefresh?: () => void;
}

export default function StatusTable({ data, onRefresh }: StatusTableProps) {
  const [categoryFilter, setCategoryFilter] = useState('');
  const [statusFilter, setStatusFilter] = useState('');

  const [editingModel, setEditingModel] = useState<ModelStatus | null>(null);
  const [editEnabled, setEditEnabled] = useState(true);
  const [editPriority, setEditPriority] = useState(0);
  const [editPipelines, setEditPipelines] = useState('');
  const theme = useContext(ThemeContext);
  const isDark = theme === 'dark';

  const modelColorMap = useMemo(() => {
    return buildModelColorMap(data.map(d => d.model), isDark);
  }, [data, isDark]);

  const handleEdit = (model: ModelStatus) => {
    setEditingModel(model);
    setEditEnabled(model.enabled !== false);
    setEditPriority(model.priority ?? 0);
    setEditPipelines((model.categories || []).join(', '));
  };

  const handleSaveConfig = async () => {
    if (!editingModel) return;
    const pipelines = editPipelines.split(',').map(s => s.trim().toUpperCase()).filter(Boolean);
    try {
      const response = await fetch('/api/models/config', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          model: editingModel.model,
          enabled: editEnabled,
          priority: editPriority,
          pipelines
        })
      });
      if (!response.ok) {
        throw new Error(`Server returned HTTP ${response.status}`);
      }
      setEditingModel(null);
      if (onRefresh) {
        onRefresh();
      }
    } catch (e) {
      console.error('Failed to save config', e);
      alert('Failed to save model configuration: ' + (e instanceof Error ? e.message : String(e)));
    }
  };

  const [resettingModel, setResettingModel] = useState<string | null>(null);

  const handleResetCircuit = async (modelId: string) => {
    setResettingModel(modelId);
    try {
      const response = await fetch(`/api/models/circuit-reset?model=${encodeURIComponent(modelId)}`, {
        method: 'POST'
      });
      if (response.ok && onRefresh) {
        onRefresh();
      }
    } catch (e) {
      console.error('Failed to reset circuit breaker', e);
    } finally {
      setResettingModel(null);
    }
  };

  const formatErrorMessage = (msg: string | null) => {
    if (!msg) return '';
    if (msg === 'Not yet checked') return msg;
    
    try {
      if (msg.trim().startsWith('{') && msg.trim().endsWith('}')) {
        const parsed = JSON.parse(msg);
        if (parsed.status === 429 || parsed.title === 'Too Many Requests') return 'Rate Limited (429)';
        if (parsed.title) return formatShort(parsed.title);
        if (parsed.detail) return formatShort(parsed.detail);
        if (parsed.error?.message) return formatShort(parsed.error.message);
      }
    } catch (e) {
      // ignore
    }

    if (msg.includes('Timeout/Error: null')) return 'Ping Timeout';
    if (msg.includes('TimeoutException')) return 'Connection Timeout';
    if (msg.includes('Connection refused')) return 'Connection Refused';
    if (msg.includes('PrematureCloseException')) return 'Connection Dropped';
    if (msg.includes('ResourceExhausted')) return 'Rate Limited (429)';
    if (msg.includes('UnknownHostException')) return 'DNS Resolution Failed';
    if (msg.includes('429')) return 'Rate Limited (429)';
    if (msg.includes('401') || msg.includes('403')) return 'Authentication Failed';
    if (msg.includes('500') || msg.includes('502') || msg.includes('503')) return 'Upstream Server Error';
    if (msg.includes('504')) return 'Gateway Timeout';
    
    if (msg.length > 35) return msg.substring(0, 35) + '...';
    return msg;
  };

  const categories = useMemo(() => {
    const cats = new Set<string>();
    data.forEach(d => d.categories.forEach(c => cats.add(c)));
    return Array.from(cats).sort();
  }, [data]);

  const isDisabled = (model: ModelStatus) => model.enabled === false;
  const getStatus = (model: ModelStatus): 'up' | 'down' => {
    const isUp = (model as any).up !== undefined ? (model as any).up : model.isUp;
    return isUp ? 'up' : 'down';
  };
  const isStatusFresh = (model: ModelStatus) => {
    if (model.statusFresh != null) return model.statusFresh;
    if (!model.lastChecked) return false;
    const checkedAt = Date.parse(model.lastChecked);
    const ageMs = Date.now() - checkedAt;
    return Number.isFinite(checkedAt) && ageMs >= 0 && ageMs <= 24 * 60 * 60 * 1000;
  };

  const getModelRpm = (model: ModelStatus): number => {
    if (!model.history || !Array.isArray(model.history)) return Math.round((model.tps || 0) * 60);
    const cutoff = Date.now() - 5 * 60 * 1000;
    const recentReqs = model.history.filter(h => h.isBackgroundProbe === false && h.timestamp && new Date(h.timestamp).getTime() >= cutoff).length;
    if (recentReqs > 0) return Number((recentReqs / 5).toFixed(1));
    const totalReqsInHistory = model.history.filter(h => h.isBackgroundProbe === false).length;
    if (totalReqsInHistory > 0) return Number((totalReqsInHistory / 15).toFixed(1));
    return Math.round((model.tps || 0) * 60);
  };

  const filteredData = useMemo(() => {
    return data.filter(d => {
      const catMatch = !categoryFilter || d.categories.includes(categoryFilter);
      let statusMatch = true;
      if (statusFilter === 'up') statusMatch = getStatus(d) === 'up';
      else if (statusFilter === 'down') statusMatch = getStatus(d) === 'down';
      return catMatch && statusMatch;
    }).sort((a, b) => {
      const getScore = (m: ModelStatus) => {
        if (isDisabled(m)) return 0;
        const st = getStatus(m);
        if (st === 'up') return 2;
        return 1;
      };
      
      const scoreA = getScore(a);
      const scoreB = getScore(b);
      
      if (scoreA !== scoreB) {
        return scoreB - scoreA;
      }
      
      const prioA = a.priority ?? 0;
      const prioB = b.priority ?? 0;
      if (prioA !== prioB) {
        return prioB - prioA;
      }
      
      return a.model.localeCompare(b.model);
    });
  }, [data, categoryFilter, statusFilter]);

  const exportCSV = () => {
    const headers = ['Model', 'Categories', 'Status', 'Latency (ms)', 'RPM', 'Total Uses', 'Active Conns', 'Last Error', 'Last Check'];
    const rows = filteredData.map(d => [
      d.model,
      d.categories.join('; '),
      getStatus(d) === 'up' ? 'UP' : 'DOWN',
      d.latencyMs,
      getModelRpm(d),
      d.totalUses,
      d.activeConnections,
      d.errorMessage || '',
      d.lastChecked ? new Date(d.lastChecked).toISOString() : ''
    ]);
    
    const csv = [headers, ...rows].map(r => r.map(c => `"${String(c).replace(/"/g, '""')}"`).join(',')).join('\n');
    const blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' });
    const link = document.createElement('a');
    link.href = URL.createObjectURL(blob);
    link.download = `neural-gateway-status-${new Date().toISOString().replace(/[:.]/g, '-')}.csv`;
    link.click();
  };

  return (
    <div className="card border-0 shadow-sm rounded-4 overflow-hidden mb-5">
      <div className="card-header bg-transparent border-bottom d-flex justify-content-between align-items-center p-3 px-4">
        <h5 className="mb-0 fw-bold d-flex align-items-center">
          <i className="bi bi-robot text-primary me-2"></i>Model Status
        </h5>
        
        <div className="d-flex align-items-center gap-3">
          <div className="d-flex gap-2">
            <div className="input-group input-group-sm">
              <span className="input-group-text bg-transparent border-end-0"><i className="bi bi-filter"></i></span>
               <select className={`${isDark ? 'bg-dark text-light border-secondary' : 'bg-light text-dark border-secondary'} form-select border-start-0 ps-0`} value={categoryFilter} onChange={e => setCategoryFilter(e.target.value)}>
                <option value="">All Categories</option>
                {categories.map(c => <option key={c} value={c}>{c}</option>)}
              </select>
            </div>
            
            <div className="input-group input-group-sm">
              <span className="input-group-text bg-transparent border-end-0"><i className="bi bi-activity"></i></span>
               <select className={`${isDark ? 'bg-dark text-light border-secondary' : 'bg-light text-dark border-secondary'} form-select border-start-0 ps-0`} value={statusFilter} onChange={e => setStatusFilter(e.target.value)}>
                <option value="">All Statuses</option>
                <option value="up">UP</option>
                <option value="down">DOWN</option>
              </select>
            </div>
          </div>
          
          <button className="btn btn-sm btn-outline-secondary rounded-pill px-3 d-none d-md-flex align-items-center gap-1" onClick={exportCSV}>
            <i className="bi bi-download"></i> Export CSV
          </button>
        </div>
      </div>
      
      <div className="table-responsive">
        <table className="table table-hover align-middle">
          <thead>
            <tr>
              <th className="px-4">Model</th>
              <th className="px-4">Category</th>
              <th className="px-4">Status</th>
              <th className="px-4">Latency</th>
              <th className="px-4">RPM</th>
              <th className="px-4">Total Uses</th>
              <th className="px-4">Active Conns</th>
              <th className="px-4">Last Error</th>
              <th className="px-4">Last Check</th>
              <th className="px-4">Actions</th>
            </tr>
          </thead>
          <tbody>
            {filteredData.length === 0 ? (
              <tr>
                <td colSpan={10}>
                  <div className="text-center py-5">
                    <i className="bi bi-search text-muted opacity-50" style={{ fontSize: '4rem' }}></i>
                    <h5 className="fw-bold mt-3 text-secondary">No models match the current filters</h5>
                    <p className="text-muted">Try clearing or adjusting the category or status filters.</p>
                    <button className="btn btn-outline-secondary btn-sm mt-2 rounded-pill fw-medium" onClick={() => { setCategoryFilter(''); setStatusFilter(''); }}>Clear Filters</button>
                  </div>
                </td>
              </tr>
            ) : (
              filteredData.map(d => (
                <tr key={d.model} className={isDisabled(d) ? 'row-disabled' : ''}>
                  <td className="py-3 px-4">
                    <div className="d-flex align-items-center gap-2 flex-wrap">
                      <span 
                        className="rounded-circle flex-shrink-0 shadow-sm" 
                        style={{ 
                          width: '10px', 
                          height: '10px', 
                          backgroundColor: getModelColor(d.model, isDark, modelColorMap),
                          display: 'inline-block' 
                        }} 
                        title={`Color: ${d.model}`}
                      />
                      <span className="model-name fw-bold" title={d.model}>{d.model}</span>
                      {d.provider && (
                        <span className="badge bg-info bg-opacity-10 text-info border border-info border-opacity-25 fw-medium" style={{ fontSize: '0.65rem' }}>
                          <i className="bi bi-cloud me-1"></i>{d.provider}
                        </span>
                      )}
                      {isDisabled(d) && (
                        <span className="badge bg-secondary text-white fw-medium" style={{ fontSize: '0.65rem' }} title="Disabled in config — excluded from routing and health checks">
                          <i className="bi bi-pause-circle me-1"></i>DISABLED
                        </span>
                      )}
                      <span className="badge bg-secondary text-white fw-medium" style={{ fontSize: '0.65rem' }}>
                        <i className="bi bi-sort-numeric-up me-1"></i>Priority: {d.priority}
                      </span>
                    </div>
                  </td>
                  <td className="py-3 px-4">
                    {d.categories.map(c => (
                      <span key={c} className={`category-badge category-${c.toLowerCase()} me-1`}>{c}</span>
                    ))}
                  </td>
                  <td className="py-3 px-4">
                    {getStatus(d) === 'up' ? (
                      <span className="badge bg-success bg-opacity-10 text-success border border-success border-opacity-25 px-3 py-2 fw-bold" style={{ fontSize: '0.85rem' }}>
                        <i className="bi bi-circle-fill me-1" style={{ fontSize: '0.5rem', verticalAlign: 'middle' }}></i>UP
                      </span>
                    ) : (
                      <span className="badge bg-danger bg-opacity-10 text-danger border border-danger border-opacity-25 px-3 py-2 fw-bold" style={{ fontSize: '0.85rem' }}>
                        <i className="bi bi-circle-fill me-1" style={{ fontSize: '0.5rem', verticalAlign: 'middle' }}></i>DOWN
                      </span>
                    )}
                  </td>
                  <td className="py-3 px-4 fw-medium text-nowrap">
                    {getStatus(d) === 'down' ? <span className="text-danger opacity-75">N/A</span> : (
                      d.latencyMs > 1000 ? <span className="text-warning">{(d.latencyMs / 1000).toFixed(2)}s</span> : <span>{d.latencyMs}ms</span>
                    )}
                  </td>
                  <td className="py-3 px-4 fw-medium">{getModelRpm(d) > 0 ? getModelRpm(d) : <span className="text-muted opacity-50">&mdash;</span>}</td>
                  <td className="py-3 px-4 fw-medium">{formatNumber(d.totalUses)}</td>
                  <td className="py-3 px-4 fw-medium">{d.activeConnections}</td>
                  <td className="py-3 px-4 error-cell">
                    {!d.errorMessage ? <span className="text-muted opacity-50">&mdash;</span> : (
                      d.errorMessage === 'Not yet checked' ? <span className="text-muted opacity-50 small">Not yet checked</span> :
                      <span className="error-text fw-medium" style={{ fontSize: '0.8rem', color: 'var(--brand-danger)' }} title={d.errorMessage}>
                        <i className="bi bi-exclamation-triangle-fill me-1 opacity-75"></i>{formatErrorMessage(d.errorMessage)}
                      </span>
                    )}
                  </td>
                  <td className="py-3 px-4 text-nowrap">
                    <div className="fw-medium" style={{ fontSize: '0.85rem' }} title={d.lastChecked ? `Last check: ${new Date(d.lastChecked).toLocaleString()}` : 'No check recorded'}>{formatTimeAgo(d.lastChecked)}</div>
                    <div className="d-flex align-items-center gap-2 mt-1">
                      <span className={`badge ${isStatusFresh(d) ? 'bg-success text-white' : 'bg-secondary text-white'}`} style={{ fontSize: '0.65rem' }}>
                        {isStatusFresh(d) ? 'FRESH' : d.lastChecked ? 'STALE' : 'NO CHECK'}
                      </span>
                      <span className="text-muted" style={{ fontSize: '0.75rem' }}>{formatTimeOnly(d.lastChecked)}</span>
                    </div>
                  </td>
                  <td className="py-3 px-4">
                    <div className="d-flex align-items-center gap-1">
                      {(d.circuitOpen || !d.isUp) && (
                        <button 
                          className="btn btn-sm btn-outline-warning text-nowrap d-flex align-items-center gap-1" 
                          onClick={() => handleResetCircuit(d.model)}
                          disabled={resettingModel === d.model}
                          title="Reset recorded errors and circuit breaker"
                        >
                          <i className={`bi ${resettingModel === d.model ? 'bi-arrow-repeat spin' : 'bi-arrow-counterclockwise'}`}></i>
                          Reset
                        </button>
                      )}
                      <button className="btn btn-sm btn-outline-primary" onClick={() => handleEdit(d)}>
                        <i className="bi bi-pencil"></i> Edit
                      </button>
                    </div>
                  </td>

                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>

      {editingModel && (
        <div className="modal show d-block" tabIndex={-1} style={{ backgroundColor: 'rgba(0,0,0,0.5)' }}>
          <div className="modal-dialog modal-dialog-centered">
            <div className="modal-content">
              <div className="modal-header">
                <h5 className="modal-title">Edit Configuration for {editingModel.model}</h5>
                <button type="button" className="btn-close" onClick={() => setEditingModel(null)}></button>
              </div>
              <div className="modal-body">
                <div className="mb-3 form-check">
                  <input type="checkbox" className="form-check-input" id="editEnabled" checked={editEnabled} onChange={e => setEditEnabled(e.target.checked)} />
                  <label className="form-check-label" htmlFor="editEnabled">Enabled</label>
                </div>
                <div className="mb-3">
                  <label htmlFor="editPriority" className="form-label">Priority</label>
                  <input type="number" className="form-control" id="editPriority" value={editPriority} onChange={e => setEditPriority(parseInt(e.target.value) || 0)} />
                </div>
                <div className="mb-3">
                  <label htmlFor="editPipelines" className="form-label">Pipelines (comma separated)</label>
                  <input type="text" className="form-control" id="editPipelines" value={editPipelines} onChange={e => setEditPipelines(e.target.value)} placeholder="e.g. CODING, REASONING" />
                </div>
              </div>
              <div className="modal-footer">
                <button type="button" className="btn btn-secondary" onClick={() => setEditingModel(null)}>Cancel</button>
                <button type="button" className="btn btn-primary" onClick={handleSaveConfig}>Save changes</button>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

