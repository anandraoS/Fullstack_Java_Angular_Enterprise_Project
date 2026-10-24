// Generates the Grafana dashboard JSON files in ./dashboards (committed output). Run: node observability/grafana/build-dashboards.mjs
// Keeping a generator avoids hand-maintaining ~1500 lines of panel JSON; edit here and regenerate.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const OUT = path.join(path.dirname(fileURLToPath(import.meta.url)), 'dashboards');
fs.mkdirSync(OUT, { recursive: true });
const DS = { type: 'prometheus', uid: 'prometheus' };

let nextId = 1;
const panel = (title, exprs, { unit = 'short', x, y, w = 12, h = 8, type = 'timeseries', desc = '', legends = [] } = {}) => ({
  id: nextId++,
  type,
  title,
  description: desc,
  datasource: DS,
  gridPos: { x, y, w, h },
  fieldConfig: { defaults: { unit }, overrides: [] },
  options: type === 'stat' ? { reduceOptions: { calcs: ['lastNotNull'] } } : { legend: { displayMode: 'table', placement: 'bottom' }, tooltip: { mode: 'multi' } },
  targets: exprs.map((expr, i) => ({ refId: String.fromCharCode(65 + i), expr, legendFormat: legends[i] ?? '{{job}}' })),
});
const grid = (specs) => specs.map((s, i) => panel(s[0], s[1], { x: (i % 2) * 12, y: Math.floor(i / 2) * 8, ...(s[2] ?? {}) }));

const dash = (uid, title, panels) => {
  nextId = 1;
  return {
    uid, title, tags: ['oms'], schemaVersion: 39, version: 1, editable: true, timezone: 'utc',
    time: { from: 'now-1h', to: 'now' }, refresh: '30s',
    templating: { list: [{ name: 'job', type: 'query', datasource: DS, query: 'label_values(up{job=~"oms-.*"}, job)', includeAll: true, multi: true, allValue: 'oms-.*', current: { text: 'All', value: '$__all' }, refresh: 2 }] },
    panels,
  };
};
const J = 'job=~"$job"';
const write = (name, d) => fs.writeFileSync(path.join(OUT, name), JSON.stringify(d, null, 2) + '\n');

write('oms-api-red.json', dash('oms-api-red', 'OMS - API Rate / Errors / Duration (RED)', grid([
  ['Request rate by service (req/s)', [`sum by (job) (rate(http_server_requests_seconds_count{${J},uri!~"/actuator.*"}[1m]))`], { unit: 'reqps' }],
  ['Error ratio (5xx) by service', [`sum by (job) (rate(http_server_requests_seconds_count{${J},status=~"5.."}[5m])) / sum by (job) (rate(http_server_requests_seconds_count{${J}}[5m]))`], { unit: 'percentunit' }],
  ['p95 latency by service', [`histogram_quantile(0.95, sum by (job, le) (rate(http_server_requests_seconds_bucket{${J},uri!~"/actuator.*"}[5m])))`], { unit: 's', desc: 'Requires percentiles-histogram for http.server.requests.' }],
  ['p99 latency by service', [`histogram_quantile(0.99, sum by (job, le) (rate(http_server_requests_seconds_bucket{${J},uri!~"/actuator.*"}[5m])))`], { unit: 's' }],
  ['Requests by status class', [`sum by (status) (rate(http_server_requests_seconds_count{${J}}[5m]))`], { unit: 'reqps', legends: ['{{status}}'] }],
  ['Slowest endpoints (avg s)', [`topk(8, sum by (job, uri) (rate(http_server_requests_seconds_sum{${J},uri!~"/actuator.*"}[5m])) / sum by (job, uri) (rate(http_server_requests_seconds_count{${J},uri!~"/actuator.*"}[5m])))`], { unit: 's', legends: ['{{job}} {{uri}}'] }],
  ['4xx rate (401/403/404/409/422/429)', [`sum by (status) (rate(http_server_requests_seconds_count{${J},status=~"4.."}[5m]))`], { unit: 'reqps', legends: ['{{status}}'] }],
  ['Targets up', [`up{${J}}`], { type: 'stat', w: 12, h: 8 }],
])));

write('oms-messaging.json', dash('oms-messaging', 'OMS - Kafka Messaging, Outbox and DLT', grid([
  ['Consumer lag (max records) by topic', ['max by (job, topic) (kafka_consumer_fetch_manager_records_lag_max{' + J + '})'], { legends: ['{{job}} {{topic}}'], desc: 'Kafka client metric exposed by Micrometer.' }],
  ['Events processed (per s) by service/type', ['sum by (service, type) (rate(oms_events_processed_total[5m]))'], { unit: 'ops', legends: ['{{service}} {{type}}'] }],
  ['Retries (per s)', ['sum by (service, topic) (rate(oms_events_retries_total[5m]))'], { unit: 'ops', legends: ['{{service}} {{topic}}'] }],
  ['Dead-lettered (increase 1h)', ['sum by (service, topic) (increase(oms_events_dead_lettered_total[1h]))'], { legends: ['{{service}} {{topic}}'], desc: 'Any value above 0 needs attention.' }],
  ['Duplicates ignored (per s)', ['sum by (service) (rate(oms_events_duplicates_total[5m]))'], { unit: 'ops', legends: ['{{service}}'] }],
  ['Outbox pending rows', ['max by (service) (oms_outbox_pending)'], { legends: ['{{service}}'] }],
  ['Order processing p95 (CREATED to terminal)', ['histogram_quantile(0.95, sum by (le) (rate(oms_order_processing_duration_seconds_bucket[5m])))'], { unit: 's', legends: ['p95'] }],
  ['Order processing mean', ['sum(rate(oms_order_processing_duration_seconds_sum[5m])) / sum(rate(oms_order_processing_duration_seconds_count[5m]))'], { unit: 's', legends: ['mean'] }],
  ['Spring Kafka listener duration p95', [`histogram_quantile(0.95, sum by (job, le) (rate(spring_kafka_listener_seconds_bucket{${J}}[5m])))`], { unit: 's', desc: 'Only present when observation/timer metrics for @KafkaListener are enabled.' }],
])));

write('oms-jvm.json', dash('oms-jvm', 'OMS - JVM Memory, GC and Threads', grid([
  ['Heap used', [`sum by (job, instance) (jvm_memory_used_bytes{${J},area="heap"})`], { unit: 'bytes', legends: ['{{job}} {{instance}}'] }],
  ['Heap used / max', [`sum by (job, instance) (jvm_memory_used_bytes{${J},area="heap"}) / sum by (job, instance) (jvm_memory_max_bytes{${J},area="heap"})`], { unit: 'percentunit', legends: ['{{job}} {{instance}}'] }],
  ['Non-heap used', [`sum by (job, instance) (jvm_memory_used_bytes{${J},area="nonheap"})`], { unit: 'bytes', legends: ['{{job}} {{instance}}'] }],
  ['GC pause time (s per s)', [`sum by (job, action) (rate(jvm_gc_pause_seconds_sum{${J}}[5m]))`], { unit: 's', legends: ['{{job}} {{action}}'] }],
  ['GC pauses (count per s)', [`sum by (job) (rate(jvm_gc_pause_seconds_count{${J}}[5m]))`], { unit: 'ops' }],
  ['Live threads', [`jvm_threads_live_threads{${J}}`], { legends: ['{{job}} {{instance}}'] }],
  ['Process CPU', [`process_cpu_usage{${J}}`], { unit: 'percentunit', legends: ['{{job}} {{instance}}'] }],
  ['Loaded classes', [`jvm_classes_loaded_classes{${J}}`], { legends: ['{{job}} {{instance}}'] }],
])));

write('oms-hikari.json', dash('oms-hikari', 'OMS - Hikari Connection Pools', grid([
  ['Active connections', [`hikaricp_connections_active{${J}}`], { legends: ['{{job}} {{pool}}'] }],
  ['Pending threads (waiting for a connection)', [`hikaricp_connections_pending{${J}}`], { legends: ['{{job}} {{pool}}'], desc: 'Sustained >0 means pool exhaustion.' }],
  ['Pool utilisation (active / max)', [`hikaricp_connections_active{${J}} / hikaricp_connections_max{${J}}`], { unit: 'percentunit', legends: ['{{job}} {{pool}}'] }],
  ['Idle / total connections', [`hikaricp_connections_idle{${J}}`, `hikaricp_connections{${J}}`], { legends: ['idle {{job}}', 'total {{job}}'] }],
  ['Connection acquire time p95 (needs histogram) / mean', [`sum by (job) (rate(hikaricp_connections_acquire_seconds_sum{${J}}[5m])) / sum by (job) (rate(hikaricp_connections_acquire_seconds_count{${J}}[5m]))`], { unit: 's' }],
  ['Connection timeouts (per s)', [`sum by (job) (rate(hikaricp_connections_timeout_total{${J}}[5m]))`], { unit: 'ops' }],
  ['Connection usage time mean', [`sum by (job) (rate(hikaricp_connections_usage_seconds_sum{${J}}[5m])) / sum by (job) (rate(hikaricp_connections_usage_seconds_count{${J}}[5m]))`], { unit: 's' }],
])));

write('oms-saga-funnel.json', dash('oms-saga-funnel', 'OMS - Saga and Order Funnel', grid([
  ['Active sagas', ['max(oms_saga_active)'], { legends: ['active'], type: 'stat' }],
  ['Compensations (increase 1h)', ['sum(increase(oms_saga_compensations_total[1h]))'], { legends: ['compensations'], type: 'stat' }],
  ['Order events per s by type (funnel)', ['sum by (type) (rate(oms_events_processed_total{service="notification-service",topic="oms.order.events.v1"}[5m]))'], { unit: 'ops', legends: ['{{type}}'], desc: 'OrderCreated -> Confirmed -> Fulfilled vs Rejected/Cancelled/Failed as seen by the notification consumer.' }],
  ['Order events (increase 1h) by type', ['sum by (type) (increase(oms_events_processed_total{service="notification-service",topic="oms.order.events.v1"}[1h]))'], { legends: ['{{type}}'] }],
  ['Inventory events processed by order-service', ['sum by (type) (rate(oms_events_processed_total{service="order-service",topic="oms.inventory.events.v1"}[5m]))'], { unit: 'ops', legends: ['{{type}}'], desc: 'InventoryReserved vs InventoryReservationFailed.' }],
  ['Fulfillment events processed by order-service', ['sum by (type) (rate(oms_events_processed_total{service="order-service",topic="oms.fulfillment.events.v1"}[5m]))'], { unit: 'ops', legends: ['{{type}}'] }],
  ['Order processing p50 / p95', ['histogram_quantile(0.5, sum by (le) (rate(oms_order_processing_duration_seconds_bucket[5m])))', 'histogram_quantile(0.95, sum by (le) (rate(oms_order_processing_duration_seconds_bucket[5m])))'], { unit: 's', legends: ['p50', 'p95'] }],
  ['Orders completed per min (timer count)', ['sum(rate(oms_order_processing_duration_seconds_count[5m])) * 60'], { legends: ['orders/min'] }],
])));

console.log('dashboards written to', OUT);
