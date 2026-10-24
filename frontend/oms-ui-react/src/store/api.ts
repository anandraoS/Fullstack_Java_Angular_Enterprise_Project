import { createApi } from '@reduxjs/toolkit/query/react';
import type {
  AuditEvent,
  CreateOrderRequest,
  Customer,
  Fulfillment,
  HealthAggregate,
  MessagingStatus,
  OmsNotification,
  Order,
  OrderHistoryItem,
  Page,
  Product,
  ReconciliationResult,
  Reservation,
  Saga,
  ServiceName,
  TokenResponse,
} from '../models';
import { omsBaseQuery } from './baseQuery';

export interface PageQuery {
  page?: number;
  size?: number;
  sort?: string;
}

/** Drops undefined/null/empty-string values and stringifies the rest (same rule as the Angular `toParams`). */
export function toParams(obj: object): Record<string, string> {
  const out: Record<string, string> = {};
  for (const [k, v] of Object.entries(obj)) {
    if (v === undefined || v === null || v === '') continue;
    out[k] = String(v);
  }
  return out;
}

export interface OrderFilter extends PageQuery {
  status?: string;
  customerId?: string;
  from?: string;
  to?: string;
  q?: string;
}
export interface ProductFilter extends PageQuery {
  q?: string;
  category?: string;
  inStock?: boolean | '';
}
export interface InventoryFilter extends PageQuery {
  sku?: string;
  lowStock?: boolean | '';
}
export interface ReservationFilter extends PageQuery {
  orderId?: string;
  status?: string;
}
export interface FulfillmentFilter extends PageQuery {
  orderId?: string;
  status?: string;
}
export interface NotificationFilter extends PageQuery {
  customerId?: string;
  orderId?: string;
  status?: string;
}
export interface AuditFilter extends PageQuery {
  entityType?: string;
  entityId?: string;
}

export interface CreateOrderResult {
  order: Order;
  replay: boolean;
  location: string | null;
}

export interface OrderSnapshot {
  order: Order;
  history: OrderHistoryItem[];
  saga: Saga | null;
}

export interface Investigation extends OrderSnapshot {
  audit: AuditEvent[];
}

const enc = encodeURIComponent;

/**
 * Server state lives here (RTK Query): cache, de-duplication, invalidation. Auth/cart/ui are plain slices.
 * `refetchOnMountOrArgChange` keeps list screens fresh every time they are opened (like the Angular ListStore).
 */
export const omsApi = createApi({
  reducerPath: 'omsApi',
  baseQuery: omsBaseQuery,
  keepUnusedDataFor: 30,
  refetchOnMountOrArgChange: true,
  tagTypes: ['Orders', 'Order', 'Failed', 'Products', 'Stock', 'Fulfillments', 'Notifications'],
  endpoints: (b) => ({
    login: b.mutation<TokenResponse, { username: string; password: string }>({
      query: (body) => ({ url: '/auth/token', method: 'POST', body }),
    }),

    /* ---- orders ---- */
    listOrders: b.query<Page<Order>, OrderFilter>({
      query: (f) => ({ url: '/orders', params: toParams(f) }),
      providesTags: ['Orders'],
    }),
    /** order + history (+ saga for staff) fetched together, like the Angular forkJoin; saga failure is tolerated. */
    getOrderSnapshot: b.query<OrderSnapshot, { id: string; withSaga: boolean }>({
      queryFn: async ({ id, withSaga }, _api, _extra, baseQuery) => {
        const [order, history, saga] = await Promise.all([
          baseQuery(`/orders/${enc(id)}`),
          baseQuery(`/orders/${enc(id)}/history`),
          withSaga ? baseQuery(`/admin/saga/${enc(id)}`) : Promise.resolve(null),
        ]);
        if (order.error) return { error: order.error };
        if (history.error) return { error: history.error };
        return {
          data: {
            order: order.data as Order,
            history: history.data as OrderHistoryItem[],
            saga: saga && !saga.error ? (saga.data as Saga) : null,
          },
        };
      },
      providesTags: (_r, _e, a) => [{ type: 'Order', id: a.id }],
    }),
    createOrder: b.mutation<CreateOrderResult, { body: CreateOrderRequest; key: string }>({
      query: ({ body, key }) => ({ url: '/orders', method: 'POST', body, headers: { 'Idempotency-Key': key } }),
      transformResponse: (body: Order, meta) => ({
        order: body,
        replay: meta?.response?.headers.get('Idempotent-Replay') === 'true',
        location: meta?.response?.headers.get('Location') ?? null,
      }),
      invalidatesTags: ['Orders'],
    }),
    cancelOrder: b.mutation<Order, { id: string; reason: string }>({
      query: ({ id, reason }) => ({ url: `/orders/${enc(id)}/cancel`, method: 'POST', body: { reason } }),
      invalidatesTags: (_r, _e, a) => [{ type: 'Order', id: a.id }, 'Orders', 'Failed'],
    }),
    failedOrders: b.query<Page<Order>, PageQuery>({
      query: (q) => ({ url: '/admin/orders/failed', params: toParams(q) }),
      providesTags: ['Failed'],
    }),
    retryOrder: b.mutation<unknown, string>({
      query: (id) => ({ url: `/admin/orders/${enc(id)}/retry`, method: 'POST', body: {} }),
      invalidatesTags: (_r, _e, id) => [{ type: 'Order', id }, 'Orders', 'Failed'],
    }),
    /** investigation = order + history + saga + audit trail; saga and audit failures only blank their own section. */
    getInvestigation: b.query<Investigation, string>({
      queryFn: async (id, _api, _extra, baseQuery) => {
        const [order, history, saga, audit] = await Promise.all([
          baseQuery(`/orders/${enc(id)}`),
          baseQuery(`/orders/${enc(id)}/history`),
          baseQuery(`/admin/saga/${enc(id)}`),
          baseQuery({ url: '/ops/order/audit', params: toParams({ entityType: 'Order', entityId: id, page: 0, size: 50 }) }),
        ]);
        if (order.error) return { error: order.error };
        if (history.error) return { error: history.error };
        return {
          data: {
            order: order.data as Order,
            history: history.data as OrderHistoryItem[],
            saga: saga.error ? null : (saga.data as Saga),
            audit: audit.error ? [] : (audit.data as Page<AuditEvent>).content,
          },
        };
      },
      providesTags: (_r, _e, id) => [{ type: 'Order', id }],
    }),
    reconcile: b.mutation<ReconciliationResult, void>({
      query: () => ({ url: '/admin/reconciliation/run', method: 'POST', body: {} }),
      invalidatesTags: ['Failed', 'Orders'],
    }),
    listCustomers: b.query<Page<Customer>, { q?: string } & PageQuery>({
      query: (q) => ({ url: '/customers', params: toParams(q) }),
    }),

    /* ---- inventory ---- */
    listProducts: b.query<Page<Product>, ProductFilter>({
      query: (f) => ({ url: '/products', params: toParams(f) }),
      providesTags: ['Products'],
    }),
    listInventory: b.query<Page<Product>, InventoryFilter>({
      query: (f) => ({ url: '/inventory', params: toParams(f) }),
      providesTags: ['Stock'],
    }),
    adjustStock: b.mutation<Product, { productId: string; delta: number; reason: string }>({
      query: ({ productId, delta, reason }) => ({ url: `/inventory/${enc(productId)}/stock`, method: 'PUT', body: { delta, reason } }),
      invalidatesTags: ['Stock', 'Products'],
    }),
    listReservations: b.query<Page<Reservation>, ReservationFilter>({
      query: (f) => ({ url: '/inventory/reservations', params: toParams(f) }),
    }),

    /* ---- fulfillment / notification ---- */
    listFulfillments: b.query<Page<Fulfillment>, FulfillmentFilter>({
      query: (f) => ({ url: '/fulfillments', params: toParams(f) }),
      providesTags: ['Fulfillments'],
    }),
    retryFulfillment: b.mutation<unknown, string>({
      query: (id) => ({ url: `/admin/fulfillments/${enc(id)}/retry`, method: 'POST', body: {} }),
      invalidatesTags: ['Fulfillments'],
    }),
    listNotifications: b.query<Page<OmsNotification>, NotificationFilter>({
      query: (f) => ({ url: '/notifications', params: toParams(f) }),
      providesTags: ['Notifications'],
    }),
    retryNotification: b.mutation<unknown, string>({
      query: (id) => ({ url: `/admin/notifications/${enc(id)}/retry`, method: 'POST', body: {} }),
      invalidatesTags: ['Notifications'],
    }),

    /* ---- ops ---- */
    getMessaging: b.query<MessagingStatus, ServiceName>({
      query: (svc) => `/ops/${svc}/messaging`,
    }),
    listAudit: b.query<Page<AuditEvent>, { svc: ServiceName } & AuditFilter>({
      query: ({ svc, ...f }) => ({ url: `/ops/${svc}/audit`, params: toParams(f) }),
    }),
    getHealth: b.query<HealthAggregate, void>({
      query: () => '/ops/health',
    }),
  }),
});

export const {
  useLoginMutation,
  useListOrdersQuery,
  useGetOrderSnapshotQuery,
  useCreateOrderMutation,
  useCancelOrderMutation,
  useFailedOrdersQuery,
  useRetryOrderMutation,
  useGetInvestigationQuery,
  useReconcileMutation,
  useListCustomersQuery,
  useListProductsQuery,
  useListInventoryQuery,
  useAdjustStockMutation,
  useListReservationsQuery,
  useListFulfillmentsQuery,
  useRetryFulfillmentMutation,
  useListNotificationsQuery,
  useRetryNotificationMutation,
  useGetMessagingQuery,
  useListAuditQuery,
  useGetHealthQuery,
} = omsApi;
