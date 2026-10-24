import { Component } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { RouterLink } from '@angular/router';
import { PageHeader } from '../../shared/components/page-header';

@Component({
  selector: 'app-forbidden',
  imports: [PageHeader, MatButtonModule, RouterLink],
  template: `
    <app-page-header title="Not allowed" subtitle="Your role does not include this screen." />
    <p>
      Navigation hides screens you cannot use, and the server enforces permissions on every request regardless.
    </p>
    <a mat-flat-button routerLink="/dashboard">Back to dashboard</a>
  `,
})
export class Forbidden {}

@Component({
  selector: 'app-not-found',
  imports: [PageHeader, MatButtonModule, RouterLink],
  template: `
    <app-page-header title="Page not found" subtitle="That address does not exist." />
    <a mat-flat-button routerLink="/dashboard">Back to dashboard</a>
  `,
})
export class NotFound {}
