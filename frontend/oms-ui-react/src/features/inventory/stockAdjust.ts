import { z } from 'zod';

/** Parse helper: '' / non-numeric gives NaN. */
const num = (v: string) => (v.trim() === '' ? NaN : Number(v));

/**
 * Zod schema for the stock adjustment dialog. The raw input is a string; messages mirror the Angular dialog.
 * The result must not drop on-hand stock below what is already reserved (the server enforces this too).
 */
export function stockAdjustSchema(onHand: number, reserved: number) {
  return z.object({
    delta: z.string().superRefine((v, ctx) => {
      const n = num(v);
      if (Number.isNaN(n)) return ctx.addIssue({ code: 'custom', message: 'Enter a number.' });
      if (!Number.isInteger(n)) return ctx.addIssue({ code: 'custom', message: 'Whole numbers only.' });
      if (n === 0) return ctx.addIssue({ code: 'custom', message: 'The change must not be zero.' });
      if (onHand + n < reserved) ctx.addIssue({ code: 'custom', message: 'Stock cannot fall below the reserved quantity.' });
    }),
    reason: z.string().trim().min(5, 'A reason of at least 5 characters is required for the audit trail.').max(200, 'At most 200 characters.'),
  });
}

export type StockAdjustValues = z.infer<ReturnType<typeof stockAdjustSchema>>;
