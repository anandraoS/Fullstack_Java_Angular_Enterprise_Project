import { useState } from 'react';
import { getConfig } from '../core/config';
import { TERMINAL_STATUSES, type Order } from '../models';
import { useGetOrderSnapshotQuery } from '../store/api';

export interface PollOptions {
  initialMs: number;
  maxMs: number;
  /** backoff multiplier, default 1.5 */
  factor?: number;
}

/** Pure: delay before the n-th re-poll (0-based), capped at maxMs. */
export function backoffDelay(index: number, opts: PollOptions): number {
  return Math.min(opts.maxMs, Math.round(opts.initialMs * (opts.factor ?? 1.5) ** index));
}

export function isTerminal(order: Order | null | undefined): boolean {
  return !!order && TERMINAL_STATUSES.includes(order.status);
}

interface PollState {
  id: string;
  attempt: number;
  stopped: boolean;
  /** last fulfilledTimeStamp that was accounted for */
  stamp: number | undefined;
  errored: boolean;
  terminal: boolean;
}

const fresh = (id: string): PollState => ({ id, attempt: 0, stopped: false, stamp: undefined, errored: false, terminal: false });

/**
 * Order detail data + polling until a terminal status.
 * RTK Query does the fetching/caching; `pollingInterval` is recomputed after every completed fetch with exponential
 * backoff (1.5s * 1.5^n, capped at 8s). Polling stops at a terminal status, on error, when the user presses
 * "Stop auto-refresh", and automatically on unmount (RTK Query releases the subscription and its timer).
 * State is adjusted while rendering (the documented React pattern for state derived from other state) rather than in
 * effects, so there is no extra commit and no stale interval.
 */
export function useOrderPolling(id: string, withSaga: boolean) {
  const cfg = getConfig();
  const [ps, setPs] = useState<PollState>(() => fresh(id));

  const halted = ps.id === id && (ps.terminal || ps.errored);
  const active = ps.id === id && !ps.stopped && !halted;
  const interval = active ? backoffDelay(ps.attempt, { initialMs: cfg.pollIntervalMs, maxMs: cfg.pollMaxIntervalMs }) : 0;

  const query = useGetOrderSnapshotQuery({ id, withSaga }, { skip: !id, pollingInterval: interval });

  const stamp = query.fulfilledTimeStamp;
  const errored = query.isError;
  const terminal = isTerminal(query.data?.order);
  if (ps.id !== id) {
    setPs(fresh(id));
  } else if (stamp !== ps.stamp || errored !== ps.errored || terminal !== ps.terminal) {
    setPs({
      ...ps,
      stamp,
      errored,
      terminal,
      attempt: stamp !== ps.stamp && ps.stamp !== undefined ? ps.attempt + 1 : ps.attempt,
    });
  }

  return {
    snapshot: query.data ?? null,
    error: query.error ?? null,
    loading: query.isLoading,
    polling: active,
    terminal,
    stopPolling: () => setPs((s) => ({ ...s, stopped: true })),
    /** manual refresh: resumes polling when the order is not terminal */
    refresh: () => {
      setPs((s) => ({ ...s, stopped: false, attempt: 0, errored: false }));
      void query.refetch();
    },
  };
}
