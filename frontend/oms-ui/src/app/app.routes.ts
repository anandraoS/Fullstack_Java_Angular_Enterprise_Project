import { Routes } from '@angular/router';
import { authGuard, guestGuard, roleGuard } from './core/auth/guards';
import { Role } from './core/models';

export const STAFF: Role[] = ['OPERATOR', 'ADMIN'];

/** All feature screens are lazy-loaded standalone components. `data.roles` is read by roleGuard (UX only). */
export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'dashboard' },
  { path: 'login', canActivate: [guestGuard], title: 'Sign in', loadComponent: () => import('./features/login/login').then((m) => m.Login) },
  {
    path: 'dashboard',
    canActivate: [authGuard],
    title: 'Dashboard',
    loadComponent: () => import('./features/dashboard/dashboard').then((m) => m.Dashboard),
  },
  {
    path: 'catalog',
    canActivate: [authGuard],
    title: 'Product catalog',
    loadComponent: () => import('./features/catalog/catalog').then((m) => m.Catalog),
  },
  {
    path: 'inventory',
    canActivate: [roleGuard],
    data: { roles: STAFF },
    title: 'Inventory',
    loadComponent: () => import('./features/inventory/inventory').then((m) => m.Inventory),
  },
  {
    path: 'orders',
    canActivate: [authGuard],
    title: 'Orders',
    loadComponent: () => import('./features/orders/order-list').then((m) => m.OrderList),
  },
  {
    path: 'orders/new',
    canActivate: [authGuard],
    title: 'New order',
    loadComponent: () => import('./features/orders/order-create').then((m) => m.OrderCreate),
  },
  {
    path: 'orders/:id',
    canActivate: [authGuard],
    title: 'Order detail',
    loadComponent: () => import('./features/orders/order-detail').then((m) => m.OrderDetail),
  },
  {
    path: 'ops',
    canActivate: [roleGuard],
    data: { roles: STAFF },
    title: 'Operations',
    loadComponent: () => import('./features/ops/ops-dashboard').then((m) => m.OpsDashboard),
  },
  {
    path: 'failed-orders',
    canActivate: [roleGuard],
    data: { roles: STAFF },
    title: 'Failed orders',
    loadComponent: () => import('./features/ops/failed-orders').then((m) => m.FailedOrders),
  },
  {
    path: 'audit',
    canActivate: [roleGuard],
    data: { roles: STAFF },
    title: 'Audit history',
    loadComponent: () => import('./features/ops/audit').then((m) => m.Audit),
  },
  {
    path: 'notifications',
    canActivate: [authGuard],
    title: 'Notifications',
    loadComponent: () => import('./features/notifications/notifications').then((m) => m.Notifications),
  },
  {
    path: 'fulfillment',
    canActivate: [roleGuard],
    data: { roles: STAFF },
    title: 'Fulfillment',
    loadComponent: () => import('./features/fulfillment/fulfillment').then((m) => m.FulfillmentView),
  },
  { path: 'forbidden', title: 'Not allowed', loadComponent: () => import('./features/misc/misc').then((m) => m.Forbidden) },
  { path: '**', title: 'Not found', loadComponent: () => import('./features/misc/misc').then((m) => m.NotFound) },
];
