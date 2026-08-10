/*
 * 단가 DB 행 모델과 순수 변환. 컴포넌트(PriceDbTable)와 분리한다 — 같은 파일에서 함수와
 * 컴포넌트를 함께 내보내면 HMR 이 그 모듈을 컴포넌트 모듈로 못 보고 화면 상태를 날린다
 * (priceRows.ts 와 같은 이유).
 *
 * 핵심 규칙: `priceKrw` 는 **null 이면 미확인**이다(0 이 아니다). 편집 초안은 문자열로 들고
 * 있다가(빈 문자열 = 미확인) 저장할 때만 number|null 로 바꾼다 — 입력 중 0 과 빈칸이 섞이지
 * 않게 하려는 것이다.
 */
import { fmtMoney } from '@/domain/format';
import {
  PRICE_SOURCES,
  type PriceCatalogRow,
  type PriceSource,
  type UpdatePriceRequest,
  type UpsertPriceRequest,
} from '@/api/price';

/** 편집·추가 폼이 들고 있는 초안. 저장 계약과 달리 값을 전부 문자열로 둔다. */
export interface PriceDbRowDraft {
  source: PriceSource;
  category: string;
  name: string;
  model: string;
  spec: string;
  /** 빈 문자열 = 미확인. 숫자 문자열이면 그 값. */
  priceKrw: string;
  url: string;
  note: string;
}

/** 새 행 초안 — 출처는 필터에서 고른 값을 이어받는다. */
export function blankPriceRow(source: PriceSource = 'danawa'): PriceDbRowDraft {
  return { source, category: '', name: '', model: '', spec: '', priceKrw: '', url: '', note: '' };
}

/**
 * 목록 행 → 편집 초안. 규격은 목록이 미리보기(specPreview)만 주므로 그것으로 채운다 —
 * 전문이 필요하면 저장 시 서버가 기존 값을 유지한다.
 */
export function draftFromRow(row: PriceCatalogRow): PriceDbRowDraft {
  return {
    source: row.source,
    category: row.category ?? '',
    name: row.name ?? '',
    model: row.model ?? '',
    spec: row.specPreview ?? '',
    priceKrw: row.priceKrw == null ? '' : String(row.priceKrw),
    url: row.url ?? '',
    note: row.note ?? '',
  };
}

/** 단가 입력 → 저장값. 빈 값은 null(미확인)이다 — 0 과 구분한다. */
export function parsePriceInput(raw: string): number | null {
  const digits = String(raw ?? '').replace(/[^0-9]/g, '');
  return digits === '' ? null : Number(digits);
}

/** 단가 표시 — null 은 '미확인'(0 이 아니다), 값은 콤마 금액. */
export function priceText(priceKrw: number | null | undefined): string {
  return priceKrw == null ? '미확인' : fmtMoney(priceKrw);
}

/** ISO 타임스탬프 → 'YYYY-MM-DD HH:mm'. capturedAt·updatedAt 표시용. */
export function capturedText(value: string | null | undefined): string {
  return String(value ?? '')
    .slice(0, 16)
    .replace('T', ' ');
}

/** 저장 전 검증 — 어기면 요청을 보내지 않고 폼에 사유를 띄운다. */
export function validateDraft(draft: PriceDbRowDraft): string | null {
  if (!(PRICE_SOURCES as readonly string[]).includes(draft.source)) return '출처를 골라주세요.';
  if (!draft.name.trim()) return '품명을 입력하세요.';
  return null;
}

/** 초안 → 추가(POST) 요청. */
export function toUpsertRequest(draft: PriceDbRowDraft): UpsertPriceRequest {
  return {
    source: draft.source,
    category: draft.category.trim(),
    name: draft.name.trim(),
    model: draft.model.trim(),
    spec: draft.spec.trim(),
    priceKrw: parsePriceInput(draft.priceKrw),
    url: draft.url.trim() || null,
    note: draft.note.trim() || null,
  };
}

/** 초안 → 수정(PUT) 요청. 출처는 수정 계약에 없어 보내지 않는다. */
export function toUpdateRequest(draft: PriceDbRowDraft): UpdatePriceRequest {
  return {
    category: draft.category.trim(),
    name: draft.name.trim(),
    model: draft.model.trim(),
    spec: draft.spec.trim(),
    priceKrw: parsePriceInput(draft.priceKrw),
    url: draft.url.trim() || null,
    note: draft.note.trim() || null,
  };
}
