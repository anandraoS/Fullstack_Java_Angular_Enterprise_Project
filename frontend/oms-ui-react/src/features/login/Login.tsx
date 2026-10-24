import { zodResolver } from '@hookform/resolvers/zod';
import Button from '@mui/material/Button';
import TextField from '@mui/material/TextField';
import { useEffect, useRef, useState } from 'react';
import { useForm } from 'react-hook-form';
import { useSearchParams } from 'react-router-dom';
import { z } from 'zod';
import { ErrorBanner } from '../../components/feedback';
import { genericSignInError, normalizeUnknown, type ApiError } from '../../core/apiError';
import { useAppDispatch } from '../../hooks/redux';
import { useLoginMutation } from '../../store/api';
import { loginSucceeded } from '../../store/authSlice';

export const loginSchema = z.object({
  username: z.string().trim().min(1, 'Username is required.'),
  password: z.string().min(1, 'Password is required.'),
});
type LoginValues = z.infer<typeof loginSchema>;

export default function Login() {
  const dispatch = useAppDispatch();
  const [params] = useSearchParams();
  const [login, { isLoading }] = useLoginMutation();
  const [error, setError] = useState<ApiError | null>(null);
  const heading = useRef<HTMLHeadingElement>(null);
  const {
    register,
    handleSubmit,
    resetField,
    setFocus,
    formState: { errors },
  } = useForm<LoginValues>({ resolver: zodResolver(loginSchema), defaultValues: { username: '', password: '' }, mode: 'onTouched' });
  const username = register('username');
  const password = register('password');

  useEffect(() => {
    document.title = 'Sign in - OMS Console';
    heading.current?.focus();
  }, []);

  const onSubmit = handleSubmit(async (v) => {
    if (isLoading) return;
    setError(null);
    try {
      const res = await login({ username: v.username.trim(), password: v.password }).unwrap();
      // GuestOnly then redirects to the (validated) returnUrl or the dashboard.
      dispatch(loginSucceeded(res));
    } catch (e) {
      // do not reveal whether the username or the password was wrong
      setError(genericSignInError(normalizeUnknown(e)));
      resetField('password', { defaultValue: '' });
      setFocus('password');
    }
  });

  return (
    <div className="login-wrap">
      <form className="card" onSubmit={onSubmit} noValidate aria-labelledby="login-title">
        <h1 id="login-title" tabIndex={-1} ref={heading} style={{ margin: '0 0 4px', fontSize: '1.5rem', outline: 'none' }}>
          Sign in
        </h1>
        <p className="muted">Development sign-in. Demo users: customer1, customer2, operator1, admin1. Ask your administrator for the demo password.</p>
        {params.get('reason') === 'expired' && (
          <p className="notice" role="status">
            Your session has expired. Please sign in again.
          </p>
        )}
        <ErrorBanner error={error} retryable={false} />
        <TextField
          label="Username"
          autoComplete="username"
          required
          error={!!errors.username}
          helperText={errors.username?.message}
          name={username.name}
          onChange={username.onChange}
          onBlur={username.onBlur}
          inputRef={username.ref}
        />
        <TextField
          label="Password"
          type="password"
          autoComplete="current-password"
          required
          error={!!errors.password}
          helperText={errors.password?.message}
          name={password.name}
          onChange={password.onChange}
          onBlur={password.onBlur}
          inputRef={password.ref}
        />
        <Button type="submit" variant="contained" disabled={isLoading}>
          {isLoading ? 'Signing in...' : 'Sign in'}
        </Button>
      </form>
    </div>
  );
}
