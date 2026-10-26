/** Normalize only safe business error fields; never expose response bodies/headers. */
export function normalizeIotError(cause: unknown): Error & { code?: number } {
  const object = (value: unknown): Record<string, unknown> | undefined =>
    value !== null && typeof value === 'object'
      ? (value as Record<string, unknown>)
      : undefined;
  const root = object(cause);
  const response = object(root?.response);
  const candidates = [object(root?.data), object(response?.data), root];
  const business = candidates.find(
    (value) =>
      value && typeof value.code === 'number' && Number.isFinite(value.code),
  );
  const message = [business?.msg, business?.message, root?.message].find(
    (value): value is string =>
      typeof value === 'string' && value.trim().length > 0,
  );
  const error: Error & { code?: number } = new Error(
    message?.slice(0, 500) ?? '请求失败，请稍后重试',
  );
  if (typeof business?.code === 'number') error.code = business.code;
  if (root?.name === 'AbortError' || root?.code === 'ERR_CANCELED')
    error.name = 'AbortError';
  return error;
}
