import { Component, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { RouterLink } from '@angular/router';
import { debounceTime } from 'rxjs';
import { InventoryApi, ProductFilter } from '../../core/api/apis';
import { Product } from '../../core/models';
import { Toaster } from '../../core/notify/toaster';
import { CartStore } from '../../core/state/cart.store';
import { ListStore } from '../../core/state/list-store';
import { Column, DataTable } from '../../shared/components/data-table';
import { PageHeader } from '../../shared/components/page-header';

@Component({
  selector: 'app-catalog',
  imports: [PageHeader, DataTable, ReactiveFormsModule, MatFormFieldModule, MatInputModule, MatCheckboxModule, MatButtonModule, RouterLink],
  template: `
    <app-page-header title="Product catalog" subtitle="Browse products and add them to your cart.">
      <a mat-stroked-button routerLink="/orders/new" [queryParams]="{ fromCart: 1 }">
        Go to order form ({{ cart.count() }} in cart)
      </a>
    </app-page-header>

    <form class="filters" [formGroup]="form" role="search" aria-label="Filter products" (ngSubmit)="apply()">
      <mat-form-field appearance="outline" class="grow">
        <mat-label>Search name or SKU</mat-label>
        <input matInput type="search" formControlName="q" />
      </mat-form-field>
      <mat-form-field appearance="outline">
        <mat-label>Category</mat-label>
        <input matInput formControlName="category" />
      </mat-form-field>
      <div class="btns"><mat-checkbox formControlName="inStock">In stock only</mat-checkbox></div>
    </form>

    <app-data-table
      caption="Products"
      [columns]="columns"
      [rows]="store.items()"
      [loading]="store.loading()"
      [error]="store.error()"
      [total]="store.total()"
      [page]="store.query().page"
      [size]="store.query().size"
      [sort]="store.query().sort"
      [actions]="addTpl"
      emptyTitle="No products match"
      (pageChange)="store.setPage($event.page, $event.size)"
      (sortChange)="store.toggleSort($event)"
      (retry)="store.refresh()"
    />

    <ng-template #addTpl let-p>
      <button mat-stroked-button type="button" [attr.aria-label]="'Add ' + p.name + ' to cart'" [disabled]="p.available < 1" (click)="add(p)">
        Add to cart
      </button>
    </ng-template>
  `,
})
export class Catalog {
  private readonly api = inject(InventoryApi);
  protected readonly cart = inject(CartStore);
  private readonly toaster = inject(Toaster);
  private readonly fb = inject(FormBuilder).nonNullable;


  protected readonly form = this.fb.group({ q: [''], category: [''], inStock: [false] });
  protected readonly store = new ListStore<Product, ProductFilter>(
    (q) =>
      this.api.products({
        ...q.filters,
        inStock: q.filters.inStock ? true : '',
        page: q.page,
        size: q.size,
        sort: q.sort,
      }),
    { q: '', category: '', inStock: false },
    20,
    'sku,asc',
  );

  protected readonly columns: Column<Product>[] = [
    { key: 'sku', header: 'SKU', value: (p) => p.sku, sortField: 'sku' },
    { key: 'name', header: 'Name', value: (p) => p.name, sortField: 'name' },
    { key: 'category', header: 'Category', value: (p) => p.category, sortField: 'category' },
    { key: 'price', header: 'Price (USD)', value: (p) => p.price, type: 'money', sortField: 'price' },
    { key: 'available', header: 'Available', value: (p) => p.available, type: 'number' },
  ];

  constructor() {
    this.form.valueChanges.pipe(debounceTime(300), takeUntilDestroyed()).subscribe(() => this.apply());
  }

  protected apply(): void {
    const v = this.form.getRawValue();
    this.store.setFilters({ q: v.q.trim(), category: v.category.trim(), inStock: v.inStock });
  }

  protected add(p: Product): void {
    this.cart.add(p);
    this.toaster.success(`${p.sku} added to cart`);
  }
}
