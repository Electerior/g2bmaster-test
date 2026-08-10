package com.electerior.g2bmaster.trend;

import com.electerior.g2bmaster.cache.SearchResultCache;
import com.electerior.g2bmaster.common.G2bDates;
import com.electerior.g2bmaster.common.G2bDates.DateWindow;
import com.electerior.g2bmaster.common.MapLimit;
import com.electerior.g2bmaster.common.Numbers;
import com.electerior.g2bmaster.notice.BidEnrichment;
import com.electerior.g2bmaster.notice.G2bEndpoints;
import com.electerior.g2bmaster.notice.NoticeFetchSupport;
import com.electerior.g2bmaster.notice.SearchCriteria;
import com.electerior.g2bmaster.pricing.MarketPriceService;
import com.electerior.g2bmaster.search.NoticeSearchSupport;
import com.electerior.g2bmaster.search.OpportunityScoring;
import com.electerior.g2bmaster.search.OpportunityScoring.Stage;
import com.electerior.g2bmaster.search.SearchQuery;
import com.electerior.g2bmaster.trend.TrendProfiles.KeywordGroup;
import com.electerior.g2bmaster.trend.TrendProfiles.TrendProfile;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/**
 * 입찰공고 트렌드 집계 ({@code lib/bid-trends.js} 이식).
 *
 * <p>원본은 협력자 7개를 주입받는 <em>핸들러 팩토리</em>였다. 그 모양은 Express 라우트에
 * 클로저로 상태를 실어 나르려던 것이고, 스프링에서는 생성자 주입 + 컨트롤러 분리로 뒤집는다.
 * 덕분에 "집계"만 따로 테스트할 수 있다(원본에서는 {@code req}/{@code res} 없이는 못 불렀다).
 *
 * <p>집계는 검색과 목적이 다르다. 검색은 "이 공고가 나에게 맞는가"를, 트렌드는 "시장이
 * 어디로 가는가"를 본다. 그래서 여기서는 <b>같은 제목의 공고를 접어서</b>({@code _sameTitleCount})
 * 분할 발주로 부풀려진 건수가 신호를 왜곡하지 않게 한다.
 */
@Service
public class BidTrendService {

	/** 하루 버킷 하나에 실어 보내는 공고 수 상한. 넘기면 응답이 수십 MB가 된다. */
	private static final int DAY_NOTICE_LIMIT = 80;

	/** 상위 목록(기관·계약방식·키워드) 길이. */
	private static final int TOP_BUCKET_LIMIT = 10;

	private static final int KEYWORD_LIMIT = 16;

	/** 마감 임박·고액 목록 길이. 화면 카드 하나에 들어가는 만큼. */
	private static final int HIGHLIGHT_LIMIT = 12;

	/** '마감 임박'의 정의(일). */
	private static final int CLOSING_SOON_DAYS = 7;

	private final G2bEndpoints endpoints;
	private final NoticeFetchSupport support;
	private final SearchResultCache cache;

	public BidTrendService(G2bEndpoints endpoints, NoticeFetchSupport support, SearchResultCache cache) {
		this.endpoints = endpoints;
		this.support = support;
		this.cache = cache;
	}

	// ── 진입점 ──────────────────────────────────────────────────────────────

	/** 프로파일 하나에 대한 트렌드 응답. 필드 구성은 {@code docs/api-contract.md} §2.B. */
	public Map<String, Object> buildTrend(TrendProfile profile, SearchCriteria criteria) {
		DateWindow dates = criteria.dates();
		String cacheKey = NoticeSearchSupport.buildSearchCacheKey(profile.cacheName(),
				criteria.cacheParams("activeOnly", criteria.activeOnly()));

		SearchResultCache.Cached<List<Map<String, Object>>> cached =
				cache.getOrFetch(cacheKey, () -> collect(profile, criteria));
		List<Map<String, Object>> items = cached.value();

		List<Long> amounts = new ArrayList<>();
		long totalAmount = 0;
		for (Map<String, Object> item : items) {
			long amount = trendAmount(item);
			if (amount > 0) {
				amounts.add(amount);
				totalAmount += amount;
			}
		}
		String today = dateLabel(LocalDate.now().toString());

		List<Map<String, Object>> closingSoon = items.stream()
				.filter(item -> {
					Integer days = G2bDates.daysUntil(item.get("bidClseDt"));
					return days != null && days >= 0 && days <= CLOSING_SOON_DAYS;
				})
				.sorted(Comparator.comparingInt(item -> {
					Integer days = G2bDates.daysUntil(item.get("bidClseDt"));
					return days == null ? Integer.MAX_VALUE : days;
				}))
				.limit(HIGHLIGHT_LIMIT)
				.map(BidTrendService::compactTrendItem)
				.toList();

		List<Map<String, Object>> highValue = items.stream()
				.sorted(Comparator.comparingLong(BidTrendService::trendAmount).reversed())
				.limit(HIGHLIGHT_LIMIT)
				.map(BidTrendService::compactTrendItem)
				.toList();

		Map<String, Object> summary = new LinkedHashMap<>();
		summary.put("totalCount", items.size());
		summary.put("totalAmount", totalAmount);
		summary.put("averageAmount", amounts.isEmpty() ? 0 : Math.round(totalAmount / (double) amounts.size()));
		summary.put("medianAmount", medianAmount(amounts));
		summary.put("todayCount", items.stream()
				.filter(item -> today.equals(dateLabel(item.get("bidNtceDt")))).count());
		summary.put("closingSoonCount", closingSoon.size());
		summary.put("activeOnly", criteria.activeOnlyEnabled());

		Map<String, Object> period = new LinkedHashMap<>();
		period.put("from", dateLabel(dates.from()));
		period.put("to", dateLabel(dates.to()));

		Map<String, Object> response = new LinkedHashMap<>();
		response.put("period", period);
		response.put("summary", summary);
		response.put("byDay", dayBuckets(items));
		response.put("topInstitutions", topBuckets(items,
				item -> firstNonBlank(item, "ntceInsttNm", "dminsttNm")));
		response.put("contractMethods", topBuckets(items,
				item -> firstNonBlank(item, "cntrctCnclsMthdNm", "_contractSummary")));
		response.put("keywords", groupedKeywordTrends(items, profile.keywordGroups()));
		response.put("closingSoon", closingSoon);
		response.put("highValue", highValue);
		response.put("_cached", cached.cached());
		return response;
	}

	// ── 수집 ────────────────────────────────────────────────────────────────

	/**
	 * 프로파일 하나의 엔드포인트를 훑어 항목을 모은다.
	 *
	 * <p>검색어가 있으면 <b>그룹 라벨을 키워드 목록으로 펴서</b> 각각 조회한다. 라벨을 그대로
	 * 상류에 보내면 0건이 온다 — 'AI' 라는 문자열이 공고명에 그대로 들어 있는 경우는 거의 없다.
	 */
	private List<Map<String, Object>> collect(TrendProfile profile, SearchCriteria criteria) {
		String endpoint = endpoints.bidAnnounce().get(profile.typeName());
		if (endpoint == null) {
			return List.of();
		}
		List<DateWindow> windows = criteria.dateWindows();
		List<String> andTerms = criteria.and();
		List<String> orTerms = criteria.or();
		List<String> notTerms = criteria.not();

		List<String> terms = expandTerms(andTerms.isEmpty() ? orTerms : andTerms, profile.keywordGroups());

		List<Supplier<List<Map<String, Object>>>> tasks = new ArrayList<>();
		if (terms.isEmpty()) {
			for (DateWindow window : windows) {
				tasks.add(() -> safeFetch(endpoint, params(window)));
			}
		}
		else {
			for (String term : terms) {
				for (DateWindow window : windows) {
					tasks.add(() -> safeFetch(endpoint, params(window, "bidNtceNm", term)));
					tasks.add(() -> safeFetch(endpoint, params(window, "dtilPrdctClsfcNoNm", term)));
					if (NoticeFetchSupport.isClassificationCode(term)) {
						tasks.add(() -> safeFetch(endpoint, params(window, "dtilPrdctClsfcNo", term)));
					}
				}
			}
		}

		List<Map<String, Object>> fetched = MapLimit.flatMap(tasks, 6, Supplier::get);

		Set<String> seen = new LinkedHashSet<>();
		List<Map<String, Object>> items = new ArrayList<>();
		for (Map<String, Object> item : fetched) {
			String key = str(item.get("bidNtceNo")) + "|" + firstNonBlank(item, "bidNtceOrd", "bidNtceSqNo");
			if (!seen.add(key)) {
				continue;
			}
			if (!matchesTrendQuery(BidEnrichment.bidSearchHaystack(item),
					andTerms, orTerms, notTerms, profile.keywordGroups())) {
				continue;
			}
			Map<String, Object> copy = new LinkedHashMap<>(item);
			copy.put("_type", profile.typeName());
			items.add(BidEnrichment.enrichBidNotice(copy));
		}

		List<Map<String, Object>> scored = OpportunityScoring.applyOpportunity(items, Stage.BID_ANNOUNCE);
		if (!criteria.activeOnlyEnabled()) {
			return scored;
		}
		return scored.stream()
				.filter(item -> !SearchQuery.isPastDeadline(item.get("bidClseDt")))
				.filter(item -> !Boolean.TRUE.equals(item.get("_isCancelled")))
				.toList();
	}

	/**
	 * 트렌드는 <b>부분 실패를 삼킨다</b> — 집계 화면에서 몇 건 빠지는 것보다 화면 전체가
	 * 오류로 죽는 쪽이 나쁘다. 단 인증·레이트리밋은 {@code fetchEnriched} 가 그대로 올린다.
	 */
	private List<Map<String, Object>> safeFetch(String endpoint, Map<String, Object> params) {
		return support.fetchEnriched(List.of(endpoint), params, NoticeFetchSupport.RESULT_ROWS);
	}

	// ── 그룹 확장 · 질의 판정 ───────────────────────────────────────────────

	/** 그룹 라벨이면 그 그룹의 키워드로, 아니면 입력 그대로. */
	static List<String> expandTerm(String term, List<KeywordGroup> groups) {
		String value = term == null ? "" : term.trim();
		if (value.isEmpty()) {
			return List.of();
		}
		String lower = value.toLowerCase(Locale.ROOT);
		for (KeywordGroup group : groups) {
			if (group.label().toLowerCase(Locale.ROOT).equals(lower)) {
				return group.keywords();
			}
		}
		return List.of(value);
	}

	static List<String> expandTerms(List<String> terms, List<KeywordGroup> groups) {
		Set<String> expanded = new LinkedHashSet<>();
		for (String term : terms == null ? List.<String>of() : terms) {
			expanded.addAll(expandTerm(term, groups));
		}
		return List.copyOf(expanded);
	}

	/**
	 * 그룹 확장을 반영한 AND/OR/NOT 판정.
	 *
	 * <p>AND 항 하나가 그룹이면 그 그룹의 <b>키워드 중 하나라도</b> 있으면 만족으로 본다.
	 * 그렇지 않으면 'AI' 그룹으로 검색했을 때 여섯 키워드를 모두 가진 공고만 남는다.
	 */
	static boolean matchesTrendQuery(String text, List<String> andTerms, List<String> orTerms,
			List<String> notTerms, List<KeywordGroup> groups) {
		String haystack = text == null ? "" : text.toLowerCase(Locale.ROOT);

		for (String term : andTerms) {
			List<String> group = expandTerm(term, groups);
			if (!group.isEmpty() && group.stream().noneMatch(t -> haystack.contains(t.toLowerCase(Locale.ROOT)))) {
				return false;
			}
		}
		List<List<String>> orGroups = orTerms.stream()
				.map(term -> expandTerm(term, groups))
				.filter(group -> !group.isEmpty())
				.toList();
		if (!orGroups.isEmpty() && orGroups.stream().noneMatch(
				group -> group.stream().anyMatch(t -> haystack.contains(t.toLowerCase(Locale.ROOT))))) {
			return false;
		}
		return expandTerms(notTerms, groups).stream()
				.noneMatch(t -> haystack.contains(t.toLowerCase(Locale.ROOT)));
	}

	// ── 집계 ────────────────────────────────────────────────────────────────

	/**
	 * 금액 추출. 필드가 넷 중 하나로 온다.
	 *
	 * <p>값이 없으면 <b>0</b> 이다 — 여기서는 {@code null} 이 아니라 0이 맞다. 합계·평균의
	 * 분모에서 빠져야 하는데, 그 판정을 호출부가 {@code > 0} 하나로 하기 때문이다.
	 */
	static long trendAmount(Map<String, Object> item) {
		if (item == null) {
			return 0;
		}
		for (String key : new String[] {"presmptPrce", "asignBdgtAmt", "bdgtAmt", "bssamt"}) {
			Object raw = item.get(key);
			if (isAbsent(raw)) {
				continue;
			}
			// 값이 '존재하는' 첫 필드에서 판정을 끝낸다. 다음 후보로 넘어가지 않는 것이 원본
			// 동작이며, 그래야 "추정가격 0원으로 공고된 건"이 예산액으로 슬쩍 대체되지 않는다.
			BigDecimal parsed = Numbers.toNumber(raw);
			return (parsed != null && parsed.signum() > 0) ? parsed.longValue() : 0;
		}
		return 0;
	}

	/** 원본 JS 의 진리값 판정과 같은 '없음': {@code null}, 빈 문자열, 숫자 0. */
	private static boolean isAbsent(Object raw) {
		if (raw == null) {
			return true;
		}
		if (raw instanceof Number number) {
			return number.doubleValue() == 0;
		}
		return String.valueOf(raw).isEmpty();
	}

	private static long medianAmount(List<Long> amounts) {
		List<BigDecimal> values = amounts.stream().map(BigDecimal::valueOf).toList();
		BigDecimal median = MarketPriceService.median(values);
		return median == null ? 0 : median.setScale(0, java.math.RoundingMode.HALF_UP).longValue();
	}

	/** {@code YYYYMMDD…} → {@code YYYY-MM-DD}. 못 읽으면 '미상'. */
	static String dateLabel(Object value) {
		String digits = value == null ? "" : String.valueOf(value).replaceAll("\\D", "");
		if (digits.length() < 8) {
			return "미상";
		}
		return digits.substring(0, 4) + "-" + digits.substring(4, 6) + "-" + digits.substring(6, 8);
	}

	/** 화면 카드가 쓰는 축약 항목. 전체 필드를 실어 보내면 응답이 수십 배가 된다. */
	static Map<String, Object> compactTrendItem(Map<String, Object> item) {
		Map<String, Object> compact = new LinkedHashMap<>();
		compact.put("bidNtceNo", str(item.get("bidNtceNo")));
		compact.put("bidNtceNm", str(item.get("bidNtceNm")));
		compact.put("ntceInsttNm", firstNonBlank(item, "ntceInsttNm", "dminsttNm"));
		compact.put("presmptPrce", trendAmount(item));
		compact.put("bidNtceDt", str(item.get("bidNtceDt")));
		compact.put("bidClseDt", str(item.get("bidClseDt")));
		compact.put("cntrctCnclsMthdNm", str(item.get("cntrctCnclsMthdNm")));
		compact.put("_type", str(item.get("_type")));
		compact.put("_opportunityScore", item.getOrDefault("_opportunityScore", 0));
		compact.put("_opportunitySummary", str(item.get("_opportunitySummary")));
		return compact;
	}

	/**
	 * 같은 제목·기관의 공고를 하나로 접는다.
	 *
	 * <p>분할 발주(같은 사업을 여러 건으로 쪼갠 것)가 그대로 세어지면 "이 기관이 갑자기
	 * 20건을 냈다"는 잘못된 신호가 된다. 접은 건수와 합계 금액을 따로 남겨
	 * ({@code _sameTitleCount}/{@code _sameTitleAmount}) 화면이 그 사실을 보여줄 수 있게 한다.
	 */
	static List<Map<String, Object>> uniqueTrendNotices(List<Map<String, Object>> items, int limit) {
		Map<String, Map<String, Object>> notices = new LinkedHashMap<>();
		for (Map<String, Object> item : items) {
			Map<String, Object> compact = compactTrendItem(item);
			String key = str(compact.get("bidNtceNm")).trim() + "|" + str(compact.get("ntceInsttNm")).trim();
			Map<String, Object> previous = notices.get(key);
			if (previous != null) {
				previous.put("_sameTitleCount", ((Number) previous.get("_sameTitleCount")).intValue() + 1);
				previous.put("_sameTitleAmount",
						((Number) previous.get("_sameTitleAmount")).longValue()
								+ ((Number) compact.get("presmptPrce")).longValue());
			}
			else {
				compact.put("_sameTitleCount", 1);
				compact.put("_sameTitleAmount", ((Number) compact.get("presmptPrce")).longValue());
				notices.put(key, compact);
			}
		}
		return notices.values().stream()
				.sorted(Comparator
						.comparingLong((Map<String, Object> m) -> ((Number) m.get("_sameTitleAmount")).longValue())
						.reversed()
						.thenComparing(m -> str(m.get("bidNtceNm"))))
				.limit(limit)
				.toList();
	}

	private static List<Map<String, Object>> topBuckets(List<Map<String, Object>> items,
			java.util.function.Function<Map<String, Object>, String> keyFn) {
		Map<String, long[]> buckets = new LinkedHashMap<>();
		for (Map<String, Object> item : items) {
			String key = keyFn.apply(item);
			long[] bucket = buckets.computeIfAbsent(key.isEmpty() ? "미상" : key, k -> new long[2]);
			bucket[0]++;
			bucket[1] += trendAmount(item);
		}
		return buckets.entrySet().stream()
				.sorted(Comparator
						.comparingLong((Map.Entry<String, long[]> e) -> e.getValue()[0]).reversed()
						.thenComparing(Comparator.comparingLong((Map.Entry<String, long[]> e) -> e.getValue()[1])
								.reversed()))
				.limit(TOP_BUCKET_LIMIT)
				.map(e -> bucket(e.getKey(), e.getValue()[0], e.getValue()[1]))
				.toList();
	}

	private static List<Map<String, Object>> dayBuckets(List<Map<String, Object>> items) {
		Map<String, List<Map<String, Object>>> byDay = new LinkedHashMap<>();
		for (Map<String, Object> item : items) {
			byDay.computeIfAbsent(dateLabel(item.get("bidNtceDt")), k -> new ArrayList<>()).add(item);
		}
		return byDay.entrySet().stream()
				.sorted(Map.Entry.comparingByKey())
				.map(entry -> {
					long count = entry.getValue().size();
					long amount = entry.getValue().stream().mapToLong(BidTrendService::trendAmount).sum();
					Map<String, Object> bucket = bucket(entry.getKey(), count, amount);
					bucket.put("notices", uniqueTrendNotices(entry.getValue(), DAY_NOTICE_LIMIT));
					return bucket;
				})
				.toList();
	}

	/**
	 * 12개 키워드 그룹별 집계.
	 *
	 * <p>한 공고가 여러 그룹에 동시에 잡힐 수 있다(예: 'AI 서버 구축'). 배타적으로 나누지
	 * 않는 것이 의도다 — 사용자가 보는 것은 분류가 아니라 "이 키워드가 얼마나 나오나"다.
	 */
	static List<Map<String, Object>> groupedKeywordTrends(List<Map<String, Object>> items,
			List<KeywordGroup> groups) {
		Map<String, long[]> counts = new LinkedHashMap<>();
		groups.forEach(group -> counts.put(group.label(), new long[2]));

		for (Map<String, Object> item : items) {
			String title = joinLower(item, "bidNtceNm", "dtilPrdctClsfcNoNm", "prdctClsfcNoNm",
					"pubPrcrmntClsfcNm", "_requirementSummary");
			long amount = trendAmount(item);
			for (KeywordGroup group : groups) {
				if (group.keywords().stream().anyMatch(k -> title.contains(k.toLowerCase(Locale.ROOT)))) {
					long[] bucket = counts.get(group.label());
					bucket[0]++;
					bucket[1] += amount;
				}
			}
		}
		return counts.entrySet().stream()
				.filter(e -> e.getValue()[0] > 0)
				.sorted(Comparator
						.comparingLong((Map.Entry<String, long[]> e) -> e.getValue()[0]).reversed()
						.thenComparing(Comparator.comparingLong((Map.Entry<String, long[]> e) -> e.getValue()[1])
								.reversed()))
				.limit(KEYWORD_LIMIT)
				.map(e -> bucket(e.getKey(), e.getValue()[0], e.getValue()[1]))
				.toList();
	}

	// ── 내부 ────────────────────────────────────────────────────────────────

	private static Map<String, Object> bucket(String name, long count, long amount) {
		Map<String, Object> bucket = new LinkedHashMap<>();
		bucket.put("name", name);
		bucket.put("count", count);
		bucket.put("amount", amount);
		return bucket;
	}

	private static Map<String, Object> params(DateWindow window) {
		Map<String, Object> params = new LinkedHashMap<>();
		params.put("inqryBgnDt", window.from());
		params.put("inqryEndDt", window.to());
		return params;
	}

	private static Map<String, Object> params(DateWindow window, String key, String value) {
		Map<String, Object> params = params(window);
		params.put(key, value);
		return params;
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
