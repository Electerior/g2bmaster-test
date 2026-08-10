package com.electerior.g2bmaster.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import com.electerior.g2bmaster.pricing.DealCalculator.Deal;
import com.electerior.g2bmaster.pricing.DealCalculator.DealInput;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * {@code lib/deal.js} 하단의 자체 검증 블록을 그대로 옮긴 것.
 *
 * <p>원본은 {@code node lib/deal.js} 로 돌리는 assert 묶음이라, 이식하면서 실행되지 않는
 * 코드가 될 뻔했다. 숫자가 하나라도 어긋나면 화면의 수익 표시가 통째로 틀어지므로
 * 여기서 계속 지킨다.
 */
class DealCalculatorTest {

	@Test
	void 원가를_알면_실수익까지_산출한다() {
		// 예산 1000만, 예상낙찰 850만, 단가 3000 × 수량 2000 = 원가 600만
		Deal deal = DealCalculator.computeDeal(new DealInput(
				10_000_000, 8_500_000, 85, 3000, 2000, 9_000_000));

		assertThat(deal.cost()).isEqualByComparingTo("6000000");
		assertThat(deal.hasCost()).isTrue();
		assertThat(deal.profitAtBudget()).isEqualByComparingTo("4000000");     // 1000만 − 600만
		assertThat(deal.profitAtExpected()).isEqualByComparingTo("2500000");   // 850만 − 600만
		assertThat(deal.profitAtBid()).isEqualByComparingTo("3000000");        // 900만 − 600만
		assertThat(deal.breakevenBid()).isEqualByComparingTo("6000000");       // 손익분기 = 원가
		assertThat(deal.marginPctAtExpected()).isEqualByComparingTo("29.4");   // 250만 / 850만
		assertThat(deal.bidRate()).isEqualByComparingTo("90");                 // 900만 / 1000만
	}

	@Test
	void 단가가_없으면_원가와_수익은_null_이고_시세만_남는다() {
		Deal deal = DealCalculator.computeDeal(new DealInput(
				10_000_000, 8_500_000, 85, null, null, 8_000_000));

		assertThat(deal.hasCost()).isFalse();
		assertThat(deal.cost()).isNull();
		assertThat(deal.profitAtExpected()).isNull();
		assertThat(deal.profitAtBudget()).isNull();
		assertThat(deal.bidRate()).isEqualByComparingTo("80");
	}

	@Test
	void 금액_문자열도_그대로_받는다() {
		// 나라장터 금액은 '1,234,000원' 같은 문자열로 온다. 호출부가 미리 파싱하지 않아도 된다.
		Deal deal = DealCalculator.computeDeal(new DealInput(
				"10,000,000원", "8,500,000", "85%", "3,000", "2000", null));

		assertThat(deal.budget()).isEqualByComparingTo("10000000");
		assertThat(deal.expectedRate()).isEqualByComparingTo("85");
		assertThat(deal.cost()).isEqualByComparingTo("6000000");
		assertThat(deal.profitAtBid()).isNull();   // 투찰가 미입력
	}

	@Test
	void 예산이_0이면_투찰률은_null_이다() {
		// 0으로 나눈 값을 화면에 띄우느니 '산출 불가'가 낫다.
		Deal deal = DealCalculator.computeDeal(new DealInput(0, null, null, null, null, 5_000_000));
		assertThat(deal.bidRate()).isNull();
	}

	@Test
	void 미입력은_0이_아니라_null_이다() {
		Deal deal = DealCalculator.computeDeal(new DealInput(null, null, null, null, null, null));

		assertThat(deal.budget()).isNull();
		assertThat(deal.expectedAward()).isNull();
		assertThat(deal.cost()).isNull();
		assertThat(deal.hasCost()).isFalse();
	}

	@Test
	void 큰_금액도_원_단위로_정확하다() {
		// double 로 계산하면 마지막 자리가 흔들리는 구간(수천억 원).
		Deal deal = DealCalculator.computeDeal(new DealInput(
				"999999999999", null, null, "1", "999999999998", null));

		assertThat(deal.profitAtBudget()).isEqualByComparingTo(new BigDecimal("1"));
	}
}
