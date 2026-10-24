import type { OrderHistoryItem, OrderStatus } from '../models';

export type StepState = 'done' | 'current' | 'upcoming' | 'skipped' | 'failed' | 'cancelled' | 'warning';

export interface TimelineStep {
  status: OrderStatus;
  state: StepState;
  branch: boolean;
  at: string | null;
}

export const HAPPY_PATH: readonly OrderStatus[] = ['CREATED', 'VALIDATED', 'INVENTORY_RESERVED', 'CONFIRMED', 'FULFILLED'];
const BRANCHES: readonly OrderStatus[] = ['REJECTED', 'COMPENSATION_PENDING', 'CANCELLED', 'FAILED'];

function branchState(s: OrderStatus, current: OrderStatus): StepState {
  if (s !== current) return 'done';
  if (s === 'REJECTED' || s === 'FAILED') return 'failed';
  if (s === 'CANCELLED') return 'cancelled';
  return 'warning'; // COMPENSATION_PENDING still in progress
}

/**
 * Pure: builds the display model for an order's progress (same algorithm as the Angular buildTimeline).
 * Happy path steps are always shown (done / current / upcoming). When the order took a terminal branch
 * (REJECTED, CANCELLED, FAILED, via COMPENSATION_PENDING), unvisited happy steps become "skipped" and the visited
 * branch statuses are appended in order. Without history the visited set is inferred from the current status.
 */
export function buildTimeline(current: OrderStatus, history: readonly OrderHistoryItem[] = []): TimelineStep[] {
  const at = new Map<OrderStatus, string>();
  for (const h of history) if (!at.has(h.toStatus)) at.set(h.toStatus, h.occurredAt);

  const visited = new Set<OrderStatus>(at.keys());
  visited.add(current);
  const currentHappyIdx = HAPPY_PATH.indexOf(current);
  if (history.length === 0 && currentHappyIdx >= 0) {
    HAPPY_PATH.slice(0, currentHappyIdx + 1).forEach((s) => visited.add(s));
  }
  const onBranch = BRANCHES.includes(current);

  const steps: TimelineStep[] = HAPPY_PATH.map((s) => {
    let state: StepState;
    if (s === current) state = s === 'FULFILLED' ? 'done' : 'current';
    else if (visited.has(s)) state = 'done';
    else state = onBranch ? 'skipped' : 'upcoming';
    return { status: s, state, branch: false, at: at.get(s) ?? null };
  });

  if (onBranch) {
    const order = history.length ? history.map((h) => h.toStatus) : [current];
    const seen = new Set<OrderStatus>();
    for (const s of order) {
      if (BRANCHES.includes(s) && !seen.has(s)) {
        seen.add(s);
        steps.push({ status: s, state: branchState(s, current), branch: true, at: at.get(s) ?? null });
      }
    }
    if (!seen.has(current)) {
      steps.push({ status: current, state: branchState(current, current), branch: true, at: at.get(current) ?? null });
    }
  }
  return steps;
}
