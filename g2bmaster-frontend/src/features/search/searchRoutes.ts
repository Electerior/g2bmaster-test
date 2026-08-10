/*
 * 검색 모드와 라우트의 대응.
 *
 * 원본에서 corp/officer/upload 는 탭과 직교하는 '모드'였고 결과 영역을 가로챘다.
 * React 에서는 셋이 각자 주소를 가지므로, 그 라우트에 있다는 사실 자체가 모드를 정한다.
 * URL 의 mode 파라미터는 나머지(키워드·품목·발주기관)만 구분하면 된다.
 */
import type { SearchMode } from '@/domain/searchModes';
import { ROUTES } from '@/routes/routePaths';

export const ROUTE_MODE: Readonly<Record<string, SearchMode>> = {
  [ROUTES.company]: 'corp',
  [ROUTES.officers]: 'officer',
  [ROUTES.analysisLab]: 'upload',
  [ROUTES.specSearch]: 'spec-search',
};

/** 라우트가 모드를 정하면 그것을, 아니면 URL 파라미터의 모드를 쓴다. */
export function activeSearchMode(pathname: string, criteriaMode: SearchMode): SearchMode {
  return ROUTE_MODE[pathname] ?? criteriaMode;
}

export function hasDedicatedRoute(pathname: string): boolean {
  return ROUTE_MODE[pathname] !== undefined;
}
