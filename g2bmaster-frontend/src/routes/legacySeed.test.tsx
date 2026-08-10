/*
 * 옛 주소로 들어와도 기본 조회 기간(최근 30일)이 살아남는가.
 *
 * 셸이 검색창을 <Outlet/> 위에 그리므로 검색창의 효과가 리다이렉트보다 **먼저** 돈다.
 * 경유지에서 기간을 심으면 곧이어 오는 리다이렉트가 그것을 지우는데, '심었다'는 표시(ref)는
 * 남아 목적지에서 다시 심지 않는다. 그러면 기간 없는 질의가 나가 색인 전체를 훑는다.
 * 서버에도 기본 기간이 없으므로(BidNoticeQueryBuilder.createdBetween 은 값이 있을 때만 건다)
 * 이건 조용히 비싸지는 종류의 버그다.
 */
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Outlet, Route, Routes, useLocation } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { SearchHeader } from '@/features/search/SearchHeader';
import { LegacyNoticeRedirect } from './LegacyNoticeRedirect';
import { LEGACY_NOTICE_ROUTES, ROUTES } from './routePaths';

vi.mock('@/lib/apiClient', () => ({
  get: vi.fn(() => new Promise(() => {})),
  post: vi.fn(),
  del: vi.fn(),
  apiClient: {},
  ApiError: class ApiError extends Error {},
}));

function Landed() {
  const { pathname, search } = useLocation();
  return <span data-testid="landed">{`${pathname}${search}`}</span>;
}

/** App.tsx 와 같은 배치 — 검색창이 Outlet 위에 있다. 이 순서가 문제의 원인이었다. */
function Shell() {
  return (
    <>
      <SearchHeader />
      <Outlet />
    </>
  );
}

function landOn(entry: string): URLSearchParams {
  render(
    <MemoryRouter initialEntries={[entry]}>
      <Routes>
        <Route element={<Shell />}>
          {LEGACY_NOTICE_ROUTES.map((legacy) => (
            <Route
              key={legacy.path}
              path={legacy.path}
              element={<LegacyNoticeRedirect category={legacy.category} />}
            />
          ))}
          <Route path={ROUTES.noticeSearch} element={<Landed />} />
        </Route>
      </Routes>
    </MemoryRouter>,
  );
  const landed = screen.getByTestId('landed').textContent!;
  return new URL(landed, 'http://x').searchParams;
}

/** 기간이 실제 날짜 한 쌍으로 채워졌는지. */
function expectSeededRange(params: URLSearchParams) {
  const from = params.get('from');
  const to = params.get('to');
  expect(from).toMatch(/^\d{4}-\d{2}-\d{2}$/);
  expect(to).toMatch(/^\d{4}-\d{2}-\d{2}$/);
  expect(from! <= to!).toBe(true);
}

describe('기본 조회 기간 심기', () => {
  it('통합 검색으로 바로 들어오면 최근 30일이 잡힌다', () => {
    render(
      <MemoryRouter initialEntries={[ROUTES.noticeSearch]}>
        <Routes>
          <Route element={<Shell />}>
            <Route path={ROUTES.noticeSearch} element={<Landed />} />
          </Route>
        </Routes>
      </MemoryRouter>,
    );
    expectSeededRange(new URL(screen.getByTestId('landed').textContent!, 'http://x').searchParams);
  });

  it.each(LEGACY_NOTICE_ROUTES.map((r) => r.path))('옛 주소 %s 로 들어와도 기간이 살아남는다', (path) => {
    expectSeededRange(landOn(path));
  });

  it('옛 주소로 들어와도 단계 필터와 사용자 조건이 함께 남는다', () => {
    const params = landOn('/notices/bid-plan?and=노트북');
    expect(params.get('cat')).toBe('계획');
    expect(params.get('and')).toBe('노트북');
    expectSeededRange(params);
  });
});
