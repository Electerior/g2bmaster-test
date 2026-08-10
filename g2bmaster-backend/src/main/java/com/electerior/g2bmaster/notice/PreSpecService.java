package com.electerior.g2bmaster.notice;

import com.electerior.g2bmaster.cache.SearchResultCache;
import com.electerior.g2bmaster.search.NoticeSearchSupport;
import com.electerior.g2bmaster.search.OpportunityScoring;
import com.electerior.g2bmaster.search.OpportunityScoring.Stage;
import com.electerior.g2bmaster.search.SearchQuery;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/**
 * 사전규격 검색 ({@code GET /api/pre-spec}, {@code HrcspSsstndrdInfoService}).
 *
 * <p>수주기회 관점에서 <b>가장 중요한 단계</b>다 — 규격이 확정되기 전이라 의견 제출로
 * 직접 개입할 수 있고, 스코어링이 25점을 얹는 이유다({@link Stage#PRE_SPEC}).
 *
 * <p>마감 경과 항목을 <b>점수 매기기 전에 제거</b>한다. 의견 접수가 끝난 사전규격은
 * 개입 여지가 0인데, 점수만 보면 여전히 높게 나와 레이더를 오염시킨다.
 *
 * <p>원자료 출처는 {@link PreSpecSource} 가 고른다 — DB 우선/카나리 라우팅이 붙을 자리다.
 */
@Service
public class PreSpecService {

	private final PreSpecSource source;
	private final SearchResultCache cache;

	public PreSpecService(PreSpecSource source, SearchResultCache cache) {
		this.source = source;
		this.cache = cache;
	}

	public SearchResultCache.Cached<List<Map<String, Object>>> search(SearchCriteria criteria, boolean bypassCache) {
		Supplier<List<Map<String, Object>>> loader = () -> fetch(criteria);
		if (bypassCache) {
			return cache.fetchUncached(loader);
		}
		// 사전규격에는 기관 필터가 없어 캐시 키의 insttNm 자리를 빈 값으로 고정한다.
		String key = NoticeSearchSupport.buildSearchCacheKey("pre-spec",
				criteria.cacheParams("insttNm", ""));
		return cache.getOrFetch(key, loader);
	}

	private List<Map<String, Object>> fetch(SearchCriteria criteria) {
		List<Map<String, Object>> allItems = source.load(criteria.dates(), criteria.type());

		List<String> andTerms = criteria.and();
		List<String> orTerms = criteria.or();
		List<String> notTerms = criteria.not();
		Set<String> seen = new LinkedHashSet<>();
		List<Map<String, Object>> filtered = new ArrayList<>();

		for (Map<String, Object> item : allItems) {
			NoticeFetchSupport.normalizePreSpecItem(item);
			// 공고번호가 없어 사업구분 + 규격등록번호로 접는다. 등록번호까지 비면 항목 자체를
			// 키로 써서 "합치지 않는" 쪽을 택한다 — 다른 규격을 하나로 접는 것보다 낫다.
			String registerNo = str(item.get("prdctClfcNo"));
			String key = str(item.get("bsnsDivNm")) + "|"
					+ (registerNo.isEmpty() ? String.valueOf(item.hashCode()) : registerNo);
			if (!seen.add(key)) {
				continue;
			}
			String haystack = NoticeFetchSupport.preSpecHaystack(item, criteria.itemSearch());
			if (SearchQuery.matchesQuery(haystack, andTerms, orTerms, notTerms)) {
				filtered.add(item);
			}
		}

		List<Map<String, Object>> live = OpportunityScoring.removeExpired(filtered, Stage.PRE_SPEC);
		return OpportunityScoring.applyOpportunity(
				NoticeFetchSupport.filterByBidType(live, criteria.type()), Stage.PRE_SPEC);
	}

	private static String str(Object value) {
		return value == null ? "" : String.valueOf(value);
	}
}
