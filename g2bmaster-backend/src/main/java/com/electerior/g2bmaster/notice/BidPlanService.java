package com.electerior.g2bmaster.notice;

import com.electerior.g2bmaster.cache.SearchResultCache;
import com.electerior.g2bmaster.common.G2bDates.DateWindow;
import com.electerior.g2bmaster.common.MapLimit;
import com.electerior.g2bmaster.integration.g2b.G2bErrorTranslator;
import com.electerior.g2bmaster.search.NoticeSearchSupport;
import com.electerior.g2bmaster.search.OpportunityScoring;
import com.electerior.g2bmaster.search.OpportunityScoring.Stage;
import com.electerior.g2bmaster.search.SearchQuery;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 발주계획 검색 ({@code GET /api/bid-plan}, {@code PrcrmntReqInfoService}).
 *
 * <p>공고 <b>이전</b> 단계라 선제 영업의 근거가 된다 — 스코어링에서 {@link Stage#BID_PLAN} 이
 * 20점을 받는 이유다.
 *
 * <p>두 가지가 입찰공고와 다르다.
 * <ol>
 *   <li><b>API가 검색 파라미터를 무시한다.</b> 그래서 기간 전체를 받아 로컬에서 거른다.
 *       {@code bidNtceNm} 같은 걸 보내 봐야 필터가 안 걸린 전량이 온다.</li>
 *   <li><b>공고번호가 없다.</b> 정정 차수로 접을 수 없어 내용 조합 키로 중복을 제거한다.</li>
 * </ol>
 *
 * <p><b>단계 간 폴백 없음</b>: 발주계획 결과가 비어도 입찰공고 행으로 채우지 않는다.
 * 소스가 섞이면 "이건 아직 공고 전인가?"라는 질문에 답할 수 없게 된다.
 */
@Service
public class BidPlanService {

	private static final Logger log = LoggerFactory.getLogger(BidPlanService.class);

	private final G2bEndpoints endpoints;
	private final NoticeFetchSupport support;
	private final G2bErrorTranslator translator;
	private final SearchResultCache cache;

	public BidPlanService(G2bEndpoints endpoints, NoticeFetchSupport support,
			G2bErrorTranslator translator, SearchResultCache cache) {
		this.endpoints = endpoints;
		this.support = support;
		this.translator = translator;
		this.cache = cache;
	}

	public SearchResultCache.Cached<List<Map<String, Object>>> search(SearchCriteria criteria) {
		String key = NoticeSearchSupport.buildSearchCacheKey("bid-plan", criteria.cacheParams());
		return cache.getOrFetch(key, () -> fetch(criteria));
	}

	private List<Map<String, Object>> fetch(SearchCriteria criteria) {
		List<DateWindow> windows = criteria.dateWindows();
		List<Map.Entry<String, String>> selected = endpoints.bidPlan().stream()
				.filter(entry -> G2bEndpoints.bidTypeMatches(entry.getKey(), criteria.type()))
				.toList();

		List<Map<String, Object>> allItems = MapLimit.flatMap(selected, 4, entry -> {
			try {
				List<Map<String, Object>> rows = new ArrayList<>();
				for (DateWindow window : windows) {
					rows.addAll(support.fetchPaged(entry.getValue(), pagedParams(window)));
				}
				List<Map<String, Object>> typed = new ArrayList<>(rows.size());
				for (Map<String, Object> row : rows) {
					Map<String, Object> copy = new LinkedHashMap<>(row);
					// 응답에 사업 구분이 없는 오퍼레이션이 있어 엔드포인트 이름에서 채운다.
					if (str(copy.get("bsnsDivNm")).isEmpty()) {
						copy.put("bsnsDivNm", entry.getKey());
					}
					typed.add(copy);
				}
				return typed;
			}
			catch (RuntimeException ex) {
				if (translator.isAuthError(ex) || translator.isRateLimitError(ex)) {
					throw ex;
				}
				log.debug("발주계획 조회 실패 {}: {}", entry.getKey(), ex.getMessage());
				return List.of();
			}
		});

		List<String> andTerms = criteria.and();
		List<String> orTerms = criteria.or();
		List<String> notTerms = criteria.not();
		Set<String> seen = new LinkedHashSet<>();
		List<Map<String, Object>> filtered = new ArrayList<>();

		for (Map<String, Object> item : allItems) {
			NoticeFetchSupport.normalizeBidPlanItem(item);
			if (!seen.add(NoticeFetchSupport.bidPlanDedupeKey(item))) {
				continue;
			}
			String haystack = criteria.itemSearch()
					? NoticeFetchSupport.bidPlanItemHaystack(item)
					: BidEnrichment.allFieldHaystack(item);
			if (!SearchQuery.matchesQuery(haystack, andTerms, orTerms, notTerms)) {
				continue;
			}
			if (!NoticeFetchSupport.institutionMatches(item, criteria.insttNm(), "orderInsttNm")) {
				continue;
			}
			filtered.add(item);
		}

		return OpportunityScoring.applyOpportunity(
				NoticeFetchSupport.filterByBidType(filtered, criteria.type()), Stage.BID_PLAN);
	}

	private static Map<String, Object> pagedParams(DateWindow window) {
		Map<String, Object> params = new LinkedHashMap<>();
		params.put("inqryDiv", 1);
		params.put("inqryBgnDt", window.from());
		params.put("inqryEndDt", window.to());
		return params;
	}

	private static String str(Object value) {
		return value == null ? "" : String.valueOf(value);
	}
}
