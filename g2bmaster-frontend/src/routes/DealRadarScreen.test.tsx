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


/** 딜 분석 응답 한 벌 — 백엔드 UnitCostValidator 가 붙이는 필드를 포함한다. */
function analysisWith(confidence: 'confirmed' | 'partial' | 'untrusted', extra: Record<string, unknown> = {}) {
  return {
    bidNtceNo: 'R26-0001',
    score: null,
    facts: { productName: '', productCode: '', quantity: 1, unitPrice: 0, budget: 45000000 },
    market: null,
    opening: null,
    deal: {
      budget: 45000000, expectedAward: null, medianRate: null, unitCost: null, quantity: 1,
      cost: 0, hasCost: false, profitAtBudget: 0, profitAtExpected: 0, profitAtBid: 0,
      marginPctAtExpected: 0, breakevenBid: 0, bidRate: 0,
      unitCostSource: confidence === 'untrusted' ? null : 'estimated',
    },
    simBidUsed: null,
    spec: {
      text: '', products: [], parsedFiles: [], fileEntryCount: 1,
      quantityFound: null, deliveryFound: null,
      files: [{ url: 'https://files.example/spec.hwp', name: '규격서.hwp',
                documentClass: '공고문', specTrusted: 'false' }],
    },
    estimatedUnitCost: {
      matched: true, low: 1, high: 3, mid: 73691235, gpuCount: 1, hasBase: true,
      allPriced: false, currency: 'KRW',
      costConfidence: confidence,
      confirmedMid: confidence === 'untrusted' ? null : 20000000,
      evidenceRatio: confidence === 'untrusted' ? 0.15 : 0.9,
      costWarnings: ['not-all-priced', 'category-conflict:gpu'],
      breakdown: [
        { category: 'System', option: 'ASUS ESC4000', product: null, qty: 1, low: 20000000,
          high: 20000000, role: 'base', acceptedForCost: true, evidenceInSpec: true },
        { category: 'GPU', option: 'NVIDIA TESLA H100', product: null, qty: 1, low: 5000000,
          high: 5000000, role: 'part', acceptedForCost: false, evidenceInSpec: false,
          rejectReason: 'no-evidence-in-spec' },
      ],
      ...extra,
    },
    d2bGw: null,
    note: '추정된 부품이 규격서 내용과 일치하지 않아 단가를 확정하지 않았습니다.',
    _fromCache: false,
  };
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

  it('untrusted 단가는 강조하지 않고 참고값으로 표시한다 — 화면이 검증을 무력화하면 안 된다', async () => {
    post.mockImplementation((url: string) =>
      url === '/api/deal-analysis' ? Promise.resolve(analysisWith('untrusted')) : Promise.resolve({}));
    const { container } = renderScreen();

    await screen.findAllByText('부품 단가(미확정)');
    expect(screen.queryByText('부품 단가(중앙)')).toBeNull();
    // 강조(highlight) 가 붙지 않는다.
    const stat = Array.from(container.querySelectorAll('div'))
      .find((el) => el.textContent === '부품 단가(미확정)');
    expect(stat?.closest('div')?.className ?? '').not.toContain('highlight');
  });

  it('확정 단가는 mid 가 아니라 confirmedMid 를 쓴다', async () => {
    post.mockImplementation((url: string) =>
      url === '/api/deal-analysis' ? Promise.resolve(analysisWith('partial')) : Promise.resolve({}));
    renderScreen();

    await screen.findAllByText('부품 단가(중앙)');
    // 20,000,000(확정) 이 뜨고 73,691,235(AI 원본) 는 단가 자리에 뜨지 않는다.
    expect(screen.getAllByText(/20,000,000/).length).toBeGreaterThan(0);
    expect(screen.queryByText(/73,691,235/)).toBeNull();
  });

  it('검증 요약과 사유, 백엔드 note 를 함께 보여 준다', async () => {
    post.mockImplementation((url: string) =>
      url === '/api/deal-analysis' ? Promise.resolve(analysisWith('untrusted')) : Promise.resolve({}));
    renderScreen();

    await screen.findAllByText(/규격서와 대조되지 않아 단가 미확정/);
    expect(screen.getAllByText(/규격서 근거 15%/).length).toBeGreaterThan(0);
    expect(screen.getAllByText(/총액 반영 1\/2행/).length).toBeGreaterThan(0);
    expect(screen.getAllByText(/일부 행 가격 미확인/).length).toBeGreaterThan(0);
    expect(screen.getAllByText(/추정된 부품이 규격서 내용과 일치하지 않아/).length).toBeGreaterThan(0);
  });

  it('규격서로 확인되지 않은 문서는 문서종과 함께 미확인으로 표시한다', async () => {
    post.mockImplementation((url: string) =>
      url === '/api/deal-analysis' ? Promise.resolve(analysisWith('untrusted')) : Promise.resolve({}));
    renderScreen();

    await screen.findAllByText(/공고문/);
    expect(screen.getAllByText('미확인').length).toBeGreaterThan(0);
  });

  it('첨부를 규격서로 지목하면 specFileUrl 을 실어 다시 분석한다', async () => {
    post.mockImplementation((url: string) =>
      url === '/api/deal-analysis' ? Promise.resolve(analysisWith('partial')) : Promise.resolve({}));
    renderScreen();

    const pick = await screen.findByRole('button', { name: '이 파일로 분석' });
    fireEvent.click(pick);

    await waitFor(() => {
      const hit = post.mock.calls
        .filter(([url]) => url === '/api/deal-analysis')
        .some(([, body]) => (body as { specFileUrl?: string; deep?: boolean }).specFileUrl
          === 'https://files.example/spec.hwp');
      expect(hit).toBe(true);
    });
    // 지목하면 얕은 분석으로는 의미가 없다 — deep 을 켜서 보낸다.
    const withPick = post.mock.calls
      .filter(([url]) => url === '/api/deal-analysis')
      .map(([, body]) => body as { specFileUrl?: string; deep?: boolean })
      .find((b) => b.specFileUrl);
    expect(withPick?.deep).toBe(true);
  });

  it('같은 첨부를 다시 누르면 지목이 풀려 자동 선택으로 돌아간다', async () => {
    post.mockImplementation((url: string) =>
      url === '/api/deal-analysis' ? Promise.resolve(analysisWith('partial')) : Promise.resolve({}));
    renderScreen();

    fireEvent.click(await screen.findByRole('button', { name: '이 파일로 분석' }));
    const toggled = await screen.findByRole('button', { name: '✓ 규격서로 지정됨' });
    expect(toggled).toHaveAttribute('aria-pressed', 'true');

    fireEvent.click(toggled);
    await screen.findByRole('button', { name: '이 파일로 분석' });
  });
});
