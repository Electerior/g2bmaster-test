import { beforeEach, describe, expect, it, vi } from 'vitest';

const post = vi.fn();
vi.mock('@/lib/apiClient', () => ({
  post,
  get: vi.fn(),
  put: vi.fn(),
  del: vi.fn(),
}));

const { analyzeDeal } = await import('./analysis');

/**
 * 여기서 지키는 것은 **마감의 순서**다.
 *
 * 서버 쪽 사슬은 `LLM(100s) < AI(120s) < 리스(300s)` 로 start_all.sh 가 검사한다.
 * 그런데 브라우저가 그 사슬 밖에 있었다 — apiClient 기본값 60초가 가장 짧은 마감이라,
 * 서버는 멀쩡히 일하는데 화면만 포기해 "분석 실패"로 보였다(20건 동시 재현에서 7건).
 */
describe('analyzeDeal', () => {
  beforeEach(() => {
    post.mockReset();
    post.mockResolvedValue({});
  });

  it('깊은 분석의 마감은 백엔드 예산(120초)보다 길다', async () => {
    await analyzeDeal({ item: {}, deep: true });

    const [, , config] = post.mock.calls[0];
    expect(config?.timeout).toBeGreaterThan(120_000);
  });

  it('규격서를 지목한 재분석도 같은 마감을 쓴다', async () => {
    await analyzeDeal({ item: {}, deep: true, specFileUrl: 'https://example.test/spec.hwp' });

    expect(post.mock.calls[0][2]?.timeout).toBeGreaterThan(120_000);
  });

  it('deep 을 안 주면 깊은 분석으로 본다 — 백엔드 기본값이 true 다', async () => {
    await analyzeDeal({ item: {} });

    expect(post.mock.calls[0][2]?.timeout).toBeGreaterThan(120_000);
  });

  it('얕은 분석은 기본 마감을 그대로 쓴다', async () => {
    await analyzeDeal({ item: {}, deep: false });

    // config 를 주지 않으면 apiClient 의 기본값(60초)이다. 얕은 분석은 첨부를 안 연다.
    expect(post.mock.calls[0][2]).toBeUndefined();
  });

  it('깊은 분석은 동시에 몰리지 않는다', async () => {
    let inFlight = 0;
    let peak = 0;
    post.mockImplementation(
      () =>
        new Promise((resolve) => {
          inFlight += 1;
          peak = Math.max(peak, inFlight);
          setTimeout(() => {
            inFlight -= 1;
            resolve({});
          }, 0);
        }),
    );

    await Promise.all(
      Array.from({ length: 20 }, () => analyzeDeal({ item: {}, deep: true })),
    );

    // 20건을 한꺼번에 던져도 실제로 나가는 것은 몇 건뿐이다. 브라우저 큐에서 기다리는
    // 동안에도 axios 마감이 흐르기 때문에, 여기서 붙잡지 않으면 뒤쪽이 통째로 깨진다.
    expect(peak).toBeLessThanOrEqual(4);
    expect(post).toHaveBeenCalledTimes(20);
  });

  it('얕은 분석은 제한기를 거치지 않는다', async () => {
    let inFlight = 0;
    let peak = 0;
    post.mockImplementation(
      () =>
        new Promise((resolve) => {
          inFlight += 1;
          peak = Math.max(peak, inFlight);
          setTimeout(() => {
            inFlight -= 1;
            resolve({});
          }, 0);
        }),
    );

    await Promise.all(
      Array.from({ length: 10 }, () => analyzeDeal({ item: {}, deep: false })),
    );

    expect(peak).toBe(10);
  });
});
