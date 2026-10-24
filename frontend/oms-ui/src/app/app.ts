import { BreakpointObserver } from '@angular/cdk/layout';
import { Component, computed, inject, signal, viewChild } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatListModule } from '@angular/material/list';
import { MatMenuModule } from '@angular/material/menu';
import { MatSidenav, MatSidenavModule } from '@angular/material/sidenav';
import { MatToolbarModule } from '@angular/material/toolbar';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { filter, map } from 'rxjs';
import { AuthStore } from './core/auth/auth.store';
import { Role } from './core/models';
import { CartStore } from './core/state/cart.store';

export interface NavItem {
  label: string;
  path: string;
  roles?: Role[];
}

const STAFF: Role[] = ['OPERATOR', 'ADMIN'];

/** Role-aware navigation. UX only: hiding a link is not access control (the backend enforces every permission). */
export const NAV_ITEMS: NavItem[] = [
  { label: 'Dashboard', path: '/dashboard' },
  { label: 'Catalog', path: '/catalog' },
  { label: 'Orders', path: '/orders' },
  { label: 'New order', path: '/orders/new' },
  { label: 'Inventory', path: '/inventory', roles: STAFF },
  { label: 'Fulfillment', path: '/fulfillment', roles: STAFF },
  { label: 'Failed orders', path: '/failed-orders', roles: STAFF },
  { label: 'Operations', path: '/ops', roles: STAFF },
  { label: 'Audit history', path: '/audit', roles: STAFF },
  { label: 'Notifications', path: '/notifications' },
];

export function visibleNav(items: NavItem[], roles: readonly Role[]): NavItem[] {
  return items.filter((i) => !i.roles || i.roles.some((r) => roles.includes(r)));
}

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, MatToolbarModule, MatSidenavModule, MatListModule, MatButtonModule, MatMenuModule],
  templateUrl: './app.html',
  styleUrl: './app.scss',
})
export class App {
  protected readonly auth = inject(AuthStore);
  protected readonly cart = inject(CartStore);
  private readonly router = inject(Router);
  private readonly bp = inject(BreakpointObserver);

  protected readonly sidenav = viewChild(MatSidenav);
  protected readonly isNarrow = toSignal(this.bp.observe('(max-width: 900px)').pipe(map((s) => s.matches)), { initialValue: false });
  protected readonly navOpen = signal(true);
  protected readonly nav = computed(() => visibleNav(NAV_ITEMS, this.auth.roles()));
  protected readonly roleLabel = computed(() => this.auth.roles().join(', '));

  constructor() {
    // Accessibility: after each navigation move focus to the page heading (or main) and close the drawer on mobile.
    this.router.events.pipe(filter((e) => e instanceof NavigationEnd)).subscribe(() => {
      if (this.isNarrow()) void this.sidenav()?.close();
      setTimeout(() => {
        const target = document.getElementById('page-title') ?? document.getElementById('main');
        target?.focus({ preventScroll: false });
      }, 0);
    });
  }

  protected toggleNav(): void {
    void this.sidenav()?.toggle();
  }

  protected logout(): void {
    this.auth.logout('user');
  }
}
