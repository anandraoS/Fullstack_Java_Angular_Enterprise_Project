import Button from '@mui/material/Button';
import { Component, type ErrorInfo, type ReactNode } from 'react';

interface State {
  error: Error | null;
}

/**
 * Last line of defence for rendering bugs (the Angular app has the global ErrorHandler): a crashed screen shows a
 * recoverable message instead of a blank page. `resetKey` (the current route) clears the error on navigation.
 */
export class ErrorBoundary extends Component<{ children: ReactNode; resetKey?: string; onError?: (e: Error, info: ErrorInfo) => void }, State> {
  override state: State = { error: null };

  static getDerivedStateFromError(error: Error): State {
    return { error };
  }

  override componentDidCatch(error: Error, info: ErrorInfo): void {
    this.props.onError?.(error, info);
  }

  override componentDidUpdate(prev: { resetKey?: string }): void {
    if (this.state.error && prev.resetKey !== this.props.resetKey) this.setState({ error: null });
  }

  override render() {
    if (!this.state.error) return this.props.children;
    return (
      <div className="banner" role="alert">
        <div className="body">
          <strong>Something went wrong while showing this page.</strong>
          <div className="ref">Try again, or reload the page. If it keeps happening, tell support what you were doing.</div>
        </div>
        <Button variant="outlined" size="small" onClick={() => this.setState({ error: null })}>
          Try again
        </Button>
      </div>
    );
  }
}
