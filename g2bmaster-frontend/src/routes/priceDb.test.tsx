/*
 * 단가 DB 라우팅 잠금 — /price-db 가 PriceDatabaseScreen 을 그리고, 탭 스트립에도 나온다.
 *
 * apiClient 의 get 만 갈아 끼운다(팬아웃/인터셉터는 이 테스트의 대상이 아니다).
 */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';

vi.mock('@/lib/apiClient', () => ({
  // 조회는 영원히 대기시킨다 — 화면 뼈대(제목)만 확인하면 되므로 데이터는 필요 없다.
  get: vi.fn(() => new Promise(() => {})),
  post: vi.fn(),
  put: vi.fn(),
  del: vi.fn(),
  apiClient: {},
  ApiError: class ApiError extends Error {},
}));

const { AppRouter } = await import('./router');
const { ROUTES, TAB_ITEMS } = await import('./routePaths');

function renderAt(pathname: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[pathname]}>
        <AppRouter />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('단가 DB 라우팅', () => {
  it('/price-db 는 PriceDatabaseScreen 을 그린다', async () => {
    renderAt(ROUTES.priceDb);
    // 탭 라벨과 같은 문자열이 헤더에도 있으므로 heading 으로 좁힌다.
    expect(await screen.findByRole('heading', { name: '단가 DB' })).toBeInTheDocument();
  });

  it('탭 스트립에 단가 DB 가 실제 화면으로(준비 배지 없이) 실린다', () => {
    const tab = TAB_ITEMS.find((t) => t.path === ROUTES.priceDb);
    expect(tab).toBeDefined();
    expect(tab?.kind).toBe('price-db');
    expect(tab?.label).toBe('단가 DB');
    expect(tab?.notReady).toBeUndefined();
  });

  it('AI 수주 데스크는 더 이상 준비 중이 아니다', () => {
    const tab = TAB_ITEMS.find((t) => t.path === ROUTES.dealRadar);
    expect(tab?.notReady).toBeUndefined();
  });
});
