import { lazy } from 'react';
import { Navigate, Route, Routes } from 'react-router-dom';
import { GuestOnly, RequireAuth, RequireRole, STAFF } from './components/guards';
import { AppShell } from './layout/AppShell';

/** Every feature screen is code-split with React.lazy; the shell renders them inside <Suspense>. */
const Login = lazy(() => import('./features/login/Login'));
const Dashboard = lazy(() => import('./features/dashboard/Dashboard'));
const Catalog = lazy(() => import('./features/catalog/Catalog'));
const Inventory = lazy(() => import('./features/inventory/Inventory'));
const OrderList = lazy(() => import('./features/orders/OrderList'));
const OrderCreate = lazy(() => import('./features/orders/OrderCreate'));
const OrderDetail = lazy(() => import('./features/orders/OrderDetail'));
const OpsDashboard = lazy(() => import('./features/ops/OpsDashboard'));
const FailedOrders = lazy(() => import('./features/ops/FailedOrders'));
const Audit = lazy(() => import('./features/ops/Audit'));
const Notifications = lazy(() => import('./features/notifications/Notifications'));
const FulfillmentView = lazy(() => import('./features/fulfillment/Fulfillment'));
const Forbidden = lazy(() => import('./features/misc/Forbidden'));
const NotFound = lazy(() => import('./features/misc/NotFound'));

/** `RequireRole` is UX only (hides screens); the backend authorizes every request. */
export function AppRoutes() {
  return (
    <Routes>
      <Route element={<AppShell />}>
        <Route index element={<Navigate to="/dashboard" replace />} />
        <Route element={<GuestOnly />}>
          <Route path="login" element={<Login />} />
        </Route>
        <Route element={<RequireAuth />}>
          <Route path="dashboard" element={<Dashboard />} />
          <Route path="catalog" element={<Catalog />} />
          <Route path="orders" element={<OrderList />} />
          <Route path="orders/new" element={<OrderCreate />} />
          <Route path="orders/:id" element={<OrderDetail />} />
          <Route path="notifications" element={<Notifications />} />
          <Route element={<RequireRole roles={STAFF} />}>
            <Route path="inventory" element={<Inventory />} />
            <Route path="fulfillment" element={<FulfillmentView />} />
            <Route path="failed-orders" element={<FailedOrders />} />
            <Route path="ops" element={<OpsDashboard />} />
            <Route path="audit" element={<Audit />} />
          </Route>
        </Route>
        <Route path="forbidden" element={<Forbidden />} />
        <Route path="*" element={<NotFound />} />
      </Route>
    </Routes>
  );
}
