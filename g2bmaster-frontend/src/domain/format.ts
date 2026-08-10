/*
 * 표시 포매팅 — 원본 app.js 유틸(5465~5535행) + trendMoney(2179) 을 옮긴 것.
 *
 * G2B API 는 날짜를 'YYYYMMDDHHmm' 같은 구분자 없는 문자열로, 금액을 콤마 섞인 문자열로
 * 내려준다. 값이 잘렸거나 형식이 다를 때 원본은 예외를 던지지 않고 "받은 문자열 그대로"를
 * 돌려줬다 — 표가 깨지는 것보다 낫다는 판단이고, 그 동작을 그대로 유지한다.
 */

/** 화면 입력(YYYY-MM-DD) → G2B 질의 형식(YYYYMMDD). 원본 toG2bDate. */
export function toG2bDate(iso: string): string {
  return iso.replace(/-/g, '');
}

/** Date → YYYY-MM-DD (UTC 기준). 원본 fmtInputDate. */
export function fmtInputDate(d: Date): string {
  return d.toISOString().slice(0, 10);
}

/**
 * 로컬 타임존 기준 오늘 날짜. 원본 localDateString.
 * toISOString() 은 UTC 라 한국(UTC+9)에서 그냥 쓰면 자정~09시에 하루 어긋난다.
 */
export function localDateString(date: Date = new Date()): string {
  const offsetMs = date.getTimezoneOffset() * 60000;
  return new Date(date.getTime() - offsetMs).toISOString().slice(0, 10);
}

/** 'YYYYMMDD…' → 'YYYY-MM-DD'. 8자리 미만이면 원본 문자열 그대로. */
export function fmtDisplayDate(s: string): string {
  const m = s.replace(/\D/g, '');
  if (m.length >= 8) return `${m.slice(0, 4)}-${m.slice(4, 6)}-${m.slice(6, 8)}`;
  return s;
}

/** 'YYYYMMDDHHmm…' → 'YYYY-MM-DD HH:mm'. 12자리 미만이면 날짜까지만. */
export function fmtDisplayDatetime(s: string): string {
  const m = s.replace(/\D/g, '');
  if (m.length >= 12) {
    return `${m.slice(0, 4)}-${m.slice(4, 6)}-${m.slice(6, 8)} ${m.slice(8, 10)}:${m.slice(10, 12)}`;
  }
  return fmtDisplayDate(s);
}

/** 금액 → '1,234,000원'. 숫자로 못 읽으면 원본 문자열 그대로. 원본 formatCell 'money'. */
export function fmtMoney(val: string | number | null | undefined): string {
  if (val == null || val === '') return '-';
  const n = Number(String(val).replace(/,/g, ''));
  return Number.isNaN(n) ? String(val) : `${n.toLocaleString()}원`;
}

/** 낙찰률 → '87.421%'. 원본은 값을 그대로 두고 % 만 붙였다. */
export function fmtRate(val: string | number | null | undefined): string {
  if (val == null || val === '') return '-';
  return `${val}%`;
}

export interface DdayInfo {
  expired: boolean;
  days: number;
  hours: number;
  text: string;
}

/**
 * 마감까지 남은 시간. 원본 calcDday(app.js:5518).
 * 시·분이 없는 값은 그날 23:59 마감으로 본다 — 날짜만 있는 공고를 당일 0시에
 * 마감 처리해 버리면 아직 넣을 수 있는 건이 목록에서 사라진다.
 */
export function calcDday(val: string | number | null | undefined): DdayInfo | null {
  if (!val) return null;
  const m = String(val).replace(/\D/g, '');
  if (m.length < 8) return null;
  const deadline = new Date(
    parseInt(m.slice(0, 4), 10),
    parseInt(m.slice(4, 6), 10) - 1,
    parseInt(m.slice(6, 8), 10),
    m.length >= 10 ? parseInt(m.slice(8, 10), 10) : 23,
    m.length >= 12 ? parseInt(m.slice(10, 12), 10) : 59,
  );
  const diffMs = deadline.getTime() - Date.now();
  if (diffMs <= 0) return { expired: true, days: 0, hours: 0, text: '마감' };
  const days = Math.floor(diffMs / 86400000);
  const hours = Math.floor((diffMs % 86400000) / 3600000);
  const text = days === 0 ? `D-당일 ${hours}시간` : `D-${days}일 ${hours}시간`;
  return { expired: false, days, hours, text };
}

/**
 * D-DAY 색 램프 — 원본 formatCell 'd-day'(app.js:4005).
 * 0~2일 빨강, 7일 이하 주황, 그 외 초록. 마감된 건은 색을 쓰지 않는다.
 */
export function ddayColor(info: DdayInfo): string {
  if (info.expired) return 'var(--muted)';
  if (info.days <= 2) return 'var(--danger)';
  if (info.days <= 7) return 'var(--warn)';
  return 'var(--success)';
}

/**
 * 트렌드 요약용 축약 금액 — 원본 trendMoney(app.js:2179).
 * 억 단위는 10억 이상이면 소수 1자리, 그 미만은 2자리. 뒤따르는 0 은 떼어 '1.5억' 처럼 만든다.
 */
export function trendMoney(n: number | string | null | undefined): string {
  const value = Number(n) || 0;
  if (!value) return '-';
  if (value >= 100000000) {
    return `${(value / 100000000).toFixed(value >= 1000000000 ? 1 : 2).replace(/\.?0+$/, '')}억`;
  }
  if (value >= 10000) return `${Math.round(value / 10000).toLocaleString()}만`;
  return `${value.toLocaleString()}원`;
}

/** 축약하지 않은 전체 금액. 원본 trendFullMoney(app.js:2187). */
export function trendFullMoney(n: number | string | null | undefined): string {
  const value = Number(n) || 0;
  return value ? `${value.toLocaleString()}원` : '-';
}

/**
 * 수주기회 등급 — 원본 formatCell 'opportunity'(app.js:4040).
 * 서버가 _opportunityGrade 를 주면 그것을 쓰고, 없으면 점수로 되계산한다.
 */
export type OpportunityGrade = 'S' | 'A' | 'B' | 'C' | 'D';

export function opportunityGrade(score: number): OpportunityGrade {
  if (score >= 75) return 'S';
  if (score >= 60) return 'A';
  if (score >= 45) return 'B';
  if (score >= 30) return 'C';
  return 'D';
}

/** 구분 배지 색 — 원본 formatCell 'type-badge'(app.js:4013). */
export function typeBadgeColors(type: string): { fg: string; bg: string } {
  if (type === '물품') return { fg: 'var(--type-goods-fg)', bg: 'var(--type-goods-bg)' };
  if (type === '용역') return { fg: 'var(--type-service-fg)', bg: 'var(--type-service-bg)' };
  if (type === '공사') return { fg: 'var(--type-works-fg)', bg: 'var(--type-works-bg)' };
  if (type === '외자') return { fg: 'var(--type-foreign-fg)', bg: 'var(--type-foreign-bg)' };
  return { fg: 'var(--type-etc-fg)', bg: 'var(--type-etc-bg)' };
}
