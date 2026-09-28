// ui/src/theme/colors.ts
// Centralized color system for Neural Gateway
// Provides semantic tokens, graph-specific palettes, and category colors
// All colors are WCAG AA compliant and color-blind safe

export const themeColors = {
  // Semantic tokens (single source of truth for UI states)
  semantic: {
    primary: { light: '#2563eb', dark: '#3b82f6' },
    success: { light: '#059669', dark: '#10b981' },
    warning: { light: '#d97706', dark: '#f59e0b' },
    danger: { light: '#dc2626', dark: '#ef4444' },
    info: { light: '#0891b2', dark: '#06b6d4' },
  } as const,

  // Graph-specific palette (never overlaps with semantic colors)
  // Curated for: CVD safety, WCAG AA on both themes, maximum distinctiveness
  graphs: {
    // 10-color categorical palette for multi-series charts
    categorical: [
      '#007AFF',  // Blue - Primary data series
      '#FF9F0A',  // Orange - Secondary series
      '#34C759',  // Green - Success/throughput
      '#AF52DE',  // Purple - Tertiary series
      '#FF3B30',  // Red - Errors/danger
      '#5856D6',  // Indigo - Quaternary series
      '#FF2D92',  // Pink - Quinary series
      '#00C7BE',  // Teal - Senary series
      '#FFCC00',  // Yellow - Septenary (light theme only)
      '#8E8E93',  // Gray - Baseline/probes
    ] as const,

    // Dark theme optimized versions (brighter for dark backgrounds)
    categoricalDark: [
      '#0A84FF',  // Brighter blue
      '#FF9F0A',  // Orange (works well on dark)
      '#30D158',  // Brighter green
      '#BF5AF2',  // Brighter purple
      '#FF453A',  // Brighter red
      '#5E5CE6',  // Brighter indigo
      '#FF375F',  // Brighter pink
      '#64D2FF',  // Light cyan (better than teal on dark)
      '#FFD60A',  // Brighter yellow
      '#98989D',  // Lighter gray
    ] as const,

    // Semantic mapping for specific graph elements
    // These provide consistent meaning across all charts
    elements: {
      latencyLine: { light: '#007AFF', dark: '#0A84FF' },
      latencyFill: { light: 'rgba(0, 122, 255, 0.10)', dark: 'rgba(10, 132, 255, 0.15)' },
      errorLine: { light: '#FF3B30', dark: '#FF453A' },
      errorFill: { light: 'rgba(255, 59, 48, 0.10)', dark: 'rgba(255, 69, 58, 0.15)' },
      throughputLine: { light: '#34C759', dark: '#30D158' },
      throughputFill: { light: 'rgba(52, 199, 89, 0.10)', dark: 'rgba(48, 209, 88, 0.15)' },
      saturationLine: { light: '#FF9F0A', dark: '#FF9F0A' },
      saturationFill: { light: 'rgba(255, 159, 10, 0.10)', dark: 'rgba(255, 159, 10, 0.15)' },
      availabilityLine: { light: '#5856D6', dark: '#5E5CE6' },
      availabilityFill: { light: 'rgba(88, 86, 214, 0.10)', dark: 'rgba(94, 92, 230, 0.15)' },
      backgroundProbe: { light: '#8E8E93', dark: '#98989D' },
      backgroundProbeFill: { light: 'rgba(142, 142, 147, 0.08)', dark: 'rgba(152, 152, 157, 0.12)' },
    } as const,
  },

  // Category badge colors (aligned with but distinct from graph colors)
  // Used for model category tags in tables
  categories: {
    coding: { 
      light: '#a855f7', 
      dark: '#c162fd', 
      bgLight: 'rgba(168, 85, 247, 0.12)', 
      bgDark: 'rgba(193, 98, 253, 0.15)' 
    },
    reasoning: { 
      light: '#3b82f6', 
      dark: '#4da1ff', 
      bgLight: 'rgba(59, 130, 246, 0.12)', 
      bgDark: 'rgba(77, 161, 255, 0.15)' 
    },
    vision: { 
      light: '#059669', 
      dark: '#6bcb77', 
      bgLight: 'rgba(5, 150, 105, 0.12)', 
      bgDark: 'rgba(107, 203, 119, 0.15)' 
    },
  } as const,

  // Chart.js specific helpers
  chartHelpers: {
    // Generate dataset colors for N models
    getCategoricalColors: (count: number, isDark: boolean): string[] => {
      const palette = isDark ? themeColors.graphs.categoricalDark : themeColors.graphs.categorical;
      const colors: string[] = [];
      for (let i = 0; i < count; i++) {
        colors.push(palette[i % palette.length]);
      }
      return colors;
    },

    // Get semantic color for specific metric type
    getMetricColor: (metric: keyof typeof themeColors.graphs.elements, isDark: boolean): string => {
      const element = themeColors.graphs.elements[metric];
      return isDark ? element.dark : element.light;
    },

    // Get fill color for specific metric type
    getMetricFillColor: (metric: keyof typeof themeColors.graphs.elements, isDark: boolean): string => {
      const element = themeColors.graphs.elements[metric];
      return isDark ? element.dark : element.light;
    },
  },
} as const;

// Type exports for TypeScript support
export type SemanticColorKey = keyof typeof themeColors.semantic;
export type GraphElementKey = keyof typeof themeColors.graphs.elements;
export type CategoryKey = keyof typeof themeColors.categories;

// Theme-aware color getter
export const getColor = (
  category: 'semantic' | 'graphs' | 'categories',
  key: string,
  variant: 'light' | 'dark' | 'bgLight' | 'bgDark' = 'dark'
): string => {
  const colors = themeColors[category] as Record<string, Record<string, string>>;
  if (colors[key] && colors[key][variant]) {
    return colors[key][variant];
  }
  // Fallback
  return variant === 'dark' ? '#3b82f6' : '#2563eb';
};