/*
 * 시스템 대시보드 — 원본에서는 별도 정적 페이지(system.html)였다.
 *
 * TODO(다음 웨이브): DB·적재 상태 · 호출 로그 · 예약 적재 · 검색 방식 비교.
 */
import { ScreenPlaceholder } from './ScreenPlaceholder';

export function SystemDashboardScreen() {
  return (
    <ScreenPlaceholder
      title="시스템 상태"
      description="DB · 적재 · LLM · 검색 엔진 상태와 예약 적재 관리."
    />
  );
}
