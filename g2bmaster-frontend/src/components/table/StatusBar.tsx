/*
 * 결과 상태 줄 — 원본 showStatus(app.js:3978).
 *
 * 원본은 호출부마다 HTML 문자열을 조립해 넘겼다(`총 <strong>${n}</strong>건 &nbsp;|&nbsp; …`).
 * 그 방식은 세 가지가 나빴다: ① 문구가 열 곳에 흩어져 서식이 갈라진다, ② 구분자 `&nbsp;|&nbsp;`
 * 를 손으로 붙여 앞뒤 공백이 어긋난다, ③ 서버 문자열이 그대로 들어가는 자리라 escHtml 을
 * 빠뜨리면 XSS 다. → **구조화된 props** 로 받고 조립은 여기서만 한다.
 */
import type { ReactNode } from 'react';
import './table.css';

export interface StatusSourceCounts {
  g2b: number;
  privateG2b: number;
  d2b: number;
}

export interface StatusBarProps {
  /** 총 N건. */
  total?: number;
  /** 조회 기간. 둘 다 있을 때만 표시한다(원본과 동일). */
  period?: { from: string; to: string };
  /** 현재/전체 페이지. */
  page?: { current: number; total: number };
  /** 구분 필터가 걸려 있으면 함께 알린다. */
  bidType?: string;
  /** 출처별 건수(입찰 공고 전용). */
  sources?: StatusSourceCounts;
  /** 일부 출처 조회 실패 건수. */
  sourceErrorCount?: number;
  /** '마감 공고 포함 재조회' 처럼 굵게 붙는 짧은 알림들. */
  notes?: readonly string[];
  /** 위 항목으로 표현되지 않는 안내 문구(로딩·빈 결과 등). */
  message?: ReactNode;
  /** 오류일 때는 색이 바뀐다. */
  error?: boolean;
}

/** 조각들을 ` | ` 로 이어 붙인다 — 원본의 `&nbsp;|&nbsp;` 자리. */
function join(parts: ReactNode[]): ReactNode[] {
  return parts.flatMap((part, i) =>
    i === 0
      ? [part]
      : [
          <span key={`sep-${i}`} className="status-sep">
            |
          </span>,
          part,
        ],
  );
}

export function StatusBar({
  total,
  period,
  page,
  bidType,
  sources,
  sourceErrorCount,
  notes,
  message,
  error = false,
}: StatusBarProps) {
  const parts: ReactNode[] = [];

  if (total != null) {
    parts.push(
      <span key="total">
        총 <strong>{total.toLocaleString()}</strong>건
      </span>,
    );
  }
  if (period?.from && period.to) {
    parts.push(
      <span key="period">
        {period.from} ~ {period.to}
      </span>,
    );
  }
  if (page) {
    parts.push(
      <span key="page">
        {page.current} / {page.total} 페이지
      </span>,
    );
  }
  if (bidType) {
    parts.push(
      <span key="type">
        구분: <strong>{bidType}</strong>
      </span>,
    );
  }
  for (const note of notes ?? []) {
    parts.push(
      <strong key={`note-${note}`}>{note}</strong>,
    );
  }
  if (sources) {
    parts.push(
      <span key="sources">
        출처: 나라장터 {sources.g2b.toLocaleString()}건 · 누리장터{' '}
        {sources.privateG2b.toLocaleString()}건 · 국방전자조달 {sources.d2b.toLocaleString()}건{' '}
        <span className="coverage-note">(온비드·기관 자체조달 제외)</span>
      </span>,
    );
  }
  if (sourceErrorCount) {
    parts.push(
      <strong key="source-error">일부 출처 조회 실패 {sourceErrorCount}건</strong>,
    );
  }
  if (message != null) parts.push(<span key="message">{message}</span>);

  if (!parts.length) return null;

  return (
    <div className={error ? 'status-bar error' : 'status-bar'} role="status">
      {join(parts)}
    </div>
  );
}
