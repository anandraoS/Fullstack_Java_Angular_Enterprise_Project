import { useEffect, useState } from 'react';

/**
 * Returns `value` after it has been stable for `ms` milliseconds (filter inputs -> API calls).
 * Works for JSON-serialisable values (strings, numbers, plain objects): equality is by serialised content, so passing a
 * fresh object literal on every render does not restart the timer forever.
 */
export function useDebouncedValue<T>(value: T, ms = 300): T {
  const [debounced, setDebounced] = useState(value);
  const key = JSON.stringify(value);
  useEffect(() => {
    const t = setTimeout(() => setDebounced(JSON.parse(key) as T), ms);
    return () => clearTimeout(t);
  }, [key, ms]);
  return debounced;
}
