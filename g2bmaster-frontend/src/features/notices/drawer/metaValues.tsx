/*
 * 서랍 메타 격자의 값 만들기 — 원본 openBidModal/openPlanModal/openSpecModal 안에 각각
 * 복사돼 있던 지역 함수 `fmt(v, type)` 와 `pick(...)`.
 *
 * 컴포넌트가 아니라 값을 만드는 함수들이라 컴포넌트 파일과 나눠 둔다(HMR 이 컴포넌트
 * 모듈만 갱신할 수 있어야 서랍을 열어 둔 채로 고칠 수 있다).
 */
import type { ReactNode } from 'react';
import { DDayBadge } from '@/components/badges/Badge';
import type { MetaRow } from '@/components/overlay/Drawer';
import { fmtDisplayDate, fmtDisplayDatetime, fmtMoney } from '@/domain/format';

/** 금액. 값이 없으면 빈 문자열 — buildMetaRows 가 그 줄을 통째로 버린다. */
export function moneyText(value: unknown): string {
  if (value == null || value === '') return '';
  return fmtMoney(value as string | number);
}

export function dateText(value: unknown): string {
  return value ? fmtDisplayDate(String(value)) : '';
}

export function datetimeText(value: unknown): string {
  return value ? fmtDisplayDatetime(String(value)) : '';
}

/** 여러 후보 필드 중 첫 값. 원본 pick(app.js:2531). */
export function pick(...values: unknown[]): string {
  const found = values.find((v) => v != null && String(v).trim() !== '');
  return found == null ? '' : String(found);
}

/**
 * 값이 있는 줄만 남긴다 — 원본 `.filter(([, v]) => v && v !== '-')`.
 * G2B 는 오퍼레이션마다 필드가 절반쯤 비어 오므로, 빈 줄을 그리면 격자가 구멍투성이가 된다.
 *
 * 세 번째 원소(`wide`)는 값이 긴 항목(기관명·품명 등)에 준다 — 카드 격자에서 두 칸을 써
 * 줄바꿈 없이 읽히게 한다.
 */
type MetaEntry = readonly [string, ReactNode] | readonly [string, ReactNode, boolean];

export function buildMetaRows(entries: ReadonlyArray<MetaEntry>): MetaRow[] {
  return entries
    .filter(([, value]) => value != null && value !== '' && value !== '-')
    .map(([label, value, wide]) => ({ label, value, wide: Boolean(wide) }));
}

export function telLink(value: unknown): ReactNode {
  const tel = pick(value);
  return tel ? <a href={`tel:${tel}`}>{tel}</a> : '';
}

export function emailLink(value: unknown): ReactNode {
  const email = pick(value);
  return email ? <a href={`mailto:${email}`}>{email}</a> : '';
}

/** D-DAY 줄. 값이 없으면 빈 문자열을 돌려 줄째 버리게 한다. */
export function ddayValue(value: unknown): ReactNode {
  return value ? <DDayBadge value={value as string} /> : '';
}
