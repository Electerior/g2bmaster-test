/*
 * 옛 공고 표 주소(`/notices/bid-plan` 등) → 통합 검색.
 *
 * 그냥 404 로 두면 공유된 링크와 즐겨찾기가 조용히 죽는다. 단계만 필터로 옮겨 붙이고
 * 나머지 조건(키워드·기간·페이지)은 **그대로 들고 간다** — 사용자가 만든 조건이지
 * 화면의 소유물이 아니기 때문이다.
 */
import { Navigate, useLocation } from 'react-router-dom';
import { ROUTES } from './routePaths';

interface LegacyNoticeRedirectProps {
  /** 붙일 단계 필터. 입찰 공고처럼 단계를 좁히면 안 되는 경우엔 주지 않는다. */
  category?: string;
}

export function LegacyNoticeRedirect({ category }: LegacyNoticeRedirectProps) {
  const location = useLocation();
  const params = new URLSearchParams(location.search);
  // 이미 단계가 걸려 있으면 사용자가 고른 것이 우선이다.
  if (category && !params.get('cat')) params.set('cat', category);
  const search = params.toString();
  return <Navigate to={{ pathname: ROUTES.noticeSearch, search }} replace />;
}
