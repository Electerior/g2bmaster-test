/*
 * 화면 맨 위 3px 진행 표시줄.
 *
 * 원본은 aiFetch 래퍼가 전역 카운터 `_aiActive` 를 증감시켜 이 바를 켰다 껐다 했다.
 * 그 방식은 fetch 를 한 곳이라도 래퍼 밖에서 부르면 카운터가 새서 바가 영원히 켜져 있었다.
 * TanStack Query 의 useIsFetching 은 진행 중인 쿼리를 직접 세므로 새지 않는다.
 */
import { useIsFetching, useIsMutating } from '@tanstack/react-query';
import './layout.css';

export function AiActivityBar() {
  const fetching = useIsFetching();
  // AI 분석은 대부분 POST(뮤테이션)다 — 쿼리만 세면 정작 오래 걸리는 작업을 놓친다.
  const mutating = useIsMutating();
  const active = fetching + mutating > 0;

  return (
    <div className={active ? 'ai-activity-bar on' : 'ai-activity-bar'} aria-hidden={!active}>
      <div className="ai-activity-track" />
      {active ? <span className="ai-activity-label">AI 분석 중…</span> : null}
    </div>
  );
}
