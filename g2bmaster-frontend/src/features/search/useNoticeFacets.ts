/*
 * 필터 줄에 붙일 건수를 모은다.
 *
 * `/api/search/notices/facets` 는 목록과 같은 WHERE 로 센다 — 그것이 계약이고, 그래야
 * "12건이라 써 놓고 3건을 보여주는" 일이 없다. 다만 그 계약을 그대로 한 번만 부르면
 * **자기 축**이 무너진다: 단계를 '입찰'로 좁히는 순간 `GROUP BY category` 가 '입찰' 한 줄만
 * 돌려주므로 계획·사전규격·마감 칩이 전부 0건으로 흐려진다. 지역 드롭다운은 더 나빠서,
 * 고른 지역을 포함하는 버킷만 남아 다른 지역으로 바로 옮겨 갈 수단이 사라진다.
 *
 * 그래서 축마다 **자기 필터만 뺀** 질의를 하나씩 더 보낸다. 다른 축의 조건은 남기므로
 * "지금 걸린 나머지 조건 안에서 이 축을 바꾸면 몇 건인가"라는, 칩이 원래 답해야 하는
 * 질문에 맞는 수가 된다. 추가 요청은 그 축에 필터가 실제로 걸렸을 때만 나간다.
 */
import { useMemo } from 'react';
import { useNoticeIndexFacets, type NoticeFacets } from '@/api/search';
import {
  buildNoticeFacetQuery,
  type FacetAxis,
  type SearchCriteria,
} from './useSearchCriteria';

/** 축 → 그 축을 좁히고 있는 조건 값. 빈 문자열이면 안 좁힌 것이다. */
const AXIS_VALUE: Record<FacetAxis, (criteria: SearchCriteria) => string> = {
  category: (criteria) => criteria.category,
  // 조건 쪽 이름만 bidType 이다 — 질의로 나갈 때 division 이 된다.
  division: (criteria) => criteria.bidType,
  state: (criteria) => criteria.noticeState,
  region: (criteria) => criteria.region,
};

function useAxisFacets(criteria: SearchCriteria, axis: FacetAxis): NoticeFacets | undefined {
  const active = AXIS_VALUE[axis](criteria) !== '';
  const query = useMemo(() => buildNoticeFacetQuery(criteria, axis), [criteria, axis]);
  return useNoticeIndexFacets(query, { enabled: active }).data;
}

/**
 * 네 축의 건수. 필터가 안 걸린 축은 목록과 같은 조건의 기본 응답에서 가져오므로
 * 요청은 평소 한 번이고, 좁힌 축의 수만큼만 늘어난다.
 */
export function useNoticeFacetBars(criteria: SearchCriteria): NoticeFacets {
  const baseQuery = useMemo(() => buildNoticeFacetQuery(criteria), [criteria]);
  const base = useNoticeIndexFacets(baseQuery).data;

  const category = useAxisFacets(criteria, 'category');
  const division = useAxisFacets(criteria, 'division');
  const state = useAxisFacets(criteria, 'state');
  const region = useAxisFacets(criteria, 'region');

  return useMemo(
    () => ({
      // 축별 응답이 아직 안 왔으면 기본 응답으로 버틴다 — 잠깐 좁은 수가 보일 뿐,
      // 칸이 통째로 비어 0건으로 그려지는 것보다는 낫다.
      category: category?.category ?? base?.category,
      division: division?.division ?? base?.division,
      state: state?.state ?? base?.state,
      region: region?.region ?? base?.region,
    }),
    [base, category, division, state, region],
  );
}

/**
 * 단계 '전체' 칩에 붙일 수.
 *
 * 단계는 DB 에서 `NOT NULL ENUM` 이라 네 버킷의 합이 곧 "단계로 안 좁혔을 때의 건수"다.
 * 목록의 `totalCount` 를 그냥 쓰면 단계를 고른 순간 '전체'가 그 단계의 건수로 줄어,
 * 같은 줄에서 '입찰 4,000 / 전체 4,000' 처럼 색인에 입찰밖에 없는 것처럼 읽힌다.
 */
export function stageTotalOf(facets: NoticeFacets, fallback: number): number {
  const buckets = facets.category;
  if (!buckets || buckets.length === 0) return fallback;
  return buckets.reduce((sum, bucket) => sum + Number(bucket?.count ?? 0), 0);
}
