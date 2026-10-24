import { useEffect } from 'react';
import { loggedOut, msUntilExpiry, selectSession } from '../store/authSlice';
import { useAppDispatch, useAppSelector } from './redux';

/** Logs the user out ("expired") when the token's lifetime ends. The timer is cleared when the session changes. */
export function useSessionExpiry(): void {
  const dispatch = useAppDispatch();
  const session = useAppSelector(selectSession);
  const expiresAt = session?.expiresAt;
  useEffect(() => {
    if (expiresAt === undefined) return;
    const t = setTimeout(() => dispatch(loggedOut('expired')), msUntilExpiry(expiresAt, Date.now()));
    return () => clearTimeout(t);
  }, [expiresAt, dispatch]);
}
