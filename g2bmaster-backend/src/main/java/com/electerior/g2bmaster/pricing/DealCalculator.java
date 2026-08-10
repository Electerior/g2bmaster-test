package com.electerior.g2bmaster.pricing;

import com.electerior.g2bmaster.common.Numbers;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 수주 딜 분석 — 수익·개찰 시뮬레이션 순수 계산 ({@code lib/deal.js} 이식).
 *
 * <p>원가(단가 × 수량)를 알면 실수익까지, 모르면 시세(예상 낙찰가)·투찰 경쟁력만 산출한다.
 * 단가 소스(종합쇼핑몰 계약단가)가 열리면 {@code unitCost} 만 채우면 되고 인터페이스는 동일하다.
 *
 * <p><b>{@code double} 이 아니라 {@link BigDecimal} 을 쓰는 이유</b>: 조달 금액은 수천억 원
 * (10^11) 단위까지 가는데, 손익분기 투찰가는 원 단위로 비교된다. double 의 유효자릿수로는
 * 마지막 자리가 흔들려 "손익분기보다 1원 높은 투찰"이 뒤집힌다.
 *
 * <p>값이 없는 것은 0이 아니라 {@code null} 이다 — "예산 미공개"와 "예산 0원"은 다른 사실이고,
 * 0으로 접으면 마진율이 -100%로 계산돼 화면이 거짓말을 한다.
 */
public final class DealCalculator {

	private DealCalculator() {
	}

	/**
	 * 딜 분석 입력.
	 *
	 * @param budget        공고 예산(추정가격) — '받을 돈'의 상한
	 * @param expectedAward 예상 낙찰가(시세) = 예산 × 카테고리 중앙 낙찰률
	 * @param expectedRate  중앙 낙찰률(%)
	 * @param unitCost      단가. 없으면 원가 미산출
	 * @param quantity      수량(규격서/API)
	 * @param bidPrice      개찰 시뮬용 투찰가
	 */
	public record DealInput(
			Object budget,
			Object expectedAward,
			Object expectedRate,
			Object unitCost,
			Object quantity,
			Object bidPrice) {}

	/** 딜 분석 결과. 필드명은 프론트 계약이므로 그대로 유지한다. */
	public record Deal(
			BigDecimal budget,
			BigDecimal expectedAward,
			BigDecimal expectedRate,
			BigDecimal unitCost,
			BigDecimal quantity,
			BigDecimal cost,
			boolean hasCost,
			BigDecimal profitAtBudget,
			BigDecimal profitAtExpected,
			BigDecimal profitAtBid,
			BigDecimal marginPctAtExpected,
			BigDecimal breakevenBid,
			BigDecimal bidRate) {}

	public static Deal computeDeal(DealInput input) {
		DealInput in = input == null
				? new DealInput(null, null, null, null, null, null)
				: input;

		BigDecimal budget = Numbers.toNumber(in.budget());
		BigDecimal expected = Numbers.toNumber(in.expectedAward());
		BigDecimal unitCost = Numbers.toNumber(in.unitCost());
		BigDecimal quantity = Numbers.toNumber(in.quantity());
		BigDecimal bidPrice = Numbers.toNumber(in.bidPrice());

		// 원가 = 단가 × 수량. 둘 중 하나라도 없으면 원가는 '모름'이다.
		BigDecimal cost = (unitCost != null && quantity != null) ? unitCost.multiply(quantity) : null;

		return new Deal(
				budget,
				expected,
				Numbers.toNumber(in.expectedRate()),
				unitCost,
				quantity,
				cost,
				cost != null,
				minus(budget, cost),          // 예산 전액 수주 시
				minus(expected, cost),        // 시세로 낙찰 시
				minus(bidPrice, cost),        // 이 투찰가로 낙찰 시
				percent(minus(expected, cost), expected),
				cost,                         // 손익분기 투찰가 = 원가
				percent(bidPrice, budget));   // 투찰률 = 투찰가 / 예산
	}

	// ── 내부 ────────────────────────────────────────────────────────────────

	private static BigDecimal minus(BigDecimal a, BigDecimal b) {
		return (a != null && b != null) ? a.subtract(b) : null;
	}

	/**
	 * 백분율(소수 첫째 자리).
	 *
	 * <p>분모가 없거나 <b>0이면 null</b> — 원본 JS 의 {@code den} 진리값 판정과 같다.
	 * 0으로 나눈 결과를 화면에 띄우느니 "산출 불가"를 띄우는 쪽이 맞다.
	 */
	private static BigDecimal percent(BigDecimal numerator, BigDecimal denominator) {
		if (numerator == null || denominator == null || denominator.signum() == 0) {
			return null;
		}
		return numerator.divide(denominator, 10, RoundingMode.HALF_UP)
				.multiply(BigDecimal.valueOf(1000))
				.setScale(0, RoundingMode.HALF_UP)
				.divide(BigDecimal.TEN, 1, RoundingMode.HALF_UP);
	}
}
