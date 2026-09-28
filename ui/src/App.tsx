import React, { useEffect, useState, useRef, createContext, useContext } from 'react';
import { ModelStatus, RequesterStatus } from './types';
import KpiGrid from './components/KpiGrid';
import StatusTable from './components/StatusTable';
import RequestersTable from './components/RequestersTable';
import Charts from './components/Charts';
import { ThemeContext } from './theme/ThemeContext';

// Theme context for charts


export default function App() {
  const [theme, setTheme] = useState<'dark' | 'light'>('dark');
  const [connectionStatus, setConnectionStatus] = useState<'CONNECTING' | 'LIVE' | 'DISCONNECTED' | 'RECONNECTING'>('CONNECTING');
  const [data, setData] = useState<ModelStatus[]>([]);
  const [requesters, setRequesters] = useState<RequesterStatus[]>([]);
  const [lastUpdated, setLastUpdated] = useState<Date | null>(null);
  const [activeTab, setActiveTab] = useState<'models' | 'requesters'>('models');

  const eventSourceRef = useRef<EventSource | null>(null);
  const reconnectAttemptsRef = useRef(0);
  const MAX_RECONNECT_ATTEMPTS = 10;
  const RECONNECT_DELAY_BASE = 1000;

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
    const connectSSE = () => {
      if (reconnectAttemptsRef.current >= MAX_RECONNECT_ATTEMPTS) {
        setConnectionStatus('DISCONNECTED');
        return;
      }

      setConnectionStatus(reconnectAttemptsRef.current === 0 ? 'CONNECTING' : 'RECONNECTING');
      const es = new EventSource('/api/models/status/stream');
      eventSourceRef.current = es;

      es.onopen = () => {
        setConnectionStatus('LIVE');
        reconnectAttemptsRef.current = 0;
      };

      es.addEventListener('init', (e: any) => {
        try {
          if (e.data) {
            const parsed = JSON.parse(e.data);
            setData(parsed);
            setLastUpdated(new Date());
            fetchRequesters();
          }
        } catch (err) {
          console.error('Error parsing init data:', err);
        }
      });

      es.addEventListener('status', (e: any) => {
        try {
          if (e.data) {
            const parsed = JSON.parse(e.data);
            setData(parsed);
            setLastUpdated(new Date());
            fetchRequesters();
          }
        } catch (err) {
          console.error('Error parsing status data:', err);
        }
      });

      es.onerror = () => {
        es.close();
        reconnectAttemptsRef.current += 1;
        setConnectionStatus('RECONNECTING');
        const delay = Math.min(RECONNECT_DELAY_BASE * Math.pow(1.5, reconnectAttemptsRef.current), 10000);
        setTimeout(connectSSE, delay);
      };
    };

    connectSSE();

    return () => {
      if (eventSourceRef.current) {
        eventSourceRef.current.close();
      }
    };
  }, []);

  const fetchRequesters = async () => {
    try {
      const res = await fetch('/api/requesters/status');
      if (res.ok) {
        const reqData = await res.json();
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
        setRequesters(entries);
      }
    } catch (err) {
      console.error('Failed to fetch requesters', err);
    }
  };

  useEffect(() => {
    fetchRequesters();
    if (activeTab === 'requesters') {
      const interval = setInterval(fetchRequesters, 4000);
      return () => clearInterval(interval);
    }
  }, [activeTab]);

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

