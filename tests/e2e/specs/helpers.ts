import { APIRequestContext, Page, expect } from '@playwright/test';

export const PASSWORD = process.env['E2E_DEMO_PASSWORD'] as string;
export const API_URL = (process.env['E2E_API_URL'] as string).replace(/\/+$/, '');
export const CUSTOMER_1 = 'c0000000-0000-0000-0000-000000000001';

export async function login(page: Page, username: string, password = PASSWORD): Promise<void> {
  await page.goto('/login');
  await page.getByLabel('Username').fill(username);
  await page.getByLabel('Password').fill(password);
  await page.getByRole('button', { name: 'Sign in' }).click();
}

export async function loginOk(page: Page, username: string): Promise<void> {
  await login(page, username);
  await expect(page.getByRole('heading', { name: 'Dashboard', level: 1 })).toBeVisible();
}

export async function logout(page: Page, username: string): Promise<void> {
  await page.getByRole('button', { name: new RegExp(`Account menu for ${username}`) }).click();
  await page.getByRole('menuitem', { name: 'Sign out' }).click();
  await expect(page.getByRole('heading', { name: 'Sign in', level: 1 })).toBeVisible();
}

export async function apiToken(request: APIRequestContext, username: string): Promise<string> {
  const res = await request.post(`${API_URL}/api/v1/auth/token`, { data: { username, password: PASSWORD } });
  expect(res.ok(), `token for ${username}`).toBeTruthy();
  return (await res.json()).accessToken as string;
}

export interface OrderInput {
  sku: string;
  quantity?: number;
  city?: string;
  line1?: string;
}

/** Fills the order form as a customer (customer is fixed) and returns without submitting. */
export async function fillOrderForm(page: Page, input: OrderInput): Promise<void> {
  await page.goto('/orders/new');
  await expect(page.getByRole('heading', { name: 'Create order', level: 1 })).toBeVisible();
  await page.getByTestId('product-0').click();
  await page.getByRole('option', { name: new RegExp(input.sku) }).click();
  await page.getByTestId('qty-0').fill(String(input.quantity ?? 1));
  await page.getByLabel('Address line').fill(input.line1 ?? '1 Test Street');
  await page.getByLabel('City').fill(input.city ?? 'Springfield');
  await page.getByLabel('Postal code').fill('12345');
  await page.getByLabel(/^Country/).fill('US');
}

/** Submits the form and returns the new order id from the detail URL. */
export async function placeOrder(page: Page, input: OrderInput): Promise<string> {
  await fillOrderForm(page, input);
  await page.getByRole('button', { name: 'Place order' }).click();
  await page.waitForURL(/\/orders\/[0-9a-f-]{36}$/);
  return page.url().split('/').pop() as string;
}

export const statusChip = (page: Page) => page.getByTestId('order-status');

export async function waitForStatus(page: Page, status: string | RegExp, timeout = 60_000): Promise<void> {
  await expect(statusChip(page)).toContainText(status, { timeout });
}
