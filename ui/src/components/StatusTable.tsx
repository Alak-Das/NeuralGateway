import React, { useState, useMemo } from 'react';
import { ModelStatus } from '../types';

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

  const formatNumber = (num: number | null | undefined) => {
    if (num == null || isNaN(num)) return '0';
    if (num >= 1000000000) return (num / 1000000000).toFixed(2) + 'B';
    if (num >= 1000000) return (num / 1000000).toFixed(2) + 'M';
    if (num >= 1000) return (num / 1000).toFixed(1) + 'K';
    return num.toString();
  };

  const formatTimeAgo = (dateStr: string) => {
    if (!dateStr) return 'Never';
    const date = new Date(dateStr);
    const seconds = Math.floor((new Date().getTime() - date.getTime()) / 1000);
    if (seconds < 60) return 'Just now';
    const minutes = Math.floor(seconds / 60);
    if (minutes < 60) return `${minutes}m ago`;
    const hours = Math.floor(minutes / 60);
    if (hours < 24) return `${hours}h ago`;
    return `${Math.floor(hours / 24)}d ago`;
  };

  const formatTimeOnly = (dateStr: string) => {
    if (!dateStr) return '';
    const date = new Date(dateStr);
    return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' }).toLowerCase();
  };

  const formatErrorMessage = (msg: string | null) => {
    if (!msg) return '';
    if (msg === 'Not yet checked') return msg;
    
    try {
      if (msg.trim().startsWith('{') && msg.trim().endsWith('}')) {
        const parsed = JSON.parse(msg);
        if (parsed.status === 429 || parsed.title === 'Too Many Requests') return 'Rate Limited (429)';
        if (parsed.title) return parsed.title;
        if (parsed.detail) return parsed.detail;
        if (parsed.error?.message) return parsed.error.message;
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

  const filteredData = useMemo(() => {
    return data.filter(d => {
      const catMatch = !categoryFilter || d.categories.includes(categoryFilter);
      let statusMatch = true;
      if (statusFilter === 'up') statusMatch = d.isUp && !d.circuitOpen;
      else if (statusFilter === 'down') statusMatch = !d.isUp;
      else if (statusFilter === 'circuit') statusMatch = d.circuitOpen;
      return catMatch && statusMatch;
    }).sort((a, b) => {
      let valA: any = 0; let valB: any = 0;
      switch (sortCol) {
        case 'model': valA = a.model; valB = b.model; break;
          case 'status': valA = a.circuitOpen ? -1 : (a.isUp ? 1 : 0); valB = b.circuitOpen ? -1 : (b.isUp ? 1 : 0); break;
        case 'latency': valA = a.latencyMs; valB = b.latencyMs; break;
        case 'tps': valA = a.tps; valB = b.tps; break;
        case 'uses': valA = a.totalUses; valB = b.totalUses; break;
        case 'conns': valA = a.activeConnections; valB = b.activeConnections; break;
        case 'circuit': valA = a.circuitOpen ? 1 : 0; valB = b.circuitOpen ? 1 : 0; break;
        case 'error': valA = a.errorMessage || ''; valB = b.errorMessage || ''; break;
        case 'updated': valA = a.lastChecked ? new Date(a.lastChecked).getTime() : 0; valB = b.lastChecked ? new Date(b.lastChecked).getTime() : 0; break;
        default: valA = a.model; valB = b.model;
      }
      if (valA < valB) return sortDir === 'asc' ? -1 : 1;
      if (valA > valB) return sortDir === 'asc' ? 1 : -1;
      return 0;
    });
  }, [data, categoryFilter, statusFilter, sortCol, sortDir]);

  const exportCSV = () => {
    const headers = ['Model', 'Categories', 'Status', 'Latency (ms)', 'TPS', 'Total Uses', 'Active Conns', 'Circuit Breaker', 'Last Error', 'Last Updated'];
    const rows = filteredData.map(d => [
      d.model,
      d.categories.join('; '),
      d.circuitOpen ? 'CIRCUIT OPEN' : (d.isUp ? 'UP' : 'DOWN'),
      d.latencyMs,
      d.tps.toFixed(2),
      d.totalUses,
      d.activeConnections,
      d.circuitOpen ? 'Open' : 'Closed',
      d.errorMessage || '',
      new Date(d.lastChecked).toISOString()
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
                <option value="up">Up (Healthy)</option>
                <option value="down">Down</option>
                <option value="circuit">Circuit Open</option>
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
              <th className="sortable px-4" onClick={() => handleSort('circuit')}>Circuit Breaker {getSortIcon('circuit')}</th>
              <th className="sortable px-4" onClick={() => handleSort('error')}>Last Error {getSortIcon('error')}</th>
              <th className="sortable px-4" onClick={() => handleSort('updated')}>Last Updated {getSortIcon('updated')}</th>
            </tr>
          </thead>
          <tbody>
            {filteredData.length === 0 ? (
              <tr>
                <td colSpan={10}>
                  <div className="text-center py-5">
                    <i className="bi bi-search text-muted opacity-50" style={{ fontSize: '4rem' }}></i>
                    <h5 className="fw-bold mt-3 text-secondary">No models match the current filters</h5>
                    <p className="text-muted">Try clearing or adjusting the category and status filters.</p>
                    <button className="btn btn-outline-secondary btn-sm mt-2 rounded-pill fw-medium" onClick={() => { setCategoryFilter(''); setStatusFilter(''); }}>Clear Filters</button>
                  </div>
                </td>
              </tr>
            ) : (
              filteredData.map(d => (
                <tr key={d.model}>
                  <td className="py-3 px-4">
                    <span className="model-name fw-bold" title={d.model}>{d.model}</span>
                  </td>
                  <td className="py-3 px-4">
                    {d.categories.map(c => (
                      <span key={c} className={`category-badge category-${c.toLowerCase()} me-1`}>{c}</span>
                    ))}
                  </td>
                  <td className="py-3 px-4">
                    {d.circuitOpen ? (
                      <span className="badge bg-warning bg-opacity-10 text-warning border border-warning border-opacity-25 px-2 py-1"><i className="bi bi-exclamation-triangle-fill me-1"></i>CIRCUIT OPEN</span>
                    ) : d.isUp ? (
                      <span className="badge bg-success bg-opacity-10 text-success border border-success border-opacity-25 px-2 py-1"><i className="bi bi-circle-fill me-1" style={{ fontSize: '0.5rem', verticalAlign: 'middle' }}></i>UP</span>
                    ) : (
                      <span className="badge bg-danger bg-opacity-10 text-danger border border-danger border-opacity-25 px-2 py-1"><i className="bi bi-arrow-down-circle-fill me-1"></i>DOWN</span>
                    )}
                  </td>
                  <td className="py-3 px-4 fw-medium text-nowrap">
                    {!d.isUp && !d.circuitOpen ? <span className="text-danger opacity-75">N/A</span> : (
                      d.latencyMs > 1000 ? <span className="text-warning">{(d.latencyMs / 1000).toFixed(2)}s</span> : <span>{d.latencyMs}ms</span>
                    )}
                  </td>
                  <td className="py-3 px-4 fw-medium">{d.tps > 0 ? d.tps.toFixed(2) : <span className="text-muted opacity-50">&mdash;</span>}</td>
                  <td className="py-3 px-4 fw-medium">{formatNumber(d.totalUses)}</td>
                  <td className="py-3 px-4 fw-medium">{d.activeConnections}</td>
                  <td className="py-3 px-4">
                    <span className={`badge ${d.circuitOpen ? 'bg-danger text-white' : 'bg-success bg-opacity-10 text-success border border-success border-opacity-25'} px-2 py-1`}>
                      {d.circuitOpen ? 'OPEN' : 'CLOSED'}
                    </span>
                  </td>
                  <td className="py-3 px-4">
                    {!d.errorMessage ? <span className="text-muted opacity-50">&mdash;</span> : (
                      d.errorMessage === 'Not yet checked' ? <span className="text-muted opacity-50 small">Not yet checked</span> :
                      <span className="error-text fw-medium" style={{ fontSize: '0.8rem', color: 'var(--brand-danger)' }} title={d.errorMessage}>
                        <i className="bi bi-exclamation-triangle-fill me-1 opacity-75"></i>{formatErrorMessage(d.errorMessage)}
                      </span>
                    )}
                  </td>
                  <td className="py-3 px-4 text-nowrap">
                    <div className="fw-medium" style={{ fontSize: '0.85rem' }} title={d.lastChecked ? new Date(d.lastChecked).toLocaleString() : ''}>{formatTimeAgo(d.lastChecked)}</div>
                    <div className="text-muted" style={{ fontSize: '0.75rem' }}>{formatTimeOnly(d.lastChecked)}</div>
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


