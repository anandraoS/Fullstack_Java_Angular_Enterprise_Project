import type { UseFormRegisterReturn } from 'react-hook-form';

/** react-hook-form `register()` result -> MUI TextField props (MUI wants `inputRef`, not `ref`). */
export function rhf(reg: UseFormRegisterReturn) {
  const { ref, ...rest } = reg;
  return { ...rest, inputRef: ref };
}
