import { describe, expect, it } from 'vitest';
import { normalizeIotError } from './iot-errors';

describe('IoT safe error normalization', () => {
  it.each([
    { data: { code: 1070000409, msg: '看板版本冲突' } },
    { response: { data: { code: 1070000409, msg: '看板版本冲突' } } },
    { code: 1070000409, msg: '看板版本冲突' },
    {
      message: 'Request failed',
      data: { code: 1070000409, msg: '看板版本冲突' },
      response: {
        headers: { Authorization: 'secret' },
        data: { code: 1070000409, msg: '看板版本冲突' },
      },
    },
  ])(
    'extracts business code/message from standard request-client rejection %j',
    (cause) => {
      const result = normalizeIotError(cause);
      expect(result).toBeInstanceOf(Error);
      expect(result.code).toBe(1070000409);
      expect(result.message).toBe('看板版本冲突');
      expect(JSON.stringify(result)).not.toContain('secret');
    },
  );
  it('preserves plain error messages and abort semantics', () => {
    expect(normalizeIotError(new Error('网络不可用')).message).toBe(
      '网络不可用',
    );
    expect(
      normalizeIotError(new DOMException('已取消', 'AbortError')).name,
    ).toBe('AbortError');
    expect(
      normalizeIotError({ code: 'ERR_CANCELED', message: 'cancelled' }).name,
    ).toBe('AbortError');
  });
  it('does not stringify arbitrary response objects', () => {
    for (const value of [
      null,
      undefined,
      'secret',
      { response: { data: { token: 'secret', password: 'secret' } } },
    ])
      expect(normalizeIotError(value).message).toBe('请求失败，请稍后重试');
    expect(
      normalizeIotError({ data: { code: 403, msg: 'a'.repeat(1000) } }).message,
    ).toHaveLength(500);
  });
});
