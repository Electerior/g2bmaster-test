package com.electerior.g2bmaster.notice;

import com.electerior.g2bmaster.cache.SearchResultCache;
import com.electerior.g2bmaster.common.G2bDates.DateWindow;
import com.electerior.g2bmaster.common.MapLimit;
import com.electerior.g2bmaster.common.SourceError;
import com.electerior.g2bmaster.integration.d2b.D2bBidAnnounceService;
import com.electerior.g2bmaster.search.NoticeSearchSupport;
import com.electerior.g2bmaster.search.OpportunityScoring;
import com.electerior.g2bmaster.search.OpportunityScoring.Stage;
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
 * 입찰공고 검색 ({@code GET /api/bid-announce} 의 조회부).
 *
 * <p>세 출처를 동시에 훑는다 — 나라장터(물품·용역·공사), 국방전자조달(D2B), 누리장터.
 * <b>한 출처의 실패로 검색 전체가 무너지지 않는 것</b>이 이 클래스의 설계 목표다.
 *
 * <p>왜 이렇게 많은 호출로 부풀어야 하는가:
 * <ul>
 *   <li>물품/용역/공사가 각각 다른 오퍼레이션 — 3배</li>
 *   <li>기간 상한(31일·월 경계)으로 날짜창 분할 — N배</li>
 *   <li>공고명·품명 두 파라미터로 각각 검색 — 2배 (한 요청에 OR 조건을 못 넣는다)</li>
 * </ul>
 * 그래서 검색 결과 캐시(2시간)가 선택이 아니라 필수다.
 */
@Service
public class BidAnnounceService {

	/**
	 * 분류코드 역탐색을 시도할 상한.
	 *
	 * <p>결과가 이미 많으면(300건 이상) 코드로 더 긁어 봐야 순위만 흐려지고 호출만 늘어난다.
	 * 반대로 결과가 적을 때는 "검색어가 분류명에만 있고 공고명에는 없는" 공고가 통째로
	 * 빠져 있을 가능성이 높다.
	 */
	private static final int CODE_DISCOVERY_THRESHOLD = 300;

	/** 역탐색에 쓸 분류코드 최대 개수. 코드 하나가 곧 (창 수)만큼의 추가 호출이다. */
	private static final int MAX_DISCOVERED_CODES = 3;

	/** 공고번호 직접 조회 상한. 한 번에 다섯 건이면 화면이 쓰는 교차 이동에 충분하다. */
	private static final int MAX_DIRECT_NOTICE_NOS = 5;

	private final G2bEndpoints endpoints;
	private final NoticeFetchSupport support;
	private final D2bBidAnnounceService d2bService;
	private final PrivateNoticeService privateService;
	private final SearchResultCache cache;

	public BidAnnounceService(G2bEndpoints endpoints, NoticeFetchSupport support,
			D2bBidAnnounceService d2bService, PrivateNoticeService privateService,
			SearchResultCache cache) {
		this.endpoints = endpoints;
		this.support = support;
		this.d2bService = d2bService;
		this.privateService = privateService;
		this.cache = cache;
	}

	/** 조회 결과 — 항목과 함께 "어느 출처가 부분 실패했는지"를 들고 다닌다. */
	public record AnnounceResult(List<Map<String, Object>> items, List<SourceError> sourceErrors) {}

	/**
	 * 조건 검색. {@code fileScan} 처럼 캐시를 못 쓰는 경로는 {@code bypassCache} 로 우회한다.
	 */
	public SearchResultCache.Cached<AnnounceResult> search(SearchCriteria criteria, boolean bypassCache) {
		Supplier<AnnounceResult> loader = () -> fetch(criteria);
		if (bypassCache) {
			return cache.fetchUncached(loader);
		}
		String key = NoticeSearchSupport.buildSearchCacheKey("bid-announce", criteria.cacheParams());
		return cache.getOrFetch(key, loader);
	}

	// ── 공고번호 직접 조회 (사전규격 → 입찰공고 교차 이동) ─────────────────

	/**
	 * 공고번호로 직접 찾는다.
	 *
	 * <p>상세조회 엔드포인트({@code getBidPblancDetailInfo*})는 차세대 나라장터 전환으로
	 * 폐기되어 HTTP 404 다. 그래서 <b>목록 엔드포인트를 날짜창으로 훑어 번호로 거르는</b>
	 * 우회를 쓴다. 신형 번호는 영문을 포함하고 차수가 별도라, 입력 끝의 {@code -NN}(차수)을
	 * 떼고 본번호로 맞춘다.
	 *
	 * <p>D2B 공고가 G2B 공고번호를 갖고 있는 경우가 있어({@code _g2bPblancNo}) 국방 쪽도
	 * 같은 창으로 조회해 교차 매칭한다.
	 */
	public List<Map<String, Object>> findByNoticeNumbers(SearchCriteria criteria, String bidNtceNoCsv) {
		Set<String> targets = new LinkedHashSet<>();
		for (String raw : bidNtceNoCsv.split(",")) {
			String normalized = normalizeNoticeNo(raw);
			if (!normalized.isEmpty() && targets.size() < MAX_DIRECT_NOTICE_NOS) {
				targets.add(normalized);
			}
		}
		if (targets.isEmpty()) {
			return List.of();
		}

		DateWindow dates = criteria.dates();
		List<DateWindow> windows = criteria.dateWindows();
		List<String> urls = announceUrls(criteria.type());

		List<Map<String, Object>> ppsItems = MapLimit.flatMap(windows, 4, window ->
				support.fetchEnriched(urls, dateParams(Map.of(), window), pageSize()).stream()
						.filter(item -> targets.contains(normalizeNoticeNo(str(item.get("bidNtceNo")))))
						.toList());

		List<Map<String, Object>> d2bItems = safeD2b(null, dates).items().stream()
				.filter(item -> targets.contains(normalizeNoticeNo(str(item.get("_g2bPblancNo"))))
						|| targets.contains(normalizeNoticeNo(str(item.get("bidNtceNo")))))
				.toList();

		List<Map<String, Object>> privateItems = safePrivate(null, dates, criteria).items().stream()
				.filter(item -> targets.contains(normalizeNoticeNo(str(item.get("bidNtceNo")))))
				.toList();

		List<Map<String, Object>> all = new ArrayList<>(ppsItems);
		all.addAll(d2bItems);
		all.addAll(privateItems);
		return OpportunityScoring.applyOpportunity(
				NoticeSearchSupport.selectLatestNoticeRevisions(all), Stage.BID_ANNOUNCE);
	}

	// ── 조회 본체 ───────────────────────────────────────────────────────────

	private AnnounceResult fetch(SearchCriteria criteria) {
		DateWindow dates = criteria.dates();
		List<DateWindow> windows = criteria.dateWindows();
		List<String> urls = announceUrls(criteria.type());

		Map<String, Object> base = new LinkedHashMap<>();
		if (!criteria.insttNm().isEmpty()) {
			base.put("ntceInsttNm", criteria.insttNm());
		}

		List<List<Map<String, Object>>> fetched = new ArrayList<>();
		List<SourceError> sourceErrors = new ArrayList<>();

		if (criteria.hasTerms()) {
			List<String> terms = criteria.upstreamTerms();
			fetched.add(MapLimit.flatMap(termTasks(criteria, urls, base, windows, terms), 8, Supplier::get));

			// D2B·누리장터는 나라장터와 파라미터 체계가 달라 같은 태스크 목록에 못 섞는다.
			for (String term : terms) {
				for (DateWindow window : windows) {
					D2bBidAnnounceService.D2bResult d2b = safeD2b(term, window);
					fetched.add(d2b.items());
					sourceErrors.addAll(d2b.errors());

					PrivateNoticeService.PrivateResult priv = safePrivate(term, window, criteria);
					fetched.add(priv.items());
					sourceErrors.addAll(priv.errors());
				}
			}
		}
		else {
			// 검색어가 없으면 기간 전체를 훑는다. D2B는 키워드 없는 전체 조회가 사실상
			// 무한정이라 원본과 같이 제외한다(누리장터는 페이징이 있어 포함).
			fetched.add(MapLimit.flatMap(windows, 4,
					window -> support.fetchEnriched(urls, dateParams(base, window), pageSize())));
			for (DateWindow window : windows) {
				PrivateNoticeService.PrivateResult priv = safePrivate(null, window, criteria);
				fetched.add(priv.items());
				sourceErrors.addAll(priv.errors());
			}
		}

		List<Map<String, Object>> all = flatten(fetched);
		all.addAll(discoverByClassificationCode(criteria, urls, base, windows, all));

		List<String> andTerms = criteria.and();
		List<String> orTerms = criteria.or();
		List<String> notTerms = criteria.not();
		List<Map<String, Object>> filtered = new ArrayList<>();
		for (Map<String, Object> item : NoticeSearchSupport.selectLatestNoticeRevisions(all)) {
			String haystack = criteria.itemSearch()
					? BidEnrichment.bidItemHaystack(item)
					: BidEnrichment.bidSearchHaystack(item);
			if (!SearchQuery.matchesQuery(haystack, andTerms, orTerms, notTerms)) {
				continue;
			}
			if (!NoticeFetchSupport.institutionMatches(item, criteria.insttNm())) {
				continue;
			}
			filtered.add(item);
		}

		List<Map<String, Object>> scored = OpportunityScoring.applyOpportunity(
				NoticeFetchSupport.filterByBidType(filtered, criteria.type()), Stage.BID_ANNOUNCE);
		return new AnnounceResult(scored, List.copyOf(sourceErrors));
	}

	/**
	 * 검색어 하나당 두 파라미터로 각각 조회한다.
	 *
	 * <p>나라장터는 한 요청에 "공고명 또는 품명" 같은 OR 조건을 못 넣는다. 공고명에만 걸면
	 * 품명으로만 표기된 공고를, 품명에만 걸면 그 반대를 놓친다.
	 */
	private List<Supplier<List<Map<String, Object>>>> termTasks(SearchCriteria criteria, List<String> urls,
			Map<String, Object> base, List<DateWindow> windows, List<String> terms) {
		List<Supplier<List<Map<String, Object>>>> tasks = new ArrayList<>();
		for (String term : terms) {
			for (DateWindow window : windows) {
				tasks.add(() -> support.fetchEnriched(urls,
						withParam(dateParams(base, window), "bidNtceNm", term), pageSize()));
				if (!criteria.itemSearch()) {
					// 품목 모드에서는 품명 검색을 걸지 않는다 — 품목 해석 결과로 대체되기 때문.
					tasks.add(() -> support.fetchEnriched(urls,
							withParam(dateParams(base, window), "dtilPrdctClsfcNoNm", term), pageSize()));
				}
				if (NoticeFetchSupport.isClassificationCode(term)) {
					tasks.add(() -> support.fetchEnriched(urls,
							withParam(dateParams(base, window), "dtilPrdctClsfcNo", term), pageSize()));
				}
			}
		}
		return tasks;
	}

	/**
	 * 분류코드 역탐색.
	 *
	 * <p>검색어가 <b>분류명에만</b> 있고 공고명에는 없는 공고는 위 태스크로 잡히지 않는다.
	 * 그래서 1차 결과에서 "검색어가 분류명에 들어 있는 항목"의 분류코드를 뽑아, 그 코드로
	 * 한 번 더 조회한다. 결과가 이미 많으면 하지 않는다({@link #CODE_DISCOVERY_THRESHOLD}).
	 */
	private List<Map<String, Object>> discoverByClassificationCode(SearchCriteria criteria, List<String> urls,
			Map<String, Object> base, List<DateWindow> windows, List<Map<String, Object>> firstPass) {
		if (criteria.itemSearch() || !criteria.hasTerms() || firstPass.size() >= CODE_DISCOVERY_THRESHOLD) {
			return List.of();
		}
		List<String> terms = criteria.queryTerms().stream()
				.map(t -> t.toLowerCase(Locale.ROOT))
				.filter(t -> !t.isEmpty() && !NoticeFetchSupport.isClassificationCode(t))
				.toList();
		if (terms.isEmpty()) {
			return List.of();
		}

		Set<String> codes = new LinkedHashSet<>();
		for (Map<String, Object> item : firstPass) {
			if (codes.size() >= MAX_DISCOVERED_CODES) {
				break;
			}
			String text = joinLower(item, "dtilPrdctClsfcNoNm", "prdctClsfcNoNm",
					"pubPrcrmntClsfcNm", "_requirementSummary");
			if (terms.stream().noneMatch(text::contains)) {
				continue;
			}
			String code = firstNonBlank(item, "dtilPrdctClsfcNo", "pubPrcrmntClsfcNo", "prdctClsfcNo");
			if (!code.isEmpty()) {
				codes.add(code);
			}
		}
		if (codes.isEmpty()) {
			return List.of();
		}

		List<Supplier<List<Map<String, Object>>>> tasks = new ArrayList<>();
		for (String code : codes) {
			for (DateWindow window : windows) {
				tasks.add(() -> support.fetchEnriched(urls,
						withParam(dateParams(base, window), "dtilPrdctClsfcNo", code), pageSize()));
			}
		}
		return MapLimit.flatMap(tasks, 8, Supplier::get);
	}

	// ── 출처별 안전 호출 ────────────────────────────────────────────────────

	private D2bBidAnnounceService.D2bResult safeD2b(String keyword, DateWindow window) {
		try {
			return d2bService.fetchBidAnnounce(keyword, window.from(), window.to());
		}
		catch (RuntimeException ex) {
			return new D2bBidAnnounceService.D2bResult(List.of(),
					List.of(new SourceError("d2b", "fetchBidAnnounce", ex.getMessage())));
		}
	}

	private PrivateNoticeService.PrivateResult safePrivate(String keyword, DateWindow window,
			SearchCriteria criteria) {
		try {
			return privateService.fetchBidAnnounce(keyword, window.from(), window.to(),
					criteria.type(), criteria.insttNm());
		}
		catch (RuntimeException ex) {
			return new PrivateNoticeService.PrivateResult(List.of(),
					List.of(new SourceError("private-g2b", "fetchBidAnnounce", ex.getMessage())));
		}
	}

	// ── 내부 ────────────────────────────────────────────────────────────────

	private List<String> announceUrls(String bidType) {
		List<String> urls = new ArrayList<>();
		endpoints.bidAnnounce().forEach((type, url) -> {
			if (G2bEndpoints.bidTypeMatches(type, bidType)) {
				urls.add(url);
			}
		});
		return urls;
	}

	private int pageSize() {
		return NoticeFetchSupport.RESULT_ROWS;
	}

	/** 끝의 {@code -01} 등 차수를 떼고 본번호만 남긴다. */
	static String normalizeNoticeNo(String raw) {
		return raw == null ? "" : raw.trim().replaceAll("-\\d{1,3}$", "");
	}

	private static Map<String, Object> dateParams(Map<String, Object> base, DateWindow window) {
		Map<String, Object> params = new LinkedHashMap<>(base);
		params.put("inqryBgnDt", window.from());
		params.put("inqryEndDt", window.to());
		return params;
	}

	private static Map<String, Object> withParam(Map<String, Object> params, String key, String value) {
		params.put(key, value);
		return params;
	}

	private static List<Map<String, Object>> flatten(List<List<Map<String, Object>>> chunks) {
		List<Map<String, Object>> all = new ArrayList<>();
		for (List<Map<String, Object>> chunk : chunks) {
			all.addAll(chunk);
		}
		return all;
	}

	private static String joinLower(Map<String, Object> item, String... keys) {
		StringBuilder sb = new StringBuilder();
		for (String key : keys) {
			String value = str(item.get(key));
			if (value.isEmpty()) {
				continue;
			}
			if (!sb.isEmpty()) {
				sb.append(' ');
			}
			sb.append(value);
		}
		return sb.toString().toLowerCase(Locale.ROOT);
	}

	private static String firstNonBlank(Map<String, Object> item, String... keys) {
		for (String key : keys) {
			String value = str(item.get(key));
			if (!value.isBlank()) {
				return value;
			}
		}
		return "";
	}

	private static String str(Object value) {
		return value == null ? "" : String.valueOf(value);
	}
}
