import { useState, useEffect } from 'react';
import { RequesterStatus } from '../types';
import {
  Chart as ChartJS,
  CategoryScale,
  LinearScale,
  PointElement,
  LineElement,
  Title,
  Tooltip,
  Legend
} from 'chart.js';
import { Line, Doughnut } from 'react-chartjs-2';

ChartJS.register(CategoryScale, LinearScale, PointElement, LineElement, Title, Tooltip, Legend);

interface RequesterAnalyticsProps {
  requesters: RequesterStatus[];
}

export default function RequesterAnalytics({ requesters }: RequesterAnalyticsProps) {
  const [selectedRequester, setSelectedRequester] = useState<string>('');
  const [history, setHistory] = useState<any[]>([]);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (requesters.length > 0 && !selectedRequester) {
      setSelectedRequester(requesters[0].requester);
    }
  }, [requesters, selectedRequester]);

  useEffect(() => {
    if (!selectedRequester) return;
    let disposed = false;
    
    const fetchHistory = async () => {
      setLoading(true);
      try {
        const res = await fetch(`/api/requesters/${selectedRequester}/history?days=14`);
        if (res.ok) {
          const data = await res.json();
          if (!disposed) setHistory(data.reverse()); // reverse so chronological order
        }
      } catch (e) {
        console.error('Failed to fetch history', e);
      } finally {
        if (!disposed) setLoading(false);
      }
    };
    
    fetchHistory();
    return () => { disposed = true; };
  }, [selectedRequester]);

  const reqData = requesters.find(r => r.requester === selectedRequester);

  // History Chart
  const historyData = {
    labels: history.map(h => {
      const d = new Date(h.date);
      return d.toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
    }),
    datasets: [
      {
        label: 'Requests',
        data: history.map(h => h.requests),
        borderColor: '#2563eb',
        backgroundColor: '#2563eb20',
        borderWidth: 2,
        fill: true,
        tension: 0.3
      },
      {
        label: 'Tokens',
        data: history.map(h => h.tokens),
        borderColor: '#64748b',
        borderWidth: 2,
        tension: 0.3,
        hidden: true // hidden by default to keep scales clean
      }
    ]
  };

  const chartOptions = {
    responsive: true,
    maintainAspectRatio: false,
    plugins: {
      legend: { position: 'top' as const, labels: { color: '#e5e7eb' } } // Light gray for legend
    },
    scales: {
      x: {
        grid: { color: '#334155', drawBorder: false as const }, // Slate-700 for grid
        ticks: { color: '#9ca3af' } // Gray-400 for ticks
      },
      y: {
        grid: { color: '#334155', drawBorder: false as const },
        ticks: { color: '#9ca3af' }
      }
    }
  };

  // Pipeline distribution
  const pipelineData = {
    labels: reqData?.pipelines ? Object.keys(reqData.pipelines) : [],
    datasets: [{
      data: reqData?.pipelines ? Object.values(reqData.pipelines) : [],
      backgroundColor: ['#2563eb', '#64748b', '#f59e0b', '#ef4444', '#10b981'],
      borderWidth: 0
    }]
  };

  return (
    <div className="card border-0 shadow-sm rounded-4 overflow-hidden mt-4">
      <div className="card-header bg-transparent border-bottom p-3 px-4 d-flex justify-content-between align-items-center">
        <h5 className="mb-0 fw-bold"><i className="bi bi-graph-up text-primary me-2"></i>Requester Analytics</h5>
        
        <select 
          className="form-select form-select-sm w-auto bg-surface text-main border-secondary"
          value={selectedRequester} 
          onChange={e => setSelectedRequester(e.target.value)}
        >
          {requesters.map(r => (
            <option key={r.requester} value={r.requester}>{r.requester}</option>
          ))}
        </select>
      </div>
      
      <div className="card-body p-4">
        {loading && <div className="text-center text-muted py-5"><i className="bi bi-arrow-repeat spinner-pulse me-2"></i>Loading history...</div>}
        
        {!loading && history.length > 0 && (
          <div className="row g-4">
            <div className="col-lg-8">
              <h6 className="fw-bold mb-3 text-secondary text-uppercase" style={{fontSize: '0.8rem', letterSpacing: '1px'}}>14-Day Trend</h6>
              <div style={{ height: '300px' }}>
                <Line data={historyData} options={chartOptions} />
              </div>
            </div>
            <div className="col-lg-4">
              <h6 className="fw-bold mb-3 text-secondary text-uppercase" style={{fontSize: '0.8rem', letterSpacing: '1px'}}>Pipeline Distribution</h6>
              <div style={{ height: '250px' }} className="d-flex justify-content-center">
                {Object.keys(reqData?.pipelines || {}).length > 0 ? (
                  <Doughnut 
                    data={pipelineData} 
                    options={{ responsive: true, maintainAspectRatio: false, plugins: { legend: { position: 'bottom', labels: { color: '#e5e7eb' } } } }} 
                  />
                ) : (
                  <div className="d-flex align-items-center text-muted">No pipeline data</div>
                )}
              </div>
              
              {/* Models List */}
              <div className="mt-4">
                <h6 className="fw-bold mb-2 text-secondary text-uppercase" style={{fontSize: '0.75rem', letterSpacing: '1px'}}>Models Used</h6>
                <div className="d-flex flex-wrap gap-2">
                  {Object.entries(reqData?.models || {}).sort((a,b) => (b[1] as number) - (a[1] as number)).map(([m, c]) => (
                    <span key={m} className="badge bg-secondary bg-opacity-10 text-light border border-secondary border-opacity-25">
                      {m} <span className="opacity-75 ms-1">({c as number})</span>
                    </span>
                  ))}
                </div>
              </div>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
