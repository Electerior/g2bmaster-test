package com.electerior.g2bmaster.notice;

import com.electerior.g2bmaster.cache.SearchResultCache;
import com.electerior.g2bmaster.common.G2bDates.DateWindow;
import com.electerior.g2bmaster.common.MapLimit;
import com.electerior.g2bmaster.search.NoticeSearchSupport;
import com.electerior.g2bmaster.search.SearchQuery;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/**
 * 입찰결과(낙찰정보) 검색 ({@code GET /api/bid-result} 의 조회부, {@code ScsbidInfoService}).
 *
 * <p>입찰공고와 달리 <b>수주기회 스코어링을 하지 않는다</b> — 이미 끝난 입찰이라 "개입 가능성"
 * 같은 개념이 없다. 대신 낙찰업체({@code corpNm}) 필터가 붙는다.
 */
@Service
public class BidResultService {

	private final G2bEndpoints endpoints;
	private final NoticeFetchSupport support;
	private final SearchResultCache cache;

	public BidResultService(G2bEndpoints endpoints, NoticeFetchSupport support, SearchResultCache cache) {
		this.endpoints = endpoints;
		this.support = support;
		this.cache = cache;
	}

	public SearchResultCache.Cached<List<Map<String, Object>>> search(SearchCriteria criteria, String corpNm) {
		String corp = corpNm == null ? "" : corpNm.trim();
		String key = NoticeSearchSupport.buildSearchCacheKey("bid-result", criteria.cacheParams("corpNm", corp));
		return cache.getOrFetch(key, () -> fetch(criteria, corp));
	}

	private List<Map<String, Object>> fetch(SearchCriteria criteria, String corpNm) {
		List<DateWindow> windows = criteria.dateWindows();
		List<String> urls = new ArrayList<>();
		endpoints.bidResult().forEach((type, url) -> {
			if (G2bEndpoints.bidTypeMatches(type, criteria.type())) {
				urls.add(url);
			}
		});

		Map<String, Object> base = new LinkedHashMap<>();
		if (!criteria.insttNm().isEmpty()) {
			base.put("dminsttNm", criteria.insttNm());
		}
		if (!corpNm.isEmpty()) {
			base.put("sucsfbidCorpNm", corpNm);
		}

		List<Supplier<List<Map<String, Object>>>> tasks = new ArrayList<>();
		if (criteria.hasTerms()) {
			for (String term : criteria.upstreamTerms()) {
				for (DateWindow window : windows) {
					if (!criteria.itemSearch()) {
						tasks.add(() -> support.fetchEnriched(urls,
								params(base, window, "bidNtceNm", term), NoticeFetchSupport.RESULT_ROWS));
					}
					tasks.add(() -> support.fetchEnriched(urls,
							params(base, window, "dtilPrdctClsfcNoNm", term), NoticeFetchSupport.RESULT_ROWS));
					if (criteria.insttNm().isEmpty() && !criteria.itemSearch()) {
						// 기관명을 안 걸었을 때만: 검색어가 기관명일 가능성을 한 번 훑는다
						// ("○○교육청"으로 검색하는 사용법이 실제로 많다).
						Map<String, Object> byInstitution = new LinkedHashMap<>();
						byInstitution.put("dminsttNm", term);
						tasks.add(() -> support.fetchEnriched(urls,
								params(byInstitution, window), NoticeFetchSupport.RESULT_ROWS));
					}
				}
			}
		}
		else {
			for (DateWindow window : windows) {
				tasks.add(() -> support.fetchEnriched(urls, params(base, window),
						NoticeFetchSupport.RESULT_ROWS));
			}
		}

		List<Map<String, Object>> fetched = MapLimit.flatMap(tasks, 4, Supplier::get);

		// 낙찰정보는 정정 차수 개념이 없어 공고번호 하나로 접는다.
		Set<String> seen = new LinkedHashSet<>();
		List<String> andTerms = criteria.and();
		List<String> orTerms = criteria.or();
		List<String> notTerms = criteria.not();
		String corpLower = corpNm.toLowerCase(Locale.ROOT);

		List<Map<String, Object>> filtered = new ArrayList<>();
		for (Map<String, Object> item : fetched) {
			if (!seen.add(str(item.get("bidNtceNo")))) {
				continue;
			}
			String haystack = criteria.itemSearch()
					? BidEnrichment.bidItemHaystack(item)
					: BidEnrichment.bidSearchHaystack(item);
			if (!SearchQuery.matchesQuery(haystack, andTerms, orTerms, notTerms)) {
				continue;
			}
			if (!NoticeFetchSupport.institutionMatches(item, criteria.insttNm())) {
				continue;
			}
			if (!corpLower.isEmpty() && !corpFields(item).contains(corpLower)) {
				continue;
			}
			filtered.add(item);
		}
		return NoticeFetchSupport.filterByBidType(filtered, criteria.type());
	}

	// ── 내부 ────────────────────────────────────────────────────────────────

	private static String corpFields(Map<String, Object> item) {
		StringBuilder sb = new StringBuilder();
		for (String key : new String[] {"sucsfbidCorpNm", "bidwinnrNm", "corpNm"}) {
			String value = str(item.get(key));
			if (!value.isEmpty()) {
				sb.append(value).append(' ');
			}
		}
		return sb.toString().toLowerCase(Locale.ROOT);
	}

	private static Map<String, Object> params(Map<String, Object> base, DateWindow window) {
		Map<String, Object> params = new LinkedHashMap<>(base);
		params.put("inqryBgnDt", window.from());
		params.put("inqryEndDt", window.to());
		return params;
	}

	private static Map<String, Object> params(Map<String, Object> base, DateWindow window,
			String key, String value) {
		Map<String, Object> params = params(base, window);
		params.put(key, value);
		return params;
	}

	private static String str(Object value) {
		return value == null ? "" : String.valueOf(value);
	}
}
