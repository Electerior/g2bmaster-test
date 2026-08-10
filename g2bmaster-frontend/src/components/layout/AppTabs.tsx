/*
 * 고전 폴더 탭 스트립. 활성 탭의 아래 테두리를 --surface 색으로 덮어 아래 패널과
 * 이어 붙인 것처럼 보이게 하는 원본의 트릭을 그대로 쓴다(layout.css .app-tab.active).
 *
 * 원본은 클릭 핸들러가 state.tab 을 바꾸고 다시 렌더했다. 여기서는 NavLink 라
 * 가운데 클릭·새 탭 열기·주소 복사가 전부 공짜로 따라온다.
 */
import { NavLink, useLocation } from 'react-router-dom';
import { TAB_ITEMS, showsTabs } from '@/routes/routePaths';
import './layout.css';

export function AppTabs() {
  const location = useLocation();
  if (!showsTabs(location.pathname)) return null;

  return (
    <nav className="app-tabs" aria-label="조회 화면">
      {TAB_ITEMS.map((tab) => (
        <NavLink
          key={tab.path}
          to={{ pathname: tab.path, search: location.search }}
          /*
           * end 가 없으면 NavLink 는 접두 일치로도 활성이 된다. 공고 검색이 '/notices' 가 되면서
           * '/notices/bid-result' 의 접두가 됐고, 그대로 두면 입찰 결과 화면에서 탭 두 개가 함께
           * 켜진다 — .app-tab.active 는 아래 패널과 이어 붙이려고 아래 테두리를 덮는 트릭이라
           * 둘이 켜지면 어느 화면인지 알 수 없게 된다(aria-current 도 둘이 된다).
           */
          end
          className={({ isActive }) =>
            [isActive ? 'app-tab active' : 'app-tab', tab.notReady ? 'not-ready' : '']
              .filter(Boolean)
              .join(' ')
          }
        >
          {tab.label}
          {/* 화면 속이 아직 준비 중임을 클릭 전에 알린다 — routePaths.ts TabItem.notReady 참고. */}
          {tab.notReady ? <span className="tab-ready-badge">준비</span> : null}
        </NavLink>
      ))}
    </nav>
  );
}
