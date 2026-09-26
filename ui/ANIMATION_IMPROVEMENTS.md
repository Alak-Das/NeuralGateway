# UI Animation & Micro-Interaction Improvement Plan

## Overview
This document outlines recommendations for enhancing the Neural Gateway UI with animations and micro-interactions to improve user experience, visual appeal, and feedback mechanisms.

## Current Technology Stack
- **Framework**: React 19.3.0 with TypeScript
- **Styling**: Bootstrap 5 + Custom CSS Variables
- **Charts**: Chart.js 4.5.1 + react-chartjs-2 5.3.1
- **Build Tool**: Vite 8.3.1
- **Real-time**: Server-Sent Events (EventSource)

## 1. Core Animation Infrastructure

### A. Add Framer Motion
Install the industry-standard animation library for React:
```bash
cd ui && npm install framer-motion@latest
```

**Benefits**:
- Handles layout animations, gestures, and complex sequences
- Works seamlessly with React 19 concurrent features
- Lightweight (~50KB gzipped) with tree-shaking capabilities
- Excellent documentation and community support

### B. Create Animation Utilities
Create `src/animations/` directory with reusable variants:

```typescript
// src/animations/variants.ts
export const fadeIn = { 
  hidden: { opacity: 0 }, 
  visible: { opacity: 1, transition: { duration: 0.3 } } 
};

export const slideUp = { 
  hidden: { opacity: 0, y: 20 }, 
  visible: { opacity: 1, y: 0, transition: { duration: 0.4, ease: [0.25, 0.46, 0.45, 0.94] } } 
};

export const staggerContainer = { 
  hidden: { opacity: 0 }, 
  visible: { opacity: 1, transition: { staggerChildren: 0.08 } } 
};

export const scaleIn = { 
  hidden: { opacity: 0, scale: 0.95 }, 
  visible: { opacity: 1, scale: 1, transition: { duration: 0.2 } } 
};
```

## 2. Component-Level Animations

### A. KPI Grid Cards
**File**: `ui/src/components/KpiGrid.tsx`

```tsx
import { motion } from 'framer-motion';
import { staggerContainer, slideUp } from '../animations/variants';

// Wrap the row with staggered entrance
<motion.div 
  className="row g-4 mb-4" 
  variants={staggerContainer} 
  initial="hidden" 
  animate="visible"
>
  {cards.map((card, i) => (
    <motion.div key={i} className="col-..." variants={slideUp}>
      <motion.div 
        className="card..." 
        whileHover={{ 
          y: -4, 
          boxShadow: '0 12px 40px rgba(0,0,0,0.15)' 
        }}
        transition={{ duration: 0.2 }}
        whileTap={{ scale: 0.98 }}
      >
        {/* Card content */}
      </motion.div>
    </motion.div>
  ))}
</motion.div>
```

### B. Status Table Rows
**File**: `ui/src/components/StatusTable.tsx`

```tsx
import { motion } from 'framer-motion';

<motion.tbody>
  {filteredData.map((d, i) => (
    <motion.tr 
      key={d.model}
      initial={{ opacity: 0, x: -20 }}
      animate={{ opacity: 1, x: 0 }}
      transition={{ delay: i * 0.03 }}
      layout  // Animates position changes on sort/filter
    >
      {/* Table row content */}
    </motion.tr>
  ))}
</motion.tbody>
```

### C. Charts Container Transitions
**File**: `ui/src/components/Charts.tsx`

```tsx
// Add motion wrappers around charts
<motion.div 
  initial={{ opacity: 0, scale: 0.95 }}
  animate={{ opacity: 1, scale: 1 }}
  transition={{ duration: 0.4, ease: 'easeOut' }}
>
  <div className="chart-container" style={{ position: 'relative', height: '300px', width: '100%' }}>
    <Line data={latencyData} options={latencyOptions} />
  </div>
</motion.div>
```

### D. Nav Tab Switching
**File**: `ui/src/App.tsx`

```tsx
import { AnimatePresence, motion } from 'framer-motion';

<AnimatePresence mode="wait">
  <motion.div
    key={activeTab}
    initial={{ opacity: 0, y: 10 }}
    animate={{ opacity: 1, y: 0 }}
    exit={{ opacity: 0, y: -10 }}
    transition={{ duration: 0.2 }}
  >
    {activeTab === 'models' ? <StatusTable data={data} /> : <RequestersTable requesters={requesters} />}
  </motion.div>
</AnimatePresence>
```

## 3. Micro-Interactions

### A. Interactive Elements
Apply to buttons, headers, and clickable elements:
```tsx
<motion.button
  whileTap={{ scale: 0.95 }}
  whileHover={{ scale: 1.02 }}
  transition={{ duration: 0.1 }}
>
  {/* Button content */}
</motion.button>
```

### B. Connection Status Badge Pulse
**File**: `ui/src/App.tsx`

```tsx
<motion.span
  animate={{ 
    opacity: connectionStatus === 'LIVE' ? [1, 0.5, 1] : [1, 0.7, 1] 
  }}
  transition={{ 
    duration: 2, 
    repeat: Infinity, 
    ease: 'easeInOut' 
  }}
>
  {connectionStatus === 'LIVE' && (
    <i className="bi bi-circle-fill me-1" style={{ fontSize: '0.5rem' }}></i>
  )}
  {connectionStatus}
</motion.span>
```

### C. Data Update Highlight
**File**: `ui/src/components/StatusTable.tsx`

```tsx
const [lastValues, setLastValues] = useRef({});

// In table cell rendering
<td 
  className={ 
    lastValues.current[d.model]?.tps !== d.tps 
      ? 'bg-warning bg-opacity-25 animate-flash' 
      : '' 
  }
  onAnimationEnd={() => {
    // Remove flash class after animation
    setTimeout(() => {
      // Implementation depends on how you manage the class
    }, 500);
  }}
>
  {d.tps.toFixed(2)}
</td>
```

### D. Loading Skeletons
Show during SSE reconnection:
```tsx
{data.length === 0 && reconnectAttemptsRef.current > 0 ? (
  <motion.div 
    className="col-12" 
    animate={{ opacity: [0.5, 1, 0.5] }} 
    transition={{ repeat: Infinity, duration: 1.5 }}
  >
    <SkeletonCard />
  </motion.div>
) : (
  // Normal KPI cards
)}
```

## 4. Theme Transition Animation

**File**: `ui/src/App.tsx`

Wrap the entire application for smooth theme transitions:
```tsx
<motion.div
  style={{ 
    backgroundColor: theme === 'dark' ? 'var(--bg-dark)' : 'var(--bg-light)',
    color: theme === 'dark' ? 'var(--text-light)' : 'var(--text-dark)'
  }}
  animate={{ 
    backgroundColor: theme === 'dark' ? 'var(--bg-dark)' : 'var(--bg-light)',
    color: theme === 'dark' ? 'var(--text-light)' : 'var(--text-dark)'
  }}
  transition={{ duration: 0.3, ease: 'easeInOut' }}
>
  <AnimatePresence mode="wait">
    <motion.div 
      key={theme}
      initial={{ opacity: 0, filter: 'blur(4px)' }}
      animate={{ opacity: 1, filter: 'blur(0)' }}
      exit={{ opacity: 0, filter: 'blur(4px)' }}
      transition={{ duration: 0.2 }}
    >
      {/* App content */}
    </motion.div>
  </AnimatePresence>
</motion.div>
```

## 5. CSS Keyframes for Special Effects

Add to `ui/src/index.css` or a dedicated animations CSS file:

```css
/* Pulse animation for active elements */
@keyframes pulse-ring {
  0% { box-shadow: 0 0 0 0 var(--brand-primary); }
  70% { box-shadow: 0 0 0 10px transparent; }
  100% { box-shadow: 0 0 0 0 transparent; }
}

@keyframes slide-in-right {
  from { transform: translateX(100%); opacity: 0; }
  to { transform: translateX(0); opacity: 1; }
}

@keyframes count-up {
  from { opacity: 0; transform: translateY(10px); }
  to { opacity: 1; transform: translateY(0); }
}

@keyframes flash {
  0% { background-color: transparent; }
  50% { background-color: rgba(255, 193, 7, 0.2); }
  100% { background-color: transparent; }
}

.animate-pulse-ring { animation: pulse-ring 2s ease-out infinite; }
.animate-slide-in-right { animation: slide-in-right 0.4s ease-out; }
.animate-count-up { animation: count-up 0.6s ease-out; }
.animate-flash { animation: flash 0.8s ease-out; }

/* Smooth transitions for all interactive elements */
* {
  transition: 
    background-color 0.3s ease, 
    border-color 0.3s ease, 
    color 0.3s ease, 
    box-shadow 0.3s ease,
    transform 0.2s ease;
}

/* Respect user's motion preferences */
@media (prefers-reduced-motion: reduce) {
  *, *::before, *::after {
    animation-duration: 0.01ms !important;
    animation-iteration-count: 1 !important;
    transition-duration: 0.01ms !important;
  }
}
```

## 6. Chart-Specific Enhancements

### A. Enhanced Doughnut Chart with Center Animation
**File**: `ui/src/components/Charts.tsx`

```tsx
const doughnutPlugins = useMemo(() => [{
  id: 'centerText',
  afterDraw(chart) {
    const { ctx, chartArea: { left, right, top, bottom, width, height } } = chart;
    ctx.save();
    
    // Animate number count-up based on chart animation progress
    const meta = chart.getDatasetMeta(0);
    const progress = meta.data[0]?._animation?.currentStep / 
                     meta.data[0]?._animation?.numSteps || 1;
    const displayValue = Math.floor(totalRequests * progress);
    
    // Calculate center position
    const centerX = (left + right) / 2;
    const centerY = (top + bottom) / 2 - 5; // Adjust for text baseline
    
    // Draw animated value
    ctx.font = 'bolder 24px Inter, sans-serif';
    ctx.textBaseline = 'middle';
    ctx.textAlign = 'center';
    
    const style = getComputedStyle(document.body);
    ctx.fillStyle = style.getPropertyValue('--text-main').trim() || '#e2e8f0';
    ctx.fillText(formatNumber(displayValue), centerX, centerY);
    
    // Draw label
    ctx.font = '12px Inter, sans-serif';
    ctx.fillStyle = style.getPropertyValue('--text-muted').trim() || '#94a3b8';
    ctx.fillText('Total Requests', centerX, centerY + 20);
    
    ctx.restore();
  }
}], [totalRequests]);
```

### B. Staggered Line Chart Appearance
**File**: `ui/src/components/Charts.tsx`

```tsx
const latencyData = useMemo(() => ({
  datasets: latencyDatasets.map((ds, i) => ({
    ...ds,
    animation: {
      delay: (index) => index * 10 + i * 100, // Stagger per dataset and point
      duration: 800,
      easing: 'easeOutQuart'
    }
  }))
}), [latencyDatasets]);
```

## 7. Performance Considerations

| Concern | Solution |
|---------|----------|
| **Layout thrashing** | Use `layout` prop sparingly; prefer `transform` and `opacity` animations |
| **Re-renders** | Wrap animated components in `React.memo` where appropriate |
| **Bundle size** | Framer Motion ~50KB; monitor bundle impact |
| **Reduced motion** | Always respect `prefers-reduced-motion` media query |
| **Chart performance** | Limit animation duration and complexity for large datasets |
| **Memory leaks** | Properly clean up motion components and event listeners |

## 8. Implementation Order (Recommended)

### **Phase 1: Foundation (Week 1)**
- [ ] Install Framer Motion
- [ ] Create animation utilities (`src/animations/variants.ts`)
- [ ] Add KPI grid entrance animations
- [ ] Implement basic hover/tap interactions

### **Phase 2: Core Interactions (Week 2)**
- [ ] Add table row layout animations (sort/filter)
- [ ] Implement tab switching transitions
- [ ] Add theme transition animations
- [ ] Create connection status pulse animation

### **Phase 3: Enhanced Feedback (Week 3)**
- [ ] Add data update flash highlights
- [ ] Implement chart entrance/stagger animations
- [ ] Add loading skeletons during reconnection
- [ ] Enhance button micro-interactions

### **Phase 4: Polish & Optimization (Week 4)**
- [ ] Add CSS keyframes for special effects
- [ ] Implement reduced motion preferences
- [ ] Performance testing and optimization
- [ ] Accessibility verification (ARIA, keyboard navigation)

## 9. Expected Benefits

1. **Improved User Engagement**: Smooth transitions create a more polished, professional feel
2. **Better Feedback**: Micro-interactions confirm user actions and system status
3. **Enhanced Data Comprehension**: Animated charts help users perceive trends and changes
4. **Reduced Cognitive Load**: Visual cues guide attention to important information
5. **Modern Appearance**: Aligns with contemporary web application standards

## 10. Accessibility Considerations

- All animations respect `prefers-reduced-motion` media query
- Ensure sufficient color contrast in animated states
- Provide alternative ways to access information (don't rely solely on animation)
- Maintain keyboard navigability throughout animated transitions
- Test with screen readers to ensure animated content is properly announced

---

*This plan leverages the existing React 19 + TypeScript + Vite stack while maintaining compatibility with the Spring Boot backend. All recommendations are designed to be incremental and can be implemented in phases based on priority and resources.*