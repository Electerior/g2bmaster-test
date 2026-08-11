package com.electerior.g2bmaster.pricing;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

import com.electerior.g2bmaster.pricing.SpecConstraintMatcher.Constraint;
import com.electerior.g2bmaster.pricing.SpecConstraintMatcher.Outcome;
import com.electerior.g2bmaster.pricing.SpecConstraintMatcher.Verdict;

/**
 * 대조가 산수인지 확인한다.
 *
 * <p>여기서 지켜야 할 선은 하나다 — <b>모르는 것을 틀린 것으로 만들지 않는다.</b>
 * 후보에 값이 없거나 "동급" 같은 요구는 판정하지 않고 남긴다. 그 선을 넘는 순간
 * 백엔드가 다시 적합성을 추론하게 되고, 그게 지금 가격 로직이 틀리는 방식이다.
 */
class SpecConstraintMatcherTest {

	private static Constraint gte(String attr, Object value) {
		return new Constraint(attr, "gte", value, attr + " " + value + " 이상");
	}

	@Test
	@DisplayName("이상 조건 — 넉넉하면 만족")
	void gteSatisfied() {
		Verdict verdict = SpecConstraintMatcher.match(
				List.of(gte("memory_size_gb", 96)),
				Map.of("memory_size_gb", 96));

		assertThat(verdict.satisfied()).isEqualTo(1);
		assertThat(verdict.acceptable()).isTrue();
		assertThat(verdict.grounded()).isTrue();
	}

	@Test
	@DisplayName("이상 조건 — 모자라면 위반")
	void gteViolated() {
		Verdict verdict = SpecConstraintMatcher.match(
				List.of(gte("shaders", 24064)),
				Map.of("shaders", 16384));

		assertThat(verdict.violated()).isEqualTo(1);
		assertThat(verdict.acceptable()).isFalse();
		assertThat(verdict.results().get(0).outcome()).isEqualTo(Outcome.VIOLATED);
	}

	@Test
	@DisplayName("이하 조건")
	void lte() {
		assertThat(SpecConstraintMatcher.match(
				List.of(new Constraint("tdp_w", "lte", 600, "600W 이하")),
				Map.of("tdp_w", 450)).acceptable()).isTrue();

		assertThat(SpecConstraintMatcher.match(
				List.of(new Constraint("tdp_w", "lte", 600, "600W 이하")),
				Map.of("tdp_w", 700)).acceptable()).isFalse();
	}

	@Test
	@DisplayName("후보에 값이 없으면 위반이 아니라 미판정")
	void missingAttributeIsNotViolation() {
		Verdict verdict = SpecConstraintMatcher.match(
				List.of(gte("tensor_cores", 528)),
				Map.of("memory_size_gb", 96));

		assertThat(verdict.unjudged()).isEqualTo(1);
		assertThat(verdict.violated()).isZero();
		// 어긴 것은 없지만 맞춰 본 것도 없다 — "맞다"고 말할 근거가 없는 상태다.
		assertThat(verdict.acceptable()).isTrue();
		assertThat(verdict.grounded()).isFalse();
	}

	@Test
	@DisplayName("\"동급·내외\" 는 기계가 판정하지 않는다")
	void approxIsNeverJudged() {
		Verdict verdict = SpecConstraintMatcher.match(
				List.of(new Constraint("memory_size_gb", "approx", 96, "96GB 내외")),
				Map.of("memory_size_gb", 48));

		assertThat(verdict.results().get(0).outcome()).isEqualTo(Outcome.UNJUDGED);
		assertThat(verdict.violated()).isZero();
	}

	@Test
	@DisplayName("문자열은 같음만 본다 — DDR5 가 DDR4 보다 크다는 건 문서에 없는 지식이다")
	void textualComparisonOnlyEquality() {
		assertThat(SpecConstraintMatcher.match(
				List.of(new Constraint("memory_type", "eq", "DDR5", "DDR5")),
				Map.of("memory_type", "DDR5 RDIMM")).acceptable()).isTrue();

		Verdict ordered = SpecConstraintMatcher.match(
				List.of(new Constraint("memory_type", "gte", "DDR5", "DDR5 이상")),
				Map.of("memory_type", "DDR4"));
		assertThat(ordered.results().get(0).outcome()).isEqualTo(Outcome.UNJUDGED);
	}

	@Test
	@DisplayName("문자열 같음은 표기 차이를 견딘다")
	void textualNormalization() {
		assertThat(SpecConstraintMatcher.match(
				List.of(new Constraint("socket", "eq", "LGA-1700", "LGA1700")),
				Map.of("socket", "lga 1700")).acceptable()).isTrue();
	}

	@Test
	@DisplayName("문자열이 다르면 위반")
	void textualMismatch() {
		assertThat(SpecConstraintMatcher.match(
				List.of(new Constraint("interface", "eq", "NVMe", "NVMe")),
				Map.of("interface", "SATA")).violated()).isEqualTo(1);
	}

	@Test
	@DisplayName("숫자 문자열도 숫자로 읽는다")
	void numericStringsAreNumbers() {
		assertThat(SpecConstraintMatcher.match(
				List.of(gte("capacity_gb", "24000")),
				Map.of("capacity_gb", "24000")).satisfied()).isEqualTo(1);
	}

	@Test
	@DisplayName("ECC 같은 참·거짓도 맞춰 본다")
	void booleanAttributes() {
		assertThat(SpecConstraintMatcher.match(
				List.of(new Constraint("ecc", "eq", true, "ECC 지원")),
				Map.of("ecc", true)).acceptable()).isTrue();

		assertThat(SpecConstraintMatcher.match(
				List.of(new Constraint("ecc", "eq", true, "ECC 지원")),
				Map.of("ecc", false)).violated()).isEqualTo(1);
	}

	@Test
	@DisplayName("모르는 연산자는 판정하지 않는다")
	void unknownOperator() {
		Verdict verdict = SpecConstraintMatcher.match(
				List.of(new Constraint("cores", "between", 32, "32~64")),
				Map.of("cores", 48));

		assertThat(verdict.results().get(0).outcome()).isEqualTo(Outcome.UNJUDGED);
	}

	@Test
	@DisplayName("실제 규격서 한 건 — 만족·위반·미판정이 섞인다")
	void mixedRealWorld() {
		// KEIT GPU 서버 규격서에서 뽑힌 GPU 요구.
		List<Constraint> constraints = List.of(
				gte("memory_size_gb", 141),
				gte("memory_bandwidth_gbs", 4800),
				new Constraint("memory_type", "eq", "HBM3e", "HBM3e"),
				new Constraint("tensor_cores", "gte", 528, "텐서코어 528 이상"));

		// 사전에 대역폭과 텐서코어가 비어 있는 후보.
		Verdict verdict = SpecConstraintMatcher.match(constraints, Map.of(
				"memory_size_gb", 141,
				"memory_type", "HBM3e"));

		assertThat(verdict.satisfied()).isEqualTo(2);
		assertThat(verdict.violated()).isZero();
		assertThat(verdict.unjudged()).isEqualTo(2);
		assertThat(verdict.acceptable()).isTrue();
		assertThat(verdict.grounded()).isTrue();
	}

	@Test
	@DisplayName("빈 입력에도 깨지지 않는다")
	void emptyInputs() {
		assertThat(SpecConstraintMatcher.match(null, null).results()).isEmpty();
		assertThat(SpecConstraintMatcher.match(List.of(), Map.of()).acceptable()).isTrue();
		assertThat(SpecConstraintMatcher.match(List.of(), Map.of()).grounded()).isFalse();
	}
}
