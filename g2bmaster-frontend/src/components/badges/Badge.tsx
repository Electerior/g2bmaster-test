/*
 * 배지 계열 — 원본 formatCell(app.js:3990) 이 문자열로 조립하던 조각들을 컴포넌트로 나눴다.
 *
 * 원본은 배지마다 인라인 style 을 붙였다(팔레트가 함수 안 리터럴에 있었다). 여기서는 전부
 * 클래스로 내보내고 색은 palette.css 가 갖는다 — 같은 배지가 표·서랍·카드 세 곳에 나오므로
 * 색이 한곳에 있어야 세 곳이 갈라지지 않는다.
 */
import type { ReactNode } from 'react';
import type { PriceStatusBadge as PriceBadgeData } from '@/domain/priceStatus';
import { calcDday, opportunityGrade, type DdayInfo } from '@/domain/format';
import './badges.css';

/* ─── 구분(물품 · 용역 · 공사) ────────────────────────────────────────────── */

const TYPE_CLASS: Readonly<Record<string, string>> = {
  물품: 'type-goods',
  용역: 'type-service',
  공사: 'type-works',
  // 외자(수입 조달)는 색인 검색·수주 데스크에만 나온다. 회색 fallback 대신 전용 색을 준다.
  외자: 'type-foreign',
};

interface TypeBadgeProps {
  value: string;
}

export function TypeBadge({ value }: TypeBadgeProps) {
  // 색은 badges.css 의 .type-badge.type-* 규칙이 갖는다. 모르는 구분은 회색(type-etc).
  const key = TYPE_CLASS[value] ?? 'type-etc';
  return <span className={`type-badge ${key}`}>{value}</span>;
}

/* ─── 출처(나라장터 · 누리장터 · 국방전자조달) ───────────────────────────── */

interface SourceBadgeProps {
  label: string;
  /** 행의 `_source`. 라벨이 아니라 이 값으로 색을 고른다 — 라벨은 서버가 바꿀 수 있다. */
  source?: string;
}

export function SourceBadge({ label, source }: SourceBadgeProps) {
  const cls =
    source === 'd2b' ? 'source-d2b' : source === 'private-g2b' ? 'source-private-g2b' : 'source-g2b';
  return <span className={`source-badge ${cls}`}>{label}</span>;
}

/* ─── 공고 상태 ──────────────────────────────────────────────────────────── */

interface StatusBadgeProps {
  label: string;
  /** 서버가 명시적으로 취소를 알려 주면 문자열 판정보다 우선한다. */
  cancelled?: boolean;
}

export function StatusBadge({ label, cancelled }: StatusBadgeProps) {
  // 원본 formatCell 'status-badge'(app.js:4022) 의 정규식 판정 그대로.
  const isCancelled = cancelled === true || /취소|철회|무효/i.test(label);
  const isCompleted = /완료|마감|종료|낙찰/i.test(label);
  const cls = isCancelled
    ? 'status-cancelled'
    : isCompleted
      ? 'status-completed'
      : 'status-active';
  return <span className={`notice-status ${cls}`}>{label}</span>;
}

/* ─── 수주기회 ───────────────────────────────────────────────────────────── */

interface OpportunityBadgeProps {
  score: number;
  /** 서버가 준 등급. 없으면 점수로 되계산한다(원본과 같은 경계값). */
  grade?: string;
  action?: string;
  reasons?: readonly string[];
  /** 규격잠금 의심 사유. 있으면 배지 아래에 경고를 붙인다. */
  specLockSummary?: string;
}

export function OpportunityBadge({
  score,
  grade,
  action,
  reasons,
  specLockSummary,
}: OpportunityBadgeProps) {
  const resolved = grade || opportunityGrade(score);
  const tooltip = reasons?.length ? reasons.join(' · ') : undefined;
  return (
    <div className={`opp-cell opp-${resolved.toLowerCase()}`} title={tooltip}>
      <strong>{resolved}</strong>
      <span>{score}점</span>
      {action ? <em>{action}</em> : null}
      {specLockSummary ? (
        <div className="spec-lock-badge" title={specLockSummary}>
          규격잠금 의심: {specLockSummary}
        </div>
      ) : null}
    </div>
  );
}

/* ─── D-DAY ──────────────────────────────────────────────────────────────── */

interface DDayBadgeProps {
  /** G2B 원본 문자열('YYYYMMDDHHmm' 등). 파싱은 domain/format 이 한다. */
  value: string | number | null | undefined;
}

/**
 * 색 램프는 domain/format.ddayColor 와 같은 경계지만, 원본은 0일과 2일 이하를 같은 빨강으로
 * 칠했다(app.js:4005 의 `r.days === 0 ? '#c0392b' : r.days <= 2 ? '#c0392b' : ...`).
 * 값이 같은 두 분기라 하나로 합쳐도 표시는 동일하다.
 */
function ddayStyle(info: DdayInfo): string {
  if (info.days <= 2) return 'var(--danger)';
  if (info.days <= 7) return 'var(--warn)';
  return 'var(--success)';
}

export function DDayBadge({ value }: DDayBadgeProps) {
  const info = calcDday(value);
  if (!info) return <span className="cell-empty">-</span>;
  if (info.expired) return <span className="dday-expired">마감</span>;
  return (
    <span className="dday" style={{ color: ddayStyle(info) }}>
      {info.text}
    </span>
  );
}

/* ─── 계약/낙찰 · 제한/자격 ──────────────────────────────────────────────── */

interface MethodStackProps {
  /** '경쟁입찰 / 적격심사' 처럼 슬래시로 이어 붙은 요약 문자열. */
  value: string;
}

export function MethodStack({ value }: MethodStackProps) {
  const parts = value
    .split('/')
    .map((s) => s.trim())
    .filter(Boolean);
  if (!parts.length) return <span className="cell-empty">-</span>;
  return (
    <div className="method-stack">
      {parts.map((part, i) => (
        <span key={`${part}-${i}`}>{part}</span>
      ))}
    </div>
  );
}

interface ReqTagsProps {
  /** 쉼표로 이어 붙은 제한/자격 요약. */
  value: string;
}

export function ReqTag({ children }: { children: ReactNode }) {
  return <span className="req-tag">{children}</span>;
}

export function ReqTags({ value }: ReqTagsProps) {
  const tags = value
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean);
  if (!tags.length) return <span className="cell-empty">-</span>;
  return (
    <div className="req-tags">
      {tags.map((tag, i) => (
        <ReqTag key={`${tag}-${i}`}>{tag}</ReqTag>
      ))}
    </div>
  );
}

/* ─── 단가 조회 상태 ─────────────────────────────────────────────────────── */

interface PriceStatusBadgeProps {
  badge: PriceBadgeData;
}

export function PriceStatusBadge({ badge }: PriceStatusBadgeProps) {
  return (
    <span className={`price-status tone-${badge.tone}`} title={badge.title}>
      {badge.label}
    </span>
  );
}
