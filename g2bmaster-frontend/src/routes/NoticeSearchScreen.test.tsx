/*
 * 공고 통합 검색 화면 — 계약이 요구하는 표시 규칙이 실제 렌더에서 지켜지는지.
 *
 * apiClient 의 get 만 갈아 끼운다. axios 인스턴스를 통째로 흉내 내면 인터셉터(오류 봉투 →
 * ApiError)까지 흉내 내야 하는데, 그건 이 테스트가 확인하려는 것이 아니다.
 */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { NoticeIndexItem } from '@/api/search';

const get = vi.fn();
vi.mock('@/lib/apiClient', () => ({
  get: (url: string) => get(url),
  post: vi.fn(),
  del: vi.fn(),
  apiClient: {},
  ApiError: class ApiError extends Error {},
}));

const { NoticeSearchScreen } = await import('./NoticeSearchScreen');

/** 조달청 대행 + 지역 있음 + 마감 있음. */
const DELEGATED: NoticeIndexItem = {
  id: 'R26BK01638523',
  noticeName: '2026년 노트북 및 모니터 구매',
  category: '입찰',
  businessDivision: '물품',
  region: '강원특별자치도',
  noticeInstitutionName: '조달청 강원지방조달청',
  demandInstitutionName: '강원대학교',
  createdDate: '2026-08-01T10:00:00',
  closeDate: '2026-09-12T11:00:00',
  officerName: '김조달',
  officerContact: '033-249-1234',
  dday: 37,
  estimatedPrice: 45000000,
  bodyPreview: '노트북컴퓨터 모니터 일반경쟁 적격심사',
};

/** 계획 단계 — 마감이 없어 dday·closeDate 필드가 아예 오지 않는다. 지역은 빈 문자열(전국). */
const PLAN: NoticeIndexItem = {
  id: '20260715001',
  noticeName: '스마트캠퍼스 통합관제 시스템 구축 발주계획',
  category: '계획',
  businessDivision: '용역',
  region: '',
  noticeInstitutionName: '한국전자통신연구원',
  demandInstitutionName: '한국전자통신연구원',
  createdDate: '2026-07-15T09:00:00',
  estimatedPrice: 1200000000,
};

/** 취소 공고 — 결과에 남기되 눈에 띄어야 한다. */
const CANCELLED: NoticeIndexItem = {
  id: 'R26BK01600001',
  noticeName: '청사 냉난방기 교체공사',
  category: '마감',
  state: '취소',
  businessDivision: '공사',
  region: '경기도,인천광역시',
  noticeInstitutionName: '수원시청',
  demandInstitutionName: '수원시청',
  createdDate: '2026-06-20T11:00:00',
  closeDate: '2026-07-01T11:00:00',
  dday: -36,
  estimatedPrice: 88000000,
};

const ITEMS = [DELEGATED, PLAN, CANCELLED];

function respond(url: string) {
  if (url.startsWith('/api/search/notices/status')) {
    return Promise.resolve({
      summary: [{ category: '입찰', count: 604, last_indexed_at: '2026-08-06T09:20:00' }],
    });
  }
  if (url.startsWith('/api/search/notices/facets')) {
    /*
     * 서버의 패싯은 목록과 한 WHERE 를 쓴다 — 조건에 category 가 실려 오면 GROUP BY 는
     * 그 값 한 줄만 돌려준다. 그 성질을 흉내 내야 축별 재조회가 실제로 도는지 확인된다.
     */
    const params = new URLSearchParams(url.split('?')[1] ?? '');
    const pickedCategory = params.get('category');
    const pickedRegion = params.get('region');
    return Promise.resolve({
      category: [
        { value: '입찰', count: 604 },
        { value: '계획', count: 312 },
        { value: '마감', count: 100 },
      ].filter((bucket) => !pickedCategory || bucket.value === pickedCategory),
      division: [{ value: '물품', count: 500 }],
      // 지역은 LIKE 라 고른 값을 '포함하는' 버킷만 남는다.
      region: [
        { value: '강원특별자치도', count: 12 },
        { value: '경기도,인천광역시', count: 5 },
      ].filter((bucket) => !pickedRegion || bucket.value.includes(pickedRegion)),
      state: [{ value: '취소', count: 3 }],
    });
  }
  return Promise.resolve({ items: ITEMS, totalCount: 3, pageNo: 1, numOfRows: 20 });
}

function renderScreen(search = '') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[`/notices${search}`]}>
        <NoticeSearchScreen />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  get.mockReset();
  get.mockImplementation(respond);
});

describe('NoticeSearchScreen', () => {
  it('색인 검색 엔드포인트를 부른다 — 팬아웃 API 가 아니다', async () => {
    renderScreen();
    await screen.findByText('2026년 노트북 및 모니터 구매');
    const urls = get.mock.calls.map(([url]) => url as string);
    expect(urls.some((u) => u.startsWith('/api/search/notices?'))).toBe(true);
    expect(urls.some((u) => u.startsWith('/api/search/notices/facets?'))).toBe(true);
    expect(urls.some((u) => u.includes('/api/bid-announce'))).toBe(false);
  });

  it('지역이 빈 공고를 전국으로 적는다', async () => {
    renderScreen();
    await screen.findByText('스마트캠퍼스 통합관제 시스템 구축 발주계획');
    expect(screen.getByText('전국')).toBeInTheDocument();
  });

  it('공고기관과 수요기관이 다르면 둘 다 보여준다', async () => {
    renderScreen();
    await screen.findByText('조달청 강원지방조달청');
    expect(screen.getByText('수요 강원대학교')).toBeInTheDocument();
    // 같은 건은 수요기관 줄을 만들지 않는다.
    expect(screen.queryByText('수요 수원시청')).not.toBeInTheDocument();
  });

  it('D-DAY 를 서버가 센 일수로 그린다', async () => {
    const { container } = renderScreen();
    // D-DAY 는 별도 컬럼이 아니라 '마감일시' 셀 안의 배지다(표를 간결하게 줄였다).
    await screen.findByText('D-37');
    // 지난 것은 '마감'. '마감'은 단계 칩·단계 배지에도 나오는 말이라 D-DAY 배지로 좁혀 본다.
    expect(container.querySelector('.dday-badge.dday-expired')?.textContent).toBe('마감');
    // 마감이 없는 계획 단계는 값 자체가 없어 배지 없이 마감일시만(또는 '-') 남는다.
    expect(container.querySelectorAll('.dday-badge.dday-expired')).toHaveLength(1);
    // 남은 일수 배지(마감 아닌 것)도 정확히 하나.
    expect(
      container.querySelectorAll('.dday-badge:not(.dday-expired)'),
    ).toHaveLength(1);
  });

  it('취소 공고를 지우지 않고 표시만 한다', async () => {
    const { container } = renderScreen();
    await screen.findByText('청사 냉난방기 교체공사');
    expect(container.querySelectorAll('.cancelled-row')).toHaveLength(1);
  });

  it('패싯 건수를 필터 칩에 붙인다', async () => {
    renderScreen();
    const stage = await screen.findByLabelText('단계 필터');
    // 패싯은 목록과 별개의 조회라 늦게 도착한다 — find 로 기다린다.
    expect(await within(stage).findByText('604')).toBeInTheDocument();
    expect(within(stage).getByText('312')).toBeInTheDocument();
    // 패싯에 없는 값도 지우지 않고 0 으로 남긴다.
    expect(within(stage).getByText('0')).toBeInTheDocument();
  });

  it('단계를 고르면 나머지 단계 건수가 0 으로 무너지지 않는다', async () => {
    // 칩에 건수를 붙인 목적이 "누르기 전에 안다"인데, 자기 축 필터를 실은 채 물으면
    // 고른 단계만 남아 나머지가 전부 0건이 된다 — 목적이 정확히 뒤집힌다.
    renderScreen('?cat=입찰');
    const stage = await screen.findByLabelText('단계 필터');
    expect(await within(stage).findByText('312')).toBeInTheDocument();
    expect(within(stage).getByText('100')).toBeInTheDocument();
    // '전체' 는 단계로 안 좁혔을 때의 건수 — 목록 건수(=입찰 건수)가 아니다.
    expect(within(stage).getByText('1,016')).toBeInTheDocument();

    // 축을 뺀 조회가 실제로 따로 나갔는지.
    const facetUrls = get.mock.calls
      .map(([url]) => url as string)
      .filter((u) => u.startsWith('/api/search/notices/facets'));
    expect(facetUrls.some((u) => !u.includes('category='))).toBe(true);
  });

  it('지역을 고르면 다른 지역이 드롭다운에서 사라지지 않는다', async () => {
    renderScreen('?region=강원특별자치도');
    await screen.findByLabelText('지역 필터');
    expect(await screen.findByRole('option', { name: /경기도,인천광역시/ })).toBeInTheDocument();
  });

  it('필터가 없으면 패싯을 한 번만 부른다', async () => {
    renderScreen();
    await screen.findByText('2026년 노트북 및 모니터 구매');
    await waitFor(() => expect(screen.getByLabelText('단계 필터')).toBeInTheDocument());
    const facetUrls = get.mock.calls
      .map(([url]) => url as string)
      .filter((u) => u.startsWith('/api/search/notices/facets'));
    expect(new Set(facetUrls).size).toBe(1);
  });

  it('URL 조건을 질의로 옮긴다 — 날짜는 YYYY-MM-DD 그대로', async () => {
    renderScreen('?and=노트북&cat=입찰&type=물품&from=2026-08-01&to=2026-08-31&active=true');
    await screen.findByText('2026년 노트북 및 모니터 구매');
    const listUrl = get.mock.calls
      .map(([url]) => url as string)
      .find((u) => u.startsWith('/api/search/notices?'))!;
    const params = new URLSearchParams(listUrl.split('?')[1]);
    expect(params.get('andTerms')).toBe('노트북');
    expect(params.get('category')).toBe('입찰');
    expect(params.get('division')).toBe('물품');
    expect(params.get('fromDate')).toBe('2026-08-01');
    expect(params.get('toDate')).toBe('2026-08-31');
    expect(params.get('activeOnly')).toBe('true');
    // 고르지 않은 정렬은 보내지 않는다 — 서버가 관련도/최신을 정한다.
    expect(params.get('sort')).toBeNull();
  });

  it('정렬할 수 없는 컬럼은 머리글을 버튼으로 그리지 않는다', async () => {
    renderScreen();
    await screen.findByText('2026년 노트북 및 모니터 구매');
    // 공고명 컬럼은 공고번호를 함께 담으므로 라벨이 '공고명 · 공고번호'다(표를 간결하게 줄였다).
    expect(screen.getByLabelText('공고명 · 공고번호 정렬')).toBeInTheDocument();
    expect(screen.getByLabelText('마감일시 정렬')).toBeInTheDocument();
    // 화이트리스트 밖(단계·구분·지역 …)
    expect(screen.queryByLabelText('단계 정렬')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('지역 정렬')).not.toBeInTheDocument();
  });

  it('색인 기준 시각을 상태 줄에 적는다', async () => {
    renderScreen();
    await waitFor(() => expect(screen.getByText(/색인 2026-08-06 09:20 기준/)).toBeInTheDocument());
  });
});
