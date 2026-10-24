import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { EnvironmentProviders, Provider } from '@angular/core';
import { provideRouter } from '@angular/router';
import { authInterceptor, correlationInterceptor, errorInterceptor, retryInterceptor } from '../core/http/interceptors';
import { Address, Order, Page, Product, Session } from '../core/models';

/** Same interceptor chain as app.config.ts, backed by HttpTestingController. */
export function httpTestProviders(extra: Provider[] = []): (Provider | EnvironmentProviders)[] {
  return [
    provideHttpClient(withInterceptors([correlationInterceptor, authInterceptor, retryInterceptor, errorInterceptor])),
    provideHttpClientTesting(),
    provideRouter([{ path: 'login', children: [] }]),
    ...extra,
  ];
}

export const API = '/api/v1';

export function page<T>(content: T[], p = 0, size = 20, total = content.length): Page<T> {
  return { content, page: p, size, totalElements: total, totalPages: Math.max(1, Math.ceil(total / size)) };
}

export const ADDRESS: Address = { line1: '1 Main St', city: 'Springfield', postalCode: '12345', country: 'US' };

export function makeOrder(over: Partial<Order> = {}): Order {
  return {
    id: '11111111-1111-1111-1111-111111111111',
    customerId: 'c0000000-0000-0000-0000-000000000001',
    status: 'CREATED',
    statusReason: null,
    totalAmount: 20,
    currency: 'USD',
    items: [{ productId: 'p1', sku: 'SKU-1001', name: 'Widget', quantity: 2, unitPrice: 10, lineTotal: 20 }],
    shippingAddress: ADDRESS,
    correlationId: 'corr-1',
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    version: 0,
    ...over,
  };
}

export function makeProduct(over: Partial<Product> = {}): Product {
  return {
    id: 'p1',
    sku: 'SKU-1001',
    name: 'Widget',
    description: null,
    category: 'Tools',
    price: 10,
    currency: 'USD',
    active: true,
    onHand: 100,
    reserved: 5,
    available: 95,
    version: 1,
    ...over,
  };
}

export function makeSession(over: Partial<Session> = {}): Session {
  return {
    accessToken: 'test-token',
    username: 'operator1',
    roles: ['OPERATOR'],
    customerId: null,
    expiresAt: Date.now() + 3_600_000,
    ...over,
  };
}
