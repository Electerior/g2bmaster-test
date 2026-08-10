/*
 * 일자별 표의 정렬·자르기 — 원본 normalizeTrendDayLimit(app.js:262) ·
 * normalizeTrendDaySort(267) · trendDayRows(2251).
 *
 * 이 계산이 순수 함수여야 하는 이유: 표시 옵션(21/60/전체, 날짜/건수/금액)을 바꿔도
 * **재조회하지 않는다**. 이미 받아 둔 byDay 배열을 다시 정렬해 그릴 뿐이다 —
 * 원본도 currentTrendData 를 들고 있다가 같은 일을 했다.
 */
import type { TrendDayBucket } from '@/api';

export type TrendDayLimit = '21' | '60' | 'all';
export type TrendDaySort = 'date' | 'count' | 'amount';

export function normalizeTrendDayLimit(value: string): TrendDayLimit {
  return value === '60' || value === 'all' ? value : '21';
}

export function normalizeTrendDaySort(value: string): TrendDaySort {
  return value === 'count' || value === 'amount' ? value : 'date';
}

export function trendDayLimitLabel(limit: TrendDayLimit): string {
  return limit === 'all' ? '전체 공고일' : `최근 ${limit}개 공고일`;
}

export function trendDaySortLabel(sort: TrendDaySort): string {
  if (sort === 'count') return '건수 많은순';
  if (sort === 'amount') return '금액 큰순';
  return '날짜순';
}

/**
 * 원본 trendDayRows 그대로.
 * 날짜순일 때만 **뒤에서** 잘라 낸다(slice(-n)) — 최근 N일을 보고 싶은 것이지
 * 가장 오래된 N일이 아니다. 건수·금액순은 위에서 N개를 자른다.
 */
export function trendDayRows(
  items: readonly TrendDayBucket[],
  limit: TrendDayLimit,
  sort: TrendDaySort,
): TrendDayBucket[] {
  const rows = [...items];
  if (sort === 'count') {
    rows.sort(
      (a, b) =>
        Number(b.count || 0) - Number(a.count || 0) ||
        String(b.name || '').localeCompare(String(a.name || '')),
    );
  } else if (sort === 'amount') {
    rows.sort(
      (a, b) =>
        Number(b.amount || 0) - Number(a.amount || 0) ||
        String(b.name || '').localeCompare(String(a.name || '')),
    );
  } else {
    rows.sort((a, b) => String(a.name || '').localeCompare(String(b.name || '')));
  }
  if (limit === 'all') return rows;
  const n = Number(limit);
  return sort === 'date' ? rows.slice(-n) : rows.slice(0, n);
}
