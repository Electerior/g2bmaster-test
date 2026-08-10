package com.electerior.g2bmaster.market;

import com.electerior.g2bmaster.analysis.CollusionAnalysis;
import com.electerior.g2bmaster.common.G2bDates;
import com.electerior.g2bmaster.common.G2bDates.DateWindow;
import com.electerior.g2bmaster.common.MapLimit;
import com.electerior.g2bmaster.common.Numbers;
import com.electerior.g2bmaster.integration.g2b.G2bFetchService;
import com.electerior.g2bmaster.market.MarketIntelRequests.CompanyHistoryRequest;
import com.electerior.g2bmaster.market.MarketIntelRequests.OfficerSearchRequest;
import com.electerior.g2bmaster.notice.G2bEndpoints;
import com.electerior.g2bmaster.notice.NoticeFetchSupport;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 시장정보 조회 ({@code docs/api-contract.md} §2.C).
 *
 * <p>세 기능이 <b>같은 한 가지 자료</b>(개찰결과 = 참여업체별 투찰금액)를 다른 각도로 본다.
 * 업체 이력은 시간축으로, 담합 분석은 업체 짝으로, 담당자 조회는 발주 측으로.
 * 그래서 개찰결과 조회기 하나를 세 곳이 공유한다.
 *
 * <p><b>개찰결과 미공개는 오류가 아니다.</b> 개찰 전이거나 API가 그 공고를 지원하지 않으면
 * 빈 목록을 돌려준다 — 여기서 예외를 던지면 "아직 개찰 안 됨"이 화면에 500 으로 뜬다.
 */
@Service
public class MarketIntelService {

	private static final Logger log = LoggerFactory.getLogger(MarketIntelService.class);

	/** 개찰결과 페이지 크기. 한 공고의 참여업체가 100을 넘는 일은 사실상 없다. */
	private static final int OPENING_ROWS = 100;

	/** 개찰결과는 {@code inqryDiv=2}(공고번호 기준)로만 조회된다 — 1은 날짜 기준이라 빈 응답이 온다. */
	private static final int OPENING_INQRY_DIV = 2;

	/** 업체 이력·담당자 조회 기본 구간(일). 검색 탭(7일)보다 넓다 — 이력은 추세가 목적이다. */
	private static final int DEFAULT_HISTORY_DAYS = 90;

	/** 날짜범위 개찰결과 조회 페이지 크기. */
	private static final int OPENING_RANGE_ROWS = 500;

	/** 낙찰정보 조회 페이지 크기. */
	private static final int RESULT_ROWS = 500;

	/** 폴백에서 개찰결과를 개별 조회할 공고 수 상한. 공고 하나당 1콜이라 그대로 호출량이다. */
	private static final int FALLBACK_BID_LIMIT = 30;

	private static final int WIN_FALLBACK_BID_LIMIT = 20;

	/** 담합 분석 처리 상한. 20건이면 개찰결과 20콜이고, 그 이상은 응답이 분 단위로 늘어진다. */
	public static final int MAX_COLLUSION_BIDS = 20;

	private final G2bEndpoints endpoints;
	private final G2bFetchService fetchService;
	private final NoticeFetchSupport support;

	public MarketIntelService(G2bEndpoints endpoints, G2bFetchService fetchService,
			NoticeFetchSupport support) {
		this.endpoints = endpoints;
		this.fetchService = fetchService;
		this.support = support;
	}

	// ── 개찰결과 ────────────────────────────────────────────────────────────

	/**
	 * 공고 하나의 참여업체별 투찰 내역.
	 *
	 * <p>실패는 빈 목록으로 삼킨다 — 미공개/미지원과 장애를 호출부가 구분할 방법이 없고,
	 * 실제 운용에서 압도적 다수가 전자다.
	 */
	public List<Map<String, Object>> fetchOpeningResults(String bidNtceNo, String bidNtceSqNo, String type) {
		Map<String, Object> params = new LinkedHashMap<>();
		params.put("inqryDiv", OPENING_INQRY_DIV);
		params.put("bidNtceNo", bidNtceNo);
		params.put("bidNtceSqNo", (bidNtceSqNo == null || bidNtceSqNo.isBlank()) ? "000" : bidNtceSqNo);
		params.put("pageNo", 1);
		params.put("numOfRows", OPENING_ROWS);
		try {
			return fetchService.callCached(endpoints.opengResultOf(type), params).items();
		}
		catch (RuntimeException ex) {
			log.debug("개찰결과 미공개/미지원 {}: {}", bidNtceNo, ex.getMessage());
			return List.of();
		}
	}

	/**
	 * 날짜범위 개찰결과.
	 *
	 * <p>파라미터 후보를 <b>순서대로 시도</b>한다. 개찰결과 API는 오퍼레이션마다 지원하는
	 * 날짜 파라미터가 다르고, 문서에 나와 있지 않다. 개찰일 기준({@code opengBgnDt})이
	 * 먼저인 것은 그쪽이 의미상 맞기 때문이고, 안 되면 조회일 기준으로 물러선다.
	 */
	public List<Map<String, Object>> fetchOpeningByDateRange(String from, String to) {
		List<Map<String, String>> candidates = List.of(
				Map.of("opengBgnDt", ymd(from), "opengEndDt", ymd(to)),
				Map.of("inqryBgnDt", from, "inqryEndDt", to));

		List<Map.Entry<String, String>> urls = new ArrayList<>(endpoints.opengResult().entrySet());
		return MapLimit.flatMap(urls, 3, entry -> {
			for (Map<String, String> dateParams : candidates) {
				try {
					Map<String, Object> params = new LinkedHashMap<>();
					params.put("inqryDiv", 1);
					params.putAll(dateParams);
					params.put("pageNo", 1);
					params.put("numOfRows", OPENING_RANGE_ROWS);
					var response = fetchService.callCached(entry.getValue(), params);
					if (response.totalCount() > 0 || !response.items().isEmpty()) {
						List<Map<String, Object>> typed = new ArrayList<>(response.items().size());
						for (Map<String, Object> item : response.items()) {
							Map<String, Object> copy = new LinkedHashMap<>(item);
							copy.put("_type", entry.getKey());
							typed.add(copy);
						}
						return typed;
					}
				}
				catch (RuntimeException ex) {
					log.debug("개찰결과 날짜조회 실패 {}: {}", entry.getKey(), ex.getMessage());
				}
			}
			return List.<Map<String, Object>>of();
		});
	}

	// ── 업체 이력 ───────────────────────────────────────────────────────────

	/**
	 * 낙찰이력 + 참여이력 + 투찰률 추세.
	 *
	 * <p>세 단계 폴백이 있는 이유: 나라장터는 "이 업체가 참여한 입찰"을 직접 물어보는 API가
	 * 없다. 그래서 (1) 날짜범위 개찰결과를 훑어 이름으로 거르고, 그게 비면 (2) 낙찰정보에
	 * 나온 공고들의 개찰결과를 개별 조회하고, 그것도 비면 (3) 낙찰건만이라도 개찰결과를
	 * 붙인다. 폴백을 지우면 업체 화면이 자주 빈 채로 나온다.
	 */
	public Map<String, Object> companyHistory(CompanyHistoryRequest request) {
		String corp = request.corpNm() == null ? "" : request.corpNm().trim();
		String brn = request.brnNo() == null ? "" : request.brnNo().replaceAll("\\D", "");
		String corpLower = corp.toLowerCase(Locale.ROOT);

		DateWindow window = historyWindow(request.fromDate(), request.toDate());

		Map<String, Object> winBase = new LinkedHashMap<>();
		winBase.put("inqryBgnDt", window.from());
		winBase.put("inqryEndDt", window.to());
		if (!corp.isEmpty()) {
			// 서버측 필터 지원 여부가 오퍼레이션마다 다르다 — 보내되 믿지는 않는다.
			winBase.put("sucsfbidCorpNm", corp);
		}

		List<Map<String, Object>> allResults;
		try {
			allResults = support.fetchEnriched(endpoints.bidResult().values(), winBase, RESULT_ROWS);
		}
		catch (RuntimeException ex) {
			log.debug("낙찰정보 조회 실패: {}", ex.getMessage());
			allResults = List.of();
		}

		List<Map<String, Object>> wins = allResults.stream()
				.filter(item -> matchesCompany(corpLower, brn,
						values(item, "bidwinnrNm", "sucsfbidCorpNm", "corpNm"),
						values(item, "sucsfbidCorpBrn", "brnNo", "brno")))
				.toList();

		List<Map<String, Object>> participations = fetchOpeningByDateRange(window.from(), window.to()).stream()
				.filter(p -> matchesCompany(corpLower, brn, values(p, "bdrNm"), values(p, "bdrBrn", "brno")))
				.map(p -> mapParticipant(p, null))
				.collect(java.util.stream.Collectors.toCollection(ArrayList::new));

		if (participations.isEmpty() && !allResults.isEmpty()) {
			participations = participantsFromBids(
					allResults.stream().filter(b -> !str(b.get("bidNtceNo")).isEmpty())
							.limit(FALLBACK_BID_LIMIT).toList(),
					corpLower, brn, false);
		}
		if (participations.isEmpty() && !wins.isEmpty()) {
			participations = participantsFromBids(
					wins.stream().limit(WIN_FALLBACK_BID_LIMIT).toList(), corpLower, brn, true);
		}

		participations.sort(Comparator.comparing(
				(Map<String, Object> p) -> str(p.get("opengDt"))).reversed());

		List<Map<String, Object>> rates = new ArrayList<>();
		for (Map<String, Object> p : participations) {
			BigDecimal rate = Numbers.toNumber(p.get("bidprcRt"));
			if (rate == null || str(p.get("bidprcRt")).isEmpty()) {
				continue;
			}
			Map<String, Object> point = new LinkedHashMap<>();
			String opengDt = str(p.get("opengDt"));
			point.put("date", opengDt.length() >= 10 ? opengDt.substring(0, 10) : opengDt);
			point.put("rate", rate);
			point.put("won", p.get("_won"));
			point.put("name", p.get("bidNtceNm"));
			point.put("rank", p.get("rank"));
			rates.add(point);
		}
		rates.sort(Comparator.comparing(point -> str(point.get("date"))));

		long wonCount = participations.stream().filter(p -> Boolean.TRUE.equals(p.get("_won"))).count();
		int winCount = wonCount > 0 ? (int) wonCount : wins.size();

		Map<String, Object> stats = new LinkedHashMap<>();
		stats.put("winCount", winCount);
		stats.put("participationCount", participations.size());
		stats.put("winRate", participations.isEmpty() ? null
				: Math.round(winCount * 100.0 / participations.size()));
		stats.put("avgBidRate", averageRate(rates));
		stats.put("bidRateSeries", rates);

		Map<String, Object> response = new LinkedHashMap<>();
		response.put("corpNm", corp);
		response.put("wins", wins);
		response.put("participations", participations);
		response.put("stats", stats);
		response.put("from", window.from());
		response.put("to", window.to());
		return response;
	}

	// ── 담당자 조회 ─────────────────────────────────────────────────────────

	/**
	 * 발주기관 담당자별 공고 묶음.
	 *
	 * <p>담당자 정보는 별도 API가 없고 <b>공고 항목에 실려 있다</b>. 그래서 기간 내 공고를
	 * 훑어 이름·전화·이메일 조합으로 묶는 수밖에 없다. 셋 중 하나라도 있어야 담당자로 센다 —
	 * 셋 다 비면 "담당자 미기재 공고"들이 한 덩어리로 묶여 버린다.
	 */
	public Map<String, Object> officerSearch(OfficerSearchRequest request) {
		String insttNm = request.insttNm().trim();
		DateWindow window = historyWindow(request.fromDate(), request.toDate());

		Map<String, Object> params = new LinkedHashMap<>();
		params.put("dminsttNm", insttNm);
		params.put("inqryBgnDt", window.from());
		params.put("inqryEndDt", window.to());

		List<Map<String, Object>> items = support.fetchEnriched(
				endpoints.bidAnnounce().values(), params, 300);

		// dminsttNm 파라미터가 무시되는 오퍼레이션이 있어 로컬에서 한 번 더 거른다.
		List<Map<String, Object>> pool = items.stream()
				.filter(item -> NoticeFetchSupport.institutionMatches(item, insttNm))
				.toList();

		Map<String, Map<String, Object>> officers = new LinkedHashMap<>();
		for (Map<String, Object> item : pool) {
			String name = str(item.get("ntceInsttOfclNm")).trim();
			String tel = str(item.get("ntceInsttOfclTelNo")).trim();
			String email = str(item.get("ntceInsttOfclEmailAdrs")).trim();
			if (name.isEmpty() && tel.isEmpty() && email.isEmpty()) {
				continue;
			}
			String key = name + "|" + tel + "|" + email;
			Map<String, Object> officer = officers.computeIfAbsent(key, k -> {
				Map<String, Object> created = new LinkedHashMap<>();
				created.put("name", name);
				created.put("tel", tel);
				created.put("email", email);
				created.put("insttNm", str(item.get("ntceInsttNm")));
				created.put("dminsttNm", blankTo(str(item.get("dminsttNm")), insttNm));
				created.put("bids", new ArrayList<Map<String, Object>>());
				return created;
			});
			@SuppressWarnings("unchecked")
			List<Map<String, Object>> bids = (List<Map<String, Object>>) officer.get("bids");
			Map<String, Object> bid = new LinkedHashMap<>();
			bid.put("bidNtceNo", str(item.get("bidNtceNo")));
			bid.put("bidNtceNm", str(item.get("bidNtceNm")));
			bid.put("bidNtceDt", firstNonBlank(item, "bidNtceDt", "rgstDt"));
			bid.put("bidClseDt", str(item.get("bidClseDt")));
			bid.put("asignBdgtAmt", str(item.get("asignBdgtAmt")));
			bid.put("dminsttNm", str(item.get("dminsttNm")));
			bid.put("type", blankTo(str(item.get("_type")), "물품"));
			bids.add(bid);
		}

		List<Map<String, Object>> sorted = officers.values().stream()
				.sorted(Comparator.comparingInt((Map<String, Object> o) -> ((List<?>) o.get("bids")).size())
						.reversed())
				.toList();

		Map<String, Object> response = new LinkedHashMap<>();
		response.put("insttNm", insttNm);
		response.put("from", window.from());
		response.put("to", window.to());
		response.put("officers", sorted);
		response.put("totalBids", pool.size());
		return response;
	}

	// ── 담합 분석 ───────────────────────────────────────────────────────────

	/** 공고들에 개찰결과를 붙여 담합 매트릭스를 만든다. 최대 {@value #MAX_COLLUSION_BIDS} 건. */
	public Map<String, Object> collusionAnalysis(List<Map<String, Object>> bids) {
		List<Map<String, Object>> slice = bids.stream().limit(MAX_COLLUSION_BIDS).toList();

		List<Map<String, Object>> enriched = MapLimit.map(slice, 4, bid -> {
			Map<String, Object> copy = new LinkedHashMap<>(bid);
			copy.put("participants", fetchOpeningResults(
					str(bid.get("bidNtceNo")),
					str(bid.get("bidNtceSqNo")),
					blankTo(str(bid.get("_type")), "물품")));
			return copy;
		});

		CollusionAnalysis.CollusionMatrix matrix = CollusionAnalysis.buildCollusionMatrix(enriched);
		Map<String, Object> response = new LinkedHashMap<>();
		response.put("bids", enriched);
		response.put("pairs", matrix.pairs());
		response.put("companies", matrix.companies());
		return response;
	}

	// ── 내부 ────────────────────────────────────────────────────────────────

	/** 개찰결과를 공고 단위로 개별 조회하는 폴백. */
	private List<Map<String, Object>> participantsFromBids(List<Map<String, Object>> bids,
			String corpLower, String brn, boolean fallbackToFirst) {
		List<Map<String, Object>> found = MapLimit.map(bids, 4, bid -> {
			String type = normalizeType(str(bid.get("_type")));
			List<Map<String, Object>> participants = fetchOpeningResults(
					str(bid.get("bidNtceNo")),
					firstNonBlank(bid, "bidNtceSqNo", "bidNtceOrd"),
					type);
			Map<String, Object> match = participants.stream()
					.filter(p -> matchesCompany(corpLower, brn, values(p, "bdrNm"), values(p, "bdrBrn")))
					.findFirst()
					.orElse(fallbackToFirst && !participants.isEmpty() ? participants.get(0) : null);
			if (match == null) {
				return null;
			}
			Map<String, Object> withTotal = new LinkedHashMap<>(match);
			withTotal.put("_totalPts", participants.size());
			return mapParticipant(withTotal, bid);
		});
		List<Map<String, Object>> out = new ArrayList<>();
		for (Map<String, Object> item : found) {
			if (item != null) {
				out.add(item);
			}
		}
		return out;
	}

	/**
	 * 참여 내역 한 건을 화면 계약 모양으로 맞춘다.
	 *
	 * <p>{@code overrideBid} 가 있으면 공고 정보를 그쪽에서 가져온다 — 개찰결과 응답에는
	 * 공고명·기관이 없는 경우가 많아, 개별 조회 폴백에서는 원 공고를 겹쳐야 한다.
	 */
	private static Map<String, Object> mapParticipant(Map<String, Object> p, Map<String, Object> overrideBid) {
		Map<String, Object> bid = overrideBid == null ? Map.of() : overrideBid;
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("bidNtceNo", pick(bid, "bidNtceNo", p, "bidNtceNo"));
		out.put("bidNtceNm", pick(bid, "bidNtceNm", p, "bidNtceNm"));
		out.put("ntceInsttNm", firstNonBlank(bid, "dminsttNm").isEmpty()
				? firstNonBlank(p, "ntceInsttNm", "dminsttNm") : str(bid.get("dminsttNm")));
		out.put("dminsttNm", firstNonBlank(bid, "dminsttNm").isEmpty()
				? firstNonBlank(p, "dminsttNm", "ntceInsttNm") : str(bid.get("dminsttNm")));
		out.put("opengDt", firstNonBlank(bid, "rlOpengDt", "opengDt").isEmpty()
				? firstNonBlank(p, "opengDt", "rlOpengDt")
				: firstNonBlank(bid, "rlOpengDt", "opengDt"));
		out.put("presmptPrce", firstNonBlank(bid, "presmptPrce").isEmpty()
				? firstNonBlank(p, "presmptPrce", "asignBdgtAmt") : str(bid.get("presmptPrce")));
		out.put("_type", firstNonBlank(bid, "_type").isEmpty()
				? firstNonBlank(p, "_type", "bsnsDivNm") : str(bid.get("_type")));
		out.put("bdrNm", str(p.get("bdrNm")));
		out.put("rank", str(p.get("rank")));
		out.put("bidAmt", str(p.get("bidAmt")));
		out.put("bidprcRt", str(p.get("bidprcRt")));
		out.put("sucsfbidYn", blankTo(str(p.get("sucsfbidYn")), "N"));
		out.put("_won", "Y".equals(str(p.get("sucsfbidYn"))));
		if (p.get("_totalPts") != null) {
			out.put("totalParticipants", p.get("_totalPts"));
		}
		return out;
	}

	/** 사업자번호가 있으면 그쪽이 우선이다 — 상호는 겹치지만 사업자번호는 유일하다. */
	private static boolean matchesCompany(String corpLower, String brn, List<String> names, List<String> brns) {
		if (!brn.isEmpty() && brns.stream().anyMatch(v -> v.replaceAll("\\D", "").equals(brn))) {
			return true;
		}
		return !corpLower.isEmpty()
				&& names.stream().anyMatch(v -> v.toLowerCase(Locale.ROOT).contains(corpLower));
	}

	private static BigDecimal averageRate(List<Map<String, Object>> rates) {
		if (rates.isEmpty()) {
			return null;
		}
		BigDecimal sum = BigDecimal.ZERO;
		for (Map<String, Object> point : rates) {
			sum = sum.add((BigDecimal) point.get("rate"));
		}
		return sum.divide(BigDecimal.valueOf(rates.size()), 3, RoundingMode.HALF_UP);
	}

	private DateWindow historyWindow(String fromDate, String toDate) {
		DateWindow def = G2bDates.defaultDates(DEFAULT_HISTORY_DAYS);
		String from = G2bDates.toG2bDt(fromDate, false);
		String to = G2bDates.toG2bDt(toDate, true);
		return new DateWindow(from == null ? def.from() : from, to == null ? def.to() : to);
	}

	private static String normalizeType(String raw) {
		if (raw.contains("용역")) {
			return "용역";
		}
		return raw.contains("공사") ? "공사" : "물품";
	}

	private static String pick(Map<String, Object> primary, String primaryKey,
			Map<String, Object> fallback, String fallbackKey) {
		String value = str(primary.get(primaryKey));
		return value.isEmpty() ? str(fallback.get(fallbackKey)) : value;
	}

	private static List<String> values(Map<String, Object> item, String... keys) {
		Set<String> out = new LinkedHashSet<>();
		for (String key : keys) {
			String value = str(item.get(key));
			if (!value.isEmpty()) {
				out.add(value);
			}
		}
		return List.copyOf(out);
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

	private static String blankTo(String value, String fallback) {
		return value == null || value.isBlank() ? fallback : value;
	}

	private static String ymd(String value) {
		String digits = value == null ? "" : value.replaceAll("\\D", "");
		return digits.length() >= 8 ? digits.substring(0, 8) : digits;
	}

	private static String str(Object value) {
		return value == null ? "" : String.valueOf(value);
	}
}
