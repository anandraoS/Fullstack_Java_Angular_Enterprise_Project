import AppBar from '@mui/material/AppBar';
import Button from '@mui/material/Button';
import Drawer from '@mui/material/Drawer';
import List from '@mui/material/List';
import ListItemButton from '@mui/material/ListItemButton';
import ListItemText from '@mui/material/ListItemText';
import Menu from '@mui/material/Menu';
import MenuItem from '@mui/material/MenuItem';
import Toolbar from '@mui/material/Toolbar';
import useMediaQuery from '@mui/material/useMediaQuery';
import { Suspense, useRef, useState } from 'react';
import { Link, NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { ErrorBoundary } from '../components/ErrorBoundary';
import { LoadingOverlay } from '../components/feedback';
import { STAFF } from '../components/guards';
import { useAppDispatch, useAppSelector } from '../hooks/redux';
import type { Role } from '../models';
import { loggedOut, selectIsAuthenticated, selectRoles, selectUsername } from '../store/authSlice';
import { selectCartCount } from '../store/cartSlice';
import { mobileNavToggled, selectMobileNavOpen } from '../store/uiSlice';

export interface NavItem {
  label: string;
  path: string;
  roles?: Role[];
}

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

const DRAWER_WIDTH = 220;

export function AppShell() {
  const dispatch = useAppDispatch();
  const navigate = useNavigate();
  const location = useLocation();
  const authed = useAppSelector(selectIsAuthenticated);
  const username = useAppSelector(selectUsername);
  const roles = useAppSelector(selectRoles);
  const cartCount = useAppSelector(selectCartCount);
  const mobileOpen = useAppSelector(selectMobileNavOpen);
  const wide = useMediaQuery('(min-width:901px)', { noSsr: true });
  const [menuAnchor, setMenuAnchor] = useState<HTMLElement | null>(null);
  const mainRef = useRef<HTMLElement>(null);
  const nav = visibleNav(NAV_ITEMS, roles);

  const logout = () => {
    setMenuAnchor(null);
    dispatch(loggedOut('user'));
    navigate('/login');
  };

  const navList = (
    <nav aria-label="Main navigation">
      <List>
        {nav.map((item) => (
          <ListItemButton
            key={item.path}
            component={NavLink}
            to={item.path}
            end={item.path === '/orders'}
            onClick={() => dispatch(mobileNavToggled(false))}
            sx={{ '&.active': { bgcolor: 'action.selected', fontWeight: 700 } }}
          >
            <ListItemText primary={item.label} />
          </ListItemButton>
        ))}
      </List>
    </nav>
  );

  return (
    <>
      <a
        className="skip-link"
        href="#main"
        onClick={(e) => {
          e.preventDefault();
          mainRef.current?.focus();
        }}
      >
        Skip to main content
      </a>
      <AppBar position="static" color="primary" elevation={1}>
        <Toolbar sx={{ gap: 1 }}>
          {authed && !wide && (
            <Button color="inherit" aria-label="Toggle navigation menu" aria-expanded={mobileOpen} onClick={() => dispatch(mobileNavToggled(undefined))}>
              Menu
            </Button>
          )}
          <span className="brand">OMS Console</span>
          <span className="demo-tag">synthetic demo</span>
          <span style={{ flex: 1 }} />
          {authed && cartCount > 0 && (
            <Button color="inherit" component={Link} to="/orders/new?fromCart=1" aria-label={`Cart: ${cartCount} items, go to order form`}>
              Cart ({cartCount})
            </Button>
          )}
          {authed && (
            <>
              <Button
                color="inherit"
                aria-label={`Account menu for ${username}`}
                aria-haspopup="menu"
                aria-expanded={menuAnchor !== null}
                onClick={(e) => setMenuAnchor(e.currentTarget)}
              >
                {username}
              </Button>
              <Menu anchorEl={menuAnchor} open={menuAnchor !== null} onClose={() => setMenuAnchor(null)}>
                <div className="menu-info" role="presentation">
                  Roles: {roles.join(', ')}
                </div>
                <MenuItem onClick={logout}>Sign out</MenuItem>
              </Menu>
            </>
          )}
        </Toolbar>
      </AppBar>

      <div className="container">
        {authed && (
          <Drawer
            variant={wide ? 'permanent' : 'temporary'}
            open={wide || mobileOpen}
            onClose={() => dispatch(mobileNavToggled(false))}
            ModalProps={{ keepMounted: false }}
            sx={wide ? { width: DRAWER_WIDTH, flexShrink: 0, '& .MuiDrawer-paper': { width: DRAWER_WIDTH, position: 'relative', top: 0 } } : { '& .MuiDrawer-paper': { width: DRAWER_WIDTH } }}
          >
            {navList}
          </Drawer>
        )}
        <main id="main" ref={mainRef} tabIndex={-1} className="content">
          <ErrorBoundary resetKey={location.pathname}>
            <Suspense fallback={<div className="rel" style={{ minHeight: 120 }}><LoadingOverlay active /></div>}>
              <Outlet />
            </Suspense>
          </ErrorBoundary>
        </main>
      </div>
    </>
  );
}
