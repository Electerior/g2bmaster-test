/*
 * 검색 방식 탭 — 원본 .search-mode-tabs / setSearchMode(app.js:1066).
 *
 * 원본에서 모드는 탭과 직교하는 두 번째 선택자였고, corp/officer/upload 는 결과 영역을
 * 가로챘다. React 에서는 그 셋이 각자 라우트를 갖는다(spec §1) — 그래서 이 컴포넌트는
 * "모드 전환"이 아니라 **모드 전환 + 필요하면 라우트 이동**을 한다.
 */
import { useLocation, useNavigate } from 'react-router-dom';
import type { SearchMode } from '@/domain/searchModes';
import { ROUTES } from '@/routes/routePaths';
import { activeSearchMode, hasDedicatedRoute } from './searchRoutes';
import './search.css';

interface ModeTab {
  mode: SearchMode;
  label: string;
  /** 전용 라우트가 있는 모드. 없으면 현재 공고 화면에 머문다. */
  route?: string;
}

/** 순서·문구는 원본 index.html(144~151행) 그대로. */
const MODE_TABS: readonly ModeTab[] = [
  { mode: 'keyword', label: '키워드 검색' },
  { mode: 'item', label: '품목 검색' },
  { mode: 'instt', label: '발주기관 검색' },
  { mode: 'corp', label: '낙찰자 조회', route: ROUTES.company },
  { mode: 'officer', label: '담당자 조회', route: ROUTES.officers },
  { mode: 'upload', label: '업로드 분석', route: ROUTES.analysisLab },
];

interface SearchModeTabsProps {
  /** URL 파라미터의 모드. 전용 라우트에서는 무시된다. */
  mode: SearchMode;
  /** 키워드/품목/발주기관으로 돌아올 때 쓰는 setter. */
  onModeChange: (mode: SearchMode) => void;
}

export function SearchModeTabs({ mode, onModeChange }: SearchModeTabsProps) {
  const location = useLocation();
  const navigate = useNavigate();
  const active = activeSearchMode(location.pathname, mode);
  const onDedicatedRoute = hasDedicatedRoute(location.pathname);

  const handleClick = (tab: ModeTab) => {
    if (tab.route) {
      // 조건(기간·업체명 등)은 그대로 들고 간다 — 낙찰자 조회로 넘어가며 기간을 잃으면
      // 사용자가 방금 고른 범위를 다시 골라야 한다.
      navigate({ pathname: tab.route, search: location.search });
      return;
    }
    if (onDedicatedRoute) {
      // 전용 라우트에서 키워드 계열로 돌아올 때는 갈 곳이 필요하다 — 공고 검색으로 보낸다.
      const params = new URLSearchParams(location.search);
      if (tab.mode === 'keyword') params.delete('mode');
      else params.set('mode', tab.mode);
      navigate({ pathname: ROUTES.noticeSearch, search: params.toString() });
      return;
    }
    onModeChange(tab.mode);
  };

  return (
    <div className="search-mode-tabs" role="tablist" aria-label="검색 방식">
      {MODE_TABS.map((tab) => (
        <button
          key={tab.mode}
          type="button"
          role="tab"
          aria-selected={tab.mode === active}
          className={tab.mode === active ? 'search-mode-btn active' : 'search-mode-btn'}
          onClick={() => handleClick(tab)}
        >
          {tab.label}
        </button>
      ))}
    </div>
  );
}
