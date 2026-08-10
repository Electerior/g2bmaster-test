/*
 * 셀 포매터 레지스트리 — 원본 formatCell(app.js:3990) 의 switch 를 컴포넌트로 옮긴 것.
 *
 * 원본은 HTML 문자열을 돌려주고 각 분기마다 escHtml 을 손으로 불렀다. 한 군데라도 빠뜨리면
 * 서버 데이터가 그대로 innerHTML 에 들어가는 XSS 였다. JSX 는 기본이 이스케이프라 그 위험이
 * 통째로 사라진다 — 그래서 이 파일에는 escHtml 에 해당하는 것이 없다.
 */
import type { ReactNode } from 'react';
import type { CellFmt } from '@/domain/columns';
import type { DecoratedRow } from '@/api/notices';
import {
  fmtDisplayDate,
  fmtDisplayDatetime,
  fmtMoney,
} from '@/domain/format';
import {
  DDayBadge,
  MethodStack,
  OpportunityBadge,
  ReqTags,
  SourceBadge,
  StatusBadge,
  TypeBadge,
} from '@/components/badges/Badge';

/** 셀이 일으킬 수 있는 화면 전환. 화면(라우트)이 구현을 넣어 준다. */
export interface CellActions {
  /** 공고 상세 서랍 (입찰 공고 · 입찰 결과). */
  openNotice?: (row: DecoratedRow) => void;
  /** 발주 계획 상세 서랍. */
  openPlan?: (row: DecoratedRow) => void;
  /** 사전 규격 상세 서랍. */
  openSpec?: (row: DecoratedRow) => void;
  /** 다른 화면으로 질의를 넘긴다 — 원본 crossTabSearch(app.js:4888). */
  crossSearch?: (target: CrossTarget, seed: CrossSeed) => void;
}

export type CrossTarget = 'bid-announce' | 'bid-result';

export interface CrossSeed {
  andTerms?: string[];
  bidNtceNo?: string;
  bidType?: string;
}

interface CellProps {
  value: unknown;
  fmt?: CellFmt;
  row: DecoratedRow;
  actions?: CellActions;
}

const EMPTY = <span className="cell-empty">-</span>;

/** 문자열/문자열 배열을 쉼표 목록으로. 사전 규격의 bidNtceNoList 가 두 형태로 온다. */
function toList(value: unknown): string[] {
  const raw = Array.isArray(value) ? value.map(String) : String(value ?? '').split(',');
  return raw.map((s) => s.trim()).filter(Boolean);
}

export function Cell({ value, fmt, row, actions }: CellProps): ReactNode {
  // 원본과 같은 조기 반환 — null/빈 문자열은 포맷 종류와 무관하게 '-'.
  if (value == null || value === '') return EMPTY;
  const text = String(value);

  switch (fmt) {
    case 'money':
      return <span className="amt">{fmtMoney(text)}</span>;

    case 'date':
      return fmtDisplayDate(text);

    case 'datetime':
      return fmtDisplayDatetime(text);

    case 'rate':
      return <span className="badge">{text}%</span>;

    case 'd-day':
      return <DDayBadge value={text} />;

    case 'tel':
      return (
        <a href={`tel:${text}`} style={{ whiteSpace: 'nowrap' }}>
          {text}
        </a>
      );

    case 'email':
      return <a href={`mailto:${text}`}>{text}</a>;

    case 'type-badge':
      return <TypeBadge value={text} />;

    case 'source-badge':
      return <SourceBadge label={text} source={row._source} />;

    case 'status-badge':
      return <StatusBadge label={text} cancelled={row._isCancelled} />;

    case 'method-summary':
      return <MethodStack value={text} />;

    case 'requirement-tags':
      return <ReqTags value={text} />;

    case 'opportunity':
      return (
        <OpportunityBadge
          score={Number(row._opportunityScore || 0)}
          grade={row._opportunityGrade}
          action={row._opportunityAction}
          reasons={row._opportunityReasons}
          specLockSummary={row._specLockSummary}
        />
      );

    case 'link':
      return (
        <a href={text} target="_blank" rel="noopener noreferrer">
          링크 ↗
        </a>
      );

    case 'ntce-link':
      return (
        <button type="button" className="bid-link" onClick={() => actions?.openNotice?.(row)}>
          {text}
        </button>
      );

    case 'plan-link':
      return (
        <button type="button" className="bid-link" onClick={() => actions?.openPlan?.(row)}>
          {text}
        </button>
      );

    case 'spec-link':
      return (
        <button type="button" className="bid-link" onClick={() => actions?.openSpec?.(row)}>
          {text}
        </button>
      );

    case 'ntce-cross': {
      // 사전 규격 → 그 규격에서 나온 입찰 공고. 여러 건이면 번호를 콤마로 이어 넘긴다.
      const nos = toList(value);
      if (!nos.length) return EMPTY;
      return (
        <button
          type="button"
          className="cross-tab-btn"
          onClick={() => actions?.crossSearch?.('bid-announce', { bidNtceNo: nos.join(',') })}
        >
          {nos.length === 1 ? '공고보기 →' : `공고 ${nos.length}건 →`}
        </button>
      );
    }

    case 'result-cross': {
      // 입찰 공고 → 같은 이름의 낙찰 결과. 번호가 아니라 공고명으로 찾는다(차수가 다르다).
      const name = row.bidNtceNm;
      if (!name) return EMPTY;
      return (
        <button
          type="button"
          className="cross-tab-btn plain"
          onClick={() => actions?.crossSearch?.('bid-result', { andTerms: [String(name)] })}
        >
          낙찰결과 →
        </button>
      );
    }

    case 'announce-cross':
      // 입찰 결과 → 그 공고의 상세 서랍. 원본은 같은 .bid-link 위임을 재사용했다.
      return row.bidNtceNo ? (
        <button
          type="button"
          className="cross-tab-btn plain"
          onClick={() => actions?.openNotice?.(row)}
        >
          공고보기 →
        </button>
      ) : (
        EMPTY
      );

    default:
      return text;
  }
}
