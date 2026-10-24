import { http, HttpResponse, type RequestHandler } from 'msw';
import type { Fulfillment, MessagingStatus, OmsNotification, Order, OrderHistoryItem, Page, Product, Reservation, Saga, Session } from '../models';
import { url } from './server';

export const CUSTOMER_ID = 'c0000000-0000-0000-0000-000000000001';
export const ORDER_ID = '11111111-2222-3333-4444-555555555555';
const FAR_FUTURE = 4_102_444_800_000; // year 2100

export const customerSession: Session = { accessToken: 'tok-customer', username: 'customer1', roles: ['CUSTOMER'], customerId: CUSTOMER_ID, expiresAt: FAR_FUTURE };
export const operatorSession: Session = { accessToken: 'tok-operator', username: 'operator1', roles: ['OPERATOR'], customerId: null, expiresAt: FAR_FUTURE };
export const adminSession: Session = { accessToken: 'tok-admin', username: 'admin1', roles: ['ADMIN'], customerId: null, expiresAt: FAR_FUTURE };

export function page<T>(content: T[], over: Partial<Page<T>> = {}): Page<T> {
  return { content, page: 0, size: 20, totalElements: content.length, totalPages: content.length ? 1 : 0, ...over };
}

export function makeProduct(over: Partial<Product> = {}): Product {
  return {
    id: 'a0000000-0000-0000-0000-000000001001',
    sku: 'SKU-1001',
    name: 'Hex Bolt M8',
    description: 'bolt',
    category: 'Fasteners',
    price: 2.5,
    currency: 'USD',
    active: true,
    onHand: 100,
    reserved: 10,
    available: 90,
    version: 1,
    ...over,
  };
}

export function makeOrder(over: Partial<Order> = {}): Order {
  return {
    id: ORDER_ID,
    customerId: CUSTOMER_ID,
    status: 'CONFIRMED',
    statusReason: null,
    totalAmount: 5,
    currency: 'USD',
    items: [{ productId: 'a0000000-0000-0000-0000-000000001001', sku: 'SKU-1001', name: 'Hex Bolt M8', quantity: 2, unitPrice: 2.5, lineTotal: 5 }],
    shippingAddress: { line1: '1 Test Street', city: 'Springfield', postalCode: '12345', country: 'US' },
    correlationId: 'corr-1',
    createdAt: '2026-10-19T10:00:00Z',
    updatedAt: '2026-10-19T10:00:05Z',
    version: 3,
    ...over,
  };
}

/** A REJECTED order exactly as the real order-service returns it: priced nothing, all nullable fields null (defect D-03). */
export function makeRejectedOrder(over: Partial<Order> = {}): Order {
  return makeOrder({
    status: 'REJECTED',
    statusReason: 'INSUFFICIENT_STOCK',
    totalAmount: 0,
    items: [{ productId: 'a0000000-0000-0000-0000-000000001020', sku: null, name: null, quantity: 1, unitPrice: null, lineTotal: null }],
    ...over,
  });
}

export const history: OrderHistoryItem[] = [
  { fromStatus: null, toStatus: 'CREATED', reason: null, actor: 'customer1', occurredAt: '2026-10-19T10:00:00Z' },
  { fromStatus: 'CREATED', toStatus: 'VALIDATED', reason: null, actor: 'system', occurredAt: '2026-10-19T10:00:01Z' },
];

export const saga: Saga = {
  orderId: ORDER_ID,
  sagaId: 's-1',
  state: 'RUNNING',
  currentStep: 'FULFILL',
  attempts: 1,
  startedAt: '2026-10-19T10:00:00Z',
  updatedAt: '2026-10-19T10:00:05Z',
  lastError: null,
  steps: [{ step: 'VALIDATE', status: 'SUCCEEDED', attempt: 1, detail: null, at: '2026-10-19T10:00:01Z' }],
};

export const reservation: Reservation = {
  id: 'r0000000-0000-0000-0000-000000000001',
  orderId: ORDER_ID,
  status: 'COMMITTED',
  items: [{ productId: 'p1', sku: 'SKU-1001', quantity: 2 }],
  expiresAt: null,
  createdAt: '2026-10-19T10:00:00Z',
  updatedAt: '2026-10-19T10:00:00Z',
  releaseReason: null,
};

export const fulfillment: Fulfillment = {
  id: 'f0000000-0000-0000-0000-000000000001',
  orderId: ORDER_ID,
  status: 'FAILED',
  trackingNumber: null,
  attempts: 3,
  failureReason: 'bad address',
  events: [],
  createdAt: '2026-10-19T10:00:00Z',
  updatedAt: '2026-10-19T10:00:00Z',
};

export const notification: OmsNotification = {
  id: 'n0000000-0000-0000-0000-000000000001',
  customerId: CUSTOMER_ID,
  orderId: ORDER_ID,
  channel: 'EMAIL',
  template: 'ORDER_CONFIRMED',
  subject: 'Order 11111111 confirmed',
  status: 'FAILED',
  attempts: 2,
  lastError: 'smtp down',
  correlationId: null,
  sourceEventId: null,
  createdAt: '2026-10-19T10:00:00Z',
  sentAt: null,
};

export const messaging = (service: string, over: Partial<MessagingStatus> = {}): MessagingStatus => ({
  service,
  consumerGroup: `${service}-service`,
  topics: [{ topic: `oms.${service}.events.v1`, partitions: 3, consumerLag: 2, committedOffsets: {} }],
  processedEvents: 10,
  duplicatesIgnored: 1,
  retries: 0,
  deadLettered: 0,
  outboxPending: 0,
  lastProcessedAt: '2026-10-19T10:00:00Z',
  dltTopics: [],
  ...over,
});

export const problem = (status: number, code: string, extra: Record<string, unknown> = {}) =>
  HttpResponse.json({ type: `https://oms.example/problems/${code.toLowerCase()}`, title: code, status, code, correlationId: 'corr-err', errors: [], ...extra }, { status, headers: { 'Content-Type': 'application/problem+json' } });

/** Sensible defaults for every GET so screens that fire extra requests never hit an unhandled route. */
export function defaultHandlers(): RequestHandler[] {
  return [
    http.get(url('/orders'), () => HttpResponse.json(page([makeOrder()]))),
    http.get(url('/orders/:id'), () => HttpResponse.json(makeOrder())),
    http.get(url('/orders/:id/history'), () => HttpResponse.json(history)),
    http.get(url('/admin/saga/:id'), () => HttpResponse.json(saga)),
    http.get(url('/admin/orders/failed'), () => HttpResponse.json(page([makeRejectedOrder()]))),
    http.get(url('/customers'), () =>
      HttpResponse.json(page([{ id: CUSTOMER_ID, name: 'Acme Industrial Supply', email: 'procurement@acme.example.test', tier: 'GOLD' }])),
    ),
    http.get(url('/products'), () => HttpResponse.json(page([makeProduct(), makeProduct({ id: 'a0000000-0000-0000-0000-000000001002', sku: 'SKU-1002', name: 'Hex Nut M8', price: 1, available: 0 })]))),
    http.get(url('/inventory'), () => HttpResponse.json(page([makeProduct()]))),
    http.get(url('/inventory/reservations'), () => HttpResponse.json(page([reservation]))),
    http.get(url('/fulfillments'), () => HttpResponse.json(page([fulfillment]))),
    http.get(url('/notifications'), () => HttpResponse.json(page([notification]))),
    http.get(url('/ops/health'), () => HttpResponse.json({ status: 'UP', services: { order: { status: 'UP' }, inventory: { status: 'UP' }, fulfillment: { status: 'UP' }, notification: { status: 'UP' } } })),
    http.get(url('/ops/:svc/messaging'), ({ params }) => HttpResponse.json(messaging(String(params.svc)))),
    http.get(url('/ops/:svc/audit'), () =>
      HttpResponse.json(page([{ id: 'a1', entityType: 'Order', entityId: ORDER_ID, action: 'ORDER_CREATED', actor: 'customer1', correlationId: null, details: 'created', occurredAt: '2026-10-19T10:00:00Z' }])),
    ),
  ];
}
