import React, { useEffect, useState, useRef } from 'react';
import { ModelStatus, RequesterStatus } from './types';
import KpiGrid from './components/KpiGrid';
import StatusTable from './components/StatusTable';
import RequestersTable from './components/RequestersTable';
import Charts from './components/Charts';
import { ThemeContext } from './theme/ThemeContext';

export default function App() {
  const [theme, setTheme] = useState<'dark' | 'light'>('dark');
  const [connectionStatus, setConnectionStatus] = useState<'CONNECTING' | 'LIVE' | 'DISCONNECTED' | 'RECONNECTING'>('CONNECTING');
  const [data, setData] = useState<ModelStatus[]>([]);
  const [requesters, setRequesters] = useState<RequesterStatus[]>([]);
  const [lastUpdated, setLastUpdated] = useState<Date | null>(null);
  const [activeTab, setActiveTab] = useState<'models' | 'requesters'>('models');

  const eventSourceRef = useRef<EventSource | null>(null);
  const reconnectAttemptsRef = useRef(0);

  useEffect(() => {
    const savedTheme = localStorage.getItem('theme');
    const systemTheme = window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
    const initialTheme: 'dark' | 'light' = savedTheme === 'dark' || savedTheme === 'light'
      ? savedTheme
      : systemTheme;
    document.documentElement.dataset.theme = initialTheme;
    document.documentElement.setAttribute('data-bs-theme', initialTheme);
    setTheme(initialTheme);
  }, []);

  const toggleTheme = () => {
    const newTheme = theme === 'dark' ? 'light' : 'dark';
    document.documentElement.dataset.theme = newTheme;
    document.documentElement.setAttribute('data-bs-theme', newTheme);
    localStorage.setItem('theme', newTheme);
    setTheme(newTheme);
  };

  useEffect(() => {
    let disposed = false;
    let refreshTimeout: number | undefined;
    let reconnectTimeout: number | undefined;
    let requestTimeout: number | undefined;
    let refreshInProgress = false;
    let statusRevision = 0;
    let activeRequest: AbortController | null = null;

    const refreshDashboard = async () => {
      if (disposed || refreshInProgress) return;

      refreshInProgress = true;
      const controller = new AbortController();
      activeRequest = controller;
      const revisionAtStart = statusRevision;
      requestTimeout = window.setTimeout(() => controller.abort(), 10000);

      const refreshModels = async () => {
        try {
          const response = await fetch('/api/models/status', {
            cache: 'no-store',
            signal: controller.signal
          });
          if (!response.ok) return false;

          const statuses: ModelStatus[] = await response.json();
          if (!Array.isArray(statuses)) {
            console.error('Failed to refresh model statuses: response was not an array');
            return false;
          }
          if (!disposed && revisionAtStart === statusRevision) setData(statuses);
          return true;
        } catch (err) {
          if (!controller.signal.aborted) console.error('Failed to refresh model statuses', err);
          return false;
        }
      };

      const refreshRequesters = async () => {
        try {
          const response = await fetch('/api/requesters/status', {
            cache: 'no-store',
            signal: controller.signal
          });
          if (!response.ok) return false;

          const reqData = await response.json();
          let entries: RequesterStatus[] = [];
          if (Array.isArray(reqData)) {
            entries = reqData.map((item: any) => ({
              requester: item.requester || item.identity || item.name || 'unknown',
              count: Number(item.count ?? item.tokens ?? item.total ?? 0)
            }));
          } else if (typeof reqData === 'object' && reqData !== null) {
            entries = Object.entries(reqData).map(([requester, count]) => ({
              requester,
              count: Number(count)
            }));
          }
          entries.sort((a, b) => b.count - a.count);
          if (!disposed) setRequesters(entries);
          return true;
        } catch (err) {
          if (!controller.signal.aborted) console.error('Failed to refresh requesters', err);
          return false;
        }
      };

      try {
        const results = await Promise.all([refreshModels(), refreshRequesters()]);
        if (!disposed && results.some(Boolean)) setLastUpdated(new Date());
      } finally {
        window.clearTimeout(requestTimeout);
        refreshInProgress = false;
        if (activeRequest === controller) activeRequest = null;
        if (!disposed) refreshTimeout = window.setTimeout(refreshDashboard, 5000);
      }
    };

    const applyStreamUpdate = (event: Event) => {
      try {
        const parsed = JSON.parse((event as MessageEvent<string>).data);
        if (Array.isArray(parsed)) {
          statusRevision += 1;
          setData(parsed);
          setLastUpdated(new Date());
        }
      } catch (err) {
        console.error('Error parsing model status stream data:', err);
      }
    };

    const connectSSE = () => {
      if (disposed) return;

      setConnectionStatus(reconnectAttemptsRef.current === 0 ? 'CONNECTING' : 'RECONNECTING');
      let eventSource: EventSource;
      try {
        eventSource = new EventSource('/api/models/status/stream');
      } catch (err) {
        console.error('Failed to open model status stream', err);
        reconnectAttemptsRef.current += 1;
        setConnectionStatus('RECONNECTING');
        const backoffStep = Math.min(reconnectAttemptsRef.current - 1, 5);
        reconnectTimeout = window.setTimeout(connectSSE, Math.min(1000 * Math.pow(2, backoffStep), 30000));
        return;
      }
      eventSourceRef.current = eventSource;

      eventSource.onopen = () => {
        reconnectAttemptsRef.current = 0;
        setConnectionStatus('LIVE');
      };
      eventSource.addEventListener('init', applyStreamUpdate);
      eventSource.addEventListener('status', applyStreamUpdate);
      eventSource.onerror = () => {
        eventSource.close();
        if (disposed) return;

        reconnectAttemptsRef.current += 1;
        setConnectionStatus('RECONNECTING');
        const backoffStep = Math.min(reconnectAttemptsRef.current - 1, 5);
        const delay = Math.min(1000 * Math.pow(2, backoffStep), 30000);
        reconnectTimeout = window.setTimeout(connectSSE, delay);
      };
    };

    const refreshWhenVisible = () => {
      if (document.visibilityState !== 'visible' || disposed) return;
      window.clearTimeout(refreshTimeout);
      if (!refreshInProgress) void refreshDashboard();
    };

    connectSSE();
    void refreshDashboard();
    document.addEventListener('visibilitychange', refreshWhenVisible);

    return () => {
      disposed = true;
      window.clearTimeout(refreshTimeout);
      window.clearTimeout(reconnectTimeout);
      window.clearTimeout(requestTimeout);
      activeRequest?.abort();
      eventSourceRef.current?.close();
      document.removeEventListener('visibilitychange', refreshWhenVisible);
    };
  }, []);

  const getConnectionBadge = () => {
    switch (connectionStatus) {
      case 'LIVE': return <span className="badge bg-success bg-opacity-10 text-success border border-success border-opacity-25 px-2 py-1"><i className="bi bi-circle-fill me-1" style={{fontSize:'0.5rem', verticalAlign:'middle'}}></i>CONNECTED (LIVE)</span>;
      case 'CONNECTING': return <span className="badge bg-warning bg-opacity-10 text-warning border border-warning border-opacity-25 px-2 py-1"><i className="bi bi-arrow-repeat spinner-pulse me-1"></i>CONNECTING...</span>;
      case 'RECONNECTING': return <span className="badge bg-warning bg-opacity-10 text-warning border border-warning border-opacity-25 px-2 py-1"><i className="bi bi-arrow-repeat spinner-pulse me-1"></i>RECONNECTING...</span>;
      case 'DISCONNECTED': return <span className="badge bg-danger bg-opacity-10 text-danger border border-danger border-opacity-25 px-2 py-1"><i className="bi bi-x-circle me-1"></i>DISCONNECTED</span>;
    }
  };

  return (
    <div>
      <nav className="navbar border-bottom py-3 sticky-top" style={{ backgroundColor: 'var(--bg-surface)', zIndex: 1020 }}>
        <div className="container-fluid px-4 px-lg-5 d-flex justify-content-between align-items-center">
          <div className="navbar-brand d-flex align-items-center gap-3 m-0">
            <div className="d-flex align-items-center justify-content-center bg-primary bg-opacity-10 text-primary rounded-3" style={{ width: '40px', height: '40px' }}>
              <i className="bi bi-cpu fs-5"></i>
            </div>
            <div>
              <h1 className="h5 mb-0 fw-bold" style={{ letterSpacing: '-0.5px' }}>Neural Gateway</h1>
              <div className="text-muted" style={{ fontSize: '0.75rem', letterSpacing: '1px' }}>by Alak</div>
            </div>
          </div>
          
          <div className="d-flex align-items-center gap-3">
            <div className="d-flex align-items-center gap-3 text-muted me-2 d-none d-sm-flex" style={{ fontSize: '0.8rem' }}>
              <span className="badge bg-secondary bg-opacity-10 text-secondary border border-secondary border-opacity-25 px-2 py-1">
                <i className="bi bi-shield-check me-1"></i>Scheduled health checks
              </span>
              {getConnectionBadge()}
              <span>Last updated: <span className="fw-medium text-main">{lastUpdated ? lastUpdated.toLocaleTimeString() : '---'}</span></span>
            </div>
            <button className="btn btn-sm btn-outline-secondary rounded-circle" onClick={toggleTheme} style={{ width: '36px', height: '36px', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
              {theme === 'dark' ? <i className="bi bi-moon-fill"></i> : <i className="bi bi-sun-fill"></i>}
            </button>
          </div>
        </div>
      </nav>
      
      <main className="container-fluid px-4 px-lg-5 py-4">
        <KpiGrid data={data} lastUpdated={lastUpdated} />
        
        <ThemeContext.Provider value={theme}>
          <Charts data={data} />
        </ThemeContext.Provider>
        
        <ul className="nav nav-tabs mb-4 border-bottom">
          <li className="nav-item">
            <button className={`nav-link ${activeTab === 'models' ? 'active' : ''}`} onClick={() => setActiveTab('models')}>
              Model Status
            </button>
          </li>
          <li className="nav-item">
            <button className={`nav-link ${activeTab === 'requesters' ? 'active' : ''}`} onClick={() => setActiveTab('requesters')}>
              Requesters
            </button>
          </li>
        </ul>

        {activeTab === 'models' ? (
          <StatusTable data={data} />
        ) : (
          <RequestersTable requesters={requesters} />
        )}
      </main>
    </div>
  );
}
