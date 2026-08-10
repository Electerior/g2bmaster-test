package com.electerior.g2bmaster.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** {@code lib/num.js} 의 self-check 를 그대로 옮긴 것 + 자바 특유의 함정 몇 개. */
class NumbersTest {

	@Test
	void 원본_self_check() {
		assertThat(Numbers.toNumber("1,234,000원")).isEqualByComparingTo("1234000");
		assertThat(Numbers.toNumber("")).isNull();          // 미입력 → null(0 아님)
		assertThat(Numbers.toNumber(null)).isNull();
		assertThat(Numbers.toNumber("-")).isNull();
		assertThat(Numbers.toNumber("90.5%")).isEqualByComparingTo("90.5");
		assertThat(Numbers.toNumber(0)).isEqualByComparingTo("0");   // 실제 0은 0 유지
	}

	@Test
	void 미입력과_0은_반드시_다르다() {
		// 이 구분이 무너지면 '추정가격 미공개' 공고가 '0원 공고'가 되어 평균·최저가가 통째로 틀어진다.
		assertThat(Numbers.toNumber("")).isNull();
		assertThat(Numbers.toNumber("   ")).isNull();
		assertThat(Numbers.toNumber("원")).isNull();
		assertThat(Numbers.toNumber("0")).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(Numbers.toNumber("0원")).isEqualByComparingTo(BigDecimal.ZERO);
	}

	@Test
	void 수천억원대_금액이_double_반올림에_흔들리지_않는다() {
		// 조달 금액은 10^11 대까지 가고 원 단위 비교(낙찰률)가 의미를 갖는다.
		assertThat(Numbers.toNumber("123,456,789,012,345원"))
				.isEqualByComparingTo(new BigDecimal("123456789012345"));
		assertThat(Numbers.toNumber("9,007,199,254,740,993"))   // 2^53+1 — double 로는 표현 불가
				.isEqualByComparingTo(new BigDecimal("9007199254740993"));
	}

	@Test
	void 숫자로_안_읽히는_문자열은_null() {
		assertThat(Numbers.toNumber(".")).isNull();
		assertThat(Numbers.toNumber("2026-08-05")).isNull();     // 마이너스 두 개
		assertThat(Numbers.toNumber("1.2.3")).isNull();
		assertThat(Numbers.toNumber("미정")).isNull();
	}

	@Test
	void 음수와_소수를_읽는다() {
		assertThat(Numbers.toNumber("-1,500원")).isEqualByComparingTo("-1500");
		assertThat(Numbers.toNumber("87.65%")).isEqualByComparingTo("87.65");
	}

	@Test
	void 숫자_타입은_문자열을_거치지_않는다() {
		// String.valueOf(1.0E10) = "1.0E10" → 'E' 제거 시 1.010 이 되는 함정.
		assertThat(Numbers.toNumber(1.0E10d)).isEqualByComparingTo("10000000000");
		assertThat(Numbers.toNumber(1234567890123L)).isEqualByComparingTo("1234567890123");
		assertThat(Numbers.toNumber(new BigDecimal("1234.56"))).isEqualByComparingTo("1234.56");
		assertThat(Numbers.toNumber(Double.NaN)).isNull();
		assertThat(Numbers.toNumber(Double.POSITIVE_INFINITY)).isNull();
	}

	@Test
	void toInt는_읽을_수_없으면_기본값() {
		assertThat(Numbers.toInt("999", 0)).isEqualTo(999);
		assertThat(Numbers.toInt(null, 4)).isEqualTo(4);
		assertThat(Numbers.toInt("", 4)).isEqualTo(4);
	}
}
