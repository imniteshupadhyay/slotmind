export function fmtTime(iso) {
  if (!iso) return '—';
  return new Date(iso).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });
}

export function fmtDay(iso) {
  if (!iso) return '';
  const d = new Date(iso);
  const today = new Date();
  const tomorrow = new Date();
  tomorrow.setDate(today.getDate() + 1);
  if (d.toDateString() === today.toDateString()) return 'Today';
  if (d.toDateString() === tomorrow.toDateString()) return 'Tomorrow';
  return d.toLocaleDateString([], { weekday: 'short', month: 'short', day: 'numeric' });
}

export const fmtWhen = (iso) => (iso ? `${fmtDay(iso)} · ${fmtTime(iso)}` : '—');

export function fmtAgo(iso) {
  const s = Math.round((Date.now() - new Date(iso).getTime()) / 1000);
  if (s < 60) return `${Math.max(s, 0)}s ago`;
  if (s < 3600) return `${Math.round(s / 60)}m ago`;
  return fmtTime(iso);
}

export const TRIGGER_LABEL = {
  PROVIDER_OFFLINE: 'Provider offline',
  SLOT_CANCELLED: 'Slot cancelled',
  INITIAL: 'Manual',
};

export const TYPE_LABEL = { ROUTINE: 'Routine', URGENT: 'Urgent', FOLLOW_UP: 'Follow-up' };
