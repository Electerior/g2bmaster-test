/*
 * 계약이 명시적으로 요구하는 표기 규칙들. 어기면 사용자가 필터를 고장 난 것으로 오해한다.
 */
import { describe, expect, it } from 'vitest';
import type { NoticeIndexItem } from '@/api/search';
import { ddayLabel, institutionPair, lowestBidRateText, productSummary, regionLabel } from './indexRows';

describe('regionLabel', () => {
  it('빈 지역은 전국이다 — 지역 제한이 없다는 뜻이지 값이 없는 게 아니다', () => {
    expect(regionLabel('')).toBe('전국');
    expect(regionLabel(null)).toBe('전국');
    expect(regionLabel(undefined)).toBe('전국');
    expect(regionLabel('   ')).toBe('전국');
  });

  it('지역이 있으면 그대로 쓴다(복수는 콤마 구분)', () => {
    expect(regionLabel('서울특별시')).toBe('서울특별시');
    expect(regionLabel('경기도,인천광역시')).toBe('경기도,인천광역시');
  });
});

describe('institutionPair', () => {
  const base = { id: 'x' } as NoticeIndexItem;

  it('공고기관과 수요기관이 다르면 둘 다 준다 — 조달청 대행 공고가 그렇다', () => {
    expect(
      institutionPair({
        ...base,
        noticeInstitutionName: '조달청 강원지방조달청',
        demandInstitutionName: '강원대학교',
      }),
    ).toEqual({ primary: '조달청 강원지방조달청', demand: '강원대학교' });
  });

  it('같으면 수요기관을 따로 그리지 않는다', () => {
    expect(
      institutionPair({ ...base, noticeInstitutionName: '수원시청', demandInstitutionName: '수원시청' }),
    ).toEqual({ primary: '수원시청', demand: '' });
  });

  it('공고기관이 없으면 수요기관으로 떨어진다', () => {
    expect(institutionPair({ ...base, demandInstitutionName: '서울대학교' })).toEqual({
      primary: '서울대학교',
      demand: '',
    });
  });

  it('코드가 없어도 이름만으로 그린다 — 사전규격은 기관코드가 아예 없다', () => {
    expect(institutionPair({ ...base, noticeInstitutionName: '서울대학교' }).primary).toBe('서울대학교');
  });
});

describe('ddayLabel', () => {
  it('서버가 센 일수를 그대로 읽는다', () => {
    expect(ddayLabel(37)).toBe('D-37');
    expect(ddayLabel(1)).toBe('D-1');
  });

  it('오늘 마감은 0 이다', () => {
    expect(ddayLabel(0)).toBe('D-DAY');
  });

  it('지난 것은 음수로 온다', () => {
    expect(ddayLabel(-36)).toBe('마감');
  });

  it('마감이 없는 계획 단계는 값 자체가 오지 않는다', () => {
    expect(ddayLabel(undefined)).toBeNull();
    expect(ddayLabel(null)).toBeNull();
  });
});

describe('lowestBidRateText', () => {
  it('백분율 그대로다 — 100을 곱하지 않는다', () => {
    expect(lowestBidRateText(88.0)).toBe('88%');
    expect(lowestBidRateText(87.745)).toBe('87.745%');
  });

  it('0 으로 끝나는 정수를 깎지 않는다', () => {
    expect(lowestBidRateText(80)).toBe('80%');
    expect(lowestBidRateText(100)).toBe('100%');
  });

  it('없으면 빈 문자열 — 서랍의 그 줄이 통째로 빠진다', () => {
    expect(lowestBidRateText(null)).toBe('');
    expect(lowestBidRateText(undefined)).toBe('');
  });
});

describe('productSummary', () => {
  it('첫 품명과 나머지 건수', () => {
    expect(productSummary([{ name: '노트북컴퓨터' }, { name: '모니터' }])).toBe('노트북컴퓨터 외 1건');
    expect(productSummary([{ name: '노트북컴퓨터' }])).toBe('노트북컴퓨터');
    expect(productSummary([])).toBe('');
    expect(productSummary(undefined)).toBe('');
  });
});
