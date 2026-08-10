/*
 * 통합 검색으로 갈아 끼우면서 주소 모양이 바뀐 탓에 생긴 세 가지 회귀에 대한 잠금 장치.
 * 셋 다 타입도 린트도 통과하면서 화면에서만 틀리는 종류라, 컴파일러가 잡아 주지 않는다.
 */
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { AppTabs } from '@/components/layout/AppTabs';
import { snapPerPage } from '@/components/table/perPage';
import { LegacyNoticeRedirect } from './LegacyNoticeRedirect';
import { DEFAULT_ROUTE, isTransitRoute, ROUTES } from './routePaths';

/** 착지한 주소를 화면에 심어 읽을 수 있게 한다. MemoryRouter 는 window.location 을 건드리지 않는다. */
function Landed() {
  const { pathname, search } = useLocation();
  return <span data-testid="landed">{`${pathname}${search}`}</span>;
}

describe('AppTabs 활성 표시', () => {
  function activeLabels(pathname: string): string[] {
    const { container } = render(
      <MemoryRouter initialEntries={[pathname]}>
        <AppTabs />
      </MemoryRouter>,
    );
    return [...container.querySelectorAll('.app-tab.active')].map((el) => el.textContent ?? '');
  }

  it('입찰 결과 화면에서 공고 검색 탭이 함께 켜지지 않는다', () => {
    // '/notices' 가 '/notices/bid-result' 의 접두라, end 없이는 둘 다 활성이 된다.
    expect(activeLabels(ROUTES.bidResult)).toEqual(['입찰 결과']);
  });

  it('공고 검색 화면에서는 그 탭만 켜진다', () => {
    expect(activeLabels(ROUTES.noticeSearch)).toEqual(['공고 검색']);
  });

  it('활성 링크는 하나만 aria-current 를 갖는다', () => {
    const { container } = render(
      <MemoryRouter initialEntries={[ROUTES.bidResult]}>
        <AppTabs />
      </MemoryRouter>,
    );
    expect(container.querySelectorAll('[aria-current="page"]')).toHaveLength(1);
  });
});

describe('LegacyNoticeRedirect', () => {
  function landOn(entry: string, path: string, category?: string): string {
    render(
      <MemoryRouter initialEntries={[entry]}>
        <Routes>
          <Route path={path} element={<LegacyNoticeRedirect category={category} />} />
          <Route path={ROUTES.noticeSearch} element={<Landed />} />
        </Routes>
      </MemoryRouter>,
    );
    return screen.getByTestId('landed').textContent!;
  }

  it('단계 필터를 붙여 통합 검색으로 넘기고, 사용자 조건은 들고 간다', () => {
    const landed = landOn('/notices/bid-plan?and=노트북&page=2', '/notices/bid-plan', '계획');
    const url = new URL(landed, 'http://x');
    expect(url.pathname).toBe(ROUTES.noticeSearch);
    expect(url.searchParams.get('cat')).toBe('계획');
    expect(url.searchParams.get('and')).toBe('노트북');
    expect(url.searchParams.get('page')).toBe('2');
  });

  it('입찰 공고는 단계를 걸지 않는다 — 걸면 마감된 같은 공고가 사라진다', () => {
    const landed = landOn('/notices/bid-announce', '/notices/bid-announce');
    expect(new URL(landed, 'http://x').searchParams.get('cat')).toBeNull();
  });

  it('이미 걸린 단계가 있으면 사용자가 고른 것이 이긴다', () => {
    const landed = landOn('/notices/bid-plan?cat=마감', '/notices/bid-plan', '계획');
    expect(new URL(landed, 'http://x').searchParams.get('cat')).toBe('마감');
  });
});

describe('경유지 판정', () => {
  it('기본 리다이렉트와 옛 표 주소를 모두 경유지로 본다', () => {
    // 경유지에서 기본 조회 기간을 심으면 곧이어 오는 리다이렉트가 그것을 지우고,
    // '심었다'는 표시만 남아 목적지에서는 다시 심지 않는다 — 기간 없이 색인 전체를 훑게 된다.
    expect(isTransitRoute('/')).toBe(true);
    expect(isTransitRoute('/notices/bid-plan')).toBe(true);
    expect(isTransitRoute('/notices/pre-spec')).toBe(true);
    expect(isTransitRoute('/notices/bid-announce')).toBe(true);
  });

  it('머무르는 화면은 경유지가 아니다', () => {
    expect(isTransitRoute(ROUTES.noticeSearch)).toBe(false);
    expect(isTransitRoute(ROUTES.bidResult)).toBe(false);
    expect(isTransitRoute(DEFAULT_ROUTE)).toBe(false);
  });
});

describe('snapPerPage', () => {
  it('선택지에 없는 값을 이하의 가장 큰 선택지로 끌어온다', () => {
    // 예전 '전체'(99999)를 담아 둔 링크. 서버도 500 으로 클램프하므로 표시와 실제가 맞는다.
    expect(snapPerPage(99999)).toBe(500);
    expect(snapPerPage(750)).toBe(500);
    expect(snapPerPage(120)).toBe(100);
  });

  it('선택지에 있는 값은 그대로 둔다', () => {
    for (const value of [20, 50, 100, 200, 500]) expect(snapPerPage(value)).toBe(value);
  });

  it('가장 작은 선택지보다 작으면 그것으로 올린다', () => {
    expect(snapPerPage(1)).toBe(20);
    expect(snapPerPage(Number.NaN)).toBe(20);
  });
});
