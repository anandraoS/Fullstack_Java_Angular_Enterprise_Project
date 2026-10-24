import { Component, input } from '@angular/core';

/** Page title block; the h1 is focusable so route changes can move focus there for screen-reader users. */
@Component({
  selector: 'app-page-header',
  template: `
    <header class="page-header">
      <div>
        <h1 id="page-title" tabindex="-1">{{ title() }}</h1>
        @if (subtitle()) {
          <p class="subtitle">{{ subtitle() }}</p>
        }
      </div>
      <div class="actions"><ng-content /></div>
    </header>
  `,
  styles: `
    .page-header { display: flex; flex-wrap: wrap; align-items: flex-start; justify-content: space-between; gap: 12px; margin-bottom: 16px; }
    h1 { margin: 0; font-size: 1.6rem; font-weight: 600; outline: none; }
    .subtitle { margin: 4px 0 0; color: var(--oms-text-muted); }
    .actions { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }
  `,
})
export class PageHeader {
  readonly title = input.required<string>();
  readonly subtitle = input<string>('');
}
