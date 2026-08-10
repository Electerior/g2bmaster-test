/*
 * 단가 카드가 숫자 옆에 함께 보여야 할 사실들 — 못 찾은 이유, 어느 경로로 찾았는지,
 * 어느 판매처를 못 읽었는지. 숫자만 있으면 그 값이 얼마나 믿을 만한지 알 수 없다.
 *
 * 원본 public/price-status.js 를 그대로 옮겼다. 함수명(priceStatusBadges, siteLabel)을
 * 유지해야 기존 node 테스트가 그대로 산다.
 * 딜 레이더 카드와 업로드 분석 섹션이 같은 가격 표를 쓰므로 판정도 한 벌만 둔다.
 */

/**
 * 도메인 → 사람이 쓰는 이름. price-web.js 의 PLATFORM_ALIAS 를 표시용으로 뒤집은 것이라
 * 그쪽에 플랫폼을 추가하면 여기도 같이 봐야 한다. 모르는 도메인은 TLD만 떼고 그대로 쓴다.
 */
const SITE_LABELS: Readonly<Record<string, string>> = {
  'danawa.com': '다나와',
  'compuzone.co.kr': '컴퓨존',
  'enuri.com': '에누리',
  'coupang.com': '쿠팡',
  '11st.co.kr': '11번가',
  'gmarket.co.kr': '지마켓',
  'auction.co.kr': '옥션',
  'shopping.naver.com': '네이버쇼핑',
  'search.shopping.naver.com': '네이버쇼핑',
  'interpark.com': '인터파크',
};

export function siteLabel(site: string | null | undefined): string {
  const host = String(site || '').replace(/^www\./, '');
  return SITE_LABELS[host] || host.split('.')[0] || host;
}

/** tone 은 화면 색만 정한다. id 가 계약이고 label/title 은 문구다. */
export type PriceBadgeTone = 'ok' | 'warn' | 'bad';

export type PriceBadgeId = 'error' | 'no-match' | 'none' | 'relaxed' | 'link' | 'snippet' | 'misses';

export interface PriceStatusBadge {
  id: PriceBadgeId;
  label: string;
  tone: PriceBadgeTone;
  title: string;
}

/** 서버 /api/web-price 의 result — 배지 판정에 필요한 필드만 좁혀 둔다. */
export interface PriceResultLike {
  price?: number | null;
  basis?: string;
}

/** 서버 /api/web-price 의 searchInfo. */
export interface PriceSearchInfoLike {
  status?: string;
  misses?: ReadonlyArray<string | { site?: string }>;
  rejectedQuoteCount?: number;
  relaxed?: boolean;
  queryUsed?: string;
}

export function priceStatusBadges(
  result: PriceResultLike | null | undefined,
  searchInfo?: PriceSearchInfoLike | null,
): PriceStatusBadge[] {
  const info: PriceSearchInfoLike = searchInfo || {};
  const misses = (Array.isArray(info.misses) ? info.misses : [])
    .map((m) => (typeof m === 'string' ? m : m?.site || ''))
    .filter(Boolean);
  const badges: PriceStatusBadge[] = [];

  if (!result || !result.price) {
    // 못 찾은 이유는 서로 다른 조치를 부른다: 백엔드 오류는 설정 문제, '동일제품 없음'은
    // 검색은 됐지만 다른 모델뿐이었다는 뜻, '가격 없음'은 판매처가 값을 안 내건 것이다.
    if (info.status === 'error') {
      badges.push({
        id: 'error',
        label: '검색 오류',
        tone: 'bad',
        title: '가격 검색 백엔드가 응답하지 않았습니다.',
      });
    } else if (Number(info.rejectedQuoteCount) > 0) {
      badges.push({
        id: 'no-match',
        label: '동일제품 없음',
        tone: 'warn',
        title: `검색 결과 ${info.rejectedQuoteCount}건이 완제품·중고 또는 다른 모델·용량이라 제외됐습니다.`,
      });
    } else {
      badges.push({
        id: 'none',
        label: '가격 없음',
        tone: 'warn',
        title: '검색된 판매 가격이 없습니다.',
      });
    }
  } else {
    // 찾았을 때도 "어떻게 찾았는지"가 정확도를 좌우한다.
    if (info.relaxed) {
      badges.push({
        id: 'relaxed',
        label: '완화검색',
        tone: 'warn',
        title: `품목명 그대로는 결과가 없어 짧은 검색어로 찾았습니다 — 검색어: "${info.queryUsed || ''}"`,
      });
    }
    if (result.basis === 'link') {
      badges.push({
        id: 'link',
        label: '링크',
        tone: 'ok',
        title: '직접 넣은 상품 링크에서 읽은 값입니다.',
      });
    } else if (result.basis === 'snippet') {
      badges.push({
        id: 'snippet',
        label: '추정',
        tone: 'warn',
        title: '페이지를 열지 못해 검색 요약에서 추정한 값입니다.',
      });
    }
  }

  // 가격을 찾았든 못 찾았든 못 읽은 판매처는 알린다 — 진짜 최저가가 거기 있을 수 있다.
  if (misses.length) {
    badges.push({
      id: 'misses',
      label: `확인실패 ${misses.map(siteLabel).join('·')}`,
      tone: 'warn',
      title: `이 판매처는 조회하지 못했습니다: ${misses.join(', ')}`,
    });
  }
  return badges;
}
