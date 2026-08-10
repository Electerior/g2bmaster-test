/*
 * 조건 ↔ 질의 변환. 여기서 틀리면 **오류 없이 조용히 틀린 결과**가 나오는 자리라 테스트가 있다.
 *
 * 백엔드는 못 읽는 날짜를 400 이 아니라 '필터 없음'으로 넘기고, 화이트리스트 밖의 정렬 키도
 * 기본값으로 떨어뜨린다. 화면이 형식을 잘못 보내도 아무도 소리치지 않는다는 뜻이다.
 */
import { describe, expect, it } from 'vitest';
import {
  buildNoticeFacetQuery,
  buildNoticeIndexQuery,
  criteriaFromParams,
  criteriaToParams,
  DEFAULT_CRITERIA,
  effectiveIndexSort,
  type SearchCriteria,
} from './useSearchCriteria';

function criteria(patch: Partial<SearchCriteria> = {}): SearchCriteria {
  return { ...DEFAULT_CRITERIA, ...patch };
}

describe('buildNoticeIndexQuery', () => {
  it('날짜를 YYYY-MM-DD 그대로 보낸다 — YYYYMMDD 로 보내면 서버가 조용히 무시한다', () => {
    const query = buildNoticeIndexQuery(
      criteria({ fromDate: '2026-08-01', toDate: '2026-08-31', closeFrom: '2026-09-01' }),
    );
    expect(query.fromDate).toBe('2026-08-01');
    expect(query.toDate).toBe('2026-08-31');
    expect(query.closeFrom).toBe('2026-09-01');
  });

  it('bidType 을 division 으로 옮긴다', () => {
    expect(buildNoticeIndexQuery(criteria({ bidType: '외자' })).division).toBe('외자');
  });

  it('빈 조건은 파라미터를 아예 붙이지 않는다', () => {
    const query = buildNoticeIndexQuery(criteria());
    expect(query.andTerms).toBeUndefined();
    expect(query.category).toBeUndefined();
    expect(query.region).toBeUndefined();
    expect(query.activeOnly).toBeUndefined();
  });

  it('activeOnly 는 켜졌을 때만 문자열 true 로 간다', () => {
    expect(buildNoticeIndexQuery(criteria({ activeOnly: true })).activeOnly).toBe('true');
    expect(buildNoticeIndexQuery(criteria({ activeOnly: false })).activeOnly).toBeUndefined();
  });

  it('perPage 를 서버 상한 500 으로 클램프한다', () => {
    // 기존 4탭의 '전체'(99999)가 조건에 남아 있어도 색인 검색에는 그대로 나가면 안 된다.
    expect(buildNoticeIndexQuery(criteria({ perPage: 99999 })).perPage).toBe(500);
    expect(buildNoticeIndexQuery(criteria({ perPage: 50 })).perPage).toBe(50);
  });

  it('금액은 숫자로 보낸다', () => {
    const query = buildNoticeIndexQuery(criteria({ minAmount: '1000000', maxAmount: '5000000' }));
    expect(query.minAmount).toBe(1000000);
    expect(query.maxAmount).toBe(5000000);
  });

  it('화이트리스트 밖의 정렬 키는 보내지 않는다', () => {
    // 옛 조건(예: bidNtceDt)이 URL 에 남아 있어도 색인 검색으로는 새어 나가면 안 된다.
    expect(buildNoticeIndexQuery(criteria({ sortKey: 'bidNtceDt' })).sort).toBeUndefined();
    expect(buildNoticeIndexQuery(criteria({ sortKey: 'close', sortDir: 'asc' })).sort).toBe('close');
  });

  it('정렬을 고르지 않으면 sort 를 아예 보내지 않는다', () => {
    // 서버가 "검색어 있으면 관련도, 없으면 최신"을 정한다. 화면이 고정값을 보내면 그 규칙이 죽는다.
    expect(buildNoticeIndexQuery(criteria({ andTerms: ['노트북'] })).sort).toBeUndefined();
  });

  it('키워드 입력줄이 숨은 모드에서는 남아 있던 낱말을 보내지 않는다', () => {
    // 보이지 않는 조건이 결과를 깎으면 사용자는 왜 0건인지 알 방법이 없다.
    const hidden = buildNoticeIndexQuery(
      criteria({ mode: 'instt', andTerms: ['노트북'], insttNm: '강원대학교' }),
    );
    expect(hidden.andTerms).toBeUndefined();
    expect(hidden.insttNm).toBe('강원대학교');

    const visible = buildNoticeIndexQuery(criteria({ mode: 'keyword', andTerms: ['노트북'] }));
    expect(visible.andTerms).toBe('노트북');
  });
});

describe('buildNoticeFacetQuery', () => {
  it('조건은 같고 페이지·정렬만 뺀다', () => {
    const base = criteria({ andTerms: ['노트북'], category: '입찰', pageNo: 3, sortKey: 'close' });
    const facet = buildNoticeFacetQuery(base);
    expect(facet.andTerms).toBe('노트북');
    expect(facet.category).toBe('입찰');
    expect(facet.page).toBeUndefined();
    expect(facet.perPage).toBeUndefined();
    expect(facet.sort).toBeUndefined();
    expect(facet.dir).toBeUndefined();
  });

  it('축을 지정하면 그 축의 필터만 뺀다 — 나머지 조건은 남는다', () => {
    // 서버 패싯은 목록과 한 WHERE 를 쓴다. category 를 남긴 채 물으면 GROUP BY 가
    // 고른 값 한 줄만 돌려주므로, 나머지 단계 칩이 전부 0건으로 그려진다.
    const base = criteria({ category: '입찰', bidType: '물품', region: '경기도', activeOnly: true });
    const facet = buildNoticeFacetQuery(base, 'category');
    expect(facet.category).toBeUndefined();
    expect(facet.division).toBe('물품');
    expect(facet.region).toBe('경기도');
    expect(facet.activeOnly).toBe('true');
  });

  it('축 이름은 조건 필드가 아니라 질의 파라미터 쪽을 따른다', () => {
    const base = criteria({ bidType: '용역', noticeState: '취소' });
    expect(buildNoticeFacetQuery(base, 'division').division).toBeUndefined();
    expect(buildNoticeFacetQuery(base, 'division').state).toBe('취소');
    expect(buildNoticeFacetQuery(base, 'state').state).toBeUndefined();
    expect(buildNoticeFacetQuery(base, 'state').division).toBe('용역');
  });
});

describe('effectiveIndexSort', () => {
  it('검색어가 없으면 최신순, 있으면 관련도순', () => {
    expect(effectiveIndexSort(criteria()).key).toBe('created');
    expect(effectiveIndexSort(criteria({ andTerms: ['노트북'] })).key).toBe('relevance');
    expect(effectiveIndexSort(criteria({ notTerms: ['임대'] })).key).toBe('relevance');
  });

  it('키워드가 숨은 모드에서는 최신순으로 돌아간다', () => {
    // 걸리지도 않는 낱말 때문에 관련도(전부 0점) 정렬로 서면 순서가 사실상 무작위가 된다.
    expect(effectiveIndexSort(criteria({ mode: 'instt', andTerms: ['노트북'] })).key).toBe('created');
  });

  it('사용자가 고른 정렬이 있으면 그것이 이긴다', () => {
    const sort = effectiveIndexSort(criteria({ andTerms: ['노트북'], sortKey: 'amount', sortDir: 'asc' }));
    expect(sort).toEqual({ key: 'amount', dir: 'asc' });
  });
});

describe('URL 코덱 왕복', () => {
  it('색인 전용 조건이 왕복해도 그대로다', () => {
    const original = criteria({
      andTerms: ['노트북', '모니터'],
      notTerms: ['임대'],
      category: '사전규격',
      noticeState: '정정',
      bidType: '외자',
      region: '강원특별자치도',
      closeFrom: '2026-09-01',
      closeTo: '2026-09-30',
      minAmount: '1000000',
      maxAmount: '5000000',
      detailProductCode: '4110',
      beforeSpecRgstNo: '20260728-사전-0042',
      activeOnly: true,
      perPage: 100,
      pageNo: 4,
      sortKey: 'amount',
      sortDir: 'desc',
    });
    expect(criteriaFromParams(criteriaToParams(original))).toEqual(original);
  });

  it('기본 조건은 주소에 아무것도 남기지 않는다', () => {
    expect(criteriaToParams(DEFAULT_CRITERIA).toString()).toBe('');
    expect(criteriaFromParams(new URLSearchParams())).toEqual(DEFAULT_CRITERIA);
  });

  it('모르는 단계·상태 값은 필터 없음으로 떨어진다', () => {
    const parsed = criteriaFromParams(new URLSearchParams('cat=낙찰&state=보류'));
    expect(parsed.category).toBe('');
    expect(parsed.noticeState).toBe('');
  });

  it('예전에 공유된 active=false 주소도 여전히 꺼짐으로 읽힌다', () => {
    expect(criteriaFromParams(new URLSearchParams('active=false')).activeOnly).toBe(false);
    expect(criteriaFromParams(new URLSearchParams('active=true')).activeOnly).toBe(true);
  });
});
