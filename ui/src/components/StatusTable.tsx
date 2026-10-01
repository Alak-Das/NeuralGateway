import { useState, useMemo } from 'react';
import { ModelStatus } from '../types';
import { formatNumber, formatTimeAgo, formatTimeOnly, formatShort } from '../utils/formatters';

interface StatusTableProps {
  data: ModelStatus[];
}

export default function StatusTable({ data }: StatusTableProps) {
  const [categoryFilter, setCategoryFilter] = useState('');
  const [statusFilter, setStatusFilter] = useState('');
  const [sortCol, setSortCol] = useState('model');
  const [sortDir, setSortDir] = useState<'asc'|'desc'>('asc');

  const handleSort = (col: string) => {
    if (sortCol === col) {
      setSortDir(sortDir === 'asc' ? 'desc' : 'asc');
    } else {
      setSortCol(col);
      setSortDir('asc');
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

  const filteredData = useMemo(() => {
    return data.filter(d => {
      const catMatch = !categoryFilter || d.categories.includes(categoryFilter);
      let statusMatch = true;
      if (statusFilter === 'up') statusMatch = getStatus(d) === 'up';
      else if (statusFilter === 'down') statusMatch = getStatus(d) === 'down';
      return catMatch && statusMatch;
    }).sort((a, b) => {
      let valA: any = 0; let valB: any = 0;
      switch (sortCol) {
        case 'model': valA = a.model; valB = b.model; break;
        case 'status':
          valA = getStatus(a) === 'up' ? 1 : 0;
          valB = getStatus(b) === 'up' ? 1 : 0;
          break;
        case 'latency': valA = a.latencyMs; valB = b.latencyMs; break;
        case 'tps': valA = a.tps; valB = b.tps; break;
        case 'uses': valA = a.totalUses; valB = b.totalUses; break;
        case 'conns': valA = a.activeConnections; valB = b.activeConnections; break;
        case 'error': valA = a.errorMessage || ''; valB = b.errorMessage || ''; break;
        case 'updated': valA = a.lastChecked ? new Date(a.lastChecked).getTime() : 0; valB = b.lastChecked ? new Date(b.lastChecked).getTime() : 0; break;
        default: valA = a.model; valB = b.model;
      }
      if (valA < valB) return sortDir === 'asc' ? -1 : 1;
      if (valA > valB) return sortDir === 'asc' ? 1 : -1;
      if (sortCol === 'status') {
        return (b.priority ?? 0) - (a.priority ?? 0);
      }
      return 0;
    });
  }, [data, categoryFilter, statusFilter, sortCol, sortDir]);

  const exportCSV = () => {
    const headers = ['Model', 'Categories', 'Status', 'Latency (ms)', 'TPS', 'Total Uses', 'Active Conns', 'Last Error', 'Last Check'];
    const rows = filteredData.map(d => [
      d.model,
      d.categories.join('; '),
      getStatus(d) === 'up' ? 'UP' : 'DOWN',
      d.latencyMs,
      d.tps.toFixed(2),
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

  const getSortIcon = (col: string) => {
    if (sortCol !== col) return <i className="bi bi-arrow-down-up sort-icon"></i>;
    return <i className={`bi bi-arrow-${sortDir === 'asc' ? 'up' : 'down'} sort-icon`}></i>;
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
              <select className="form-select border-start-0 ps-0" value={categoryFilter} onChange={e => setCategoryFilter(e.target.value)}>
                <option value="">All Categories</option>
                {categories.map(c => <option key={c} value={c}>{c}</option>)}
              </select>
            </div>
            
            <div className="input-group input-group-sm">
              <span className="input-group-text bg-transparent border-end-0"><i className="bi bi-activity"></i></span>
              <select className="form-select border-start-0 ps-0" value={statusFilter} onChange={e => setStatusFilter(e.target.value)}>
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
              <th className="sortable px-4" onClick={() => handleSort('model')}>Model {getSortIcon('model')}</th>
              <th className="px-4">Category</th>
              <th className="sortable px-4" onClick={() => handleSort('status')}>Status {getSortIcon('status')}</th>
              <th className="sortable px-4" onClick={() => handleSort('latency')}>Latency {getSortIcon('latency')}</th>
              <th className="sortable px-4" onClick={() => handleSort('tps')}>TPS {getSortIcon('tps')}</th>
              <th className="sortable px-4" onClick={() => handleSort('uses')}>Total Uses {getSortIcon('uses')}</th>
              <th className="sortable px-4" onClick={() => handleSort('conns')}>Active Conns {getSortIcon('conns')}</th>
              <th className="sortable px-4" onClick={() => handleSort('error')}>Last Error {getSortIcon('error')}</th>
              <th className="sortable px-4" onClick={() => handleSort('updated')}>Last Check {getSortIcon('updated')}</th>
            </tr>
          </thead>
          <tbody>
            {filteredData.length === 0 ? (
              <tr>
                <td colSpan={9}>
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
                      <span className="model-name fw-bold" title={d.model}>{d.model}</span>
                      {d.provider && (
                        <span className="badge bg-info bg-opacity-10 text-info border border-info border-opacity-25 fw-medium" style={{ fontSize: '0.65rem' }}>
                          <i className="bi bi-cloud me-1"></i>{d.provider}
                        </span>
                      )}
                      {isDisabled(d) && (
                        <span className="badge bg-secondary bg-opacity-10 text-secondary border border-secondary border-opacity-25 fw-medium" style={{ fontSize: '0.65rem' }} title="Disabled in config — excluded from routing and health checks">
                          <i className="bi bi-pause-circle me-1"></i>DISABLED
                        </span>
                      )}
                      <span className="badge bg-secondary bg-opacity-10 text-secondary border border-secondary border-opacity-25 fw-medium" style={{ fontSize: '0.65rem' }}>
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
                  <td className="py-3 px-4 fw-medium">{d.tps > 0 ? d.tps.toFixed(2) : <span className="text-muted opacity-50">&mdash;</span>}</td>
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
                      <span className={`badge ${isStatusFresh(d) ? 'bg-success bg-opacity-10 text-success border border-success border-opacity-25' : 'bg-secondary bg-opacity-10 text-secondary border border-secondary border-opacity-25'}`} style={{ fontSize: '0.65rem' }}>
                        {isStatusFresh(d) ? 'FRESH' : d.lastChecked ? 'STALE' : 'NO CHECK'}
                      </span>
                      <span className="text-muted" style={{ fontSize: '0.75rem' }}>{formatTimeOnly(d.lastChecked)}</span>
                    </div>
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
