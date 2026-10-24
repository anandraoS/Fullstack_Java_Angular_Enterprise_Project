import { DatePipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { Subject, catchError, filter, forkJoin, map, merge, of, switchMap, timer } from 'rxjs';
import { OpsApi } from '../../core/api/apis';
import { ApiError, normalizeError } from '../../core/http/api-error';
import { HealthAggregate, MessagingStatus, SERVICES, ServiceName, TopicStatus } from '../../core/models';
import { ErrorBanner } from '../../shared/components/feedback';
import { PageHeader } from '../../shared/components/page-header';
import { StatusChip } from '../../shared/components/status-chip';

export interface ServiceCard {
  service: ServiceName;
  data: MessagingStatus | null;
  error: ApiError | null;
}

/** Pure: total consumer lag, or null when the service says lag is unavailable / no topic reports it. */
export function totalLag(m: Pick<MessagingStatus, 'topics' | 'lagAvailable'>): number | null {
  if (m.lagAvailable === false) return null;
  const known = m.topics.map((t: TopicStatus) => t.consumerLag).filter((l): l is number => typeof l === 'number');
  return known.length ? known.reduce((a, b) => a + b, 0) : null;
}

export const REFRESH_MS = 10_000;

@Component({
  selector: 'app-ops-dashboard',
  imports: [PageHeader, StatusChip, ErrorBanner, MatButtonModule, MatSlideToggleModule, DatePipe],
  template: `
    <app-page-header title="Operations" subtitle="Messaging health per service: consumer lag, retries, dead letters and outbox backlog.">
      <mat-slide-toggle [checked]="auto()" (change)="auto.set($event.checked)">Auto-refresh (10s)</mat-slide-toggle>
      <button mat-stroked-button type="button" (click)="manual$.next()">Refresh now</button>
    </app-page-header>

    <section class="card" aria-labelledby="health-h">
      <h2 id="health-h" class="h2">Platform health</h2>
      @if (healthError()) {
        <app-error-banner [error]="healthError()" (retry)="manual$.next()" />
      } @else if (health(); as h) {
        <div class="row" role="status" aria-live="polite">
          <app-status-chip [status]="h.status" data-testid="health-overall" />
          @for (entry of healthEntries(h); track entry.name) {
            <span class="svc-health"><span class="mono">{{ entry.name }}</span> <app-status-chip [status]="entry.status" /></span>
          }
        </div>
      } @else {
        <p class="muted">Loading...</p>
      }
    </section>

    <div class="grid cards section" aria-label="Messaging status by service">
      @for (c of cards(); track c.service) {
        <section class="card" [attr.aria-labelledby]="'svc-' + c.service" [attr.data-testid]="'svc-card-' + c.service">
          <h2 [id]="'svc-' + c.service" class="h2">{{ c.service }}-service</h2>
          @if (c.error) {
            <app-error-banner [error]="c.error" (retry)="manual$.next()" />
          } @else if (c.data; as m) {
            <dl class="kv">
              <dt>Consumer group</dt><dd class="mono">{{ m.consumerGroup }}</dd>
              <dt>Consumer lag</dt><dd data-testid="lag">{{ lag(m) }}</dd>
              <dt>Processed</dt><dd>{{ m.processedEvents }}</dd>
              <dt>Duplicates ignored</dt><dd>{{ m.duplicatesIgnored }}</dd>
              <dt>Retries</dt><dd>{{ m.retries }}</dd>
              <dt>Dead-lettered</dt><dd [class.bad]="m.deadLettered > 0" data-testid="dlt">{{ m.deadLettered }}{{ m.deadLettered > 0 ? ' (needs attention)' : '' }}</dd>
              <dt>Outbox pending</dt><dd [class.bad]="m.outboxPending > 10">{{ m.outboxPending }}</dd>
              <dt>Last processed</dt><dd>{{ m.lastProcessedAt ? (m.lastProcessedAt | date: 'MMM d, HH:mm:ss') : 'never' }}</dd>
            </dl>
            @if (m.topics.length) {
              <table class="topics">
                <caption class="sr-only">Topics for {{ m.service }}</caption>
                <thead><tr><th scope="col">Topic</th><th scope="col" class="num">Partitions</th><th scope="col" class="num">Lag</th></tr></thead>
                <tbody>
                  @for (t of m.topics; track t.topic) {
                    <tr><td class="mono">{{ t.topic }}</td><td class="num">{{ t.partitions }}</td><td class="num">{{ t.consumerLag ?? 'n/a' }}</td></tr>
                  }
                </tbody>
              </table>
            }
            @if (m.dltTopics.length) {
              <p class="muted small">DLT topics: {{ m.dltTopics.join(', ') }}</p>
            }
          } @else {
            <p class="muted">Loading...</p>
          }
        </section>
      }
    </div>
  `,
  styles: `
    .h2 { font-size: 1.1rem; margin: 0 0 8px; text-transform: capitalize; }
    #health-h { text-transform: none; }
    .svc-health { display: inline-flex; gap: 6px; align-items: center; }
    .bad { color: #7a0a0a; font-weight: 700; }
    .topics { width: 100%; border-collapse: collapse; margin-top: 8px; font-size: 0.8rem; }
    .topics th, .topics td { text-align: left; padding: 4px; border-bottom: 1px solid #e3e7ee; }
    .topics .num { text-align: right; }
    .small { font-size: 0.8rem; }
  `,
})
export class OpsDashboard {
  private readonly api = inject(OpsApi);

  protected readonly manual$ = new Subject<void>();
  protected readonly auto = signal(true);
  protected readonly cards = signal<ServiceCard[]>(SERVICES.map((service) => ({ service, data: null, error: null })));
  protected readonly health = signal<HealthAggregate | null>(null);
  protected readonly healthError = signal<ApiError | null>(null);

  constructor() {
    merge(timer(0, REFRESH_MS).pipe(filter(() => this.auto())), this.manual$)
      .pipe(
        switchMap(() =>
          forkJoin({
            cards: forkJoin(
              SERVICES.map((service) =>
                this.api.messaging(service).pipe(
                  map((data): ServiceCard => ({ service, data, error: null })),
                  catchError((e: unknown) => of<ServiceCard>({ service, data: null, error: normalizeError(e) })),
                ),
              ),
            ),
            health: this.api.health().pipe(
              map((h) => ({ h, e: null as ApiError | null })),
              catchError((e: unknown) => of({ h: null as HealthAggregate | null, e: normalizeError(e) })),
            ),
          }),
        ),
        takeUntilDestroyed(),
      )
      .subscribe(({ cards, health }) => {
        this.cards.set(cards);
        this.health.set(health.h);
        this.healthError.set(health.e);
      });
  }

  protected healthEntries(h: HealthAggregate): { name: string; status: string }[] {
    return Object.entries(h.services ?? {}).map(([name, v]) => ({ name, status: v.status }));
  }

  protected lag(m: MessagingStatus): string {
    const l = totalLag(m);
    return l === null ? 'n/a (lag unavailable)' : String(l);
  }
}
