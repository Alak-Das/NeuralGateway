import { useEffect, useState, useRef } from 'react';
import { ModelStatus, RequesterStatus } from './types';
import KpiGrid from './components/KpiGrid';
import StatusTable from './components/StatusTable';
import RequestersTable from './components/RequestersTable';
import RequesterAnalytics from './components/RequesterAnalytics';
import Charts from './components/Charts';
import LiveLogs from './components/LiveLogs';
import { ThemeContext } from './theme/ThemeContext';

export default function App() {
  const [theme, setTheme] = useState<'dark' | 'light'>('dark');
  const [connectionStatus, setConnectionStatus] = useState<'CONNECTING' | 'LIVE' | 'DISCONNECTED' | 'RECONNECTING'>('CONNECTING');
  const [data, setData] = useState<ModelStatus[]>([]);
  const [requesters, setRequesters] = useState<RequesterStatus[]>([]);
  const [lastUpdated, setLastUpdated] = useState<Date | null>(null);
  const [activeTab, setActiveTab] = useState<'models' | 'requesters' | 'traces'>('models');
  const [isAutoRefreshPaused, setIsAutoRefreshPaused] = useState(false);

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

  const manualRefresh = async () => {
    try {
      const [modelsRes, reqsRes] = await Promise.all([
        fetch('/api/models/status', { cache: 'no-store' }),
        fetch('/api/requesters/status', { cache: 'no-store' })
      ]);
      if (modelsRes.ok) {
        const statuses = await modelsRes.json();
        if (Array.isArray(statuses)) setData(statuses);
      }
      if (reqsRes.ok) {
        const reqData = await reqsRes.json();
        let entries: RequesterStatus[] = [];
        if (Array.isArray(reqData)) {
          entries = reqData.map((item: any) => ({
            requester: item.requester || item.identity || item.name || 'unknown',
            count: Number(item.count ?? item.tokens ?? item.total ?? 0),
            tokenCount: Number(item.tokenCount ?? item.count ?? item.tokens ?? 0),
            requestCount: Number(item.requestCount ?? 0),
            errorCount: Number(item.errorCount ?? 0),
            avgLatencyMs: Number(item.avgLatencyMs ?? 0),
            models: item.models,
            pipelines: item.pipelines
          }));
        } else if (typeof reqData === 'object' && reqData !== null) {
          entries = Object.entries(reqData).map(([requester, itemData]: [string, any]) => {
            if (typeof itemData === 'object' && itemData !== null) {
              return {
                requester,
                count: Number(itemData.tokenCount ?? itemData.count ?? 0),
                tokenCount: Number(itemData.tokenCount ?? itemData.count ?? 0),
                requestCount: Number(itemData.requestCount ?? 0),
                errorCount: Number(itemData.errorCount ?? 0),
                avgLatencyMs: Number(itemData.avgLatencyMs ?? 0),
                models: itemData.models,
                pipelines: itemData.pipelines
              };
            }
            return {
              requester,
              count: Number(itemData),
              tokenCount: Number(itemData),
              requestCount: 0,
              errorCount: 0,
              avgLatencyMs: 0
            };
          });
        }
        entries.sort((a, b) => (b.tokenCount || b.count) - (a.tokenCount || a.count));
        setRequesters(entries);
      }
      setLastUpdated(new Date());
    } catch (e) {
      console.error('Manual refresh failed', e);
    }
  };

  useEffect(() => {
    if (isAutoRefreshPaused) {
      setConnectionStatus('DISCONNECTED');
      eventSourceRef.current?.close();
      return;
    }

    let disposed = false;
    let reconnectTimeout: number | undefined;

    const applyStreamUpdate = (event: Event) => {
      try {
        const parsed = JSON.parse((event as MessageEvent<string>).data);
        if (Array.isArray(parsed)) {
          setData(parsed);
          setLastUpdated(new Date());
        } else if (parsed && typeof parsed === 'object' && parsed.models && parsed.requesters) {
          setData(parsed.models);
          
          let entries: RequesterStatus[] = [];
          if (Array.isArray(parsed.requesters)) {
            entries = parsed.requesters.map((item: any) => ({
              requester: item.requester || item.identity || item.name || 'unknown',
              count: Number(item.count ?? item.tokens ?? item.total ?? 0),
              tokenCount: Number(item.tokenCount ?? item.count ?? item.tokens ?? 0),
              requestCount: Number(item.requestCount ?? 0),
              errorCount: Number(item.errorCount ?? 0),
              avgLatencyMs: Number(item.avgLatencyMs ?? 0),
              models: item.models,
              pipelines: item.pipelines
            }));
          }
          entries.sort((a, b) => (b.tokenCount || b.count) - (a.tokenCount || a.count));
          setRequesters(entries);
          setLastUpdated(new Date());
        }
      } catch (err) {
        console.error('Error parsing stream data:', err);
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
      if (document.visibilityState === 'visible' && !disposed && eventSourceRef.current?.readyState === EventSource.CLOSED) {
         connectSSE();
      }
    };

    connectSSE();
    document.addEventListener('visibilitychange', refreshWhenVisible);

    return () => {
      disposed = true;
      window.clearTimeout(reconnectTimeout);
      eventSourceRef.current?.close();
      document.removeEventListener('visibilitychange', refreshWhenVisible);
    };
  }, [isAutoRefreshPaused]);

  const getConnectionBadge = () => {
    switch (connectionStatus) {
      case 'LIVE': return <span className="badge bg-success bg-opacity-10 text-success border border-success border-opacity-25 px-2 py-1"><i className="bi bi-circle-fill me-1" style={{fontSize:'0.5rem', verticalAlign:'middle'}}></i>CONNECTED (LIVE)</span>;
      case 'CONNECTING': return <span className="badge bg-warning bg-opacity-10 text-warning border border-warning border-opacity-25 px-2 py-1"><i className="bi bi-arrow-repeat spinner-pulse me-1"></i>CONNECTING...</span>;
      case 'RECONNECTING': return <span className="badge bg-warning bg-opacity-10 text-warning border border-warning border-opacity-25 px-2 py-1"><i className="bi bi-arrow-repeat spinner-pulse me-1"></i>RECONNECTING...</span>;
      case 'DISCONNECTED': return <span className="badge bg-danger bg-opacity-10 text-danger border border-danger border-opacity-25 px-2 py-1"><i className="bi bi-pause-circle me-1"></i>PAUSED</span>;
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
          
          <div className="d-flex flex-wrap align-items-center justify-content-end gap-2 gap-md-3">
            <div className="d-flex align-items-center gap-2 text-muted" style={{ fontSize: '0.8rem' }}>
              <span className="badge bg-secondary text-white px-2 py-1 d-none d-md-inline-block">
                <i className="bi bi-shield-check me-1"></i>Scheduled health checks
              </span>
              
              {getConnectionBadge()}
              
              <div className="form-check form-switch m-0 d-flex align-items-center gap-2 border-start ps-3 ms-2">
                <input 
                  className="form-check-input m-0" 
                  type="checkbox" 
                  role="switch" 
                  id="autoRefreshToggle" 
                  checked={!isAutoRefreshPaused}
                  onChange={() => setIsAutoRefreshPaused(!isAutoRefreshPaused)}
                  style={{ cursor: 'pointer' }}
                />
                <label className="form-check-label m-0 user-select-none d-none d-sm-inline-block" htmlFor="autoRefreshToggle" style={{ cursor: 'pointer', whiteSpace: 'nowrap' }}>
                  Live Updates
                </label>
              </div>

              <button 
                className="btn btn-sm btn-outline-primary py-0 px-2 d-flex align-items-center justify-content-center"
                onClick={manualRefresh}
                title="Refresh Now"
                style={{ height: '26px' }}
              >
                <i className="bi bi-arrow-clockwise"></i>
              </button>

              <span className="ms-1 d-none d-lg-inline-block">Last updated: <span className="fw-medium text-main">{lastUpdated ? lastUpdated.toLocaleTimeString() : '---'}</span></span>
            </div>
            <button className="btn btn-sm btn-outline-secondary rounded-circle ms-2" onClick={toggleTheme} style={{ width: '36px', height: '36px', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
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
          <li className="nav-item">
            <button className={`nav-link ${activeTab === 'traces' ? 'active' : ''}`} onClick={() => setActiveTab('traces')}>
              Live Traces
            </button>
          </li>
        </ul>

        {activeTab === 'models' ? (
          <StatusTable data={data} />
        ) : activeTab === 'requesters' ? (
          <>
            <RequestersTable requesters={requesters} />
            {requesters.length > 0 && <RequesterAnalytics requesters={requesters} />}
          </>
        ) : (
          <LiveLogs />
        )}
      </main>
    </div>
  );
}
