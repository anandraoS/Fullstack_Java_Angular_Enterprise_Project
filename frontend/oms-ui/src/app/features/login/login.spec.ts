import { HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { SESSION_STORAGE_KEY } from '../../core/auth/auth.store';
import { API, httpTestProviders } from '../../testing/test-utils';
import { Login } from './login';

describe('Login component', () => {
  let fixture: ComponentFixture<Login>;
  let mock: HttpTestingController;
  let router: Router;

  beforeEach(() => {
    sessionStorage.clear();
    TestBed.configureTestingModule({ imports: [Login], providers: httpTestProviders() });
    mock = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
    spyOn(router, 'navigateByUrl').and.resolveTo(true);
    fixture = TestBed.createComponent(Login);
    fixture.detectChanges();
  });

  afterEach(() => {
    mock.verify();
    sessionStorage.clear();
  });

  const el = () => fixture.nativeElement as HTMLElement;
  function type(selector: string, value: string): void {
    const input = el().querySelector<HTMLInputElement>(selector)!;
    input.value = value;
    input.dispatchEvent(new Event('input'));
  }
  const submit = () => {
    el().querySelector<HTMLButtonElement>('button[type="submit"]')!.click();
    fixture.detectChanges();
  };

  it('has labelled fields and no prefilled credentials', () => {
    const inputs = el().querySelectorAll<HTMLInputElement>('input');
    expect(inputs.length).toBe(2);
    inputs.forEach((i) => expect(i.value).toBe(''));
    expect(el().querySelector('input[type="password"]')?.getAttribute('autocomplete')).toBe('current-password');
    expect(el().textContent).toContain('Username');
    expect(el().textContent).toContain('Password');
  });

  it('requires both fields before calling the API', () => {
    submit();
    expect(el().textContent).toContain('Username is required.');
    expect(el().textContent).toContain('Password is required.');
    mock.expectNone(`${API}/auth/token`);
  });

  it('signs in and navigates to the dashboard (or a safe returnUrl)', () => {
    fixture.componentRef.setInput('returnUrl', '/orders/abc');
    type('input[formcontrolname="username"]', 'operator1');
    type('input[formcontrolname="password"]', 'pw-from-test');
    submit();
    const req = mock.expectOne(`${API}/auth/token`);
    expect(req.request.body).toEqual({ username: 'operator1', password: 'pw-from-test' });
    req.flush({ accessToken: 't', tokenType: 'Bearer', expiresIn: 3600, username: 'operator1', roles: ['OPERATOR'], customerId: null });
    expect(router.navigateByUrl).toHaveBeenCalledWith('/orders/abc');
    expect(sessionStorage.getItem(SESSION_STORAGE_KEY)).not.toContain('pw-from-test');
  });

  it('shows a generic error on 401 without revealing which field was wrong, and clears the password', () => {
    type('input[formcontrolname="username"]', 'operator1');
    type('input[formcontrolname="password"]', 'wrong');
    submit();
    mock.expectOne(`${API}/auth/token`).flush({ code: 'UNAUTHENTICATED', detail: 'Bad password for operator1' }, { status: 401, statusText: 'Unauthorized' });
    fixture.detectChanges();
    const alert = el().querySelector('[role="alert"]');
    expect(alert?.textContent).toContain('Sign-in failed. Check your username and password.');
    expect(alert?.textContent).not.toContain('Bad password');
    expect(el().querySelector<HTMLInputElement>('input[type="password"]')?.value).toBe('');
    expect(router.navigateByUrl).not.toHaveBeenCalled();
  });

  it('shows the network message when the gateway is unreachable', () => {
    type('input[formcontrolname="username"]', 'a');
    type('input[formcontrolname="password"]', 'b');
    submit();
    mock.expectOne(`${API}/auth/token`).error(new ProgressEvent('error'), { status: 0, statusText: '' });
    fixture.detectChanges();
    expect(el().querySelector('[role="alert"]')?.textContent).toContain('Cannot reach the server');
  });

  it('announces session expiry politely', () => {
    fixture.componentRef.setInput('reason', 'expired');
    fixture.detectChanges();
    expect(el().querySelector('[role="status"]')?.textContent).toContain('session has expired');
  });
});
