/*
 * 단가 DB 행 헬퍼 — 계약이 요구하는 규칙이 실제로 지켜지는지.
 *
 * 가장 중요한 규칙: priceKrw 는 **null 이면 미확인**이다(0 이 아니다). 빈 입력과 0 이 섞이면
 * "가격을 못 구한 것"과 "정말 0 원"이 구분되지 않는다.
 */
import { describe, expect, it } from 'vitest';
import type { PriceCatalogRow } from '@/api/price';
import {
  blankPriceRow,
  capturedText,
  draftFromRow,
  parsePriceInput,
  priceText,
  toUpdateRequest,
  toUpsertRequest,
  validateDraft,
  type PriceDbRowDraft,
} from './priceDbRows';

const ROW: PriceCatalogRow = {
  id: 42,
  source: 'itmaya',
  category: 'GPU',
  name: 'RTX 5090',
  model: 'ASUS ROG',
  priceKrw: 4500000,
  url: 'https://itmaya.example/5090',
  note: '재고 있음',
  specPreview: '32GB GDDR7 · 600W',
  capturedAt: '2026-08-07T09:12:34',
  updatedAt: '2026-08-07T09:12:34',
};

describe('blankPriceRow', () => {
  it('기본 출처는 danawa 이고 나머지는 빈 값이다', () => {
    expect(blankPriceRow()).toEqual({
      source: 'danawa',
      category: '',
      name: '',
      model: '',
      spec: '',
      priceKrw: '',
      url: '',
      note: '',
    });
  });

  it('필터에서 고른 출처를 이어받는다', () => {
    expect(blankPriceRow('enuri').source).toBe('enuri');
  });
});

describe('draftFromRow', () => {
  it('목록 행을 편집 초안으로 편다 — 규격은 미리보기로 채운다', () => {
    const draft = draftFromRow(ROW);
    expect(draft.source).toBe('itmaya');
    expect(draft.name).toBe('RTX 5090');
    expect(draft.spec).toBe('32GB GDDR7 · 600W');
    expect(draft.priceKrw).toBe('4500000');
  });

  it('미확인 단가(null)는 빈 문자열이 된다 — 0 이 아니다', () => {
    const draft = draftFromRow({ ...ROW, priceKrw: null });
    expect(draft.priceKrw).toBe('');
  });
});

describe('parsePriceInput', () => {
  it('빈 값은 null(미확인)이다', () => {
    expect(parsePriceInput('')).toBeNull();
    expect(parsePriceInput('   ')).toBeNull();
  });

  it('콤마·문자를 떼고 숫자로 읽는다', () => {
    expect(parsePriceInput('4,500,000')).toBe(4500000);
    expect(parsePriceInput('4500000')).toBe(4500000);
  });

  it('0 은 미확인이 아니라 값 0 이다', () => {
    expect(parsePriceInput('0')).toBe(0);
  });

  it('숫자가 없으면 null 이다', () => {
    expect(parsePriceInput('없음')).toBeNull();
  });
});

describe('priceText', () => {
  it('null 은 미확인으로 표시한다 — 절대 0 으로 그리지 않는다', () => {
    expect(priceText(null)).toBe('미확인');
    expect(priceText(undefined)).toBe('미확인');
  });

  it('0 원은 미확인이 아니라 0원으로 표시한다', () => {
    expect(priceText(0)).toBe('0원');
    expect(priceText(0)).not.toBe('미확인');
  });

  it('값은 콤마 금액으로 표시한다', () => {
    expect(priceText(4500000)).toBe('4,500,000원');
  });
});

describe('capturedText', () => {
  it('ISO 타임스탬프를 분까지 자른다', () => {
    expect(capturedText('2026-08-07T09:12:34')).toBe('2026-08-07 09:12');
  });

  it('없으면 빈 문자열', () => {
    expect(capturedText(null)).toBe('');
    expect(capturedText(undefined)).toBe('');
  });
});

describe('validateDraft', () => {
  it('품명이 없으면 막는다', () => {
    expect(validateDraft(blankPriceRow())).toBe('품명을 입력하세요.');
  });

  it('출처가 어휘 밖이면 막는다', () => {
    const bad: PriceDbRowDraft = { ...blankPriceRow(), name: 'X', source: 'foo' as PriceDbRowDraft['source'] };
    expect(validateDraft(bad)).toBe('출처를 골라주세요.');
  });

  it('품명과 출처가 있으면 통과한다', () => {
    expect(validateDraft({ ...blankPriceRow('itmaya'), name: 'RTX 5090' })).toBeNull();
  });
});

describe('toUpsertRequest', () => {
  it('초안을 추가 요청으로 바꾼다 — 빈 값은 null, 빈 단가는 미확인(null)', () => {
    const req = toUpsertRequest({
      source: 'danawa',
      category: ' GPU ',
      name: ' RTX 5090 ',
      model: '',
      spec: '',
      priceKrw: '',
      url: '',
      note: '',
    });
    expect(req).toEqual({
      source: 'danawa',
      category: 'GPU',
      name: 'RTX 5090',
      model: '',
      spec: '',
      priceKrw: null,
      url: null,
      note: null,
    });
  });
});

describe('toUpdateRequest', () => {
  it('수정 요청에는 출처가 없다(PUT 계약에 없다)', () => {
    const req = toUpdateRequest({ ...blankPriceRow('itmaya'), name: 'X', priceKrw: '2000000' });
    expect('source' in req).toBe(false);
    expect(req.priceKrw).toBe(2000000);
    expect(req.name).toBe('X');
  });
});
