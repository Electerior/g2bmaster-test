/*
 * 대기 표시 두 종류.
 *  - Spinner    : 패널 한가운데의 회전자. 원본 .report-spinner.
 *  - PanelNotice: 패널 안 한 줄짜리 안내. 원본 .trend-loading / .trend-empty.
 *
 * 표의 스켈레톤(원본 showLoading 의 5행 shimmer)은 DataTable 이 `loading` prop 으로 직접
 * 그린다 — 컬럼 수를 알아야 만들 수 있어 표 밖으로 뺄 수가 없다.
 */
import type { ReactNode } from 'react';
import './feedback.css';

interface SpinnerProps {
  small?: boolean;
  label?: string;
}

export function Spinner({ small = false, label = '불러오는 중' }: SpinnerProps) {
  return <span className={small ? 'spinner small' : 'spinner'} role="status" aria-label={label} />;
}

interface PanelNoticeProps {
  children: ReactNode;
  /** 결과가 비었다는 뜻이면 true — 색만 같고 의미가 달라 aria-live 를 붙이지 않는다. */
  empty?: boolean;
}

export function PanelNotice({ children, empty = false }: PanelNoticeProps) {
  return (
    <div className={empty ? 'panel-empty' : 'panel-loading'} role={empty ? undefined : 'status'}>
      {children}
    </div>
  );
}
