/*
 * 공고 통합 검색의 필터 줄 — 단계 · 구분 · 상태 칩 + 지역 · 마감일 · 금액.
 *
 * 칩에 건수를 붙이는 것이 요점이다. "0건짜리 필터"를 눌러 보고 나서야 아는 것과, 누르기 전에
 * 아는 것의 차이가 크다. 건수는 **그 축을 제외한** 지금 조건에서 센 값이다 — 즉 "이 칩을
 * 누르면 몇 건이 되는가". 다른 축의 조건은 반영돼 있다(useNoticeFacets 참고).
 *
 * 값이 0 인 항목도 지우지 않고 흐리게 남긴다 — 사라지면 "그 필터가 원래 없다"로 읽힌다.
 */
import { useRef, useState, type KeyboardEvent } from 'react';
import {
  BUSINESS_DIVISIONS,
  NOTICE_CATEGORIES,
  NOTICE_STATES,
  type BusinessDivision,
  type NoticeCategory,
  type NoticeFacetBucket,
  type NoticeFacets,
  type NoticeState,
} from '@/api/search';
import { regionLabel } from '@/features/notices/indexRows';
import './search.css';

/** 패싯 건수를 값으로 바로 찾을 수 있게 폅니다. 없는 값은 0 이 아니라 undefined 다. */
function countsOf(buckets: NoticeFacetBucket[] | undefined): Map<string, number> {
  const map = new Map<string, number>();
  for (const bucket of buckets ?? []) {
    if (bucket && bucket.value != null) map.set(String(bucket.value), Number(bucket.count ?? 0));
  }
  return map;
}

interface ChipRowProps<T extends string> {
  label: string;
  values: readonly T[];
  counts: Map<string, number>;
  active: string;
  /** 빈 문자열은 '전체'(= 이 축으로는 안 좁힘). */
  onPick: (value: T | '') => void;
  /** '전체' 칩에 붙일 총 건수. */
  total?: number;
}

function ChipRow<T extends string>({
  label,
  values,
  counts,
  active,
  onPick,
  total,
}: ChipRowProps<T>) {
  return (
    <div className="type-filter" aria-label={`${label} 필터`}>
      <span className="type-filter-label">{label}</span>
      <button
        type="button"
        className={active === '' ? 'type-filter-btn active' : 'type-filter-btn'}
        onClick={() => onPick('')}
      >
        전체
        {total != null ? <em className="chip-count">{total.toLocaleString()}</em> : null}
      </button>
      {values.map((value) => {
        const count = counts.get(value);
        const classes = ['type-filter-btn'];
        if (active === value) classes.push('active');
        if (count === undefined || count === 0) classes.push('empty');
        return (
          <button
            key={value}
            type="button"
            className={classes.join(' ')}
            onClick={() => onPick(active === value ? '' : value)}
          >
            {value}
            <em className="chip-count">{(count ?? 0).toLocaleString()}</em>
          </button>
        );
      })}
    </div>
  );
}

export interface NoticeFilterValues {
  category: NoticeCategory | '';
  division: BusinessDivision | '';
  noticeState: NoticeState | '';
  region: string;
  closeFrom: string;
  closeTo: string;
  minAmount: string;
  maxAmount: string;
  activeOnly: boolean;
}

interface NoticeFilterBarProps {
  values: NoticeFilterValues;
  facets: NoticeFacets | undefined;
  /** 단계로는 안 좁혔을 때의 건수(나머지 조건은 그대로). 단계 '전체' 칩에 쓴다. */
  totalCount: number;
  onChange: (patch: Partial<NoticeFilterValues>) => void;
}

export function NoticeFilterBar({ values, facets, totalCount, onChange }: NoticeFilterBarProps) {
  const regionBuckets = facets?.region ?? [];

  /*
   * 금액은 한 글자씩 확정하면 안 된다. 조건이 곧 URL 이라 '45000000' 을 치는 동안 히스토리가
   * 여덟 칸 쌓이고 조회도 여덟 번 나간다 — 그러면 뒤로 가기가 못 쓰게 된다.
   * 확정(Enter · 포커스 이탈)될 때만 올린다. SearchHeader 의 한 줄 입력과 같은 규칙이다.
   */
  const [minDraft, setMinDraft] = useState(values.minAmount);
  const [maxDraft, setMaxDraft] = useState(values.maxAmount);

  // 프리셋·뒤로 가기처럼 조건이 밖에서 바뀌면 입력칸도 따라가야 한다.
  const syncedRef = useRef(`${values.minAmount}|${values.maxAmount}`);
  const signature = `${values.minAmount}|${values.maxAmount}`;
  if (syncedRef.current !== signature) {
    syncedRef.current = signature;
    if (minDraft !== values.minAmount) setMinDraft(values.minAmount);
    if (maxDraft !== values.maxAmount) setMaxDraft(values.maxAmount);
  }

  const commitAmounts = () => {
    if (minDraft === values.minAmount && maxDraft === values.maxAmount) return;
    onChange({ minAmount: minDraft, maxAmount: maxDraft });
  };

  const onAmountKeyDown = (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key !== 'Enter') return;
    e.preventDefault();
    commitAmounts();
  };

  return (
    <div className="notice-filters">
      <ChipRow
        label="단계"
        values={NOTICE_CATEGORIES}
        counts={countsOf(facets?.category)}
        active={values.category}
        total={totalCount}
        onPick={(category) => onChange({ category })}
      />
      <ChipRow
        label="구분"
        values={BUSINESS_DIVISIONS}
        counts={countsOf(facets?.division)}
        active={values.division}
        onPick={(division) => onChange({ division })}
      />
      {/*
        상태 넷은 전부 '예외' 상태다. 정상적으로 처음 올라온 공고는 값이 없으므로 네 건수의
        합이 총 건수와 맞지 않는 것이 정상이다 — 그래서 여기 '전체' 는 '상태 무관'을 뜻한다.
      */}
      <ChipRow
        label="상태"
        values={NOTICE_STATES}
        counts={countsOf(facets?.state)}
        active={values.noticeState}
        onPick={(noticeState) => onChange({ noticeState })}
      />

      <div className="filter-row">
        <label className="filter-field">
          <span>지역</span>
          <select
            value={values.region}
            onChange={(e) => onChange({ region: e.target.value })}
            aria-label="지역 필터"
          >
            <option value="">전체</option>
            {regionBuckets.map((bucket) => (
              <option key={bucket.value} value={bucket.value}>
                {regionLabel(bucket.value)} ({Number(bucket.count ?? 0).toLocaleString()})
              </option>
            ))}
          </select>
        </label>

        <label className="filter-field">
          <span>마감일</span>
          <input
            type="date"
            value={values.closeFrom}
            aria-label="마감 시작일"
            onChange={(e) => onChange({ closeFrom: e.target.value })}
          />
          <span className="filter-tilde">~</span>
          <input
            type="date"
            value={values.closeTo}
            aria-label="마감 종료일"
            onChange={(e) => onChange({ closeTo: e.target.value })}
          />
        </label>

        <label className="filter-field">
          <span>추정가격</span>
          <input
            type="text"
            inputMode="numeric"
            className="amount-input"
            placeholder="최소"
            aria-label="추정가격 최소"
            value={minDraft}
            onChange={(e) => setMinDraft(e.target.value.replace(/[^0-9]/g, ''))}
            onKeyDown={onAmountKeyDown}
            onBlur={commitAmounts}
          />
          <span className="filter-tilde">~</span>
          <input
            type="text"
            inputMode="numeric"
            className="amount-input"
            placeholder="최대"
            aria-label="추정가격 최대"
            value={maxDraft}
            onChange={(e) => setMaxDraft(e.target.value.replace(/[^0-9]/g, ''))}
            onKeyDown={onAmountKeyDown}
            onBlur={commitAmounts}
          />
          <span className="filter-unit">원</span>
        </label>

        {/*
          마감일시를 직접 본다(분류가 아니라). 스위퍼가 주기적으로 도는 것이라 방금 마감된
          건이 아직 '입찰'로 남아 있을 수 있는데, 이 필터는 그 지연을 물려받지 않는다.
        */}
        <label className="filter-check">
          <input
            type="checkbox"
            checked={values.activeOnly}
            onChange={(e) => onChange({ activeOnly: e.target.checked })}
          />{' '}
          마감 전 공고만 보기
        </label>
      </div>
    </div>
  );
}
