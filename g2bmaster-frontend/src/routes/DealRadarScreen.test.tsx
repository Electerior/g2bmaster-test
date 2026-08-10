/*
 * AI 수주 데스크 — 이번 웨이브가 요구한 표시·동작 규칙이 실제 렌더에서 지켜지는지.
 *
 * apiClient 의 get/post 만 갈아 끼운다(인터셉터·팬아웃은 이 테스트의 대상이 아니다).
 *  - get  : 공고 검색(색인) 결과
 *  - post : 딜 분석(/api/deal-analysis) — forceRefresh 값을 여기서 들여다본다
 */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { NoticeIndexItem } from '@/api/search';

const get = vi.fn();
const post = vi.fn();
vi.mock('@/lib/apiClient', () => ({
  get: (url: string) => get(url),
  post: (url: string, body: unknown) => post(url, body),
  put: vi.fn(),
  del: vi.fn(),
  apiClient: {},
  ApiError: class ApiError extends Error {},
}));

const { DealRadarScreen } = await import('./DealRadarScreen');

/** 나라장터 상세(sourceUrl) 있음 + 첨부 있음(하나는 URL 없음 → 걸러져야 한다). */
const WITH_URL: NoticeIndexItem = {
  id: 'R26-0001',
  noticeName: 'GPU 서버 도입',
  businessDivision: '물품',
  sourceUrl: 'https://g2b.example/notice/R26-0001',
  attachmentUrls: [
    { name: '규격서.hwp', url: 'https://files.example/spec.hwp' },
    { name: '이름만-있고-URL없음' },
  ],
  estimatedPrice: 45000000,
  dday: 12,
};

/** 사전규격/외자 — 원문 URL 이 없다(합성하면 안 된다). */
const NO_URL: NoticeIndexItem = {
  id: '20260801-사전-0007',
  noticeName: '수입 계측장비 도입',
  businessDivision: '외자',
  estimatedPrice: 12000000,
};

const ITEMS = [WITH_URL, NO_URL];

function respondGet(url: string) {
  if (url.startsWith('/api/search/notices')) {
    return Promise.resolve({ items: ITEMS, totalCount: ITEMS.length, pageNo: 1, numOfRows: 20 });
  }
  return Promise.resolve({ items: [], totalCount: 0, pageNo: 1, numOfRows: 20 });
}

function renderScreen(search = '') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[`/deal-radar${search}`]}>
        <DealRadarScreen />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  get.mockReset();
  get.mockImplementation(respondGet);
  post.mockReset();
  post.mockImplementation(() => Promise.resolve({}));
});

describe('DealRadarScreen', () => {
  it('공고명은 sourceUrl 이 있을 때만 링크다 — 없으면 그냥 글자다(URL 합성 금지)', async () => {
    renderScreen();
    const link = await screen.findByRole('link', { name: 'GPU 서버 도입' });
    expect(link).toHaveAttribute('href', WITH_URL.sourceUrl);
    expect(link).toHaveAttribute('target', '_blank');

    // sourceUrl 없는 공고는 링크가 아니라 글자다.
    expect(screen.queryByRole('link', { name: '수입 계측장비 도입' })).toBeNull();
    expect(screen.getByText('수입 계측장비 도입')).toBeInTheDocument();
  });

  it('첨부는 URL 있는 것만 새 탭 다운로드 링크로 그린다', async () => {
    renderScreen();
    const att = await screen.findByRole('link', { name: '규격서.hwp' });
    expect(att).toHaveAttribute('href', 'https://files.example/spec.hwp');
    expect(att).toHaveAttribute('target', '_blank');
    expect(att.getAttribute('rel')).toContain('noopener');

    // URL 없는 첨부는 렌더되지 않는다.
    expect(screen.queryByText('이름만-있고-URL없음')).toBeNull();
  });

  it('구분 필터가 검색 조건(division)을 바꾼다', async () => {
    renderScreen();
    await screen.findByText('GPU 서버 도입');

    fireEvent.click(screen.getByRole('button', { name: '외자' }));

    await waitFor(() => {
      const hit = get.mock.calls
        .map(([url]) => url as string)
        .filter((url) => url.startsWith('/api/search/notices'))
        .some((url) => new URLSearchParams(url.split('?')[1] ?? '').get('division') === '외자');
      expect(hit).toBe(true);
    });
  });

  it('재검토는 forceRefresh:true 로 서버를 다시 친다', async () => {
    renderScreen();
    // 버튼 라벨이 '재검토'가 됐다는 것은 초기 분석이 끝났다는 뜻이다(대기 중엔 '분석 중…').
    const buttons = await screen.findAllByRole('button', { name: '재검토' });

    // 초기 조회는 저장된 결과를 그대로 쓴다 — forceRefresh 는 전부 false.
    const analysisCalls = () => post.mock.calls.filter(([url]) => url === '/api/deal-analysis');
    expect(analysisCalls().length).toBeGreaterThan(0);
    expect(analysisCalls().every(([, body]) => (body as { forceRefresh?: boolean }).forceRefresh === false)).toBe(
      true,
    );

    fireEvent.click(buttons[0]);

    await waitFor(() => {
      expect(
        analysisCalls().some(([, body]) => (body as { forceRefresh?: boolean }).forceRefresh === true),
      ).toBe(true);
    });
  });

  it('외자 구분은 회색 fallback 이 아니라 전용 TypeBadge 로 그린다', async () => {
    const { container } = renderScreen();
    await screen.findByText('수입 계측장비 도입');

    const badge = container.querySelector('.type-badge.type-foreign');
    expect(badge).not.toBeNull();
    expect(badge?.textContent).toBe('외자');
    // 어떤 구분도 회색 fallback(type-etc)으로 떨어지지 않는다.
    expect(container.querySelector('.type-badge.type-etc')).toBeNull();
  });
});
