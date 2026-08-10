package com.electerior.g2bmaster.notice;

import com.electerior.g2bmaster.common.MapLimit;
import com.electerior.g2bmaster.integration.g2b.G2bErrorTranslator;
import com.electerior.g2bmaster.integration.g2b.G2bFetchService;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 검색 4탭이 공유하는 상류 조회·필터 헬퍼.
 *
 * <p>{@code G2bFetchService.fetchAll} 과 겹쳐 보이지만 하는 일이 다르다. 그쪽은 "여러 URL을
 * 훑어 원자료를 합친다"까지고, 여기는 거기에 <b>사업 구분({@code _type}) 부착과 항목 보강</b>을
 * 얹는다. 이 두 가지는 통합 계층이 아니라 공고 도메인의 지식이라 여기 있어야 한다.
 *
 * <p>부분 실패 정책은 상류와 같다 — 물품 API 하나가 삐끗했다고 용역·공사 결과까지 화면에서
 * 사라지면 안 된다. 단 인증·레이트리밋은 즉시 올린다(전체가 못 쓸 상태다).
 */
@Component
public class NoticeFetchSupport {

	/** 낙찰정보 페이지 크기. 100이면 한 달 6,857건 중 100건만 받아 98%를 유실한다(실측). */
	public static final int RESULT_ROWS = 999;

	/** 발주계획·사전규격은 날짜 이분 분할 없이 페이지만 훑는다. 원본 {@code g2bFetchPaged} 기본값. */
	public static final int PAGED_ROWS = 999;

	private static final int PAGED_MAX_ROWS = 10_000;

	/**
	 * 물품분류번호(8자리)·세부품명번호(10자리)만 코드로 인정한다.
	 * (물품목록정보서비스 1.2 기준. 다른 자릿수를 코드로 보내면 API가 빈 응답을 준다.)
	 */
	private static final Pattern CLASSIFICATION_CODE = Pattern.compile("^\\d{8}$|^\\d{10}$");

	private final G2bFetchService fetchService;
	private final G2bErrorTranslator translator;

	public NoticeFetchSupport(G2bFetchService fetchService, G2bErrorTranslator translator) {
		this.fetchService = fetchService;
		this.translator = translator;
	}

	public static boolean isClassificationCode(String term) {
		return term != null && CLASSIFICATION_CODE.matcher(term.trim()).matches();
	}

	// ── 조회 ────────────────────────────────────────────────────────────────

	/**
	 * 여러 엔드포인트를 훑어 보강된 공고 목록을 만든다 ({@code g2bFetchAll} 이식).
	 *
	 * <p>{@code _type} 은 URL 에서 되읽는다 — 응답 본문에는 사업 구분이 없는 오퍼레이션이 있다.
	 */
	public List<Map<String, Object>> fetchEnriched(Collection<String> urls, Map<String, ?> params, int numOfRows) {
		List<String> urlList = List.copyOf(urls);
		if (urlList.isEmpty()) {
			return List.of();
		}
		List<FetchOutcome> results = MapLimit.map(urlList, 3, url -> {
			try {
				List<Map<String, Object>> rows = fetchService.fetchUrl(url, params, numOfRows);
				String type = G2bEndpoints.typeOfUrl(url);
				List<Map<String, Object>> enriched = new ArrayList<>(rows.size());
				for (Map<String, Object> row : rows) {
					Map<String, Object> copy = new LinkedHashMap<>(row);
					copy.put("_type", type);
					enriched.add(BidEnrichment.enrichBidNotice(copy));
				}
				return new FetchOutcome(enriched, null);
			}
			catch (RuntimeException ex) {
				// 인증·레이트리밋은 다른 엔드포인트에서도 똑같이 실패한다. 즉시 올려서
				// 남은 쿼터를 태우지 않는다.
				if (translator.isAuthError(ex) || translator.isRateLimitError(ex)) {
					throw ex;
				}
				return new FetchOutcome(List.of(), ex);
			}
		});

		List<Map<String, Object>> merged = new ArrayList<>();
		RuntimeException firstError = null;
		int failed = 0;
		for (FetchOutcome outcome : results) {
			merged.addAll(outcome.items());
			if (outcome.error() != null) {
				failed++;
				if (firstError == null) {
					firstError = outcome.error();
				}
			}
		}
		if (firstError != null && failed == urlList.size()) {
			throw firstError;
		}
		return merged;
	}

	/**
	 * 날짜 이분 분할 없이 페이지만 훑는다 ({@code g2bFetchPaged} 이식).
	 *
	 * <p>발주계획·사전규격은 응답량이 작고 {@code [07]} 범위오류도 나지 않아 분할이 필요 없다.
	 * 오히려 분할을 걸면 같은 자료를 여러 창으로 나눠 받으며 호출만 늘어난다.
	 */
	public List<Map<String, Object>> fetchPaged(String url, Map<String, ?> params) {
		return fetchService.fetchPaged(url, params, PAGED_ROWS, PAGED_MAX_ROWS);
	}

	// ── 필터 ────────────────────────────────────────────────────────────────

	/** 사업 구분 필터. {@code _type}·{@code bsnsDivNm}·{@code busiDivs} 중 아무거나 걸리면 통과. */
	public static boolean itemMatchesBidType(Map<String, Object> item, String bidType) {
		if (bidType == null || bidType.isEmpty()) {
			return true;
		}
		String text = join(item, "_type", "bsnsDivNm", "busiDivs");
		return text.contains(bidType);
	}

	public static List<Map<String, Object>> filterByBidType(List<Map<String, Object>> items, String bidType) {
		if (bidType == null || bidType.isEmpty() || items == null) {
			return items == null ? List.of() : items;
		}
		return items.stream().filter(item -> itemMatchesBidType(item, bidType)).toList();
	}

	/**
	 * 발주기관 필터.
	 *
	 * <p>서버측 {@code ntceInsttNm}/{@code dminsttNm} 파라미터가 <b>무시되는 오퍼레이션이 있다</b>.
	 * 그래서 보낸 뒤에도 로컬에서 한 번 더 거른다.
	 */
	public static boolean institutionMatches(Map<String, Object> item, String insttNm, String... keys) {
		if (insttNm == null || insttNm.isEmpty()) {
			return true;
		}
		String[] fields = keys.length > 0 ? keys : new String[] {"ntceInsttNm", "dminsttNm"};
		return join(item, fields).toLowerCase(Locale.ROOT).contains(insttNm.toLowerCase(Locale.ROOT));
	}

	// ── 단계별 필드 정규화 ──────────────────────────────────────────────────

	/**
	 * 발주계획 항목을 화면·스코어링이 아는 필드명으로 맞춘다.
	 *
	 * <p>조달요청 API는 같은 뜻의 값을 입찰공고와 전혀 다른 이름으로 준다
	 * ({@code prcrmntReqNm}, {@code rprsntPrdctClsfcNo}, {@code bdgtAmt} …).
	 * 여기서 흡수하지 않으면 발주계획 탭만 점수가 0으로 나온다.
	 */
	public static Map<String, Object> normalizeBidPlanItem(Map<String, Object> item) {
		item.put("bizNm", firstNonBlank(item, "prcrmntReqNm", "bizNm"));
		item.put("__sourceStage", "bid-plan");
		item.put("prdctClsfcNoNm", firstNonBlank(item, "rprsntPrdctClsfcNoNm", "prdctClsfcNoNm"));
		item.put("prdctClsfcNo", firstNonBlank(item,
				"rprsntPrdctClsfcNo", "prdctClsfcNo", "dtilPrdctClsfcNo", "prdctClfcNo"));
		item.put("sumOrderAmt", firstNonBlank(item, "bdgtAmt", "rprsntAmt", "sumOrderAmt"));
		item.put("cntrctMthdNm", firstNonBlank(item, "cntrctCnclsStleNm", "cntrctMthdNm"));
		item.put("orderMnth", firstNonBlank(item, "rcptDt", "orderMnth"));
		item.put("nticeDt", firstNonBlank(item, "rcptDt", "nticeDt"));
		return item;
	}

	/** 발주계획 중복 판정 키. 공고번호가 없어 내용 조합으로 만들 수밖에 없다. */
	public static String bidPlanDedupeKey(Map<String, Object> item) {
		return String.join("|",
				str(item.get("bsnsDivNm")).trim(),
				str(item.get("prcrmntReqNo")).trim(),
				firstNonBlank(item, "prcrmntReqNm", "bizNm").trim(),
				str(item.get("orderInsttNm")).trim(),
				firstNonBlank(item, "rcptDt", "nticeDt").trim(),
				firstNonBlank(item, "prdctClsfcNo", "rprsntPrdctClsfcNo", "dtilPrdctClsfcNo").trim(),
				firstNonBlank(item, "prdctClsfcNoNm", "rprsntPrdctClsfcNoNm").trim(),
				str(item.get("prdctDtlList")).trim());
	}

	/** 발주계획 품목 검색용 haystack. */
	public static String bidPlanItemHaystack(Map<String, Object> item) {
		return join(item, "prdctClsfcNoNm", "rprsntPrdctClsfcNoNm", "rprsntSpecDtlsCntnts",
				"prdctDtlList", "prdctClsfcNo", "rprsntPrdctClsfcNo", "dtilPrdctClsfcNo");
	}

	/**
	 * 사전규격 항목 정규화.
	 *
	 * <p>{@code prdctDtlList} 는 {@code [코드^품명^…]} 형태의 문자열 목록이라, 첫 품목을
	 * 뽑아 분류코드/품명 자리를 채운다. 안 채우면 품목 검색과 스코어링이 사전규격만 놓친다.
	 */
	public static Map<String, Object> normalizePreSpecItem(Map<String, Object> item) {
		item.putIfAbsent("__sourceStage", "pre-spec");
		ProductDetail detail = firstProductDetail(str(item.get("prdctDtlList")));
		if (detail != null) {
			item.put("prdctClsfcNo", firstNonBlankOr(item, detail.code(), "prdctClsfcNo", "dtilPrdctClsfcNo"));
			item.put("prdctClsfcNoNm", firstNonBlankOr(item, detail.name(), "prdctClsfcNoNm", "dtilPrdctClsfcNoNm"));
		}
		item.put("prdctClfcNo", firstNonBlank(item, "bfSpecRgstNo", "prdctClfcNo"));
		item.put("ntceInsttNm", firstNonBlank(item, "orderInsttNm", "ntceInsttNm"));
		item.put("pstDt", firstNonBlank(item, "rcptDt", "rgstDt", "pstDt"));
		item.put("primarySpecFileUrl", firstNonBlank(item,
				"specDocFileUrl1", "specDocFileUrl2", "specDocFileUrl3", "primarySpecFileUrl"));
		return item;
	}

	/** 사전규격 검색 대상 텍스트. 필드 구성이 입찰공고와 달라 별도로 만든다. */
	public static String preSpecHaystack(Map<String, Object> item, boolean itemSearch) {
		return itemSearch
				? join(item, "prdctClsfcNoNm", "prdctDtlList")
				: join(item, "prdctClsfcNoNm", "ntceInsttNm", "rlDminsttNm", "prdctDtlList");
	}

	/** {@code prdctDtlList} 의 첫 항목. {@code [코드^품명^…]} 또는 {@code 코드^품명}. */
	static ProductDetail firstProductDetail(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		String first = raw.startsWith("[") ? raw.substring(1) : raw;
		int close = first.indexOf(']');
		if (close >= 0) {
			first = first.substring(0, close);
		}
		String[] parts = first.split("\\^");
		if (parts.length < 2) {
			return null;
		}
		return new ProductDetail(parts[0].trim(), parts[1].trim());
	}

	record ProductDetail(String code, String name) {}

	// ── 내부 ────────────────────────────────────────────────────────────────

	private record FetchOutcome(List<Map<String, Object>> items, RuntimeException error) {}

	private static String join(Map<String, Object> item, String... keys) {
		StringBuilder sb = new StringBuilder();
		for (String key : keys) {
			String value = str(item == null ? null : item.get(key));
			if (value.isEmpty()) {
				continue;
			}
			if (!sb.isEmpty()) {
				sb.append(' ');
			}
			sb.append(value);
		}
		return sb.toString();
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

	/** 기존 필드가 있으면 그것을, 없으면 {@code fallback} 을 쓴다. */
	private static String firstNonBlankOr(Map<String, Object> item, String fallback, String... keys) {
		String existing = firstNonBlank(item, keys);
		return existing.isEmpty() ? (fallback == null ? "" : fallback) : existing;
	}

	private static String str(Object value) {
		return value == null ? "" : String.valueOf(value);
	}
}
