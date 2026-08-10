/*
 * 라우트 표. 원본은 탭(state.tab, 10개)과 검색 모드(state.searchMode, 6개)라는 직교하는
 * 두 선택자로 화면이 결정됐고, corp/officer/upload 모드는 탭과 무관하게 결과 영역을
 * 가로챘다. React 에서는 그 조합을 전부 URL 로 편다 — 화면 하나 = 주소 하나.
 */
import type { ScreenKind } from '@/domain/columns';
import { SCREENS } from '@/domain/columns';

export const ROUTES = {
  /**
   * 공고 통합 검색. 예전 표 셋(bidPlan · preSpec · bidAnnounce)이 여기로 합쳐졌다 —
   * 단계는 탭이 아니라 필터이므로 주소도 하나다. 옛 주소는 router 가 단계 필터를 붙여
   * 이리로 넘긴다(공유된 링크가 죽지 않도록).
   */
  noticeSearch: '/notices',
  bidResult: '/notices/bid-result',
  dealRadar: '/deal-radar',
  saved: '/saved',
  specSearch: '/spec-search',
  priceDb: '/price-db',
  trendProduct: '/trends/product',
  trendService: '/trends/service',
  trendConstruction: '/trends/construction',
  company: '/company',
  officers: '/officers',
  analysisLab: '/analysis-lab',
  system: '/system',
  /**
   * 베타 모집 랜딩. 탭도 검색도 없는 독립 페이지라 TAB_ITEMS 에 넣지 않는다.
   * 앱 셸 밖에서 렌더링된다 — router.tsx 참고.
   */
  beta: '/beta',
} as const;

export type RoutePath = (typeof ROUTES)[keyof typeof ROUTES];

/** '/' 로 들어오면 여기로 보낸다. 실사용 진입점은 언제나 공고 검색이다. */
export const DEFAULT_ROUTE: RoutePath = ROUTES.noticeSearch;

/**
 * 옛 표 주소 → 통합 검색의 단계 필터.
 *
 * 링크를 공유하거나 즐겨찾기에 넣어 둔 사람이 404 를 보지 않게 한다. 입찰 공고는 단계를
 * 걸지 않는다 — '입찰'만 남기면 이미 마감된 같은 공고가 목록에서 사라져, 예전 탭보다
 * 좁은 결과를 보게 된다.
 */
export const LEGACY_NOTICE_ROUTES: ReadonlyArray<{ path: string; category?: string }> = [
  { path: '/notices/bid-plan', category: '계획' },
  { path: '/notices/pre-spec', category: '사전규격' },
  { path: '/notices/bid-announce' },
];

/**
 * 머무르지 않고 곧바로 다른 주소로 넘기는 경유지.
 *
 * 셸(App)은 이 경로에서도 렌더되므로 검색창의 효과가 한 번 돈다. 경유지에서 조건을 심으면
 * 곧이어 일어나는 리다이렉트가 그것을 지우는데, 심었다는 사실(플래그)은 남아 목적지에서
 * 다시 심지 않는다 — 기본 조회 기간이 통째로 사라진다. 그래서 경유지 목록이 필요하다.
 */
const TRANSIT_ROUTES: readonly string[] = ['/', ...LEGACY_NOTICE_ROUTES.map((r) => r.path)];

export function isTransitRoute(pathname: string): boolean {
  return TRANSIT_ROUTES.includes(pathname);
}

export interface TabItem {
  path: RoutePath;
  label: string;
  kind: ScreenKind;
  /**
   * 화면 속은 아직 ScreenPlaceholder('준비 중')다 — 대응 API 는 백엔드에 있으나 화면 구현이
   * 다음 웨이브다. 탭에서 숨기지 않고 «준비» 배지로 표시한다: 숨기면 로드맵이 안 보이고,
   * 아무 표시 없이 두면 클릭한 뒤에야 준비 중임을 알게 된다.
   */
  notReady?: boolean;
}

/**
 * 폴더 탭 스트립. 원본 10개에서 공고 표 셋이 '공고 검색' 하나로 합쳐져 8개다.
 * 라벨은 SCREENS 에서 가져온다 — 탭 이름과 화면 제목이 갈라지면 안 된다.
 */
export const TAB_ITEMS: readonly TabItem[] = [
  { path: ROUTES.noticeSearch, kind: 'notice-search', label: SCREENS['notice-search'].label },
  { path: ROUTES.bidResult, kind: 'bid-result', label: SCREENS['bid-result'].label },
  // AI 수주 데스크는 이제 실제 화면이다 — 준비 배지를 떼었다.
  { path: ROUTES.dealRadar, kind: 'deal-radar', label: SCREENS['deal-radar'].label },
  { path: ROUTES.saved, kind: 'saved-notices', label: SCREENS['saved-notices'].label },
  { path: ROUTES.specSearch, kind: 'spec-search', label: SCREENS['spec-search'].label, notReady: true },
  { path: ROUTES.priceDb, kind: 'price-db', label: SCREENS['price-db'].label },
  { path: ROUTES.trendProduct, kind: 'product-trend', label: SCREENS['product-trend'].label },
  { path: ROUTES.trendService, kind: 'service-trend', label: SCREENS['service-trend'].label },
  {
    path: ROUTES.trendConstruction,
    kind: 'construction-trend',
    label: SCREENS['construction-trend'].label,
  },
];

/**
 * 탭 스트립을 감출 라우트.
 * - /analysis-lab : 올린 파일 하나를 보는 화면이라 공고 검색 도구가 남아 있으면 오해를 부른다
 *   (원본 searchModeLayout('upload').searchUi === false 와 같은 이유).
 * - /system       : 원본에서 아예 별도 페이지(system.html)였다.
 * 낙찰자/담당자 조회(/company, /officers)는 원본에서도 탭을 유지했으므로 그대로 둔다.
 */
const TABLESS_ROUTES: readonly string[] = [ROUTES.analysisLab, ROUTES.system];

export function showsTabs(pathname: string): boolean {
  return !TABLESS_ROUTES.includes(pathname);
}
