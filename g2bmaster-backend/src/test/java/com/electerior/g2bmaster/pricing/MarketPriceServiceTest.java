package com.electerior.g2bmaster.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import com.electerior.g2bmaster.pricing.MarketPriceService.Award;
import com.electerior.g2bmaster.pricing.MarketPriceService.MarketSummary;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@code lib/marketprice.js} 하단의 자체 검증 블록 이식.
 *
 * <p>여기 숫자들은 "예산 × 중앙 낙찰률 = 예상 낙찰가"라는 시가 산출 규칙 그 자체다.
 * 규칙이 흔들리면 딜 분석의 예상 수익이 통째로 틀린다.
 */
class MarketPriceServiceTest {

	private static BigDecimal n(String value) {
		return new BigDecimal(value);
	}

	@Test
	void 중앙값은_짝수_표본에서_두_가운데의_평균이다() {
		assertThat(MarketPriceService.median(List.of(n("3"), n("1"), n("2")))).isEqualByComparingTo("2");
		assertThat(MarketPriceService.median(List.of(n("1"), n("2"), n("3"), n("4"))))
				.isEqualByComparingTo("2.5");
		assertThat(MarketPriceService.median(List.of())).isNull();
	}

	@Test
	void 검색어_추출은_연도_괄호_일반어를_걷어낸다() {
		List<String> terms = MarketPriceService.priceTerms("2026년 업무용 노트북 구매(긴급, 제2026-1호)");

		assertThat(terms).contains("업무용", "노트북");
		assertThat(terms).doesNotContain("구매", "2026");
	}

	@Test
	void 느슨한_공유어_판정() {
		assertThat(MarketPriceService.shareTerm("노트북 구매", "업무용 노트북 임대")).isTrue();
		assertThat(MarketPriceService.shareTerm("금속탐지기 구매", "정수기 임대")).isFalse();
	}

	@Test
	void 강한_매칭은_3글자_식별어_하나면_통과하고_일반어_우연일치는_탈락한다() {
		assertThat(MarketPriceService.isStrongMatch("업무용 노트북 구매", "노트북 임대")).isTrue();
		// '2분기'·'정기청구'·'물품'은 모두 STOP 이라 공유어가 하나도 남지 않는다.
		assertThat(MarketPriceService.isStrongMatch("2분기 정기청구 물품", "2분기 시약 구매")).isFalse();
	}

	@Test
	void 매칭된_낙찰만_추린다() {
		List<Award> awards = List.of(
				new Award(null, null, null, "노트북 임대"),
				new Award(null, null, null, "정수기 구매"));

		assertThat(MarketPriceService.matchAwards("노트북 구매", awards)).hasSize(1);
	}

	@Test
	void 표본_요약은_예산_대비_예상낙찰가와_절감액을_낸다() {
		MarketSummary summary = MarketPriceService.summarizeMarket(List.of(
				new Award(9_000_000, 90, "2026-07-22", "노트북"),
				new Award(8_000_000, 80, "2026-07-20", "노트북")), 10_000_000);

		assertThat(summary.sampleCount()).isEqualTo(2);
		assertThat(summary.medianRate()).isEqualByComparingTo("85");          // 낙찰률 중앙값
		assertThat(summary.expectedAward()).isEqualByComparingTo("8500000");  // 1000만 × 85%
		assertThat(summary.expectedSaving()).isEqualByComparingTo("1500000");
		assertThat(summary.expectedSavingPct()).isEqualByComparingTo("15");
		assertThat(summary.latestDate()).isEqualTo("2026-07-22");
	}

	@Test
	void 낙찰률_이상치는_통계에서_빠진다() {
		// 0 이하·130% 초과는 미기재/오류로 본다. 남겨 두면 값 하나가 중앙값을 끌고 다닌다.
		MarketSummary summary = MarketPriceService.summarizeMarket(List.of(
				new Award(1_000, 90, "2026-07-01", "x"),
				new Award(1_000, 0, "2026-07-02", "x"),
				new Award(1_000, 999, "2026-07-03", "x")), null);

		assertThat(summary.sampleCount()).isEqualTo(3);
		assertThat(summary.rateSampleCount()).isEqualTo(1);
		assertThat(summary.medianRate()).isEqualByComparingTo("90");
	}

	@Test
	void 예산이_없으면_예상낙찰가도_없다() {
		MarketSummary summary = MarketPriceService.summarizeMarket(
				List.of(new Award(1_000, 90, "2026-07-01", "x")), null);

		assertThat(summary.budget()).isNull();
		assertThat(summary.expectedAward()).isNull();
		assertThat(summary.expectedSaving()).isNull();
		assertThat(summary.expectedSavingPct()).isEqualByComparingTo("10");
	}
}
