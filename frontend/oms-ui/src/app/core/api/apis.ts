import { HttpClient, HttpHeaders, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ConfigService } from '../config.service';
import {
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
} from '../models';

export interface PageQuery {
  page?: number;
  size?: number;
  sort?: string;
}

/** Drops undefined/null/empty-string values and builds HttpParams. */
export function toParams(obj: object): HttpParams {
  let params = new HttpParams();
  for (const [k, v] of Object.entries(obj)) {
    if (v === undefined || v === null || v === '') continue;
    params = params.set(k, String(v));
  }
  return params;
}

abstract class BaseApi {
  protected readonly http = inject(HttpClient);
  private readonly cfg = inject(ConfigService);
  protected url(path: string): string {
    return `${this.cfg.apiBaseUrl}${path}`;
  }
}

export interface OrderFilter extends PageQuery {
  status?: string;
  customerId?: string;
  from?: string;
  to?: string;
  q?: string;
}

export interface CreateOrderResult {
  order: Order;
  replay: boolean;
  location: string | null;
}

@Injectable({ providedIn: 'root' })
export class OrderApi extends BaseApi {
  list(filter: OrderFilter): Observable<Page<Order>> {
    return this.http.get<Page<Order>>(this.url('/orders'), { params: toParams(filter) });
  }

  get(id: string): Observable<Order> {
    return this.http.get<Order>(this.url(`/orders/${encodeURIComponent(id)}`));
  }

  history(id: string): Observable<OrderHistoryItem[]> {
    return this.http.get<OrderHistoryItem[]>(this.url(`/orders/${encodeURIComponent(id)}/history`));
  }

  create(body: CreateOrderRequest, idempotencyKey: string): Observable<CreateOrderResult> {
    return new Observable<CreateOrderResult>((subscriber) => {
      const sub = this.http
        .post<Order>(this.url('/orders'), body, {
          headers: new HttpHeaders({ 'Idempotency-Key': idempotencyKey }),
          observe: 'response',
        })
        .subscribe({
          next: (res) =>
            subscriber.next({
              order: res.body as Order,
              replay: res.headers.get('Idempotent-Replay') === 'true',
              location: res.headers.get('Location'),
            }),
          error: (e) => subscriber.error(e),
          complete: () => subscriber.complete(),
        });
      return () => sub.unsubscribe();
    });
  }

  cancel(id: string, reason: string): Observable<Order> {
    return this.http.post<Order>(this.url(`/orders/${encodeURIComponent(id)}/cancel`), { reason });
  }

  failed(q: PageQuery): Observable<Page<Order>> {
    return this.http.get<Page<Order>>(this.url('/admin/orders/failed'), { params: toParams(q) });
  }

  retry(id: string): Observable<unknown> {
    return this.http.post(this.url(`/admin/orders/${encodeURIComponent(id)}/retry`), {});
  }

  saga(orderId: string): Observable<Saga> {
    return this.http.get<Saga>(this.url(`/admin/saga/${encodeURIComponent(orderId)}`));
  }

  reconcile(): Observable<ReconciliationResult> {
    return this.http.post<ReconciliationResult>(this.url('/admin/reconciliation/run'), {});
  }

  customers(q: { q?: string } & PageQuery): Observable<Page<Customer>> {
    return this.http.get<Page<Customer>>(this.url('/customers'), { params: toParams(q) });
  }

  customer(id: string): Observable<Customer> {
    return this.http.get<Customer>(this.url(`/customers/${encodeURIComponent(id)}`));
  }
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

@Injectable({ providedIn: 'root' })
export class InventoryApi extends BaseApi {
  products(f: ProductFilter): Observable<Page<Product>> {
    return this.http.get<Page<Product>>(this.url('/products'), { params: toParams(f) });
  }

  inventory(f: InventoryFilter): Observable<Page<Product>> {
    return this.http.get<Page<Product>>(this.url('/inventory'), { params: toParams(f) });
  }

  adjustStock(productId: string, delta: number, reason: string): Observable<Product> {
    return this.http.put<Product>(this.url(`/inventory/${encodeURIComponent(productId)}/stock`), { delta, reason });
  }

  reservations(f: ReservationFilter): Observable<Page<Reservation>> {
    return this.http.get<Page<Reservation>>(this.url('/inventory/reservations'), { params: toParams(f) });
  }
}

export interface FulfillmentFilter extends PageQuery {
  orderId?: string;
  status?: string;
}

@Injectable({ providedIn: 'root' })
export class FulfillmentApi extends BaseApi {
  list(f: FulfillmentFilter): Observable<Page<Fulfillment>> {
    return this.http.get<Page<Fulfillment>>(this.url('/fulfillments'), { params: toParams(f) });
  }

  retry(id: string): Observable<unknown> {
    return this.http.post(this.url(`/admin/fulfillments/${encodeURIComponent(id)}/retry`), {});
  }
}

export interface NotificationFilter extends PageQuery {
  customerId?: string;
  orderId?: string;
  status?: string;
}

@Injectable({ providedIn: 'root' })
export class NotificationApi extends BaseApi {
  list(f: NotificationFilter): Observable<Page<OmsNotification>> {
    return this.http.get<Page<OmsNotification>>(this.url('/notifications'), { params: toParams(f) });
  }

  retry(id: string): Observable<unknown> {
    return this.http.post(this.url(`/admin/notifications/${encodeURIComponent(id)}/retry`), {});
  }
}

export interface AuditFilter extends PageQuery {
  entityType?: string;
  entityId?: string;
}

@Injectable({ providedIn: 'root' })
export class OpsApi extends BaseApi {
  messaging(svc: ServiceName): Observable<MessagingStatus> {
    return this.http.get<MessagingStatus>(this.url(`/ops/${svc}/messaging`));
  }

  audit(svc: ServiceName, f: AuditFilter): Observable<Page<AuditEvent>> {
    return this.http.get<Page<AuditEvent>>(this.url(`/ops/${svc}/audit`), { params: toParams(f) });
  }

  health(): Observable<HealthAggregate> {
    return this.http.get<HealthAggregate>(this.url('/ops/health'));
  }
}
