/*
 * 첨부 전수조사는 백엔드에 표면이 없다 — 그 사실이 조건 판정에 반영돼 있는지 본다.
 *
 * 회귀 내용: `shouldScanAttachments` 가 체크박스와 무관하게 참이 되도록 쓰여 있어서
 * (`isBlockingRelevant` 만으로도 참) 공고 표 화면이 없는 `POST /api/scan-attachments` 를
 * 불렀다. 404 로 스캔이 실패하면 화면은 `rows = scan.data?.rows ?? []` 로 **빈 표**를
 * 그린다 — 검색은 성공했는데 결과가 0건으로 보이는, 오류처럼 안 보이는 고장이다.
 *
 * 검색창의 입력은 이미 '준비 중'으로 막혀 있지만 **막힌 것은 입력이지 조건이 아니다.**
 * 조건의 단일 출처는 URL 이라 예전에 공유된 `?file=...` 링크가 그대로 살아 있다.
 */
import { describe, expect, it } from 'vitest';
import {
  ATTACHMENT_SCAN_READY,
  buildQuery,
  DEFAULT_CRITERIA,
  isBlockingRelevant,
  shouldScanAttachments,
  type SearchCriteria,
} from './useSearchCriteria';

function criteria(patch: Partial<SearchCriteria> = {}): SearchCriteria {
  return { ...DEFAULT_CRITERIA, ...patch };
}

describe('shouldScanAttachments', () => {
  it('백엔드에 스캔 표면이 없는 동안은 URL 에 파일 키워드가 있어도 켜지지 않는다', () => {
    // 이 테스트가 지키는 것은 "스캔을 하지 않는다"가 아니라 "없는 엔드포인트를 부르지
    // 않는다"이다. 백엔드가 표면을 내면 ATTACHMENT_SCAN_READY 를 true 로 되돌리고
    // 이 기대값도 함께 뒤집는다.
    expect(ATTACHMENT_SCAN_READY).toBe(false);
    expect(shouldScanAttachments(criteria({ fileKeywords: ['제조사'] }), 'bid-announce')).toBe(false);
  });

  it('블로킹 판정이 의미있는 화면에서도 켜지지 않는다 — 예전엔 여기서 늘 참이었다', () => {
    // 원본 판정 자체는 살아 있다. 되살릴 때 무엇이 참이어야 하는지를 함께 박아 둔다.
    expect(isBlockingRelevant('bid-announce', DEFAULT_CRITERIA.mode)).toBe(true);
    expect(shouldScanAttachments(criteria(), 'bid-announce')).toBe(false);
    expect(shouldScanAttachments(criteria(), 'bid-plan')).toBe(false);
    expect(shouldScanAttachments(criteria(), 'pre-spec')).toBe(false);
  });
});

describe('buildQuery', () => {
  it('스캔하지 않으므로 fileScan(캐시 우회)도 보내지 않는다', () => {
    // fileScan=true 는 백엔드에서 검색 캐시를 우회한다. 스캔하지도 않으면서 매 조회마다
    // 우회하면 나라장터 팬아웃만 그대로 더 태운다.
    expect(buildQuery(criteria({ fileKeywords: ['제조사'] }), 'bid-announce').fileScan).toBeUndefined();
    expect(buildQuery(criteria(), 'bid-announce').fileScan).toBeUndefined();
  });

  it('후보 전체(pageNo=0)로 넓히지 않는다 — 스캔용 확장이었다', () => {
    expect(buildQuery(criteria(), 'bid-announce').pageNo).not.toBe(0);
  });
});
