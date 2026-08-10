package com.electerior.g2bmaster.integration.d2b;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 국방전자조달(D2B) 응답을 나라장터 항목 모양으로 맞추는 순수 변환 ({@code lib/d2b-normalizer.js} 이식).
 *
 * <p>D2B는 필드 이름이 나라장터와 전혀 다르다({@code bidNm} vs {@code bidNtceNm},
 * {@code ornt} vs {@code ntceInsttNm}). 화면·스코어링·정렬이 한 벌의 필드만 알면 되도록
 * 여기서 흡수한다 — 소비처마다 "D2B면 이 필드" 분기를 넣으면 언젠가 한 곳이 빠진다.
 *
 * <p>모든 메서드는 부수효과가 없다.
 */
public final class D2bNormalizer {

	private static final Pattern CANCELLED = Pattern.compile("취소|철회|무효|cancel",
			Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

	private static final Pattern NON_DIGIT = Pattern.compile("\\D");

	private D2bNormalizer() {
	}

	/** 임의 형식의 날짜에서 앞 8자리({@code YYYYMMDD})만 뽑는다. 8자리 미만이면 빈 문자열. */
	public static String compactD2bDate(Object value) {
		String digits = digitsOf(value);
		return digits.length() >= 8 ? digits.substring(0, 8) : "";
	}

	/** 표시용 포맷. 12자리면 분까지, 8자리면 날짜까지. 그 외에는 빈 문자열. */
	public static String d2bFmtDate(Object value) {
		String d = digitsOf(value);
		if (d.length() >= 12) {
			return d.substring(0, 4) + "-" + d.substring(4, 6) + "-" + d.substring(6, 8)
					+ " " + d.substring(8, 10) + ":" + d.substring(10, 12);
		}
		if (d.length() >= 8) {
			return d.substring(0, 4) + "-" + d.substring(4, 6) + "-" + d.substring(6, 8);
		}
		return "";
	}

	/** 공고일 후보. 오퍼레이션마다 어느 필드가 채워지는지가 달라 순서대로 훑는다. */
	public static String d2bNoticeDate(Map<String, Object> item) {
		if (item == null) {
			return "";
		}
		return firstNonBlank(item, "pblancDate", "ntatPlanDate", "rgstDt");
	}

	/**
	 * 조회 창 안에 드는가.
	 *
	 * <p><b>날짜를 못 읽으면 {@code true}</b> 다. D2B는 공고일이 비어 오는 항목이 실제로 있는데,
	 * 그걸 범위 밖으로 보고 버리면 국방 공고가 검색에서 통째로 사라진다.
	 */
	public static boolean isDateInRange(Object value, String fromDate, String toDate) {
		String date = compactD2bDate(value);
		String from = compactD2bDate(fromDate);
		String to = compactD2bDate(toDate);
		if (date.isEmpty()) {
			return true;
		}
		if (!from.isEmpty() && date.compareTo(from) < 0) {
			return false;
		}
		return to.isEmpty() || date.compareTo(to) <= 0;
	}

	/** 취소·철회·무효 공고인가. 상태 필드가 오퍼레이션마다 달라 텍스트를 모아 훑는다. */
	public static boolean isCancelledNotice(Map<String, Object> item) {
		if (item == null) {
			return false;
		}
		StringBuilder sb = new StringBuilder();
		for (String key : new String[] {"_noticeStatus", "progrsSttus", "pblancSe", "ntceKindNm",
				"bidNtceNm", "bidNm", "othbcNtatNm"}) {
			String value = str(item.get(key));
			if (!value.isEmpty()) {
				if (!sb.isEmpty()) {
					sb.append(' ');
				}
				sb.append(value);
			}
		}
		return CANCELLED.matcher(sb).find();
	}

	/**
	 * D2B 항목 → 나라장터 모양.
	 *
	 * <p>{@code bidNtceNo} 를 {@code D2B-기관-공고번호-차수} 로 합성하는 것이 핵심이다.
	 * D2B는 단일 공고번호가 없어 네 값이 모여야 유일해지고, 접두사가 없으면 나라장터 번호와
	 * 우연히 겹쳐 중복 제거 단계에서 서로를 지운다.
	 */
	public static Map<String, Object> normalizeD2bItem(Map<String, Object> item, String operation) {
		Map<String, Object> src = item == null ? Map.of() : item;
		String op = operation == null ? "" : operation;

		String busiDivs = str(src.get("busiDivs"));
		String type = "공사".equals(busiDivs) ? "공사" : "용역".equals(busiDivs) ? "용역" : "물품";
		boolean negotiation = op.contains("OthbcVltrnNtatPlan");

		// 'pblanc0dr'(숫자 0)은 D2B 응답에 실제로 섞여 오는 오타 필드다. 원본이 둘 다 보므로 유지한다.
		String noticeOrder = firstNonBlank(src, "pblancOdr", "pblanc0dr");
		if (noticeOrder.isEmpty()) {
			noticeOrder = "1";
		}
		String noticeStatus = firstNonBlank(src, "progrsSttus", "pblancSe", "excutTy");
		if (noticeStatus.isEmpty()) {
			noticeStatus = negotiation ? "공개수의" : "경쟁입찰";
		}

		Map<String, Object> out = new LinkedHashMap<>(src);
		out.put("_source", "d2b");
		out.put("_sourceLabel", "국방전자조달");
		out.put("_noticeStatus", noticeStatus);
		out.put("_isCancelled", isCancelledNotice(out));
		out.put("_d2bOperation", op);
		out.put("_type", type);
		out.put("bidNtceNo", "D2B-" + str(src.get("orntCode")) + "-"
				+ firstNonBlank(src, "pblancNo", "dcsNo") + "-" + noticeOrder);
		out.put("bidNtceOrd", noticeOrder);
		out.put("bidNtceNm", firstNonBlank(src, "bidNm", "othbcNtatNm"));
		out.put("ntceInsttNm", str(src.get("ornt")));
		out.put("dminsttNm", str(src.get("ornt")));
		out.put("presmptPrce", firstNonBlank(src, "bsicExpt", "budgetAmount"));
		out.put("bidClseDt", d2bFmtDate(firstNonBlank(src,
				"biddocPresentnClosDt", "bidPartcptRegistClosDt", "prqudoPresentnClosDt")));
		out.put("bidNtceDt", d2bFmtDate(d2bNoticeDate(src)));
		out.put("cntrctCnclsMthdNm", firstNonBlank(src, "cntrctMth", "bidMth"));
		out.put("bsnsDivNm", busiDivs.isEmpty() ? type : busiDivs);
		out.put("_g2bPblancNo", str(src.get("g2bPblancNo")));
		return out;
	}

	/**
	 * 오퍼레이션별 조회 파라미터.
	 *
	 * <p>공개수의({@code OthbcVltrnNtatPlan})는 <b>날짜 파라미터를 받지 않는다</b> — 붙이면
	 * 빈 응답이 온다. 그래서 날짜 필터는 조회 후 {@link #isDateInRange} 로 다시 건다.
	 */
	public static Map<String, String> d2bParamsForOperation(String operation, String keyword,
			String fromDate, String toDate) {
		Map<String, String> params = new LinkedHashMap<>();
		String op = operation == null ? "" : operation;
		String kw = keyword == null ? "" : keyword;

		if (op.contains("OthbcVltrnNtatPlan")) {
			if (!kw.isEmpty()) {
				params.put("othbcNtatNm", kw);
			}
			return params;
		}
		String from = compactD2bDate(fromDate);
		String to = compactD2bDate(toDate);
		if (!kw.isEmpty()) {
			params.put("bidNm", kw);
		}
		if (!from.isEmpty()) {
			params.put("anmtDateBegin", from);
		}
		if (!to.isEmpty()) {
			params.put("anmtDateEnd", to);
		}
		return params;
	}

	// ── 내부 ────────────────────────────────────────────────────────────────

	private static String digitsOf(Object value) {
		if (value == null) {
			return "";
		}
		return NON_DIGIT.matcher(String.valueOf(value)).replaceAll("");
	}

	private static String firstNonBlank(Map<String, Object> item, String... keys) {
		for (String key : keys) {
			String value = str(item.get(key));
			if (!value.isEmpty()) {
				return value;
			}
		}
		return "";
	}

	private static String str(Object value) {
		return value == null ? "" : String.valueOf(value);
	}
}
