import { useEffect, useRef, type ReactNode } from 'react';

/**
 * Page title block. The h1 is focusable and receives focus when the page mounts, so route changes announce the new
 * page to screen-reader users (the Angular app does the same after each navigation). Also sets document.title.
 */
export function PageHeader({ title, subtitle, children }: { title: string; subtitle?: string; children?: ReactNode }) {
  const ref = useRef<HTMLHeadingElement>(null);
  useEffect(() => {
    document.title = `${title} - OMS Console`;
  }, [title]);
  useEffect(() => {
    ref.current?.focus({ preventScroll: false });
  }, []);
  return (
    <header className="page-header">
      <div>
        <h1 id="page-title" tabIndex={-1} ref={ref}>
          {title}
        </h1>
        {subtitle ? <p className="subtitle">{subtitle}</p> : null}
      </div>
      <div className="actions">{children}</div>
    </header>
  );
}
