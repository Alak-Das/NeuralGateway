/**
 * Shared formatting utilities for the Neural Gateway UI
 */

export const formatNumber = (num: number | null | undefined): string => {
  if (num == null || isNaN(num)) return '0';
  if (num >= 1000000000) return (num / 1000000000).toFixed(2) + 'B';
  if (num >= 1000000) return (num / 1000000).toFixed(2) + 'M';
  if (num >= 1000) return (num / 1000).toFixed(1) + 'K';
  return num.toLocaleString();
};

export const formatTimeAgo = (dateStr: string | null): string => {
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

export const formatTimeOnly = (dateStr: string | null): string => {
  if (!dateStr) return '';
  const date = new Date(dateStr);
  return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' }).toLowerCase();
};

export const formatShort = (text: string, maxLength = 35): string => 
  text.length > maxLength ? text.substring(0, maxLength) + '...' : text;