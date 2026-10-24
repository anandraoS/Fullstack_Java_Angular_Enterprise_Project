/** Types mirroring docs/architecture/00-shared-contract.md. Keep in sync with the contract. */

export type Role = 'CUSTOMER' | 'OPERATOR' | 'ADMIN' | 'SERVICE';
export type ServiceName = 'order' | 'inventory' | 'fulfillment' | 'notification';
export const SERVICES: readonly ServiceName[] = ['order', 'inventory', 'fulfillment', 'notification'];

export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface TokenResponse {
  accessToken: string;
  tokenType: 'Bearer';
  expiresIn: number;
  username: string;
  roles: Role[];
  customerId: string | null;
}

export interface Session {
  accessToken: string;
  username: string;
  roles: Role[];
  customerId: string | null;
  /** epoch millis */
  expiresAt: number;
}

export type OrderStatus =
  | 'CREATED'
  | 'VALIDATED'
  | 'INVENTORY_RESERVED'
  | 'CONFIRMED'
  | 'FULFILLED'
  | 'REJECTED'
  | 'CANCELLED'
  | 'COMPENSATION_PENDING'
  | 'FAILED';

export const ORDER_STATUSES: readonly OrderStatus[] = [
  'CREATED',
  'VALIDATED',
  'INVENTORY_RESERVED',
  'CONFIRMED',
  'FULFILLED',
  'REJECTED',
  'CANCELLED',
  'COMPENSATION_PENDING',
  'FAILED',
];
export const TERMINAL_STATUSES: readonly OrderStatus[] = ['FULFILLED', 'REJECTED', 'CANCELLED', 'FAILED'];
export const CANCELLABLE_STATUSES: readonly OrderStatus[] = ['CREATED', 'VALIDATED', 'INVENTORY_RESERVED', 'CONFIRMED'];

export interface Address {
  line1: string;
  city: string;
  postalCode: string;
  country: string;
}

export interface OrderItem {
  productId: string;
  /** sku, name and prices are null until validation has priced the order, and stay null if it is rejected there. */
  sku: string | null;
  name: string | null;
  quantity: number;
  unitPrice: number | null;
  lineTotal: number | null;
}

export interface Order {
  id: string;
  customerId: string;
  status: OrderStatus;
  statusReason: string | null;
  totalAmount: number;
  currency: string;
  items: OrderItem[];
  shippingAddress: Address;
  correlationId: string;
  createdAt: string;
  updatedAt: string;
  version: number;
}

export interface OrderHistoryItem {
  fromStatus: OrderStatus | null;
  toStatus: OrderStatus;
  reason: string | null;
  actor: string | null;
  occurredAt: string;
}

export interface CreateOrderRequest {
  customerId: string;
  items: { productId: string; quantity: number }[];
  shippingAddress: Address;
}

export interface SagaStep {
  step: string;
  status: string;
  attempt: number;
  detail: string | null;
  at: string;
}

export interface Saga {
  orderId: string;
  sagaId: string;
  state: string;
  currentStep: string;
  attempts: number;
  startedAt: string;
  updatedAt: string;
  lastError: string | null;
  steps: SagaStep[];
}

export interface Customer {
  id: string;
  name: string;
  email: string;
  tier?: string | null;
  createdAt?: string;
}

export interface ReconciliationResult {
  examined: number;
  repaired: number;
  details: string[];
}

export interface Product {
  id: string;
  sku: string;
  name: string;
  description: string | null;
  category: string;
  price: number;
  currency: string;
  active: boolean;
  /** stock fields only for OPERATOR/ADMIN */
  onHand?: number;
  reserved?: number;
  available: number;
  version: number;
}

export type ReservationStatus = 'RESERVED' | 'COMMITTED' | 'RELEASED' | 'EXPIRED' | 'FAILED';

export interface Reservation {
  id: string;
  orderId: string;
  status: ReservationStatus;
  items: { productId: string; sku: string; quantity: number }[];
  expiresAt: string | null;
  createdAt: string;
  updatedAt: string;
  releaseReason: string | null;
}

export type FulfillmentStatus = 'PENDING' | 'PICKING' | 'PACKED' | 'SHIPPED' | 'COMPLETED' | 'FAILED';

export interface Fulfillment {
  id: string;
  orderId: string;
  status: FulfillmentStatus;
  trackingNumber: string | null;
  attempts: number;
  failureReason: string | null;
  events: { status: string; at: string }[];
  createdAt: string;
  updatedAt: string;
}

export type NotificationStatus = 'PENDING' | 'SENT' | 'FAILED' | 'RETRYING';

export interface OmsNotification {
  id: string;
  customerId: string;
  orderId: string | null;
  channel: 'EMAIL' | 'SMS';
  template: string;
  subject: string;
  status: NotificationStatus;
  attempts: number;
  lastError: string | null;
  correlationId: string | null;
  sourceEventId: string | null;
  createdAt: string;
  sentAt: string | null;
}

export interface TopicStatus {
  topic: string;
  partitions: number;
  consumerLag: number | null;
  committedOffsets: unknown;
}

export interface MessagingStatus {
  service: string;
  consumerGroup: string;
  topics: TopicStatus[];
  processedEvents: number;
  duplicatesIgnored: number;
  retries: number;
  deadLettered: number;
  outboxPending: number;
  lastProcessedAt: string | null;
  dltTopics: string[];
  lagAvailable?: boolean;
}

/** Shape defined in contract section 5.5 (added by the UI work). */
export interface HealthAggregate {
  status: 'UP' | 'DOWN' | 'DEGRADED';
  services: Record<string, { status: string; detail?: string | null }>;
}

/** Shape defined in contract section 5.5 (added by the UI work). */
export interface AuditEvent {
  id: string;
  entityType: string;
  entityId: string;
  action: string;
  actor: string | null;
  correlationId: string | null;
  details: string | null;
  occurredAt: string;
}

export interface ProblemDetail {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  instance?: string;
  code?: string;
  correlationId?: string;
  timestamp?: string;
  errors?: { field: string; message: string }[];
}
