package com.electerior.g2bmaster.notice;

import com.electerior.g2bmaster.cache.SearchResultCache;
import com.electerior.g2bmaster.common.PagedResponse;
import com.electerior.g2bmaster.common.SourceError;
import com.electerior.g2bmaster.config.OpenApiConfig;
import com.electerior.g2bmaster.search.OpportunityScoring;
import com.electerior.g2bmaster.search.SearchQuery;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 입찰 검색 4탭 ({@code docs/api-contract.md} §2.A).
 *
 * <p>응답은 <b>레코드가 아니라 {@link LinkedHashMap}</b> 으로 만든다. 봉투 필드가
 * {@code _cached} 처럼 밑줄로 시작하고, 탭마다 붙는 부가 필드가 다르기 때문이다
 * (입찰공고만 {@code sourceCounts}/{@code sourceStatus}). 프론트 계약이 이미 그 모양이라
 * 통일하지 않는 것이 계약을 지키는 길이다 — {@code docs/api-contract.md} §1.1 참고.
 *
 * <p>조회·필터는 각 서비스가, <b>순위·페이징만</b> 여기가 한다. 정렬 규칙이 탭마다 다르고
 * (수주기회 점수 순 / BM25 / 사용자 지정 컬럼) 그 차이가 화면 동작 그 자체라서, 한 곳에
 * 모아 두면 서로 어긋나는 것을 바로 볼 수 있다.
 */
@RestController
@RequestMapping("/api")
@Tag(name = OpenApiConfig.TAG_SEARCH)
public class NoticeController {

	/** 검색 팬아웃이 커버하는 출처. 화면이 "몇 개 출처를 봤는가"를 표시하는 데 쓴다. */
	private static final List<String> SOURCE_COVERAGE = List.of("g2b", "d2b", "private-g2b");

	private final BidAnnounceService bidAnnounceService;
	private final BidResultService bidResultService;
	private final BidPlanService bidPlanService;
	private final PreSpecService preSpecService;

	public NoticeController(BidAnnounceService bidAnnounceService, BidResultService bidResultService,
			BidPlanService bidPlanService, PreSpecService preSpecService) {
		this.bidAnnounceService = bidAnnounceService;
		this.bidResultService = bidResultService;
		this.bidPlanService = bidPlanService;
		this.preSpecService = preSpecService;
	}

	// ── 입찰공고 ────────────────────────────────────────────────────────────

	/**
	 * 나라장터(물품·용역·공사) + D2B + 누리장터 팬아웃 검색.
	 *
	 * @param bidNtceNo 공고번호 직접 조회(콤마, 최대 5). 사전규격 → 입찰공고 교차 이동용
	 * @param fileScan  {@code true} 면 캐시를 우회한다 — 첨부 스캔은 매번 최신 목록이 필요하다
	 */
	@Operation(summary = "입찰공고 검색",
			description = "나라장터(물품·용역·공사) + D2B + 누리장터 팬아웃. 수주기회 점수 순으로 정렬한다.")
	@GetMapping("/bid-announce")
	public Map<String, Object> bidAnnounce(@ModelAttribute SearchCriteria criteria,
			@RequestParam(value = "bidNtceNo", required = false) String bidNtceNo,
			@RequestParam(value = "fileScan", required = false) String fileScan) {

		if (bidNtceNo != null && !bidNtceNo.isBlank()) {
			List<Map<String, Object>> items = bidAnnounceService.findByNoticeNumbers(criteria, bidNtceNo);
			// 번호 직접 조회는 결과가 한 줌이라 페이징하지 않는다(원본과 같다).
			return page(new PagedResponse<>(items, items.size(), 1, items.size()));
		}

		SearchResultCache.Cached<BidAnnounceService.AnnounceResult> cached =
				bidAnnounceService.search(criteria, "true".equalsIgnoreCase(fileScan));
		BidAnnounceService.AnnounceResult result = cached.value();

		List<Map<String, Object>> items = criteria.activeOnlyEnabled()
				? activeOnly(result.items())
				: result.items();

		Function<Map<String, Object>, String> rankText = criteria.itemSearch()
				? BidEnrichment::bidItemHaystack
				: BidEnrichment::bidSearchHaystack;
		List<Map<String, Object>> ranked = rank(items, criteria, rankText, "bidNtceDt");

		Map<String, Object> response = page(PagedResponse.of(ranked, criteria.page(), criteria.perPageValue()));
		response.put("_cached", cached.cached());
		response.put("sourceCounts", sourceCounts(ranked));
		response.put("sourceCoverage", SOURCE_COVERAGE);
		response.put("sourceErrors", result.sourceErrors());
		response.put("sourceStatus", sourceStatus(result.sourceErrors()));
		return response;
	}

	// ── 입찰결과 ────────────────────────────────────────────────────────────

	/** 낙찰정보 검색. {@code corpNm} 으로 낙찰업체를 좁힐 수 있다. */
	@Operation(summary = "개찰결과 검색",
			description = "이미 끝난 입찰이라 수주기회 점수가 없다 — 정렬 지정이 없으면 BM25 를 쓴다.")
	@GetMapping("/bid-result")
	public Map<String, Object> bidResult(@ModelAttribute SearchCriteria criteria,
			@RequestParam(value = "corpNm", required = false) String corpNm) {

		SearchResultCache.Cached<List<Map<String, Object>>> cached = bidResultService.search(criteria, corpNm);

		Function<Map<String, Object>, String> rankText = criteria.itemSearch()
				? BidEnrichment::bidItemHaystack
				: BidEnrichment::bidSearchHaystack;

		// 입찰결과는 수주기회 점수가 없다(이미 끝난 입찰) — 점수 정렬 대신 BM25 를 그대로 쓴다.
		List<Map<String, Object>> ranked;
		if (criteria.sortKey().isEmpty() && !criteria.queryTerms().isEmpty()) {
			ranked = SearchQuery.bm25(criteria.queryTerms(), cached.value(), rankText);
		}
		else {
			ranked = SearchQuery.sortItems(cached.value(), criteria.sortKey(), criteria.sortDir());
		}

		Map<String, Object> response = page(PagedResponse.of(ranked, criteria.page(), criteria.perPageValue()));
		response.put("_cached", cached.cached());
		return response;
	}

	// ── 발주계획 ────────────────────────────────────────────────────────────

	@Operation(summary = "발주계획 검색",
			description = "BM25 를 쓰지 않는다 — 검색 대상이 전체 필드라 문서 길이가 들쭉날쭉하다.")
	@GetMapping("/bid-plan")
	public Map<String, Object> bidPlan(@ModelAttribute SearchCriteria criteria) {
		SearchResultCache.Cached<List<Map<String, Object>>> cached = bidPlanService.search(criteria);

		// 발주계획은 BM25 를 쓰지 않는다 — 검색 대상이 전체 필드 haystack 이라 문서 길이가
		// 들쭉날쭉하고, 그 상태로 BM25 를 태우면 필드 많은 항목이 무조건 위로 올라온다.
		List<Map<String, Object>> ranked = criteria.sortKey().isEmpty()
				? OpportunityScoring.sortByOpportunity(cached.value(), "nticeDt")
				: SearchQuery.sortItems(cached.value(), criteria.sortKey(), criteria.sortDir());

		Map<String, Object> response = page(PagedResponse.of(ranked, criteria.page(), criteria.perPageValue()));
		response.put("_cached", cached.cached());
		return response;
	}

	// ── 사전규격 ────────────────────────────────────────────────────────────

	@Operation(summary = "사전규격 검색",
			description = "지금은 항상 live 나라장터 API 를 본다. DB 우선/카나리 라우팅은 미이식.")
	@GetMapping("/pre-spec")
	public Map<String, Object> preSpec(@ModelAttribute SearchCriteria criteria,
			@RequestParam(value = "fileScan", required = false) String fileScan) {

		SearchResultCache.Cached<List<Map<String, Object>>> cached =
				preSpecService.search(criteria, "true".equalsIgnoreCase(fileScan));

		boolean itemSearch = criteria.itemSearch();
		Function<Map<String, Object>, String> rankText =
				item -> NoticeFetchSupport.preSpecHaystack(item, itemSearch);
		List<Map<String, Object>> ranked = rank(cached.value(), criteria, rankText, "pstDt");

		Map<String, Object> response = page(PagedResponse.of(ranked, criteria.page(), criteria.perPageValue()));
		response.put("_cached", cached.cached());
		return response;
	}

	// ── 공통 ────────────────────────────────────────────────────────────────

	/**
	 * 기본 정렬 = 수주기회 점수 순, 단 검색어가 있으면 BM25 로 먼저 관련도 순서를 만든 뒤
	 * 점수로 다시 세운다.
	 *
	 * <p>순서가 중요하다: BM25 → 점수 정렬이라, 점수 동점 구간에서 관련도가 살아난다.
	 * 반대로 하면 관련도가 통째로 사라진다.
	 */
	private static List<Map<String, Object>> rank(List<Map<String, Object>> items, SearchCriteria criteria,
			Function<Map<String, Object>, String> rankText, String dateKey) {
		if (!criteria.sortKey().isEmpty()) {
			return SearchQuery.sortItems(items, criteria.sortKey(), criteria.sortDir());
		}
		List<Map<String, Object>> base = criteria.queryTerms().isEmpty()
				? items
				: SearchQuery.bm25(criteria.queryTerms(), items, rankText);
		return OpportunityScoring.sortByOpportunity(base, dateKey);
	}

	/** 마감 경과·취소 공고를 뺀다. */
	private static List<Map<String, Object>> activeOnly(List<Map<String, Object>> items) {
		List<Map<String, Object>> out = new ArrayList<>(items.size());
		for (Map<String, Object> item : items) {
			if (SearchQuery.isPastDeadline(item.get("bidClseDt"))) {
				continue;
			}
			if (Boolean.TRUE.equals(item.get("_isCancelled"))) {
				continue;
			}
			out.add(item);
		}
		return out;
	}

	private static Map<String, Integer> sourceCounts(List<Map<String, Object>> items) {
		Map<String, Integer> counts = new LinkedHashMap<>();
		// 0으로 초기화해 둔다 — 결과가 없는 출처가 응답에서 사라지면 화면이 "조회 안 함"과
		// "조회했는데 0건"을 구분하지 못한다.
		SOURCE_COVERAGE.forEach(source -> counts.put(source, 0));
		for (Map<String, Object> item : items) {
			String source = String.valueOf(item.getOrDefault("_source", "g2b"));
			String key = SOURCE_COVERAGE.contains(source) ? source : "g2b";
			counts.merge(key, 1, Integer::sum);
		}
		return counts;
	}

	private static Map<String, String> sourceStatus(List<SourceError> errors) {
		Map<String, String> status = new LinkedHashMap<>();
		for (String source : SOURCE_COVERAGE) {
			boolean partial = errors.stream().anyMatch(error -> source.equals(error.source()));
			status.put(source, partial ? "partial" : "ok");
		}
		return status;
	}

	private static Map<String, Object> page(PagedResponse<Map<String, Object>> paged) {
		Map<String, Object> response = new LinkedHashMap<>();
		response.put("items", paged.items());
		response.put("totalCount", paged.totalCount());
		response.put("pageNo", paged.pageNo());
		response.put("numOfRows", paged.numOfRows());
		return response;
	}
}
