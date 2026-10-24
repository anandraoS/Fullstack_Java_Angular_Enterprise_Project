import Button from '@mui/material/Button';
import FormControlLabel from '@mui/material/FormControlLabel';
import Switch from '@mui/material/Switch';
import { useState } from 'react';
import { ErrorBanner } from '../../components/feedback';
import { PageHeader } from '../../components/PageHeader';
import { StatusChip } from '../../components/StatusChip';
import { fmtDate } from '../../core/format';
import { SERVICES, type HealthAggregate, type MessagingStatus, type ServiceName, type TopicStatus } from '../../models';
import { useGetHealthQuery, useGetMessagingQuery } from '../../store/api';

export const REFRESH_MS = 10_000;

/** Pure: total consumer lag, or null when the service says lag is unavailable / no topic reports it. */
export function totalLag(m: Pick<MessagingStatus, 'topics' | 'lagAvailable'>): number | null {
  if (m.lagAvailable === false) return null;
  const known = m.topics.map((t: TopicStatus) => t.consumerLag).filter((l): l is number => typeof l === 'number');
  return known.length ? known.reduce((a, b) => a + b, 0) : null;
}

export function healthEntries(h: HealthAggregate): { name: string; status: string }[] {
  return Object.entries(h.services ?? {}).map(([name, v]) => ({ name, status: v.status }));
}

function ServiceCard({ service, auto }: { service: ServiceName; auto: boolean }) {
  const q = useGetMessagingQuery(service, { pollingInterval: auto ? REFRESH_MS : 0 });
  const m = q.data;
  const lag = m ? totalLag(m) : null;
  return (
    <section className="card" aria-labelledby={`svc-${service}`} data-testid={`svc-card-${service}`}>
      <h2 id={`svc-${service}`} className="h2" style={{ textTransform: 'capitalize' }}>
        {service}-service
      </h2>
      {q.isError ? (
        <ErrorBanner error={q.error} onRetry={() => void q.refetch()} />
      ) : m ? (
        <>
          <dl className="kv">
            <dt>Consumer group</dt>
            <dd className="mono">{m.consumerGroup}</dd>
            <dt>Consumer lag</dt>
            <dd data-testid="lag">{lag === null ? 'n/a (lag unavailable)' : String(lag)}</dd>
            <dt>Processed</dt>
            <dd>{m.processedEvents}</dd>
            <dt>Duplicates ignored</dt>
            <dd>{m.duplicatesIgnored}</dd>
            <dt>Retries</dt>
            <dd>{m.retries}</dd>
            <dt>Dead-lettered</dt>
            <dd className={m.deadLettered > 0 ? 'bad' : undefined} data-testid="dlt">
              {m.deadLettered}
              {m.deadLettered > 0 ? ' (needs attention)' : ''}
            </dd>
            <dt>Outbox pending</dt>
            <dd className={m.outboxPending > 10 ? 'bad' : undefined}>{m.outboxPending}</dd>
            <dt>Last processed</dt>
            <dd>{m.lastProcessedAt ? fmtDate(m.lastProcessedAt) : 'never'}</dd>
          </dl>
          {m.topics.length > 0 && (
            <table className="plain-table small" style={{ marginTop: 8 }}>
              <caption className="sr-only">Topics for {m.service}</caption>
              <thead>
                <tr>
                  <th scope="col">Topic</th>
                  <th scope="col" className="num">
                    Partitions
                  </th>
                  <th scope="col" className="num">
                    Lag
                  </th>
                </tr>
              </thead>
              <tbody>
                {m.topics.map((t) => (
                  <tr key={t.topic}>
                    <td className="mono">{t.topic}</td>
                    <td className="num">{t.partitions}</td>
                    <td className="num">{t.consumerLag ?? 'n/a'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
          {m.dltTopics.length > 0 && <p className="muted small">DLT topics: {m.dltTopics.join(', ')}</p>}
        </>
      ) : (
        <p className="muted">Loading...</p>
      )}
    </section>
  );
}

export default function OpsDashboard() {
  const [auto, setAuto] = useState(true);
  const health = useGetHealthQuery(undefined, { pollingInterval: auto ? REFRESH_MS : 0 });
  // "Refresh now" bumps a key so every card refetches immediately
  const [nonce, setNonce] = useState(0);
  const refreshAll = () => {
    void health.refetch();
    setNonce((n) => n + 1);
  };
  return (
    <>
      <PageHeader title="Operations" subtitle="Messaging health per service: consumer lag, retries, dead letters and outbox backlog.">
        <FormControlLabel control={<Switch checked={auto} onChange={(e) => setAuto(e.target.checked)} />} label="Auto-refresh (10s)" />
        <Button variant="outlined" onClick={refreshAll}>
          Refresh now
        </Button>
      </PageHeader>

      <section className="card" aria-labelledby="health-h">
        <h2 id="health-h" className="h2">
          Platform health
        </h2>
        {health.isError ? (
          <ErrorBanner error={health.error} onRetry={refreshAll} />
        ) : health.data ? (
          <div className="row" role="status" aria-live="polite">
            <StatusChip status={health.data.status} testId="health-overall" />
            {healthEntries(health.data).map((e) => (
              <span key={e.name} style={{ display: 'inline-flex', gap: 6, alignItems: 'center' }}>
                <span className="mono">{e.name}</span> <StatusChip status={e.status} />
              </span>
            ))}
          </div>
        ) : (
          <p className="muted">Loading...</p>
        )}
      </section>

      <div className="grid cards section" aria-label="Messaging status by service">
        {SERVICES.map((s) => (
          <ServiceCard key={`${s}-${nonce}`} service={s} auto={auto} />
        ))}
      </div>
    </>
  );
}
