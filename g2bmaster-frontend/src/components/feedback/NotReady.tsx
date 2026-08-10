/*
 * "아직 준비 중" 토스트.
 *
 * 셸(App)이 한 번만 감싸고, 검색창과 결과 화면이 같은 알림 창구를 쓴다. 화면마다 각자
 * 토스트를 그리면 검색창에서 누른 알림과 표에서 누른 알림이 겹쳐 두 장이 뜬다.
 */
import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { NotReadyContext } from './notReadyContext';
import './feedback.css';

/** 자동으로 사라지는 시간. 짧으면 못 읽고, 길면 다음 조작을 가린다. */
const DISMISS_MS = 4000;

export function NotReadyProvider({ children }: { children: ReactNode }) {
  const [label, setLabel] = useState<string | null>(null);
  const timerRef = useRef<number | undefined>(undefined);

  const clear = useCallback(() => {
    window.clearTimeout(timerRef.current);
    setLabel(null);
  }, []);

  // 같은 것을 다시 누르면 타이머만 다시 시작해야 한다 — 안 그러면 첫 타이머가 남아
  // 방금 띄운 알림을 이르게 지운다.
  const notify = useCallback((next: string) => {
    window.clearTimeout(timerRef.current);
    setLabel(next);
    timerRef.current = window.setTimeout(() => setLabel(null), DISMISS_MS);
  }, []);

  useEffect(() => () => window.clearTimeout(timerRef.current), []);

  const api = useMemo(() => ({ notify }), [notify]);

  return (
    <NotReadyContext.Provider value={api}>
      {children}
      {label ? (
        <div className="not-ready-toast" role="status">
          <span className="not-ready-icon" aria-hidden="true">
            🚧
          </span>
          {/* 조사(은/는)를 붙이지 않는다 — 라벨의 받침에 따라 달라져서 반드시 틀리는 자리가 생긴다. */}
          <span>
            <strong>{label}</strong> — 백엔드에서 작업 중인 기능입니다. 곧 열립니다.
          </span>
          <button type="button" onClick={clear} aria-label="알림 닫기">
            ×
          </button>
        </div>
      ) : null}
    </NotReadyContext.Provider>
  );
}
