package com.electerior.g2bmaster.pricing;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
 * <h2>바뀐 점 (2026-08): 기종(unit) 축이 생겼고, 검증축을 실제 오차축에 맞췄다</h2>
 *
 * <p>AI 는 이제 <b>기종별</b>로 부품을 보낸다({@code units[]}, 평평한 {@code breakdown[]} 의 각
 * 행에는 {@code unitId}·{@code unitQty} 가 붙는다). 이 층이 그 축을 모르면 A형·B형에 같은 CPU 가
 * 들어갔을 때 한쪽을 <b>중복으로 지운다</b> — 실측에서 2기종 규격서의 단가가 그렇게 25만원
 * 깎였고, 그러고도 {@code evidenceRatio} 는 1.00 이었다(진 행이 분모에서도 빠지므로).
 *
 * <p>그리고 이 층이 보던 축(모델명)과 총액이 실제로 틀리는 축(수량)이 어긋나 있었다.
 * 실측: LLM 이 발주 30대를 부품 수량에 곱해 보내면 단가가 30배가 되는데 판정은
 * <b>CONFIRMED, 경고 0건</b>이었다. 그래서 값 위생(수량 범위·가격 부호)을 근거 대조와
 * <b>같은 층위</b>로 올렸다.
 *
 * <h3>고친 오판 네 가지 (전부 실행으로 재현한 것)</h3>
 * <table border="1">
 *   <caption>기존 동작에서 확인된 결함</caption>
 *   <tr><th>증상</th><th>원인</th><th>고친 방법</th></tr>
 *   <tr><td>규격서가 128GB 인데 {@code 8GB} 행이 근거를 통과</td>
 *       <td>정규화가 공백을 지운 뒤 {@code contains} — {@code 8gb} 가 {@code 128gb} 안에 있다</td>
 *       <td>낱말 경계를 남기고 <b>낱말 단위</b>로 맞춘다({@link #normalize})</td></tr>
 *   <tr><td>규격서에 적힌 "미들타워 케이스"가 근거 없음으로 탈락</td>
 *       <td>식별 토큰이 숫자 낀 낱말뿐이라 분모가 0 → 비율 0</td>
 *       <td>0 과 <b>판정 불가</b>를 가른다({@link #tokenMatchRatio} 가 -1 을 돌려준다)</td></tr>
 *   <tr><td>모델명을 알아낼수록 멀쩡한 부품이 삭제({@code X710-DA2 10GbE})</td>
 *       <td>경합 판정이 <b>모델코드까지</b> 인용문과 맞기를 요구했다</td>
 *       <td>인용 대조는 <b>사양값 토큰</b>만 본다 — 조달 규격서는 모델코드를 적지 않는다</td></tr>
 *   <tr><td>수량 0·음수 단가가 CONFIRMED 로 통과</td>
 *       <td>값 위생 검사가 없었다({@code qty<=0} 은 1 로 치환)</td>
 *       <td>{@link #WARN_QTY_OUT_OF_RANGE}·{@link #WARN_BAD_PRICE} 로 행을 뺀다</td></tr>
 * </table>
 *
 * <h3>중복 판정의 축</h3>
 * <p>중복이란 "같은 물건을 두 번 셌다"는 뜻이고, 그 판정은 <b>같은 기종 안에서</b>
 * 두 행이 규격서의 같은 자리를 가리키는가로 한다. 카테고리는 축이 될 수 없다 — 한 서버에
 * 스토리지가 두 종류(OS용·데이터용) 들어가는 것은 정상이다.
 */
public final class UnitCostValidator {

	/** 이 비율 미만이면 총액을 확정하지 않는다. 근거 있는 행의 금액 비중 기준. */
	public static final double MIN_EVIDENCE_RATIO = 0.60;
	/**
	 * 한 행을 근거 있다고 인정할 식별 토큰 일치 비율.
	 *
	 * <p><b>0.5 는 안 된다.</b> 토큰이 둘인 라벨({@code "512GB DDR5"})에서 흔한 쪽 하나만
	 * 맞아도 정확히 0.5 가 되어 통과한다 — 실측 3,016만원 오류가 그 모양이었다.
	 * 2/3 를 넘겨야 통과하도록 0.66 으로 올렸다: {@code 1/2} 는 떨어지고 {@code 2/3}·
	 * {@code 1/1}(단일 모델코드 라벨)은 통과한다.
	 */
	public static final double ROW_TOKEN_MATCH_FLOOR = 0.66;
	/**
	 * 장비 <b>1대</b>에 같은 부품이 이보다 많이 들어가는 일은 없다.
	 *
	 * <p>넘으면 LLM 이 발주 대수를 부품 수량에 곱한 것이다(실측: 30대 규격서에 {@code qty=30}).
	 * 되돌리는 것은 AI 층의 몫이고({@code estimate.repair_part_qty}), 이 층은 <b>그래도 넘어온
	 * 행을 총액에서 뺀다</b> — 30배 부푼 단가보다 빠진 단가가 낫다.
	 */
	public static final int MAX_PART_QTY = 16;

	public static final String WARN_NOT_ALL_PRICED = "not-all-priced";
	/**
	 * 같은 부품을 두 번 센 행을 뺐다. 예전 이름은 {@code category-conflict} 였다 —
	 * 카테고리가 같다는 것과 같은 물건이라는 것은 다른 말이라 이름부터 고쳤다.
	 */
	public static final String WARN_DUPLICATE_ROW = "duplicate-row";
	public static final String WARN_ZERO_PRICED = "zero-priced-row";
	public static final String WARN_INFERRED = "inferred-row";
	public static final String WARN_NO_EVIDENCE = "no-evidence-in-spec";
	public static final String WARN_NO_BASE = "no-base-system";
	public static final String WARN_LOW_EVIDENCE = "evidence-ratio-below-floor";
	public static final String WARN_NO_SPEC_TEXT = "no-spec-text-to-verify";
	public static final String WARN_PREBUILT = "prebuilt-bundle";
	/** 1대 기준을 벗어난 수량이 넘어왔다 — 발주 대수를 곱한 흔적이다. */
	public static final String WARN_QTY_OUT_OF_RANGE = "part-qty-out-of-range";
	/** 단가가 음수이거나 {@code high < low} 다. 값 소스가 망가졌다는 뜻이라 세지 않는다. */
	public static final String WARN_BAD_PRICE = "bad-price";
	/**
	 * 라벨에 대조할 식별자(용량·모델코드)가 없어 <b>근거를 판정할 수 없었다.</b>
	 *
	 * <p>"근거 없음"과 반드시 구분한다 — "미들타워 케이스"는 규격서에 그대로 적혀 있어도
	 * 숫자가 없어 토큰 대조의 분모가 0 이 된다. 이걸 0 점으로 읽으면 멀쩡한 부품이 사라진다.
	 */
	public static final String WARN_UNVERIFIABLE_LABEL = "label-not-verifiable";
	/**
	 * {@code breakdown} 행이 {@code children} 을 달고 왔다 — 이 층은 <b>평평한 목록</b>을 전제한다.
	 *
	 * <p>중첩이 들어오면 부모 행에는 값이 없어 전부 {@code unpriced} 로 떨어지고, 결과는
	 * <b>경고 하나 없이</b> UNTRUSTED 가 된다(실측: ratio 0.0, warnings 비어 있음). 계약이
	 * 바뀌는 것 자체는 괜찮지만 <b>조용히</b> 바뀌면 안 된다. 이 경고가 그 순간을 드러낸다.
	 */
	public static final String WARN_NESTED_BREAKDOWN = "nested-breakdown-unsupported";
	/**
	 * 사양→모델 탐색기가 막혀 있었다. <b>"규격서에 없는 부품"과 구분해야 한다</b> —
	 * 실측에서 SearXNG 상위 엔진이 연속 16질의 만에 전부 정지하고도 HTTP 200 에
	 * 빈 결과를 돌려줬다. 이걸 못 찾음으로 읽으면 차단 구간 내내 조용히 품질이 떨어진다.
	 */
	public static final String WARN_SEARCH_UNAVAILABLE = "discovery-search-unavailable";

	/**
	 * 등급을 깎지 않는 경고 — <b>숫자가 틀렸다는 뜻이 아닌 것들</b>.
	 *
	 * <p>베어본 유무와 완제품 판정은 "이 값을 어떻게 읽어야 하는가"를 알릴 뿐 계산을 틀리게
	 * 하지 않는다. 예전엔 이것들이 {@code warnings} 에 들어가 있다는 이유만으로 사무용 PC 처럼
	 * ITMAYA 색인 밖 품목이 <b>영원히 CONFIRMED 가 되지 못했다</b>(근거 100% 여도 PARTIAL).
	 */
	private static final Set<String> INFORMATIONAL = Set.of(WARN_PREBUILT, WARN_NO_BASE);

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
	 * @param confirmedMid 손익 계산에 쓸 <b>1대 단가</b>. {@code UNTRUSTED} 면 {@code null}.
	 *                     기종이 여럿이면 단일 단가라는 것이 없으므로 총액을 총 대수로 나눈
	 *                     혼합 단가다
	 * @param indicativeMid AI 가 낸 원래 단가(참고용). 항상 채운다
	 * @param evidenceRatio 채택된 금액 / 전체 금액
	 * @param warnings     붙은 경고들
	 * @param rows         행마다 판정을 덧붙인 breakdown 사본
	 * @param isPrebuilt   완제품 번들로 판정됐는가
	 * @param hasBase      베어본(System)이 잡혔는가
	 * @param confirmedTotal 채택된 금액의 <b>발주 전체 합</b>(= Σ 기종단가 × 대수).
	 *                     {@code UNTRUSTED} 면 {@code null}
	 * @param totalUnits   전체 납품 대수(기종별 {@code unitQty} 의 합). 축이 없으면 1
	 * @param confirmedByUnit 기종별 <b>1대 단가</b>. 화면이 기종마다 값을 보여 줄 수 있어야 한다 —
	 *                     혼합 단가 하나만 주면 어느 기종이 비싼지 알 수 없다
	 */
	public record Verdict(Confidence confidence, BigDecimal confirmedMid, BigDecimal indicativeMid,
			double evidenceRatio, List<String> warnings, List<Map<String, Object>> rows,
			boolean isPrebuilt, boolean hasBase, BigDecimal confirmedTotal, int totalUnits,
			Map<String, BigDecimal> confirmedByUnit) {

		public Verdict {
			warnings = List.copyOf(warnings);
			rows = List.copyOf(rows);
			confirmedByUnit = Map.copyOf(confirmedByUnit);
		}
	}

	/**
	 * 인용문으로 인정할 최소 길이(정규화 후). 이보다 짧으면 규격서 아무 데나 걸려
	 * 근거 구실을 못 한다 — 자리를 잡는 데도 쓰므로 짧은 인용은 자리로도 인정하지 않는다.
	 */
	private static final int MIN_QUOTE_LENGTH = 6;

	/** 모델을 식별하는 토큰 — 숫자를 포함한 3자 이상 영숫자 덩어리. 순수 숫자 1~2자는 뺀다. */
	private static final Pattern MODEL_TOKEN = Pattern.compile("[a-z0-9]{3,}");
	private static final Pattern PURE_SHORT_NUMBER = Pattern.compile("\\d{1,2}");
	private static final Pattern NON_ALNUM_HANGUL = Pattern.compile("[^a-z0-9가-힣]+");
	/**
	 * <b>사양값</b> 토큰 — 숫자 + 단위. 규격서가 실제로 적는 것은 이것이다.
	 *
	 * <p>모델코드({@code X710-DA2}·{@code 9354})와 갈라야 한다. 조달 규격서는 담합 소지 때문에
	 * 모델명을 적지 않으므로, "라벨이 자기 인용문과 맞는가"를 모델코드까지 요구하면
	 * <b>모델을 특정할수록 탈락한다</b> — 실측에서 {@code Intel X710-DA2 10GbE 2port} 가
	 * 사양만 적었을 때는 살고 모델명을 붙이자 삭제됐다.
	 */
	private static final Pattern SPEC_VALUE_TOKEN = Pattern.compile(
			"\\d+(?:\\.\\d+)?(?:gb|tb|mb|kb|gbe|gbps|mbps|mhz|ghz|hz|w|kw|v|a|port|core|way|u|nm|인치)");

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
			return new Verdict(Confidence.UNTRUSTED, null, null, 0, List.of(), List.of(), false, false,
					null, 1, Map.of());
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
		Set<String> specWords = words(specNorm);
		if (specNorm.isEmpty()) {
			warnings.add(WARN_NO_SPEC_TEXT);
		}
		if (raw.stream().anyMatch(r -> Boolean.TRUE.equals(r.get("searchUnavailable")))) {
			warnings.add(WARN_SEARCH_UNAVAILABLE);
		}
		if (raw.stream().anyMatch(r -> r.get("children") instanceof List<?> c && !c.isEmpty())) {
			warnings.add(WARN_NESTED_BREAKDOWN);
		}

		// 행마다 근거 여부를 먼저 확정한다 — 중복 판정이 근거 위에서 이뤄지기 때문이다.
		//
		// 인용문과 토큰 대조는 <b>둘 중 하나만 통과해도 근거로 인정한다.</b> 예전엔 인용문이
		// 실려 오면 그것 하나로만 판정해서, LLM 이 원문을 한 글자 다르게 옮겨 적으면 모델명이
		// 규격서에 그대로 있는 부품까지 "규격서에 없음"으로 떨어졌다. 인용은 근거를 <b>더하는</b>
		// 수단이지 다른 근거를 없애는 수단이 아니다.
		Map<Map<String, Object>, String> basis = new java.util.IdentityHashMap<>();
		for (Map<String, Object> row : raw) {
			boolean byQuote = !specNorm.isEmpty() && hasQuoteEvidence(str(row.get("evidence")), specText);
			double ratio = specNorm.isEmpty() ? 0 : tokenMatchRatio(evidenceLabel(row), specWords);
			// 세 갈래다: 인용/토큰으로 확인됨 · 대조할 식별자가 없어 판정 불가 · 규격서에 없음.
			basis.put(row, byQuote ? "quote"
					: ratio >= ROW_TOKEN_MATCH_FLOOR ? "token"
					: ratio < 0 ? "unverifiable"
					: "none");
		}

		// 같은 부품을 두 번 세지 않는다. 묶는 축은 <b>기종 → 근거</b>다.
		Set<Map<String, Object>> duplicateLosers =
				java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
		// 진 행을 화면에서 이긴 행 옆에 세우려면 <b>짝을 알 수 있어야 한다.</b> 사유만 붙이면
		// "무엇의 중복인지" 알 수 없어 사람이 비교하고 고를 수가 없다. 묶음 번호를 함께 실어
		// 준다 — 같은 번호를 가진 행끼리가 한 자리를 두고 겨룬 후보들이다.
		Map<Map<String, Object>, Integer> duplicateGroupId = new java.util.IdentityHashMap<>();
		int groupId = 0;
		for (List<Map<String, Object>> group : duplicateGroups(raw, specNorm, specWords)) {
			Map<String, Object> winner = pickWinner(group, basis, specWords);
			groupId++;
			for (Map<String, Object> row : group) {
				duplicateGroupId.put(row, groupId);
				if (row != winner) {
					duplicateLosers.add(row);
				}
			}
			warnings.add(WARN_DUPLICATE_ROW);
		}

		List<Map<String, Object>> rows = new ArrayList<>(raw.size());
		// 기종별로 따로 센다 — 축이 없으면(예전 봉투) 전부 한 기종으로 떨어져 예전과 같아진다.
		Map<String, BigDecimal> acceptedByUnit = new LinkedHashMap<>();
		Map<String, Integer> qtyByUnit = new LinkedHashMap<>();
		BigDecimal acceptedTotal = BigDecimal.ZERO;
		BigDecimal allTotal = BigDecimal.ZERO;

		for (Map<String, Object> row : raw) {
			Map<String, Object> out = new LinkedHashMap<>(row);
			BigDecimal amount = rowAmount(row);
			String unitId = unitId(row);
			qtyByUnit.putIfAbsent(unitId, unitQty(row));

			String rowBasis = basis.get(row);
			out.put("evidenceInSpec", "quote".equals(rowBasis) || "token".equals(rowBasis));
			// 무엇으로 인정됐는지 — 인용이 실려 왔는지가 아니라 실제로 통과한 쪽을 적는다.
			out.put("evidenceBasis", rowBasis);
			Integer group = duplicateGroupId.get(row);
			if (group != null) {
				out.put("duplicateGroup", group);
			}

			// ── 판정 ────────────────────────────────────────────────────────
			// 값을 셀 수 없는 행(unpriced·수량 이상·가격 이상)은 <b>분모에서도 뺀다</b> —
			// 금액을 모르는 것이지 근거가 없는 것이 아니다. 반대로 "규격서에 없는 부품"은
			// 금액을 알기 때문에 분모에 남긴다. 그래야 비율이 "얼마나 근거 있나"를 뜻한다.
			String reject = null;
			boolean countable = true;
			if (toDecimal(row.get("low")) == null) {
				reject = "unpriced";                       // 값이 없어 애초에 총액에 없다
				countable = false;
			}
			else if (badPrice(row)) {
				reject = WARN_BAD_PRICE;
				warnings.add(WARN_BAD_PRICE);
				countable = false;
			}
			else if (badQty(row)) {
				reject = WARN_QTY_OUT_OF_RANGE;
				warnings.add(WARN_QTY_OUT_OF_RANGE);
				countable = false;
			}
			else if (amount.signum() == 0) {
				reject = WARN_ZERO_PRICED;
				warnings.add(WARN_ZERO_PRICED);
			}
			else if (duplicateLosers.contains(row)) {
				reject = WARN_DUPLICATE_ROW;               // 같은 자리를 두고 경합해 진 쪽이다
				countable = false;                         // 같은 부품을 두 번 세는 것이므로 분모에서도 뺀다
			}
			else if (Boolean.TRUE.equals(row.get("inferred"))) {
				reject = WARN_INFERRED;                    // AI 가 "참고값"이라 표시한 행이다
				warnings.add(WARN_INFERRED);
			}
			else if ("none".equals(rowBasis)) {
				reject = WARN_NO_EVIDENCE;                 // 규격서에 없는 부품이다
				warnings.add(WARN_NO_EVIDENCE);
			}
			else if ("unverifiable".equals(rowBasis)) {
				// 대조할 식별자가 없다. <b>빼지 않고 알린다</b> — 여기서 빼면 케이스·쿨러처럼
				// 숫자 없는 부품이 규격서에 적혀 있어도 통째로 사라진다.
				warnings.add(WARN_UNVERIFIABLE_LABEL);
			}

			if (countable) {
				allTotal = allTotal.add(amount);
			}
			out.put("acceptedForCost", reject == null);
			if (reject != null) {
				out.put("rejectReason", reject);
			}
			else {
				acceptedTotal = acceptedTotal.add(amount);
				acceptedByUnit.merge(unitId, amount, BigDecimal::add);
			}
			rows.add(out);
		}

		double ratio = allTotal.signum() == 0 ? 0
				: acceptedTotal.doubleValue() / allTotal.doubleValue();

		// 베어본 유무는 <b>등급을 가르지 않는다.</b> `hasBase` 는 ITMAYA GPU서버 색인이 System
		// 행을 잡았을 때만 켜지는데, 그 카탈로그 밖 품목(사무용 PC·네트워크 장비 등)은 웹 경로로
		// 오면서 구조적으로 base 행을 못 만든다. 이걸 확정 조건에 걸어 두면 근거가 100% 여도
		// 영원히 미확정이 된다. 부품 합만으로 장비 단가를 삼는 위험은 경고로 알린다.
		Set<String> grading = new LinkedHashSet<>(warnings);
		grading.removeAll(INFORMATIONAL);

		Confidence confidence;
		if (specNorm.isEmpty() || acceptedTotal.signum() == 0) {
			confidence = Confidence.UNTRUSTED;
		}
		else if (ratio < MIN_EVIDENCE_RATIO) {
			warnings.add(WARN_LOW_EVIDENCE);
			confidence = Confidence.UNTRUSTED;
		}
		else if (grading.isEmpty()) {
			confidence = Confidence.CONFIRMED;
		}
		else {
			confidence = Confidence.PARTIAL;
		}

		// 총액 = Σ(기종 단가 × 그 기종 대수). 단가(confirmedMid)는 <b>1대</b> 값이어야 한다 —
		// DealCalculator 가 `원가 = 단가 × 수량` 으로 쓰기 때문에 여기에 총액을 넣으면 두 번 곱해진다.
		BigDecimal total = BigDecimal.ZERO;
		int totalUnits = 0;
		for (Map.Entry<String, BigDecimal> entry : acceptedByUnit.entrySet()) {
			int qty = qtyByUnit.getOrDefault(entry.getKey(), 1);
			total = total.add(entry.getValue().multiply(BigDecimal.valueOf(qty)));
			totalUnits += qty;
		}
		if (totalUnits <= 0) {
			totalUnits = 1;
			total = acceptedTotal;
		}
		BigDecimal blended = total.divide(BigDecimal.valueOf(totalUnits), 0, java.math.RoundingMode.HALF_UP);

		boolean untrusted = confidence == Confidence.UNTRUSTED;
		return new Verdict(confidence, untrusted ? null : blended, indicative, ratio,
				new ArrayList<>(warnings), rows, isPrebuilt, hasBase,
				untrusted ? null : total, totalUnits, untrusted ? Map.of() : acceptedByUnit);
	}

	/** 이 행이 어느 기종의 것인가. 축이 없는 예전 봉투는 전부 한 기종으로 본다. */
	private static String unitId(Map<String, Object> row) {
		String id = str(row.get("unitId")).trim();
		return id.isEmpty() ? "-" : id;
	}

	/** 이 행이 속한 기종을 몇 대 납품하는가. 없으면 1(= 단가와 총액이 같아진다). */
	private static int unitQty(Map<String, Object> row) {
		BigDecimal qty = toDecimal(row.get("unitQty"));
		return qty == null || qty.signum() <= 0 ? 1 : qty.intValue();
	}

	/** 값 소스가 망가진 행 — 음수 단가이거나 {@code high < low}. */
	private static boolean badPrice(Map<String, Object> row) {
		BigDecimal low = toDecimal(row.get("low"));
		if (low == null) {
			return false;
		}
		BigDecimal high = toDecimal(row.get("high"));
		return low.signum() < 0 || (high != null && high.compareTo(low) < 0);
	}

	/** 1대 기준을 벗어난 수량 — 0·음수이거나 {@value #MAX_PART_QTY} 초과. */
	private static boolean badQty(Map<String, Object> row) {
		BigDecimal qty = toDecimal(row.get("qty"));
		if (qty == null) {
			return false;   // 수량 미기재는 1 로 본다(rowAmount 와 같은 해석)
		}
		return qty.signum() <= 0 || qty.compareTo(BigDecimal.valueOf(MAX_PART_QTY)) > 0;
	}

	/**
	 * 같은 부품을 가리키는 행들을 묶는다. <b>2개 이상인 묶음만</b> 돌려준다.
	 *
	 * <p>묶음은 <b>기종 안에서만</b> 만든다. A형과 B형에 같은 CPU 가 들어가는 것은 중복이
	 * 아니라 정상이다 — 예전에는 이 축이 없어 한쪽이 삭제됐다.
	 *
	 * <p>기종 안에서 1차 축은 <b>인용문이 원문에서 차지하는 구간</b>이다. 구간이 겹치면
	 * 규격서의 같은 자리를 두고 경합하는 읽기다. 구간이 다르면 서로 다른 부품이므로 몇 개든
	 * 함께 산다 — "NVMe 960GB x2(OS용)" 과 "NVMe 7.68TB x4(데이터용)" 이 여기서 살아남는다.
	 *
	 * <p>인용이 없어 자리를 못 잡는 행은 라벨의 모델 토큰 서명으로 묶는다. 색인과 웹이 같은
	 * 부품을 각각 실어 보내면 서명이 같아져 하나로 접힌다.
	 */
	private static List<List<Map<String, Object>>> duplicateGroups(List<Map<String, Object>> raw,
			String specNorm, Set<String> specWords) {
		Map<String, List<Map<String, Object>>> byUnit = new LinkedHashMap<>();
		for (Map<String, Object> row : raw) {
			byUnit.computeIfAbsent(unitId(row), k -> new ArrayList<>()).add(row);
		}
		List<List<Map<String, Object>>> groups = new ArrayList<>();
		for (List<Map<String, Object>> unitRows : byUnit.values()) {
			groups.addAll(duplicateGroupsInUnit(unitRows, specNorm, specWords));
		}
		return groups;
	}

	private static List<List<Map<String, Object>>> duplicateGroupsInUnit(List<Map<String, Object>> raw,
			String specNorm, Set<String> specWords) {
		record Span(int start, int end, Map<String, Object> row) {}

		List<Span> anchored = new ArrayList<>();
		for (Map<String, Object> row : raw) {
			String quote = normalize(str(row.get("evidence")));
			int at = quote.length() >= MIN_QUOTE_LENGTH && !specNorm.isEmpty()
					? specNorm.indexOf(quote) : -1;
			if (at >= 0) {
				anchored.add(new Span(at, at + quote.length(), row));
			}
		}

		List<List<Map<String, Object>>> groups = new ArrayList<>();
		Set<Map<String, Object>> grouped =
				java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

		// 구간이 겹치는 것끼리 훑어 모은다(시작 위치 정렬 후 한 번 통과).
		anchored.sort(java.util.Comparator.comparingInt(Span::start));
		List<List<Map<String, Object>>> clusters = new ArrayList<>();
		int clusterEnd = -1;
		for (Span span : anchored) {
			if (!clusters.isEmpty() && span.start() < clusterEnd) {
				clusters.getLast().add(span.row());
				clusterEnd = Math.max(clusterEnd, span.end());
				continue;
			}
			clusters.add(new ArrayList<>(List.of(span.row())));
			clusterEnd = span.end();
		}
		for (List<Map<String, Object>> cluster : clusters) {
			if (cluster.size() > 1 && isCompeting(cluster)) {
				groups.add(cluster);
				grouped.addAll(cluster);
			}
		}

		// 자리로 묶이지 않은 행은 모델 토큰 서명으로. 서명이 비면(숫자 낀 토큰이 없으면) 묶지 않는다 —
		// "케이스"·"쿨러" 처럼 식별자가 없는 행까지 하나로 접으면 멀쩡한 부품이 사라진다.
		Map<String, List<Map<String, Object>>> bySignature = new LinkedHashMap<>();
		for (Map<String, Object> row : raw) {
			if (grouped.contains(row)) {
				continue;
			}
			String signature = modelSignature(evidenceLabel(row));
			if (!signature.isEmpty()) {
				bySignature.computeIfAbsent(signature, k -> new ArrayList<>()).add(row);
			}
		}
		for (List<Map<String, Object>> group : bySignature.values()) {
			if (group.size() > 1) {
				groups.add(group);
			}
		}
		return groups;
	}

	/**
	 * 같은 자리를 인용한 행들이 <b>경합</b>인가, 아니면 한 줄에 나란히 적힌 <b>서로 다른 부품</b>인가.
	 *
	 * <p>규격서는 한 줄에 부품을 둘 이상 적는다 — {@code "네트워크: 10GbE 2port, 1GbE 4port"}.
	 * 자리가 같다는 이유만으로 하나를 버리면 멀쩡한 부품이 사라진다. 가르는 기준은
	 * <b>각 행의 사양값이 자기가 인용한 줄과 맞아떨어지는가</b>이다:
	 *
	 * <ul>
	 *   <li>모델 서명이 겹치면 → 같은 부품을 두 번 적은 것이다. 하나만 남긴다.
	 *   <li>하나라도 <b>사양값</b>이 자기 인용문과 어긋나면 → 그 줄을 두고 경합하는 읽기다
	 *       ("128GB" 줄을 인용해 놓고 "512GB" 를 사겠다는 행). 하나만 남긴다.
	 *   <li>전부 맞으면 → 한 줄에 적힌 별개 부품이다. 그대로 둔다.
	 * </ul>
	 *
	 * <p><b>모델코드는 보지 않는다.</b> 예전엔 라벨의 모든 식별 토큰이 인용문에 있기를
	 * 요구해서, {@code "Intel X710-DA2 10GbE 2port"} 처럼 모델을 특정한 행이
	 * {@code x710}·{@code da2} 때문에 0.5 로 떨어져 삭제됐다 — 조달 규격서는 모델코드를
	 * 적지 않으므로 그 요구는 애초에 만족될 수 없다.
	 */
	private static boolean isCompeting(List<Map<String, Object>> cluster) {
		Set<String> signatures = new java.util.HashSet<>();
		for (Map<String, Object> row : cluster) {
			if (!signatures.add(modelSignature(evidenceLabel(row)))) {
				return true;   // 같은 모델이 두 번
			}
			Set<String> quoteWords = words(normalize(str(row.get("evidence"))));
			if (specValueMatchRatio(evidenceLabel(row), quoteWords) == 0) {
				return true;   // 사양값이 자기가 인용한 줄과 어긋난다
			}
		}
		return false;
	}

	/**
	 * 경합에서 남길 한 행 — <b>근거 있는 쪽 → 규격서와 더 많이 겹치는 쪽 → 싼 쪽</b> 순.
	 *
	 * <p>마지막 동률을 싼 쪽으로 깨는 것은 의도적이다. 같은 자리를 두고 고를 수 없을 때
	 * 비싼 쪽을 남기면 검증기가 총액을 부풀리는 방향으로 실수한다.
	 */
	private static Map<String, Object> pickWinner(List<Map<String, Object>> group,
			Map<Map<String, Object>, String> basis, Set<String> specWords) {
		return group.stream()
				.max(java.util.Comparator
						.<Map<String, Object>, Boolean>comparing(r -> {
							String b = basis.get(r);
							return "quote".equals(b) || "token".equals(b);
						})
						.thenComparingDouble(r -> Math.max(0, tokenMatchRatio(evidenceLabel(r), specWords)))
						.thenComparing(UnitCostValidator::rowAmount, java.util.Comparator.reverseOrder()))
				.orElse(group.getFirst());
	}

	/** 라벨에서 숫자 낀 토큰만 뽑아 정렬한 서명. 같은 부품이면 표기 순서가 달라도 같아진다. */
	static String modelSignature(String label) {
		Matcher m = MODEL_TOKEN.matcher(label == null ? "" : label.toLowerCase(Locale.ROOT));
		Set<String> tokens = new java.util.TreeSet<>();
		while (m.find()) {
			String token = m.group();
			if (!PURE_SHORT_NUMBER.matcher(token).matches()
					&& token.chars().anyMatch(Character::isDigit)) {
				tokens.add(token);
			}
		}
		return String.join(" ", tokens);
	}

	/**
	 * 이 부품이 규격서에 근거를 갖는가 — 식별 토큰의 <b>일치 비율</b>로 본다.
	 *
	 * <p>{@code evidence.quote} 의 <b>느슨한 판</b>이다. 둘은 배타적이지 않다 —
	 * 어느 한쪽만 통과해도 근거로 인정한다({@link #validate}).
	 */
	static boolean hasEvidence(String label, String specText) {
		return tokenMatchRatio(label, words(normalize(specText))) >= ROW_TOKEN_MATCH_FLOOR;
	}

	/**
	 * 라벨의 식별 토큰 중 규격서에 있는 비율. <b>셀 토큰이 없으면 -1(판정 불가)</b>.
	 *
	 * <p>-1 은 0 과 다르다. 0 은 "규격서에 없다"이고 -1 은 "이 이름으로는 확인할 수 없다"이다.
	 * 예전엔 둘 다 0 이라 규격서에 그대로 적힌 "미들타워 케이스"가 근거 없음으로 탈락했다.
	 *
	 * <p>대조는 <b>낱말 단위</b>다. 예전엔 공백을 지운 문자열에 {@code contains} 를 걸어서,
	 * 규격서가 128GB 를 요구해도 라벨의 {@code 8gb} 가 {@code 128gb} 안에 들어 있다는 이유로
	 * 통과했다(실행으로 재현). 단위 접미만 다른 것({@code 6400} ↔ {@code 6400mhz})은 붙여 준다.
	 */
	static double tokenMatchRatio(String label, Set<String> specWords) {
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
			if (matchesWord(token, specWords)) {
				hit++;
			}
		}
		return total == 0 ? -1 : (double) hit / total;
	}

	/** 라벨의 <b>사양값</b> 토큰만 대조한 비율. 셀 것이 없으면 1(어긋날 수 없다). */
	private static double specValueMatchRatio(String label, Set<String> words) {
		Matcher m = SPEC_VALUE_TOKEN.matcher(label == null ? "" : label.toLowerCase(Locale.ROOT));
		int total = 0;
		int hit = 0;
		while (m.find()) {
			total++;
			if (matchesWord(m.group(), words)) {
				hit++;
			}
		}
		return total == 0 ? 1 : (double) hit / total;
	}

	/**
	 * 토큰이 낱말 집합에 있는가. 단위 접미만 붙은 낱말({@code 6400} ↔ {@code 6400mhz})까지
	 * 인정하되, <b>숫자가 앞에 더 붙은 것</b>({@code 8gb} ↔ {@code 128gb})은 인정하지 않는다.
	 */
	private static boolean matchesWord(String token, Set<String> words) {
		if (words.contains(token)) {
			return true;
		}
		for (String word : words) {
			if (word.length() > token.length() && word.startsWith(token)
					&& word.substring(token.length()).chars().allMatch(Character::isLetter)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * AI 가 실어 보낸 근거 문장이 규격서 원문에 <b>그대로</b> 있는가.
	 *
	 * <p>구두점만 공백으로 바꿔 비교한다. LLM 이 {@code "RAM DDR5 …"} 를
	 * {@code "RAM: DDR5 …"} 로 옮겨 적는 정도는 통과시키되, <b>요약·바꿔쓰기·지어내기는
	 * 통과하지 못한다</b> — 지어낸 사실에는 지어낸 인용이 딸려오고, 그것은 원문에 없다.
	 */
	public static boolean hasQuoteEvidence(String quote, String specText) {
		if (quote == null || quote.isBlank() || specText == null) {
			return false;
		}
		String q = normalize(quote);
		if (q.length() < MIN_QUOTE_LENGTH) {
			return false;
		}
		// 낱말 경계를 지켜 찾는다 — 공백을 지우고 비교하면 낱말 중간에 걸린다.
		return (" " + normalize(specText) + " ").contains(" " + q + " ");
	}

	private static String str(Object value) {
		return value == null ? "" : String.valueOf(value);
	}

	/**
	 * 근거 대조에 쓸 라벨 — <b>{@code option}(규격서가 요구한 것)만</b> 쓴다.
	 *
	 * <p>{@code product} 는 쇼핑몰이 파는 상품명이라 규격서에 있을 이유가 없다. 예전엔 둘을
	 * 이어 붙여 대조해서, 다나와 제목의 토큰(브랜드·패키지 표기)이 분모만 키우고 정상 부품의
	 * 일치 비율을 끌어내렸다. 규격서와 대조할 것은 <b>무엇을 사려 했는가</b>이지
	 * 무엇이 검색됐는가가 아니다.
	 */
	private static String evidenceLabel(Map<String, Object> row) {
		String option = str(row.get("option")).trim();
		return option.isEmpty() ? str(row.get("product")).trim() : option;
	}

	/**
	 * 소문자 + 영숫자·한글만 남기되 <b>낱말 사이는 공백 하나로 남긴다.</b>
	 *
	 * <p>공백까지 지우면 {@code "128GB"} 안에서 {@code "8gb"} 가 걸린다 — 규격서가 요구한 것보다
	 * 작은 부품이 근거를 통과하는 실측 오판이 정확히 이 모양이었다.
	 */
	static String normalize(String s) {
		return s == null ? "" : NON_ALNUM_HANGUL.matcher(s.toLowerCase(Locale.ROOT)).replaceAll(" ").trim();
	}

	private static Set<String> words(String normalized) {
		Set<String> out = new java.util.HashSet<>();
		for (String word : normalized.split(" ")) {
			if (!word.isEmpty()) {
				out.add(word);
			}
		}
		return out;
	}

	/**
	 * 행 금액 = 대표 단가 × qty.
	 *
	 * <p>대표 단가는 소스가 실어 보낸 {@code mid}(후보들의 <b>중앙값</b>)를 먼저 쓰고, 없으면
	 * {@code (low+high)/2} 로 물러난다. {@code high} 는 "같은 부품의 비싼 값"이 아니라
	 * <b>검색어에 걸린 아무 물건의 최고가</b>다 — 실측에서 {@code "i5-14400"} 이
	 * 223,000~1,853,730원으로 잡혔고(뒤쪽은 그 CPU 가 들어간 <i>완성 PC</i>), 중간값
	 * 103만원이 CPU 한 개의 단가로 총액에 들어갔다. 중앙값은 그 꼬리에 끌려가지 않는다.
	 */
	private static BigDecimal rowAmount(Map<String, Object> row) {
		BigDecimal low = toDecimal(row.get("low"));
		if (low == null) {
			return BigDecimal.ZERO;
		}
		BigDecimal representative = toDecimal(row.get("mid"));
		if (representative == null || representative.signum() <= 0) {
			BigDecimal high = toDecimal(row.get("high"));
			representative = high == null ? low
					: low.add(high).divide(BigDecimal.valueOf(2), 0, java.math.RoundingMode.HALF_UP);
		}
		BigDecimal qty = toDecimal(row.get("qty"));
		return representative.multiply(qty == null || qty.signum() <= 0 ? BigDecimal.ONE : qty);
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
