import React, { useMemo, useState } from 'react';
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

const colors = [
  '#FF6B6B', '#4DA1FF', '#C162FD', '#FFB86C', '#48DBFB',
  '#6BCB77', '#FF9F1C', '#E056FD', '#00D4AA', '#FF6F91'
];

interface ChartsProps {
  data: ModelStatus[];
}

export default function Charts({ data }: ChartsProps) {
  const [rangeMins, setRangeMins] = useState(60);

  const formatNumber = (num: number) => {
    if (num >= 1000000000) return (num / 1000000000).toFixed(2) + 'B';
    if (num >= 1000000) return (num / 1000000).toFixed(2) + 'M';
    if (num >= 1000) return (num / 1000).toFixed(1) + 'K';
    return num.toString();
  };

  const { latencyData, latencyOptions, usageData, usageOptions, totalRequests, displayData, bgColors } = useMemo(() => {
    // Model Color Map
    const modelColorMap: Record<string, string> = {};
    data.forEach((d, i) => { modelColorMap[d.model] = colors[i % colors.length]; });

    // Latency Chart
    const cutoffTime = Date.now() - (rangeMins * 60 * 1000);
    const allTimestamps = new Set<number>();
    
    data.forEach(d => {
      if (d.history) {
        d.history.forEach(h => {
          const time = new Date(h.timestamp).getTime();
          if (time >= cutoffTime) {
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

    const latencyDatasets = data
      .filter(d => d.history && d.history.some(h => {
        const isUp = h.up !== undefined ? h.up : h.isUp;
        return isUp && new Date(h.timestamp).getTime() >= cutoffTime;
      }))
      .map((d, i) => {
        const latencyMap: Record<number, number> = {};
        d.history.forEach(h => {
          const isUp = h.up !== undefined ? h.up : h.isUp;
          const time = new Date(h.timestamp).getTime();
          if (isUp && time >= cutoffTime) {
            const bucketedTime = Math.floor(time / 60000) * 60000;
            latencyMap[bucketedTime] = h.latencyMs;
          }
        });
        
        const alignedData = sortedTimestamps.map(ts => latencyMap[ts] !== undefined ? latencyMap[ts] : null);
        const myColor = modelColorMap[d.model] || colors[i % colors.length];
      
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
        legend: { position: 'bottom' as const, labels: { usePointStyle: true, boxWidth: 6 } }
      },
      scales: {
        y: { grid: { color: 'rgba(0,0,0,0.05)' }, beginAtZero: true },
        x: { grid: { display: false } }
      }
    };

    // Usage Chart
    const activeModels = data.filter(d => d.totalUses > 0);
    const fallbackData = data.length > 0 ? [{ model: 'No Traffic Yet', totalUses: 1 }] : [];
    const displayData = activeModels.length > 0 ? activeModels : fallbackData;
    
    const modelNames = displayData.map(d => d.model.split('/').pop() || d.model);
    const usageValues = displayData.map(d => d.totalUses);
    const bgColors = displayData.map((d, i) => modelColorMap[d.model] || colors[i % colors.length]);
    
    const totalRequestsVal = activeModels.length > 0 ? usageValues.reduce((a, b) => a + b, 0) : 0;

    const usageDatasets = [{
      data: usageValues,
      backgroundColor: bgColors,
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
              if (activeModels.length === 0) return ' No traffic yet';
              let label = context.label || '';
              if (label) {
                label += ': ';
              }
              if (context.parsed !== null) {
                label += new Intl.NumberFormat().format(context.parsed) + ' reqs';
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
      usageData: { labels: modelNames, datasets: usageDatasets },
      usageOptions: usageOptionsObj,
      totalRequests: totalRequestsVal,
      displayData,
      bgColors
    };
  }, [data, rangeMins]);

  const doughnutPlugins = useMemo(() => {
    return [{
      id: 'centerText',
      beforeDraw(chart: any) {
        const { ctx, chartArea } = chart;
        if (!chartArea) return;
        const { top, width, height, left } = chartArea;
        ctx.save();
        
        const centerX = left + width / 2;
        const centerY = top + height / 2 - 5;
        
        ctx.font = 'bolder 24px Inter, sans-serif';
        ctx.textBaseline = 'middle';
        ctx.textAlign = 'center';
        
        const style = getComputedStyle(document.body);
        ctx.fillStyle = style.getPropertyValue('--text-main').trim() || '#e2e8f0';
        
        ctx.fillText(formatNumber(totalRequests), centerX, centerY);
        
        ctx.font = '12px Inter, sans-serif';
        ctx.fillStyle = style.getPropertyValue('--text-muted').trim() || '#94a3b8';
        ctx.fillText('Total Requests', centerX, centerY + 20);
        
        ctx.restore();
      }
    }];
  }, [totalRequests]);

  return (
    <div className="row g-4 mb-4">
      <div className="col-12 col-xl-8">
        <div className="card border-0 shadow-sm rounded-4 p-4 h-100">
          <div className="d-flex justify-content-between align-items-center mb-4">
            <div className="text-secondary fw-semibold" style={{ fontSize: '0.85rem', letterSpacing: '0.5px', textTransform: 'uppercase' }}>
              <i className="bi bi-graph-up text-primary me-2"></i>Latency History (ms)
            </div>
            <select className="form-select form-select-sm w-auto rounded-pill" value={rangeMins} onChange={e => setRangeMins(Number(e.target.value))}>
              <option value={15}>Last 15 Mins</option>
              <option value={60}>Last 1 Hour</option>
              <option value={360}>Last 6 Hours</option>
              <option value={1440}>Last 24 Hours</option>
            </select>
          </div>
          <div className="chart-container" style={{ position: 'relative', height: '300px', width: '100%' }}>
            <Line data={latencyData} options={latencyOptions} />
          </div>
        </div>
      </div>
      
      <div className="col-12 col-xl-4">
        <div className="card border-0 shadow-sm rounded-4 p-4 h-100">
          <div className="text-secondary fw-semibold mb-4" style={{ fontSize: '0.85rem', letterSpacing: '0.5px', textTransform: 'uppercase' }}>
            <i className="bi bi-pie-chart-fill text-primary me-2"></i>Usage Distribution
          </div>
          <div className="d-flex flex-wrap flex-xl-nowrap justify-content-center align-items-center w-100" style={{ minHeight: '280px' }}>
            <div className="position-relative flex-shrink-0" style={{ width: '240px', height: '240px' }}>
              <Doughnut data={usageData} options={usageOptions} plugins={doughnutPlugins} />
            </div>
            <div className="d-flex flex-column justify-content-center ms-4" style={{ flex: '1', minWidth: '150px', maxWidth: '200px', maxHeight: '240px', overflowY: 'auto' }}>
              {displayData.map((d: any, i: number) => {
                const name = d.model.split('/').pop() || d.model;
                return (
                  <div key={d.model} className="d-flex align-items-center mb-2" style={{ fontSize: '0.8rem' }}>
                    <span className="rounded-circle me-2 flex-shrink-0 shadow-sm" style={{ width: '10px', height: '10px', backgroundColor: bgColors[i] }}></span>
                    <span className="text-muted text-truncate fw-medium" title={name}>{name}</span>
                  </div>
                );
              })}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}

