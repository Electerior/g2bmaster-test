import { describe, expect, it } from 'vitest';
import { createLimiter } from './taskQueue';

/** 손으로 풀 수 있는 promise. 언제 시작되고 언제 끝나는지를 시험이 정한다. */
function deferred<T = void>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

describe('createLimiter', () => {
  it('한도를 넘는 작업은 시작조차 하지 않는다', async () => {
    const run = createLimiter(2);
    const gates = [deferred(), deferred(), deferred()];
    const started: number[] = [];

    gates.forEach((gate, i) => {
      void run(() => {
        started.push(i);
        return gate.promise;
      });
    });

    // 세 번째는 아직 시작하면 안 된다 — 시작하면 그 순간부터 마감이 흐른다.
    expect(started).toEqual([0, 1]);

    gates[0].resolve();
    await Promise.resolve();
    await Promise.resolve();
    expect(started).toEqual([0, 1, 2]);
  });

  it('앞 작업이 실패해도 슬롯을 돌려준다', async () => {
    const run = createLimiter(1);
    const first = run(() => Promise.reject(new Error('boom')));
    await expect(first).rejects.toThrow('boom');

    await expect(run(() => Promise.resolve('ok'))).resolves.toBe('ok');
  });

  it('작업이 동기적으로 던져도 큐가 멈추지 않는다', async () => {
    const run = createLimiter(1);
    await expect(
      run(() => {
        throw new Error('sync');
      }),
    ).rejects.toThrow('sync');

    // 슬롯이 잠겼다면 이 호출은 영원히 대기한다.
    await expect(run(() => Promise.resolve('통과'))).resolves.toBe('통과');
  });

  it('결과와 순서를 보존한다', async () => {
    const run = createLimiter(2);
    const results = await Promise.all([1, 2, 3, 4, 5].map((n) => run(() => Promise.resolve(n * 2))));
    expect(results).toEqual([2, 4, 6, 8, 10]);
  });

  it('한도가 0 이하면 만들지 않는다', () => {
    expect(() => createLimiter(0)).toThrow();
  });
});
