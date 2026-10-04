import { useMemo, useState, useContext } from 'react';
import { ModelStatus } from '../types';
import {
  Chart as ChartJS,
  CategoryScale,
  LinearScale,
  PointElement,
  LineElement,
  Title,
  Tooltip,
  Legend,
  Filler,
  ArcElement
} from 'chart.js';
import { Line, Doughnut } from 'react-chartjs-2';
import { themeColors } from '../theme/colors';

ChartJS.register(
  CategoryScale,
  LinearScale,
  PointElement,
  LineElement,
  Title,
  Tooltip,
  Legend,
  Filler,
  ArcElement
);


// Theme context to access current theme (provided by App)
import { ThemeContext } from '../theme/ThemeContext';
import { formatNumber } from '../utils/formatters';

interface ChartsProps {
  data: ModelStatus[];
}

export default function Charts({ data }: ChartsProps) {
  const [latencyRangeMins, setLatencyRangeMins] = useState(15);
  const [usageRangeMins, setUsageRangeMins] = useState(15);
  const [successRangeMins, setSuccessRangeMins] = useState(15);
  const theme = useContext(ThemeContext);
  const isDark = theme === 'dark';

  // Get theme-aware categorical colors for model series
  const getModelColors = (count: number) => 
    themeColors.chartHelpers.getCategoricalColors(count, isDark);

  // Get semantic colors for specific metrics
  const hasLatencyMeasurement = (historyEntry: ModelStatus['history'][number]) =>
    Number.isFinite(historyEntry.latencyMs) && historyEntry.latencyMs > 0;

  const getModelRequestsForRange = (d: ModelStatus, rangeMins: number) => {
    if (rangeMins === 0) {
      return d.history ? d.history.filter(h => !h.isBackgroundProbe).length : 0;
    }
    if (!d.history || d.history.length === 0) return 0;
    const cutoff = Date.now() - (rangeMins * 60 * 1000);
    return d.history.filter(h => {
      if (!h.timestamp) return false;
      const t = new Date(h.timestamp).getTime();
      return !isNaN(t) && t >= cutoff && !h.isBackgroundProbe;
    }).length;
  };

  const { latencyData, latencyOptions, latencyDatasetsCount, usageData, usageOptions, errorData, errorOptions, totalRequests, displayData, bgColors, activeModels } = useMemo(() => {
    // Model Color Map - using new categorical palette
    const modelColorMap: Record<string, string> = {};
    const modelColors = getModelColors(data.length);
    data.forEach((d, i) => { modelColorMap[d.model] = modelColors[i]; });

    // 1. Latency History Chart - Only models that are UP, in seconds
    const isModelUp = (m: ModelStatus) => ((m as any).up !== undefined ? (m as any).up : m.isUp);
    const upModels = data.filter(isModelUp);

    const latencyCutoffTime = latencyRangeMins === 0
      ? 0
      : Date.now() - (latencyRangeMins * 60 * 1000);
    const allTimestamps = new Set<number>();
    
    upModels.forEach(d => {
      if (d.history && Array.isArray(d.history)) {
        d.history.forEach(h => {
          if (!h.timestamp || !hasLatencyMeasurement(h)) return;
          const time = new Date(h.timestamp).getTime();
          if (!isNaN(time) && time >= latencyCutoffTime) {
            const bucketedTime = Math.floor(time / 60000) * 60000;
            allTimestamps.add(bucketedTime);
          }
        });
      }
    });

    const sortedTimestamps = Array.from(allTimestamps).sort((a, b) => a - b);
    const labels = sortedTimestamps.map(ts => {
      const d = new Date(ts);
      return `${d.getHours().toString().padStart(2, '0')}:${d.getMinutes().toString().padStart(2, '0')}`;
    });

    const latencyDatasets = upModels
      .filter(d => d.history && Array.isArray(d.history) && d.history.some(h => {
        if (!h.timestamp || !hasLatencyMeasurement(h)) return false;
        const time = new Date(h.timestamp).getTime();
        return !isNaN(time) && time >= latencyCutoffTime;
      }))
      .map((d, i) => {
        const latencyMap: Record<number, number> = {};
        if (d.history && Array.isArray(d.history)) {
          d.history.forEach(h => {
            if (h.timestamp && hasLatencyMeasurement(h)) {
              const time = new Date(h.timestamp).getTime();
              if (!isNaN(time) && time >= latencyCutoffTime) {
                const bucketedTime = Math.floor(time / 60000) * 60000;
                latencyMap[bucketedTime] = Number((h.latencyMs / 1000).toFixed(2));
              }
            }
          });
        }
        
        const alignedData = sortedTimestamps.map(ts => latencyMap[ts] !== undefined ? latencyMap[ts] : null);
        const myColor = modelColorMap[d.model] || getModelColors(data.length)[i];
      
        return {
          label: d.model.split('/').pop() || d.model,
          data: alignedData,
          borderColor: myColor,
          backgroundColor: myColor + '20',
          fill: false,
          tension: 0.3,
          pointRadius: 3,
          pointHoverRadius: 5,
          spanGaps: true
        };
      });

    const latencyOptionsObj = {
      responsive: true, 
      maintainAspectRatio: false,
      interaction: { mode: 'index' as const, intersect: false },
      plugins: {
        legend: { position: 'bottom' as const, labels: { usePointStyle: true, boxWidth: 6, color: isDark ? '#94a3b8' : '#64748b' } },
        tooltip: {
          callbacks: {
            label: function(context: any) {
              let label = context.dataset.label || '';
              if (label) label += ': ';
              if (context.parsed.y !== null && context.parsed.y !== undefined) {
                label += context.parsed.y.toFixed(2) + 's';
              }
              return label;
            }
          }
        }
      },
      scales: {
        y: { 
          grid: { color: isDark ? 'rgba(255,255,255,0.05)' : 'rgba(0,0,0,0.05)' }, 
          beginAtZero: true,
          ticks: { 
            callback: function(value: any) { return Number(value).toFixed(1) + 's'; },
            color: isDark ? '#94a3b8' : '#64748b' 
          }
        },
        x: { 
          grid: { display: false },
          ticks: { color: isDark ? '#94a3b8' : '#64748b' }
        }
      }
    };


    // 1.5 Success Rate History Chart
    const successCutoffTime = successRangeMins === 0
      ? 0
      : Date.now() - (successRangeMins * 60 * 1000);
    const successTimestamps = new Set<number>();
    
    data.forEach(d => {
      if (d.history && Array.isArray(d.history)) {
        d.history.forEach(h => {
          if (!h.timestamp) return;
          const time = new Date(h.timestamp).getTime();
          if (!isNaN(time) && time >= successCutoffTime) {
            const bucketedTime = Math.floor(time / 60000) * 60000;
            successTimestamps.add(bucketedTime);
          }
        });
      }
    });

    const sortedSuccessTimestamps = Array.from(successTimestamps).sort((a, b) => a - b);
    const successLabels = sortedSuccessTimestamps.map(ts => {
      const d = new Date(ts);
      return `${d.getHours().toString().padStart(2, "0")}:${d.getMinutes().toString().padStart(2, "0")}`;
    });

    const successDatasets = data
      .filter(d => d.history && Array.isArray(d.history) && d.history.some(h => {
        return h.timestamp && new Date(h.timestamp).getTime() >= successCutoffTime && !h.isBackgroundProbe;
      }))
      .map((d, i) => {
        const successStats: Record<number, { total: number, errors: number }> = {};
        if (d.history && Array.isArray(d.history)) {
          d.history.forEach(h => {
            const isUp = h.up !== undefined ? h.up : h.isUp;
            if (h.timestamp && !h.isBackgroundProbe) {
              const time = new Date(h.timestamp).getTime();
              if (!isNaN(time) && time >= successCutoffTime) {
                const bucketedTime = Math.floor(time / 60000) * 60000;
                if (!successStats[bucketedTime]) successStats[bucketedTime] = { total: 0, errors: 0 };
                successStats[bucketedTime].total++;
                if (!isUp) successStats[bucketedTime].errors++;
              }
            }
          });
        }
        
        const alignedData = sortedSuccessTimestamps.map(ts => {
           if (successStats[ts] && successStats[ts].total > 0) {
               return ((successStats[ts].total - successStats[ts].errors) / successStats[ts].total) * 100;
           }
           return null;
        });
        const myColor = modelColorMap[d.model] || getModelColors(data.length)[i];
      
        return {
          label: d.model.split("/").pop() || d.model,
          data: alignedData,
          borderColor: myColor,
          backgroundColor: myColor + "20",
          fill: false,
          tension: 0.3,
          pointRadius: 3,
          pointHoverRadius: 5,
          spanGaps: true
        };
      });

    const successOptionsObj = {
      responsive: true, 
      maintainAspectRatio: false,
      interaction: { mode: "index" as const, intersect: false },
      plugins: {
        legend: { position: "bottom" as const, labels: { usePointStyle: true, boxWidth: 6, color: isDark ? '#94a3b8' : '#64748b' } },
        tooltip: {
          callbacks: {
            label: function(context: any) {
              let label = context.dataset.label || "";
              if (label) label += ": ";
              if (context.parsed.y !== null) label += context.parsed.y.toFixed(1) + "% Success Rate";
              return label;
            }
          }
        }
      },
      scales: {
        y: { 
          grid: { color: isDark ? 'rgba(255,255,255,0.05)' : 'rgba(0,0,0,0.05)' }, 
          beginAtZero: true, 
          max: 100, 
          ticks: { 
            callback: function(value: any) { return value + "%"; },
            color: isDark ? '#94a3b8' : '#64748b'
          } 
        },
        x: { 
          grid: { display: false },
          ticks: { color: isDark ? '#94a3b8' : '#64748b' }
        }
      }
    };

    // 2. Usage Distribution Chart

    const modelsWithUsage = data.map(d => ({
      model: d.model,
      uses: getModelRequestsForRange(d, usageRangeMins)
    }));

    const activeModelsList = modelsWithUsage.filter(d => d.uses > 0);
    const fallbackData = [{ model: 'No Traffic in Period', uses: 1 }];
    const displayDataList = activeModelsList.length > 0 ? activeModelsList : (data.length > 0 ? fallbackData : []);
    
    const modelNames = displayDataList.map(d => d.model.split('/').pop() || d.model);
    const usageValues = displayDataList.map(d => d.uses);
    const bgColorsList = displayDataList.map((d, i) => activeModelsList.length > 0 ? (modelColorMap[d.model] || getModelColors(data.length)[i]) : '#64748b40');
    
    const totalRequestsVal = activeModelsList.length > 0 ? activeModelsList.reduce((sum, d) => sum + d.uses, 0) : 0;

    const usageDatasets = [{
      data: usageValues,
      backgroundColor: bgColorsList,
      borderWidth: 0,
      hoverOffset: 4
    }];

    const usageOptionsObj = {
      responsive: true, 
      maintainAspectRatio: false,
      cutout: '75%',
      plugins: {
        legend: { display: false },
        tooltip: {
          callbacks: {
            label: function(context: any) {
              if (activeModelsList.length === 0) return ' No traffic yet in selected period';
              let label = context.label || '';
              if (label) {
                label += ': ';
              }
              if (context.parsed !== null) {
                label += new Intl.NumberFormat().format(context.parsed) + ' reqs';
                // Calculate percentage
                const total = context.dataset.data.reduce((sum: number, val: number) => sum + val, 0);
                const percentage = ((context.parsed / total) * 100).toFixed(1);
                label += ` (${percentage}%)`;
              }
              return label;
            }
          }
        }
      }
    };

    return {
      latencyData: { labels, datasets: latencyDatasets },
      latencyOptions: latencyOptionsObj,
      latencyDatasetsCount: latencyDatasets.length,
      usageData: { labels: modelNames, datasets: usageDatasets },
      usageOptions: usageOptionsObj,
      errorData: { labels: successLabels, datasets: successDatasets },
      errorOptions: successOptionsObj,
      totalRequests: totalRequestsVal,
      displayData: displayDataList,
      bgColors: bgColorsList,
      activeModels: activeModelsList
    };
  }, [data, latencyRangeMins, usageRangeMins, successRangeMins]);

  return (
    <div className="row g-4 mb-4">
      {/* Latency History */}
      <div className="col-12 col-xl-4">
        <div className="card border-0 shadow-sm rounded-4 p-4 h-100">
          <div className="d-flex justify-content-between align-items-center mb-4">
            <div className="text-secondary fw-semibold" style={{ fontSize: '0.85rem', letterSpacing: '0.5px', textTransform: 'uppercase' }}>
              <i className="bi bi-graph-up text-primary me-2"></i>Latency History (s)
            </div>
            <select className="form-select form-select-sm w-auto rounded-pill" value={latencyRangeMins} onChange={e => setLatencyRangeMins(Number(e.target.value))}>
              <option value={15}>Last 15 Mins</option>
              <option value={60}>Last 1 Hour</option>
              <option value={360}>Last 6 Hours</option>
              <option value={1440}>Last 24 Hours</option>
            </select>
          </div>
          <div className="chart-container" style={{ position: 'relative', height: '300px', width: '100%' }}>
            {latencyDatasetsCount === 0 ? (
              <div className="d-flex flex-column align-items-center justify-content-center h-100 text-muted opacity-75 small">
                <i className="bi bi-info-circle mb-2" style={{ fontSize: '1.5rem' }}></i>
                <span>No UP models with latency data in this window</span>
              </div>
            ) : (
              <Line data={latencyData} options={latencyOptions} />
            )}
          </div>
        </div>
      </div>
      
      
      {/* Success Rate History */}
      <div className="col-12 col-xl-4">
        <div className="card border-0 shadow-sm rounded-4 p-4 h-100">
          <div className="d-flex justify-content-between align-items-center mb-4">
            <div className="text-secondary fw-semibold" style={{ fontSize: "0.85rem", letterSpacing: "0.5px", textTransform: "uppercase" }}>
              <i className="bi bi-check-circle-fill text-success me-2"></i>Success Rate
            </div>
            <select className="form-select form-select-sm w-auto rounded-pill" value={successRangeMins} onChange={e => setSuccessRangeMins(Number(e.target.value))}>
              <option value={15}>Last 15 Mins</option>
              <option value={60}>Last 1 Hour</option>
              <option value={360}>Last 6 Hours</option>
              <option value={1440}>Last 24 Hours</option>
            </select>
          </div>
          <div className="chart-container" style={{ position: "relative", height: "300px", width: "100%" }}>
            <Line data={errorData} options={errorOptions} />
          </div>
        </div>
      </div>

      {/* Usage Distribution */}
      <div className="col-12 col-xl-4">
        <div className="card border-0 shadow-sm rounded-4 p-4 h-100">
          <div className="d-flex justify-content-between align-items-center mb-4">
            <div className="text-secondary fw-semibold" style={{ fontSize: '0.85rem', letterSpacing: '0.5px', textTransform: 'uppercase' }}>
              <i className="bi bi-pie-chart-fill text-primary me-2"></i>Usage Distribution
            </div>
            <select className="form-select form-select-sm w-auto rounded-pill" value={usageRangeMins} onChange={e => setUsageRangeMins(Number(e.target.value))}>
              <option value={15}>Last 15 Mins</option>
              <option value={60}>Last 1 Hour</option>
              <option value={360}>Last 6 Hours</option>
              <option value={1440}>Last 24 Hours</option>
              <option value={0}>Retained History</option>
            </select>
          </div>
          <div className="d-flex flex-wrap flex-sm-nowrap justify-content-center align-items-center w-100" style={{ minHeight: '280px' }}>
            <div className="position-relative flex-shrink-0 d-flex align-items-center justify-content-center" style={{ flex: '0 0 40%', maxWidth: '40%', minWidth: '150px', height: '210px' }}>
              <Doughnut data={usageData} options={usageOptions} />
              <div 
                className="position-absolute d-flex flex-column align-items-center justify-content-center text-center" 
                style={{ pointerEvents: 'none', width: '120px', height: '120px' }}
              >
                <span className="fw-bolder text-main" style={{ fontSize: '1.5rem', lineHeight: '1.2' }}>
                  {formatNumber(totalRequests)}
                </span>
                <span className="text-muted" style={{ fontSize: '0.72rem', marginTop: '2px', letterSpacing: '0.3px' }}>
                  Total Requests
                </span>
              </div>
            </div>
            <div 
              className="d-flex flex-column justify-content-start ms-sm-4 mt-3 mt-sm-0 custom-scroll pe-2" 
              style={{ flex: '1', minWidth: '180px', maxWidth: '60%', maxHeight: '250px', overflowY: 'auto' }}
            >
              {activeModels.length === 0 ? (
                <div className="text-muted text-center py-4 small my-auto">
                  <i className="bi bi-clock-history d-block mb-1 fs-5 opacity-50"></i>
                  No requests in this time window
                </div>
              ) : (
                displayData.map((d: any, i: number) => {
                  const name = d.model.split('/').pop() || d.model;
                  return (
                    <div 
                      key={d.model} 
                      className="d-flex align-items-center justify-content-between mb-2 py-1 px-2 rounded-2" 
                      style={{ fontSize: '0.82rem', backgroundColor: 'rgba(255,255,255,0.03)' }}
                    >
                      <div className="d-flex align-items-center text-truncate me-2" style={{ minWidth: 0 }}>
                        <span className="rounded-circle me-2 flex-shrink-0 shadow-sm" style={{ width: '10px', height: '10px', backgroundColor: bgColors[i] }}></span>
                        <span className="text-main text-truncate fw-medium" title={d.model}>{name}</span>
                      </div>
                      <span className="badge bg-secondary bg-opacity-25 text-main font-monospace px-2 py-1 flex-shrink-0" style={{ fontSize: '0.75rem' }}>
                        {formatNumber(d.uses)}
                      </span>
                    </div>
                  );
                })
              )}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
