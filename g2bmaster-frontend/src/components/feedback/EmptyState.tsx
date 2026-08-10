/*
 * 빈 결과 · 온보딩 안내 — 원본 #empty-msg 와 renderPending(app.js:4077).
 *
 * 원본은 같은 DOM 하나에 세 가지를 번갈아 넣었다: 평범한 '검색 결과가 없습니다', 서비스 신청
 * 안내, 승인 대기 안내. 셋을 한 컴포넌트의 variant 로 둔다 — 표시 위치와 여백이 같아야 하고,
 * 서로 배타적이기 때문이다.
 */
import type { ReactNode } from 'react';
import './feedback.css';

/** 서버가 200 과 함께 내려주는 온보딩 상태. 오류가 아니다. */
export type PendingStatus = 'pending-apply' | 'pending-approval';

interface EmptyStateProps {
  children?: ReactNode;
}

export function EmptyState({ children = '검색 결과가 없습니다.' }: EmptyStateProps) {
  return <div className="empty-state">{children}</div>;
}

interface PendingStateProps {
  status: PendingStatus;
  message: string;
  /** 공공데이터포털 신청 링크. 서버가 줄 때만 버튼이 나온다. */
  link?: string;
}

export function PendingState({ status, message, link }: PendingStateProps) {
  const isApply = status === 'pending-apply';
  return (
    <div className="empty-state">
      <div className="pending-icon">{isApply ? '📋' : '⏳'}</div>
      <div className="pending-title">{isApply ? '서비스 신청 필요' : '승인 대기 중'}</div>
      <div className="pending-msg">
        {/* 서버 문구는 여러 줄이다. 원본은 \n 을 <br> 로 바꿨다. */}
        {message.split('\n').map((line, i) => (
          <div key={`${i}-${line}`}>{line}</div>
        ))}
      </div>
      {link ? (
        <a className="pending-link" href={link} target="_blank" rel="noopener noreferrer">
          공공데이터포털에서 신청 ↗
        </a>
      ) : null}
    </div>
  );
}
