/** Small, honest formatting for the mod browser. */

/** 1234567 as "1.2M", 45678 as "45.7K". */
export function formatCount(count: number): string {
  if (count >= 1_000_000) return `${(count / 1_000_000).toFixed(count >= 10_000_000 ? 0 : 1)}M`;
  if (count >= 1_000) return `${(count / 1_000).toFixed(count >= 100_000 ? 0 : 1)}K`;
  return String(count);
}

/**
 * An index's timestamp as "3 days ago" or "Mar 2021".
 *
 * Timestamps arrive as opaque strings; one that does not parse is shown as
 * nothing rather than as "Invalid Date".
 */
export function formatWhen(raw: string | null | undefined, now = Date.now()): string {
  if (!raw) return '';
  const when = Date.parse(raw);
  if (Number.isNaN(when)) return '';
  const days = Math.floor((now - when) / 86_400_000);
  if (days < 0) return 'today';
  if (days === 0) return 'today';
  if (days === 1) return 'yesterday';
  if (days < 30) return `${days} days ago`;
  if (days < 60) return 'last month';
  if (days < 365) return `${Math.floor(days / 30)} months ago`;
  return new Date(when).toLocaleDateString(undefined, { month: 'short', year: 'numeric' });
}
