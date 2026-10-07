// ui/src/theme/colors.ts
// Centralized color system for Neural Gateway
// Provides semantic tokens, graph-specific palettes, and category colors
// All colors are WCAG AA compliant and color-blind safe

// 24 highly distinct, high-contrast categorical colors
// Alternating hues and tones across color space to maximize perceptual distinction
const categoricalLight = [
  '#0284C7', // 0: Sky Blue
  '#EA580C', // 1: Vivid Orange
  '#059669', // 2: Emerald Green
  '#7C3AED', // 3: Purple / Violet
  '#DC2626', // 4: Crimson Red
  '#D97706', // 5: Amber Gold
  '#DB2777', // 6: Hot Pink
  '#0891B2', // 7: Cyan / Aqua
  '#65A30D', // 8: Lime Green
  '#4F46E5', // 9: Indigo Blue
  '#E11D48', // 10: Rose Red
  '#0D9488', // 11: Teal
  '#C026D3', // 12: Fuchsia / Magenta
  '#2563EB', // 13: Cobalt Blue
  '#CA8A04', // 14: Bright Lemon
  '#16A34A', // 15: Bright Green
  '#9333EA', // 16: Soft Lavender
  '#C2410C', // 17: Warm Tangerine
  '#0F766E', // 18: Mint Aqua
  '#BE123C', // 19: Deep Rose
  '#4D7C0F', // 20: Bright Chartreuse
  '#4338CA', // 21: Periwinkle
  '#9D174D', // 22: Soft Carnation
  '#475569', // 23: Cool Slate
] as const;

const categoricalDark = [
  '#38BDF8', // 0: Sky Blue
  '#F97316', // 1: Vivid Orange
  '#10B981', // 2: Emerald Green
  '#A855F7', // 3: Purple / Violet
  '#EF4444', // 4: Crimson Red
  '#FBBF24', // 5: Amber Gold
  '#EC4899', // 6: Hot Pink
  '#06B6D4', // 7: Cyan / Aqua
  '#84CC16', // 8: Lime Green
  '#6366F1', // 9: Indigo Blue
  '#F43F5E', // 10: Rose Red
  '#14B8A6', // 11: Teal
  '#D946EF', // 12: Fuchsia / Magenta
  '#3B82F6', // 13: Cobalt Blue
  '#EAB308', // 14: Bright Lemon
  '#22C55E', // 15: Bright Green
  '#C084FC', // 16: Soft Lavender
  '#FB923C', // 17: Warm Tangerine
  '#2DD4BF', // 18: Mint Aqua
  '#FDA4AF', // 19: Light Peach Rose
  '#A3E635', // 20: Bright Chartreuse
  '#818CF8', // 21: Periwinkle
  '#F472B6', // 22: Soft Carnation
  '#94A3B8', // 23: Cool Slate
] as const;

const graphElements = {
  latencyLine: { light: '#007AFF', dark: '#0A84FF' },
  latencyFill: { light: 'rgba(0, 122, 255, 0.10)', dark: 'rgba(10, 132, 255, 0.15)' },
  errorLine: { light: '#FF3B30', dark: '#FF453A' },
  errorFill: { light: 'rgba(255, 59, 48, 0.10)', dark: 'rgba(255, 69, 58, 0.15)' },
  throughputLine: { light: '#34C759', dark: '#30D158' },
  throughputFill: { light: 'rgba(52, 199, 89, 0.10)', dark: 'rgba(48, 209, 88, 0.15)' },
  saturationLine: { light: '#FF9F0A', dark: '#FFB84D' },
  saturationFill: { light: 'rgba(255, 159, 10, 0.10)', dark: 'rgba(255, 184, 77, 0.15)' },
  availabilityLine: { light: '#5856D6', dark: '#5E5CE6' },
  availabilityFill: { light: 'rgba(88, 86, 214, 0.10)', dark: 'rgba(94, 92, 230, 0.15)' },
  backgroundProbe: { light: '#8E8E93', dark: '#98989D' },
  backgroundProbeFill: { light: 'rgba(142, 142, 147, 0.08)', dark: 'rgba(152, 152, 157, 0.12)' },
} as const;

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
    categorical: categoricalLight,

    // Dark theme optimized versions (brighter for dark backgrounds)
    categoricalDark: categoricalDark,

    // Semantic mapping for specific graph elements
    // These provide consistent meaning across all charts
    elements: graphElements,
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
      const palette = isDark ? categoricalDark : categoricalLight;
      const colors: string[] = [];
      for (let i = 0; i < count; i++) {
        colors.push(palette[i % palette.length]);
      }
      return colors;
    },

    // Get semantic line color for specific metric type (e.g., 'latencyLine', 'errorLine')
    getMetricColor: (metric: keyof typeof graphElements, isDark: boolean): string => {
      const element = graphElements[metric];
      return isDark ? element.dark : element.light;
    },

    // Get semantic fill color for specific metric type (e.g., 'latencyFill', 'errorFill')
    // Accepts fill metric keys directly (e.g., 'latencyFill', 'errorFill', 'throughputFill', etc.)
    getMetricFillColor: (metric: keyof typeof graphElements, isDark: boolean): string => {
      const element = graphElements[metric];
      return isDark ? element.dark : element.light;
    },

    // Get fill color by base metric name (e.g., 'latencyLine' -> returns latencyFill color)
    getFillColorForMetric: (baseMetric: 'latencyLine' | 'errorLine' | 'throughputLine' | 'saturationLine' | 'availabilityLine' | 'backgroundProbe', isDark: boolean): string => {
      const fillKey = baseMetric.replace('Line', 'Fill') as keyof typeof graphElements;
      const element = graphElements[fillKey];
      return isDark ? element.dark : element.light;
    },
  },
  // Neutral tones for UI elements like borders, text, surfaces
  neutral: {
    border: '#334155',   // slate-700
    muted:   '#9ca3af',  // slate-400
    light:   '#e5e7eb',  // slate-100
    surfaceLight: '#ffffff',
    surfaceDark:  '#1e1e1e'
  }
} as const;

// Type exports for TypeScript support
export type SemanticColorKey = keyof typeof themeColors.semantic;
export type GraphElementKey = keyof typeof themeColors.graphs.elements;
export type CategoryKey = keyof typeof themeColors.categories;
export type NeutralKey = keyof typeof themeColors.neutral;

// Theme-aware color getter
export const getColor = (
  category: 'semantic' | 'graphs' | 'categories' | 'neutral',
  key: string,
  variant: 'light' | 'dark' | 'bgLight' | 'bgDark' = 'dark'
): string => {
  // Handle neutral category separately as it doesn't have light/dark variants in the same way
  if (category === 'neutral') {
    const neutral = themeColors.neutral;
    // For neutral, we return the key directly if it's surfaceLight/surfaceDark, otherwise we pick based on theme
    if (key === 'surfaceLight') return neutral.surfaceLight;
    if (key === 'surfaceDark') return neutral.surfaceDark;
    // For border, muted, light, we return the same value regardless of theme (they are neutral)
    return neutral[key as keyof typeof neutral];
  }

  const colors = themeColors[category] as Record<string, Record<string, string>>;
  if (colors[key] && colors[key][variant]) {
    return colors[key][variant];
  }
  // Fallback
  return variant === 'dark' ? '#3b82f6' : '#2563eb';
};

/**
 * Simple string hash for deterministic fallback color selection.
 */
export function hashString(str: string): number {
  let hash = 0;
  for (let i = 0; i < str.length; i++) {
    hash = ((hash << 5) - hash) + str.charCodeAt(i);
    hash |= 0;
  }
  return Math.abs(hash);
}

/**
 * Creates a deterministic, alphabetically sorted model-to-color mapping.
 * Ensures every model gets a unique, consistent color across all dashboard cards.
 */
export function buildModelColorMap(modelIds: string[], isDark: boolean): Record<string, string> {
  const palette = isDark ? categoricalDark : categoricalLight;
  const uniqueIds = Array.from(new Set(modelIds.filter(Boolean))).sort((a, b) => a.localeCompare(b));
  const colorMap: Record<string, string> = {};

  uniqueIds.forEach((id, index) => {
    const color = palette[index % palette.length];
    colorMap[id] = color;
    // Also map short name so lookups by simple name or full path match identically
    const shortName = id.split('/').pop() || id;
    if (!colorMap[shortName]) {
      colorMap[shortName] = color;
    }
  });

  return colorMap;
}

/**
 * Resolves a model color deterministically using the color map or a hash into the 24-color palette.
 */
export function getModelColor(
  modelId: string,
  isDark: boolean,
  colorMap?: Record<string, string>
): string {
  if (!modelId || modelId === 'No Traffic in Period') {
    return isDark ? '#64748b' : '#94a3b8';
  }
  if (colorMap && colorMap[modelId]) {
    return colorMap[modelId];
  }
  const shortName = modelId.split('/').pop() || modelId;
  if (colorMap && colorMap[shortName]) {
    return colorMap[shortName];
  }
  const palette = isDark ? categoricalDark : categoricalLight;
  return palette[hashString(modelId) % palette.length];
}

