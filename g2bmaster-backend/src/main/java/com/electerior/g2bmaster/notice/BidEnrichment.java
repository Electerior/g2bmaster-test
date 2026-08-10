package com.electerior.g2bmaster.notice;

import com.electerior.g2bmaster.integration.d2b.D2bNormalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 공고 항목 보강 ({@code lib/bid-enrichment.js} 이식).
 *
 * <p>나라장터 공고에는 "이 공고에 우리가 참여할 수 있는가"를 가르는 제한 조건이 들어 있는데,
 * <b>필드가 번호로 흩어져 있고 이름이 제각각</b>이다({@code rgnLmtBidLocplcJdgmBssNm},
 * {@code jntcontrctDutyRgnNm1..3}, {@code indstrytyLmtYn}, {@code cmmnSpldmdCorpRgnLmtYn} …).
 * 화면에서 60개 필드를 늘어놓을 수는 없으므로 여기서 지역/업종/직접생산/자격 요약 한 줄
 * ({@code _requirementSummary})로 접는다.
 *
 * <p>규칙이 "Y 플래그 + 텍스트 정규식" 양쪽을 보는 이유는 <b>플래그가 비어 있는 공고가
 * 흔하기 때문</b>이다. 플래그만 믿으면 실제로는 업종제한이 걸린 공고를 제한 없음으로 표시한다.
 */
public final class BidEnrichment {

	private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

	private static final Pattern DATE_LIKE_KEY = Pattern.compile("dt|date|time|tm", FLAGS);
	private static final Pattern DATE_LIKE_VALUE = Pattern.compile("^\\d{4}[-./]?\\d{2}[-./]?\\d{2}");
	private static final Pattern WHITESPACE = Pattern.compile("\\s+");

	private static final Pattern REGION_KEY = Pattern.compile("rgn|region|locplc|area", FLAGS);
	private static final Pattern REGION_VALUE = Pattern.compile(
			"서울|부산|대구|인천|광주|대전|울산|세종|경기|강원|충북|충남|전북|전남|경북|경남|제주", FLAGS);
	private static final Pattern DIRECT_KEY = Pattern.compile("direct|mnfct|prod|smb|small|biz", FLAGS);
	private static final Pattern DIRECT_VALUE = Pattern.compile("직접생산|중소기업|소기업|소상공인|창업기업", FLAGS);
	private static final Pattern QUALIFICATION_KEY = Pattern.compile("qlf|license|lcns|cert|permit|indstryty", FLAGS);
	private static final Pattern PRODUCT_KEY = Pattern.compile("prdct|clsfc|item", FLAGS);

	private static final Pattern PURCHASE_PRODUCT = Pattern.compile("\\[\\d+\\^([^^\\]]+)\\^([^\\]]+)\\]");

	private static final Pattern INDUSTRY_LIMIT = Pattern.compile("업종제한|업종\\s*제한", FLAGS);
	private static final Pattern INDUSTRY_LICENSE = Pattern.compile(
			"정보통신공사업|소프트웨어사업자|컴퓨터관련서비스사업", FLAGS);
	private static final Pattern SOFTWARE_BIZ = Pattern.compile("소프트웨어사업자|SW사업|소프트웨어\\s*사업", FLAGS);
	private static final Pattern TELECOM_WORK = Pattern.compile("정보통신공사업|정보통신공사", FLAGS);
	private static final Pattern PRODUCT_LIMIT = Pattern.compile(
			"물품분류제한|물품\\s*분류\\s*제한|세부품명번호", FLAGS);
	private static final Pattern PARTICIPATION_LIMIT = Pattern.compile("참가제한|참가\\s*제한", FLAGS);
	private static final Pattern DIRECT_PRODUCTION = Pattern.compile("직접생산", FLAGS);
	private static final Pattern SMALL_BIZ = Pattern.compile("중소기업자간|중소기업", FLAGS);
	private static final Pattern TINY_BIZ = Pattern.compile("소기업", FLAGS);
	private static final Pattern MICRO_BIZ = Pattern.compile("소상공인", FLAGS);

	/** 자격 판정표. 라벨과 정규식의 짝이며, 순서가 곧 표시 순서다. */
	private static final List<Map.Entry<String, Pattern>> QUALIFICATION_CHECKS = List.of(
			Map.entry("정보통신공사업", Pattern.compile("정보통신공사업|정보통신공사", FLAGS)),
			Map.entry("소프트웨어사업자", Pattern.compile("소프트웨어사업자|SW사업자|소프트웨어\\s*사업자", FLAGS)),
			Map.entry("컴퓨터관련서비스사업", Pattern.compile("컴퓨터관련서비스사업", FLAGS)),
			Map.entry("직접생산확인", Pattern.compile("직접생산확인|직접생산\\s*증명", FLAGS)),
			Map.entry("중소기업확인", Pattern.compile("중소기업확인|중소기업\\s*확인", FLAGS)),
			Map.entry("소기업/소상공인", Pattern.compile("소기업|소상공인", FLAGS)),
			Map.entry("벤처/창업기업", Pattern.compile("벤처기업|창업기업", FLAGS)));

	private BidEnrichment() {
	}

	// ── 검색 대상 텍스트 ────────────────────────────────────────────────────

	/**
	 * 일반 검색용 haystack.
	 *
	 * <p>전체 필드가 아니라 <b>고른 필드만</b> 쓴다. 전체를 넣으면 URL·담당자 전화번호까지
	 * 걸려 "1234" 같은 검색어가 아무 공고나 잡는다.
	 */
	public static String bidSearchHaystack(Map<String, Object> item) {
		return joinNonBlank(item,
				"bidNtceNm", "ntceInsttNm", "dminsttNm",
				"dtilPrdctClsfcNo", "dtilPrdctClsfcNoNm",
				"prdctClsfcNo", "prdctClsfcNoNm", "purchsObjPrdctList",
				"pubPrcrmntClsfcNo", "pubPrcrmntClsfcNm",
				"pubPrcrmntMidClsfcNm", "pubPrcrmntLrgClsfcNm",
				"_contractSummary", "_requirementSummary");
	}

	/**
	 * 품목 검색({@code searchField=item})용 haystack — 분류·규격 필드만 본다.
	 *
	 * <p>D2B·누리장터 공고는 분류 필드가 비어 있고 품목이 공고명에만 있으므로,
	 * 그 두 출처에 한해 공고명을 포함한다.
	 */
	public static String bidItemHaystack(Map<String, Object> item) {
		List<String> values = new ArrayList<>();
		for (String key : new String[] {"dtilPrdctClsfcNo", "dtilPrdctClsfcNoNm", "prdctClsfcNo",
				"prdctClsfcNoNm", "rprsntPrdctClsfcNoNm", "pubPrcrmntClsfcNo", "pubPrcrmntClsfcNm",
				"pubPrcrmntMidClsfcNm", "pubPrcrmntLrgClsfcNm", "purchsObjPrdctList",
				"prdctDtlList", "rprsntSpecDtlsCntnts"}) {
			String value = str(item.get(key));
			if (!value.isEmpty()) {
				values.add(value);
			}
		}
		String source = str(item.get("_source"));
		if ("d2b".equals(source) || "private-g2b".equals(source)) {
			String name = str(item.get("bidNtceNm"));
			if (!name.isEmpty()) {
				values.add(name);
			}
		}
		return String.join(" ", values);
	}

	/** 발주계획처럼 필드 구성이 통째로 다른 단계에서 쓰는 전체 필드 haystack. */
	public static String allFieldHaystack(Map<String, Object> item) {
		if (item == null) {
			return "";
		}
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<String, Object> entry : item.entrySet()) {
			if (entry.getKey().startsWith("_") || entry.getValue() == null) {
				continue;
			}
			if (!sb.isEmpty()) {
				sb.append(' ');
			}
			sb.append(entry.getValue());
		}
		return sb.toString();
	}

	// ── 보강 ────────────────────────────────────────────────────────────────

	/**
	 * 항목에 출처·상태·계약방식·제한조건 요약을 얹는다. <b>전달받은 맵을 그대로 수정</b>하고
	 * 같은 참조를 돌려준다(원본 JS 와 동일). 호출부는 가변 맵을 넘겨야 한다.
	 */
	public static Map<String, Object> enrichBidNotice(Map<String, Object> item) {
		putIfBlank(item, "__sourceStage", "bid-announce");
		putIfBlank(item, "_source", "g2b");
		putIfBlank(item, "_sourceLabel", "d2b".equals(str(item.get("_source"))) ? "국방전자조달" : "나라장터");
		String status = firstNonBlank(item, "_noticeStatus", "ntceKindNm", "rgstTyNm");
		item.put("_noticeStatus", status.isEmpty() ? "공고" : status);
		item.put("_isCancelled", Boolean.TRUE.equals(item.get("_isCancelled"))
				|| D2bNormalizer.isCancelledNotice(item));

		item.put("_contractSummary", joinSlash(
				compactMethodName(item.get("cntrctCnclsMthdNm")),
				compactMethodName(item.get("sucsfbidMthdNm"))));

		// haystack 은 _contractSummary 를 얹은 뒤에 만든다 — 계약방식 문구에 들어 있는
		// '중소기업자간 경쟁' 같은 표현이 제한조건 판정의 근거가 되기 때문이다.
		String haystack = fullHaystack(item);
		List<String> requirements = new ArrayList<>();

		String regionDetail = compactList(List.of(
				str(item.get("rgnLmtBidLocplcJdgmBssNm")),
				str(item.get("jntcontrctDutyRgnNm1")),
				str(item.get("jntcontrctDutyRgnNm2")),
				str(item.get("jntcontrctDutyRgnNm3")),
				valuesByKey(item, REGION_KEY, REGION_VALUE, 5)), 3);
		if (yes(item.get("cmmnSpldmdCorpRgnLmtYn")) || !regionDetail.isEmpty()) {
			appendUnique(requirements, "지역", regionDetail.isEmpty() ? "공고서 확인" : regionDetail);
		}
		if (yes(item.get("indstrytyLmtYn"))
				|| INDUSTRY_LIMIT.matcher(haystack).find() || INDUSTRY_LICENSE.matcher(haystack).find()) {
			appendUnique(requirements, "업종", firstNonEmpty(
					industryLimitDetail(item), qualificationDetail(item, haystack), "공고서 확인"));
		}
		if (yes(item.get("infoBizYn")) || SOFTWARE_BIZ.matcher(haystack).find()) {
			appendUnique(requirements, "SW", firstNonEmpty(serviceLimitDetail(item), "소프트웨어사업자"));
		}
		if (TELECOM_WORK.matcher(haystack).find()) {
			appendUnique(requirements, "정보통신", "정보통신공사업");
		}
		if (yes(item.get("prdctClsfcLmtYn")) || PRODUCT_LIMIT.matcher(haystack).find()) {
			appendUnique(requirements, "물품분류", firstNonEmpty(
					productLimitDetail(item), valuesByKey(item, PRODUCT_KEY, null, 5), "공고서 확인"));
		}
		if (yes(item.get("mnfctYn"))) {
			appendUnique(requirements, "제조", firstNonEmpty(productLimitDetail(item), "제조물품"));
		}
		if (yes(item.get("dsgntCmptYn"))) {
			appendUnique(requirements, "경쟁", "지명경쟁");
		}
		if (yes(item.get("bidPrtcptLmtYn")) || PARTICIPATION_LIMIT.matcher(haystack).find()) {
			appendUnique(requirements, "참가제한",
					firstNonEmpty(qualificationDetail(item, haystack), "공고서 확인"));
		}
		String directDetail = directProductionDetail(item, haystack);
		if (!directDetail.isEmpty()) {
			appendUnique(requirements, "중소/직접생산", directDetail);
		}
		String qualification = qualificationDetail(item, haystack);
		if (!qualification.isEmpty() && requirements.stream().noneMatch(v -> v.contains(qualification))) {
			appendUnique(requirements, "자격", qualification);
		}

		item.put("_requirementSummary", String.join(", ", requirements));
		return item;
	}

	// ── 내부: 요약 조립 ────────────────────────────────────────────────────

	/** 판정용 텍스트. {@code __} 로 시작하는 내부 표식만 뺀다(단일 {@code _} 는 포함). */
	private static String fullHaystack(Map<String, Object> item) {
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<String, Object> entry : item.entrySet()) {
			if (entry.getKey().startsWith("__") || entry.getValue() == null) {
				continue;
			}
			if (!sb.isEmpty()) {
				sb.append(' ');
			}
			sb.append(entry.getValue());
		}
		return sb.toString();
	}

	private static boolean yes(Object value) {
		return "Y".equalsIgnoreCase(str(value));
	}

	private static void appendUnique(List<String> list, String label, String detail) {
		if (label == null || label.isEmpty()) {
			return;
		}
		String text = detail == null || detail.isEmpty() ? label : label + ": " + detail;
		if (!list.contains(text)) {
			list.add(text);
		}
	}

	/** 계약방식 문구에서 하이픈 뒤 부연을 잘라낸다("일반경쟁 - 총액" → "일반경쟁"). */
	static String compactMethodName(Object value) {
		String text = WHITESPACE.matcher(str(value)).replaceAll(" ");
		return text.replaceAll("-.*$", "").trim();
	}

	/** 중복 제거 후 최대 {@code limit} 개까지 나열하고 나머지는 "외 N건"으로 접는다. */
	static String compactList(List<String> values, int limit) {
		LinkedHashSet<String> unique = new LinkedHashSet<>();
		for (String value : values) {
			String trimmed = value == null ? "" : value.trim();
			if (!trimmed.isEmpty()) {
				unique.add(trimmed);
			}
		}
		List<String> list = new ArrayList<>(unique);
		if (list.size() <= limit) {
			return String.join(" / ", list);
		}
		return String.join(" / ", list.subList(0, limit)) + " 외 " + (list.size() - limit) + "건";
	}

	private static String productLimitDetail(Map<String, Object> item) {
		List<String> products = new ArrayList<>();
		Matcher matcher = PURCHASE_PRODUCT.matcher(str(item.get("purchsObjPrdctList")));
		while (matcher.find()) {
			products.add(matcher.group(2) + "(" + matcher.group(1) + ")");
		}
		if (products.isEmpty() && !str(item.get("dtilPrdctClsfcNoNm")).isEmpty()) {
			String code = str(item.get("dtilPrdctClsfcNo"));
			products.add(code.isEmpty()
					? str(item.get("dtilPrdctClsfcNoNm"))
					: str(item.get("dtilPrdctClsfcNoNm")) + "(" + code + ")");
		}
		if (products.isEmpty() && !str(item.get("prdctClsfcNoNm")).isEmpty()) {
			products.add(str(item.get("prdctClsfcNoNm")));
		}
		return compactList(products, 3);
	}

	private static String serviceLimitDetail(Map<String, Object> item) {
		List<String> details = new ArrayList<>();
		if (!str(item.get("pubPrcrmntClsfcNm")).isEmpty()) {
			String code = str(item.get("pubPrcrmntClsfcNo"));
			details.add(code.isEmpty()
					? str(item.get("pubPrcrmntClsfcNm"))
					: str(item.get("pubPrcrmntClsfcNm")) + "(" + code + ")");
		}
		addIfPresent(details, item, "pubPrcrmntMidClsfcNm");
		addIfPresent(details, item, "srvceDivNm");
		return compactList(details, 3);
	}

	private static String industryLimitDetail(Map<String, Object> item) {
		return compactList(List.of(
				str(item.get("indstrytyNm")),
				str(item.get("indstrytyLmtNm")),
				str(item.get("indstrytyLmtCn")),
				serviceLimitDetail(item)), 3);
	}

	/**
	 * 키/값 정규식으로 필드를 훑어 근거 문구를 모은다.
	 *
	 * <p>필드 <em>이름</em>을 다 알 수 없기 때문에 존재하는 접근이다. 날짜류 키와 날짜 모양 값,
	 * {@code 'N'}·{@code '0'} 은 의미가 없어 걸러낸다.
	 */
	static String valuesByKey(Map<String, Object> item, Pattern keyPattern, Pattern valuePattern, int limit) {
		List<String> values = new ArrayList<>();
		if (item == null) {
			return "";
		}
		for (Map.Entry<String, Object> entry : item.entrySet()) {
			if (values.size() >= limit) {
				break;
			}
			Object raw = entry.getValue();
			if (raw == null || str(raw).isEmpty() || DATE_LIKE_KEY.matcher(entry.getKey()).find()) {
				continue;
			}
			String text = WHITESPACE.matcher(String.valueOf(raw)).replaceAll(" ").trim();
			if (text.isEmpty() || "N".equals(text) || "0".equals(text)
					|| DATE_LIKE_VALUE.matcher(text).find()) {
				continue;
			}
			if (!keyPattern.matcher(entry.getKey()).find()) {
				continue;
			}
			if (valuePattern != null && !valuePattern.matcher(text).find()) {
				continue;
			}
			if (text.length() <= 120) {
				values.add(text);
			}
		}
		return compactList(values, 4);
	}

	private static String directProductionDetail(Map<String, Object> item, String haystack) {
		String detail = valuesByKey(item, DIRECT_KEY, DIRECT_VALUE, 5);
		if (!detail.isEmpty()) {
			return detail;
		}
		List<String> hits = new ArrayList<>();
		if (DIRECT_PRODUCTION.matcher(haystack).find()) {
			hits.add("직접생산");
		}
		if (SMALL_BIZ.matcher(haystack).find()) {
			hits.add("중소기업");
		}
		if (TINY_BIZ.matcher(haystack).find()) {
			hits.add("소기업");
		}
		if (MICRO_BIZ.matcher(haystack).find()) {
			hits.add("소상공인");
		}
		return compactList(hits, 3);
	}

	private static String qualificationDetail(Map<String, Object> item, String haystack) {
		List<String> hits = new ArrayList<>();
		for (Map.Entry<String, Pattern> check : QUALIFICATION_CHECKS) {
			if (check.getValue().matcher(haystack).find()) {
				hits.add(check.getKey());
			}
		}
		String fieldDetail = valuesByKey(item, QUALIFICATION_KEY, null, 5);
		if (!fieldDetail.isEmpty()) {
			hits.add(fieldDetail);
		}
		return compactList(hits, 5);
	}

	// ── 내부: 잡동사니 ──────────────────────────────────────────────────────

	private static void addIfPresent(List<String> out, Map<String, Object> item, String key) {
		String value = str(item.get(key));
		if (!value.isEmpty()) {
			out.add(value);
		}
	}

	private static void putIfBlank(Map<String, Object> item, String key, String value) {
		if (str(item.get(key)).isEmpty()) {
			item.put(key, value);
		}
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

	private static String firstNonEmpty(String... candidates) {
		for (String candidate : candidates) {
			if (candidate != null && !candidate.isEmpty()) {
				return candidate;
			}
		}
		return "";
	}

	private static String joinSlash(String a, String b) {
		if (a.isEmpty()) {
			return b;
		}
		return b.isEmpty() ? a : a + " / " + b;
	}

	private static String joinNonBlank(Map<String, Object> item, String... keys) {
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
		return sb.toString();
	}

	private static String str(Object value) {
		return value == null ? "" : String.valueOf(value);
	}
}
