package com.electerior.g2bmaster.pricing;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI 가 낸 부품 단가 추정을 <b>규격서 원문과 대조해</b> 채택 여부를 판정한다.
 *
 * <p>경계 계약({@code docs/ai-boundary.md} §4)이 이 층을 백엔드에 두라고 정한 이유는 하나다 —
 * <b>AI 가 자기 응답을 자기가 검증하면 그건 검증이 아니다.</b> 같은 문서가 문서 인용
 * ({@code evidence.quote})에 대해 쓰는 원칙을 가격에도 그대로 적용한다: LLM 은 부품을
 * <i>제안</i>할 뿐이고, 채택은 그 근거가 규격서 원문에 실제로 있는지로 결정한다.
 *
 * <p>AI 는 이미 판단에 필요한 신호를 전부 봉투에 실어 보낸다({@code allPriced}·{@code inferred}·
 * {@code hasBase}·{@code prebuilt}). 지금까지 백엔드는 {@code matched} 와 {@code mid} 만 읽었다.
 *
 * <h2>실측 근거 (규격서 115건 · 가격이 나온 23건 · 199행)</h2>
 * <table border="1">
 *   <caption>기존 동작에서 관측된 결함</caption>
 *   <tr><th>관측</th><th>값</th></tr>
 *   <tr><td>모델명이 규격서 본문에 있는 행</td><td>115 / 199 (57.8%)</td></tr>
 *   <tr><td>같은 카테고리에 상충 모델이 공존한 문서</td><td>10 / 23</td></tr>
 *   <tr><td>{@code allPriced=false} 인데 총액이 확정된 문서</td><td>5 / 23</td></tr>
 *   <tr><td>0 원으로 계상된 행</td><td>4</td></tr>
 *   <tr><td>{@code inferred}(모델 미특정 참고값)인데 합산된 행</td><td>19 (1.1억원)</td></tr>
 * </table>
 *
 * <p>한 사례에서는 규격서가 <b>128GB</b> 를 요구했는데 512GB 가 3,016만원으로 잡혀 총액의
 * 41.6% 를 차지했고, 그 부품명은 규격서에 한 글자도 없었다.
 */
public final class UnitCostValidator {

	/** 이 비율 미만이면 총액을 확정하지 않는다. 근거 있는 행의 금액 비중 기준. */
	public static final double MIN_EVIDENCE_RATIO = 0.60;
	/** 한 행을 근거 있다고 인정할 식별 토큰 일치 비율. */
	public static final double ROW_TOKEN_MATCH_FLOOR = 0.5;

	public static final String WARN_NOT_ALL_PRICED = "not-all-priced";
	public static final String WARN_CATEGORY_CONFLICT = "category-conflict";
	public static final String WARN_ZERO_PRICED = "zero-priced-row";
	public static final String WARN_INFERRED = "inferred-row";
	public static final String WARN_NO_EVIDENCE = "no-evidence-in-spec";
	public static final String WARN_NO_BASE = "no-base-system";
	public static final String WARN_LOW_EVIDENCE = "evidence-ratio-below-floor";
	public static final String WARN_NO_SPEC_TEXT = "no-spec-text-to-verify";
	public static final String WARN_PREBUILT = "prebuilt-bundle";
	/**
	 * 사양→모델 탐색기가 막혀 있었다. <b>"규격서에 없는 부품"과 구분해야 한다</b> —
	 * 실측에서 SearXNG 상위 엔진이 연속 16질의 만에 전부 정지하고도 HTTP 200 에
	 * 빈 결과를 돌려줬다. 이걸 못 찾음으로 읽으면 차단 구간 내내 조용히 품질이 떨어진다.
	 */
	public static final String WARN_SEARCH_UNAVAILABLE = "discovery-search-unavailable";

	/** 총액 신뢰 등급. */
	public enum Confidence {
		/** 전 행이 근거를 갖고 값이 다 붙었다. 손익 계산에 쓴다. */
		CONFIRMED,
		/** 일부를 제외하고도 근거 비중이 바닥선을 넘었다. 손익 계산에 쓰되 경고를 붙인다. */
		PARTIAL,
		/** 확정할 수 없다. <b>손익 계산에 쓰지 않는다</b> — 참고값으로만 남긴다. */
		UNTRUSTED
	}

	/**
	 * 판정 결과.
	 *
	 * @param confidence   {@link Confidence}
	 * @param confirmedMid 손익 계산에 쓸 단가. {@code UNTRUSTED} 면 {@code null}
	 * @param indicativeMid AI 가 낸 원래 총액(참고용). 항상 채운다
	 * @param evidenceRatio 채택된 금액 / 전체 금액
	 * @param warnings     붙은 경고들
	 * @param rows         행마다 판정을 덧붙인 breakdown 사본
	 * @param isPrebuilt   완제품 번들로 판정됐는가
	 * @param hasBase      베어본(System)이 잡혔는가
	 */
	public record Verdict(Confidence confidence, BigDecimal confirmedMid, BigDecimal indicativeMid,
			double evidenceRatio, List<String> warnings, List<Map<String, Object>> rows,
			boolean isPrebuilt, boolean hasBase) {

		public Verdict {
			warnings = List.copyOf(warnings);
			rows = List.copyOf(rows);
		}
	}

	/** 색인(Processor/Memory/Storage)과 웹(CPU/RAM/SSD)의 카테고리 이름을 한 축으로 모은다. */
	private static final Map<String, String> CANON = Map.ofEntries(
			Map.entry("processor", "cpu"), Map.entry("cpu", "cpu"),
			Map.entry("gpu", "gpu"), Map.entry("vga", "gpu"), Map.entry("그래픽카드", "gpu"),
			Map.entry("memory", "ram"), Map.entry("ram", "ram"), Map.entry("메모리", "ram"),
			Map.entry("storage", "storage"), Map.entry("ssd", "storage"),
			Map.entry("hdd", "storage"), Map.entry("저장장치", "storage"),
			Map.entry("system", "base"));

	/** 모델을 식별하는 토큰 — 숫자를 포함한 3자 이상 영숫자 덩어리. 순수 숫자 1~2자는 뺀다. */
	private static final Pattern MODEL_TOKEN = Pattern.compile("[a-z0-9]{3,}");
	private static final Pattern PURE_SHORT_NUMBER = Pattern.compile("\\d{1,2}");
	private static final Pattern NON_ALNUM_HANGUL = Pattern.compile("[^a-z0-9가-힣]+");

	private UnitCostValidator() {
	}

	/**
	 * 검증한다.
	 *
	 * @param estimated AI 의 {@code estimatedUnitCost} 봉투. {@code null}·{@code matched=false} 면
	 *                  {@code UNTRUSTED}
	 * @param specText  규격서 원문. 비어 있으면 근거 대조를 할 수 없어 확정하지 않는다
	 */
	@SuppressWarnings("unchecked")
	public static Verdict validate(Map<String, Object> estimated, String specText) {
		if (estimated == null || !Boolean.TRUE.equals(estimated.get("matched"))) {
			return new Verdict(Confidence.UNTRUSTED, null, null, 0, List.of(), List.of(), false, false);
		}
		BigDecimal indicative = toDecimal(estimated.get("mid"));
		boolean isPrebuilt = estimated.get("prebuilt") instanceof Map<?, ?> p
				&& Boolean.TRUE.equals(p.get("isPrebuilt"));
		boolean hasBase = Boolean.TRUE.equals(estimated.get("hasBase"));

		List<Map<String, Object>> raw = estimated.get("breakdown") instanceof List<?> list
				? (List<Map<String, Object>>) (List<?>) list
				: List.of();

		Set<String> warnings = new LinkedHashSet<>();
		if (!Boolean.TRUE.equals(estimated.get("allPriced"))) {
			// AI 가 "값을 못 붙인 행이 있다"고 이미 말하고 있다. 지금까지 아무도 읽지 않았다.
			warnings.add(WARN_NOT_ALL_PRICED);
		}
		if (isPrebuilt) {
			warnings.add(WARN_PREBUILT);
		}
		if (!hasBase) {
			warnings.add(WARN_NO_BASE);
		}

		String specNorm = normalize(specText);
		if (specNorm.isEmpty()) {
			warnings.add(WARN_NO_SPEC_TEXT);
		}
		if (raw.stream().anyMatch(r -> Boolean.TRUE.equals(r.get("searchUnavailable")))) {
			warnings.add(WARN_SEARCH_UNAVAILABLE);
		}

		// 행마다 근거 여부를 먼저 확정한다 — 카테고리 충돌을 근거로 풀기 때문이다.
		Map<Map<String, Object>, Boolean> backed = new java.util.IdentityHashMap<>();
		for (Map<String, Object> row : raw) {
			String quote = str(row.get("evidence"));
			backed.put(row, !specNorm.isEmpty() && (!quote.isBlank()
					? hasQuoteEvidence(quote, specText)
					: hasEvidence(label(row), specNorm)));
		}

		// 카테고리별 모델 수를 센다 — 한 장비에 CPU 가 둘일 수는 없다.
		//
		// 충돌이 나면 <b>근거 있는 쪽이 이긴다.</b> 색인 토큰 매칭과 근거를 가진 부품을 동률로
		// 놓고 둘 다 버리면, 규격서와 일치하는 정답까지 함께 죽는다(실측: 128GB·4TB 가
		// 512GB·1TB 와 함께 탈락). 근거 있는 행이 <b>정확히 하나</b>일 때만 그것을 남기고,
		// 0개거나 2개 이상이면 어느 쪽도 고를 수 없으므로 카테고리를 통째로 뺀다.
		Map<String, List<Map<String, Object>>> byCategory = new TreeMap<>();
		for (Map<String, Object> row : raw) {
			byCategory.computeIfAbsent(canon(row.get("category")), k -> new ArrayList<>()).add(row);
		}
		Set<Map<String, Object>> conflictLosers =
				java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
		Set<Map<String, Object>> resolvedDuplicates =
				java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
		for (Map.Entry<String, List<Map<String, Object>>> e : byCategory.entrySet()) {
			List<Map<String, Object>> group = e.getValue();
			long distinct = group.stream().map(r -> normalize(label(r))).distinct().count();
			if (distinct <= 1) {
				continue;
			}
			List<Map<String, Object>> winners = group.stream().filter(backed::get).toList();
			if (winners.size() == 1) {
				group.stream().filter(r -> r != winners.getFirst()).forEach(r -> {
					conflictLosers.add(r);
					resolvedDuplicates.add(r);   // 누락이 아니라 중복이다 — 근거 비율 분모에서 뺀다
				});
				warnings.add(WARN_CATEGORY_CONFLICT + ":" + e.getKey() + ":resolved");
			}
			else {
				conflictLosers.addAll(group);
				warnings.add(WARN_CATEGORY_CONFLICT + ":" + e.getKey());
			}
		}

		List<Map<String, Object>> rows = new ArrayList<>(raw.size());
		BigDecimal acceptedTotal = BigDecimal.ZERO;
		BigDecimal allTotal = BigDecimal.ZERO;

		for (Map<String, Object> row : raw) {
			Map<String, Object> out = new LinkedHashMap<>(row);
			BigDecimal amount = rowAmount(row);
			// 근거 비율의 분모는 "이 장비를 이루는 돈"이다. 충돌에서 진 중복 행은 같은 부품을
			// 두 번 세는 것이므로 분모에서 뺀다 — 넣으면 정답을 골라 놓고도 비율이 깎인다.
			if (!resolvedDuplicates.contains(row)) {
				allTotal = allTotal.add(amount);
			}

			// AI 가 근거 문장을 실어 보냈으면 그것을 원문과 대조한다 — 이게 계약이 말한
			// `evidence.quote` 판정이다. 없으면 식별 토큰 대조로 내려간다(느슨한 판).
			boolean hasEvidence = Boolean.TRUE.equals(backed.get(row));
			out.put("evidenceInSpec", hasEvidence);
			out.put("evidenceBasis", str(row.get("evidence")).isBlank() ? "token" : "quote");

			String reject = null;
			if (toDecimal(row.get("low")) == null) {
				reject = "unpriced";                       // 값이 없어 애초에 총액에 없다
			}
			else if (amount.signum() == 0) {
				reject = WARN_ZERO_PRICED;
				warnings.add(WARN_ZERO_PRICED);
			}
			else if (conflictLosers.contains(row)) {
				reject = WARN_CATEGORY_CONFLICT;           // 근거로 이기지 못한 쪽이다
			}
			else if (Boolean.TRUE.equals(row.get("inferred"))) {
				reject = WARN_INFERRED;                    // AI 가 "참고값"이라 표시한 행이다
				warnings.add(WARN_INFERRED);
			}
			else if (!hasEvidence) {
				reject = WARN_NO_EVIDENCE;                 // 규격서에 없는 부품이다
				warnings.add(WARN_NO_EVIDENCE);
			}

			out.put("acceptedForCost", reject == null);
			if (reject != null) {
				out.put("rejectReason", reject);
			}
			else {
				acceptedTotal = acceptedTotal.add(amount);
			}
			rows.add(out);
		}

		double ratio = allTotal.signum() == 0 ? 0
				: acceptedTotal.doubleValue() / allTotal.doubleValue();

		Confidence confidence;
		if (specNorm.isEmpty() || acceptedTotal.signum() == 0 || !hasBase) {
			confidence = Confidence.UNTRUSTED;
		}
		else if (ratio < MIN_EVIDENCE_RATIO) {
			warnings.add(WARN_LOW_EVIDENCE);
			confidence = Confidence.UNTRUSTED;
		}
		else if (warnings.isEmpty()) {
			confidence = Confidence.CONFIRMED;
		}
		else {
			confidence = Confidence.PARTIAL;
		}

		BigDecimal confirmed = confidence == Confidence.UNTRUSTED ? null : acceptedTotal;
		return new Verdict(confidence, confirmed, indicative, ratio,
				new ArrayList<>(warnings), rows, isPrebuilt, hasBase);
	}

	/**
	 * 이 부품이 규격서에 근거를 갖는가 — 식별 토큰의 <b>일치 비율</b>로 본다.
	 *
	 * <p>토큰 하나만 겹쳐도 통과시키면 안 된다. {@code "512GB DDR5 PC5 5600"} 은 규격서가
	 * 128GB 를 요구해도 {@code DDR5} 하나로 통과한다 — 실측에서 3,016만원짜리 오류가 이 모양이었다.
	 * 그래서 <b>숫자를 포함한 토큰</b>(용량·세대·모델코드) 중 원문에 있는 비율을 세고
	 * {@value #ROW_TOKEN_MATCH_FLOOR} 이상일 때만 근거로 인정한다.
	 *
	 * <p>{@code evidence.quote} 의 <b>느슨한 판</b>이다 — AI 가 근거 문장을 실어 보내기 시작하면
	 * 그 문장의 원문 포함 여부로 바꾼다({@link #hasQuoteEvidence}).
	 */
	static boolean hasEvidence(String label, String specNormalized) {
		return tokenMatchRatio(label, specNormalized) >= ROW_TOKEN_MATCH_FLOOR;
	}

	/** 라벨의 숫자 포함 토큰 중 규격서 원문에 있는 비율. 셀 토큰이 없으면 0(검증 불가). */
	static double tokenMatchRatio(String label, String specNormalized) {
		// 토큰은 <b>원문 라벨</b>에서 뽑는다. 정규화본에서 뽑으면 공백이 지워져 라벨 전체가
		// 토큰 하나가 되고, 부분 일치가 전부 실패한다.
		Matcher m = MODEL_TOKEN.matcher(label == null ? "" : label.toLowerCase(Locale.ROOT));
		int total = 0;
		int hit = 0;
		while (m.find()) {
			String token = m.group();
			if (PURE_SHORT_NUMBER.matcher(token).matches()
					|| token.chars().noneMatch(Character::isDigit)) {
				continue;
			}
			total++;
			if (specNormalized.contains(token)) {
				hit++;
			}
		}
		return total == 0 ? 0 : (double) hit / total;
	}

	/**
	 * AI 가 실어 보낸 근거 문장이 규격서 원문에 <b>그대로</b> 있는가.
	 *
	 * <p>공백과 구두점만 지우고 비교한다. LLM 이 {@code "RAM DDR5 …"} 를
	 * {@code "RAM: DDR5 …"} 로 옮겨 적는 정도는 통과시키되, <b>요약·바꿔쓰기·지어내기는
	 * 통과하지 못한다</b> — 지어낸 사실에는 지어낸 인용이 딸려오고, 그것은 원문에 없다.
	 */
	public static boolean hasQuoteEvidence(String quote, String specText) {
		if (quote == null || quote.isBlank() || specText == null) {
			return false;
		}
		String q = normalize(quote);
		return q.length() >= 6 && normalize(specText).contains(q);
	}

	private static String str(Object value) {
		return value == null ? "" : String.valueOf(value);
	}

	private static String label(Map<String, Object> row) {
		Object option = row.get("option");
		Object product = row.get("product");
		return String.valueOf(option == null ? "" : option) + ' '
				+ String.valueOf(product == null ? "" : product);
	}

	private static String canon(Object category) {
		String key = String.valueOf(category == null ? "" : category).trim().toLowerCase(Locale.ROOT);
		return CANON.getOrDefault(key, key);
	}

	private static String normalize(String s) {
		return s == null ? "" : NON_ALNUM_HANGUL.matcher(s.toLowerCase(Locale.ROOT)).replaceAll("");
	}

	/** 행 금액 = (low+high)/2 × qty. {@code high} 가 없으면 {@code low} 를 쓴다. */
	private static BigDecimal rowAmount(Map<String, Object> row) {
		BigDecimal low = toDecimal(row.get("low"));
		if (low == null) {
			return BigDecimal.ZERO;
		}
		BigDecimal high = toDecimal(row.get("high"));
		BigDecimal mid = high == null ? low
				: low.add(high).divide(BigDecimal.valueOf(2), 0, java.math.RoundingMode.HALF_UP);
		BigDecimal qty = toDecimal(row.get("qty"));
		return mid.multiply(qty == null || qty.signum() <= 0 ? BigDecimal.ONE : qty);
	}

	private static BigDecimal toDecimal(Object value) {
		if (value instanceof Number n) {
			return new BigDecimal(n.toString());
		}
		if (value instanceof String s && !s.isBlank()) {
			try {
				return new BigDecimal(s.trim());
			}
			catch (NumberFormatException ignored) {
				return null;
			}
		}
		return null;
	}
}
