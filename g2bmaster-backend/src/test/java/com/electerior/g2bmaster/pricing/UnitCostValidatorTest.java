package com.electerior.g2bmaster.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import com.electerior.g2bmaster.pricing.UnitCostValidator.Confidence;
import com.electerior.g2bmaster.pricing.UnitCostValidator.Verdict;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class UnitCostValidatorTest {

	/** 실제 규격서에서 뽑은 형태의 원문. 모델명이 아니라 사양으로 적힌 것이 보통이다. */
	private static final String SPEC = """
			물품구매 비교 규격서
			2. 세부 성능 및 규격
			- 128GB Error Correcting Code,Registered DDR5-6400Mhz (64GBx2)
			- M.2 NVMe PCIe 5.0x4 (128GT/s) , TLC , Include DRAM(4GB) , 4TB
			GPU memory: 96GB GDDR7 With ECC
			NVIDIA RTX PRO 6000 Blackwell
			ASUS ESC4000 계열 베어본 허용
			""";

	private static Map<String, Object> row(String category, String option, Integer low, Integer high,
			int qty, boolean inferred) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("category", category);
		m.put("option", option);
		m.put("low", low);
		m.put("high", high);
		m.put("qty", qty);
		m.put("inferred", inferred);
		return m;
	}

	private static Map<String, Object> envelope(boolean allPriced, boolean hasBase,
			List<Map<String, Object>> rows) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("matched", true);
		m.put("mid", rows.stream().mapToLong(r -> {
			Object low = r.get("low");
			Object high = r.get("high");
			if (low == null) {
				return 0;
			}
			long l = ((Number) low).longValue();
			long h = high == null ? l : ((Number) high).longValue();
			return (l + h) / 2 * ((Number) r.get("qty")).longValue();
		}).sum());
		m.put("allPriced", allPriced);
		m.put("hasBase", hasBase);
		m.put("breakdown", new ArrayList<>(rows));
		return m;
	}

	@Test
	@DisplayName("전 행이 규격서에 근거를 가지면 확정한다")
	void confirmedWhenAllBacked() {
		Verdict v = UnitCostValidator.validate(envelope(true, true, List.of(
				row("System", "ASUS ESC4000 베어본", 6_000_000, 6_000_000, 1, false),
				row("GPU", "NVIDIA RTX PRO 6000 Blackwell 96GB GDDR7", 20_000_000, 22_000_000, 1, false))),
				SPEC);
		assertThat(v.confidence()).isEqualTo(Confidence.CONFIRMED);
		assertThat(v.confirmedMid()).isEqualByComparingTo(BigDecimal.valueOf(27_000_000));
		assertThat(v.warnings()).isEmpty();
		assertThat(v.rows()).allSatisfy(r -> assertThat(r.get("acceptedForCost")).isEqualTo(true));
	}

	@Test
	@DisplayName("규격서에 없는 부품은 총액에서 뺀다 — 실측에서 TESLA H100 이 이렇게 들어왔다")
	void unbackedRowExcluded() {
		Verdict v = UnitCostValidator.validate(envelope(true, true, List.of(
				row("System", "ASUS ESC4000 베어본", 20_000_000, 20_000_000, 1, false),
				row("GPU", "NVIDIA TESLA H100 80GB HBM2", 5_000_000, 5_000_000, 1, false))),
				SPEC);
		Map<String, Object> gpu = v.rows().get(1);
		assertThat(gpu.get("evidenceInSpec")).isEqualTo(false);
		assertThat(gpu.get("acceptedForCost")).isEqualTo(false);
		assertThat(gpu.get("rejectReason")).isEqualTo(UnitCostValidator.WARN_NO_EVIDENCE);
		assertThat(v.confirmedMid()).isEqualByComparingTo(BigDecimal.valueOf(20_000_000));
		assertThat(v.warnings()).contains(UnitCostValidator.WARN_NO_EVIDENCE);
	}

	@Test
	@DisplayName("충돌 시 근거 있는 쪽이 이긴다 — 색인 추측이 규격서와 맞는 부품을 죽이면 안 된다")
	void categoryConflictResolvedByEvidence() {
		Verdict v = UnitCostValidator.validate(envelope(true, true, List.of(
				row("System", "ASUS ESC4000 베어본", 20_000_000, 20_000_000, 1, false),
				row("Memory", "512GB DDR5 PC5 5600 32GBx16", 30_000_000, 30_000_000, 1, false),
				row("RAM", "128GB DDR5-6400 64GBx2", 3_000_000, 3_000_000, 1, false))),
				SPEC);
		assertThat(v.rows().get(1).get("rejectReason")).isEqualTo(UnitCostValidator.WARN_CATEGORY_CONFLICT);
		assertThat(v.rows().get(2).get("acceptedForCost")).isEqualTo(true);
		assertThat(v.warnings()).contains(UnitCostValidator.WARN_CATEGORY_CONFLICT + ":ram:resolved");
		assertThat(v.confirmedMid()).isEqualByComparingTo(BigDecimal.valueOf(23_000_000));
	}

	@Test
	@DisplayName("양쪽 다 근거가 없으면 카테고리를 통째로 뺀다 — 어느 쪽도 고를 수 없다")
	void categoryConflictDropsBoth() {
		Verdict v = UnitCostValidator.validate(envelope(true, true, List.of(
				row("System", "ASUS ESC4000 베어본", 20_000_000, 20_000_000, 1, false),
				row("Processor", "Intel Xeon w3-2425", 1_100_000, 1_100_000, 1, false),
				row("CPU", "AMD EPYC 9124", 2_300_000, 2_300_000, 1, false))),
				SPEC);
		assertThat(v.warnings()).contains(UnitCostValidator.WARN_CATEGORY_CONFLICT + ":cpu");
		assertThat(v.rows().get(1).get("rejectReason")).isEqualTo(UnitCostValidator.WARN_CATEGORY_CONFLICT);
		assertThat(v.rows().get(2).get("rejectReason")).isEqualTo(UnitCostValidator.WARN_CATEGORY_CONFLICT);
		assertThat(v.confirmedMid()).isEqualByComparingTo(BigDecimal.valueOf(20_000_000));
	}

	@Test
	@DisplayName("0원 행과 inferred(참고값) 행은 합산하지 않는다")
	void zeroAndInferredExcluded() {
		Verdict v = UnitCostValidator.validate(envelope(true, true, List.of(
				row("System", "ASUS ESC4000 베어본", 20_000_000, 20_000_000, 1, false),
				row("GPU", "NVIDIA RTX PRO 6000 Blackwell", 0, 0, 1, false),
				row("Memory", "128GB DDR5-6400", 3_000_000, 3_000_000, 1, true))),
				SPEC);
		assertThat(v.rows().get(1).get("rejectReason")).isEqualTo(UnitCostValidator.WARN_ZERO_PRICED);
		assertThat(v.rows().get(2).get("rejectReason")).isEqualTo(UnitCostValidator.WARN_INFERRED);
		assertThat(v.confirmedMid()).isEqualByComparingTo(BigDecimal.valueOf(20_000_000));
	}

	@Test
	@DisplayName("allPriced=false 는 AI 가 이미 알려주던 신호다 — 이제 읽어서 경고로 남긴다")
	void notAllPricedWarns() {
		Verdict v = UnitCostValidator.validate(envelope(false, true, List.of(
				row("System", "ASUS ESC4000 베어본", 20_000_000, 20_000_000, 1, false),
				row("GPU", "NVIDIA RTX PRO 6000 Blackwell", 20_000_000, 20_000_000, 1, false))),
				SPEC);
		assertThat(v.warnings()).contains(UnitCostValidator.WARN_NOT_ALL_PRICED);
		assertThat(v.confidence()).isEqualTo(Confidence.PARTIAL);
	}

	@Test
	@DisplayName("근거 비중이 바닥선 미만이면 확정하지 않는다 — 틀린 단가는 없는 단가보다 나쁘다")
	void lowEvidenceRatioIsUntrusted() {
		Verdict v = UnitCostValidator.validate(envelope(true, true, List.of(
				row("System", "ASUS ESC4000 베어본", 1_000_000, 1_000_000, 1, false),
				row("Memory", "512GB DDR5 PC5 5600 32GBx16", 30_000_000, 30_000_000, 1, false))),
				SPEC);
		assertThat(v.confidence()).isEqualTo(Confidence.UNTRUSTED);
		assertThat(v.confirmedMid()).isNull();
		assertThat(v.warnings()).contains(UnitCostValidator.WARN_LOW_EVIDENCE);
		assertThat(v.indicativeMid()).isNotNull();   // 참고값은 남는다
	}

	@Test
	@DisplayName("베어본이 없으면 확정하지 않는다 — 부품 합만으로는 장비 단가가 아니다")
	void noBaseIsUntrusted() {
		Verdict v = UnitCostValidator.validate(envelope(true, false, List.of(
				row("GPU", "NVIDIA RTX PRO 6000 Blackwell 96GB", 20_000_000, 20_000_000, 1, false))),
				SPEC);
		assertThat(v.confidence()).isEqualTo(Confidence.UNTRUSTED);
		assertThat(v.warnings()).contains(UnitCostValidator.WARN_NO_BASE);
	}

	@Test
	@DisplayName("규격서 원문이 없으면 대조할 수 없으므로 확정하지 않는다")
	void noSpecTextIsUntrusted() {
		Verdict v = UnitCostValidator.validate(envelope(true, true, List.of(
				row("System", "ASUS ESC4000", 20_000_000, 20_000_000, 1, false))), "");
		assertThat(v.confidence()).isEqualTo(Confidence.UNTRUSTED);
		assertThat(v.warnings()).contains(UnitCostValidator.WARN_NO_SPEC_TEXT);
	}

	@Test
	@DisplayName("완제품 번들 판정을 읽어 표시한다 — AI 가 내던 것을 아무도 안 봤다")
	void prebuiltSurfaced() {
		Map<String, Object> env = envelope(true, true, List.of(
				row("System", "ASUS ESC4000 베어본", 20_000_000, 20_000_000, 1, false)));
		env.put("prebuilt", Map.of("isPrebuilt", true, "score", 7));
		Verdict v = UnitCostValidator.validate(env, SPEC);
		assertThat(v.isPrebuilt()).isTrue();
		assertThat(v.warnings()).contains(UnitCostValidator.WARN_PREBUILT);
	}

	@Test
	@DisplayName("탐색기가 막혀서 못 찾은 것을 '규격서에 없음'과 구분해 알린다")
	void searchUnavailableSurfaced() {
		Map<String, Object> row = row("GPU", "96GB GDDR7 ECC 사양", 5_000_000, 5_000_000, 1, false);
		row.put("searchUnavailable", true);
		Verdict v = UnitCostValidator.validate(envelope(true, true, List.of(
				row("System", "ASUS ESC4000 베어본", 20_000_000, 20_000_000, 1, false), row)), SPEC);
		assertThat(v.warnings()).contains(UnitCostValidator.WARN_SEARCH_UNAVAILABLE);
	}

	@Test
	@DisplayName("matched=false 나 null 은 신뢰하지 않는다")
	void unmatchedIsUntrusted() {
		assertThat(UnitCostValidator.validate(null, SPEC).confidence()).isEqualTo(Confidence.UNTRUSTED);
		assertThat(UnitCostValidator.validate(Map.of("matched", false), SPEC).confidence())
				.isEqualTo(Confidence.UNTRUSTED);
	}

	@Test
	@DisplayName("근거 문장은 원문에 그대로 있어야 통과한다 — 바꿔 쓰면 떨어진다")
	void quoteEvidenceIsVerbatim() {
		assertThat(UnitCostValidator.hasQuoteEvidence("GPU memory: 96GB GDDR7 With ECC", SPEC)).isTrue();
		// 구두점 차이는 통과한다 — LLM 이 "RAM DDR5" 를 "RAM: DDR5" 로 옮겨 적는 정도.
		assertThat(UnitCostValidator.hasQuoteEvidence("GPU memory 96GB, GDDR7 With ECC", SPEC)).isTrue();
		assertThat(UnitCostValidator.hasQuoteEvidence("GPU 메모리는 96기가바이트입니다", SPEC)).isFalse();
		assertThat(UnitCostValidator.hasQuoteEvidence("NVIDIA TESLA H100 80GB HBM2", SPEC)).isFalse();
		assertThat(UnitCostValidator.hasQuoteEvidence("", SPEC)).isFalse();
		assertThat(UnitCostValidator.hasQuoteEvidence("96GB GDDR7", null)).isFalse();
	}

	@Test
	@DisplayName("근거 문장이 실려 오면 토큰 대조 대신 그것으로 판정한다")
	void quoteBeatsTokenMatching() {
		Map<String, Object> withQuote = row("RAM", "DDR5 ECC Registered 서버용 메모리 32GB", 3_000_000,
				3_000_000, 1, false);
		// 모델 토큰(32gb)은 원문에 없지만, 근거 문장은 원문 그대로다.
		withQuote.put("evidence", "128GB Error Correcting Code,Registered DDR5-6400Mhz (64GBx2)");
		Verdict v = UnitCostValidator.validate(envelope(true, true, List.of(
				row("System", "ASUS ESC4000 베어본", 20_000_000, 20_000_000, 1, false), withQuote)), SPEC);
		Map<String, Object> ram = v.rows().get(1);
		assertThat(ram.get("evidenceBasis")).isEqualTo("quote");
		assertThat(ram.get("evidenceInSpec")).isEqualTo(true);
		assertThat(ram.get("acceptedForCost")).isEqualTo(true);
	}

	@Test
	@DisplayName("지어낸 근거 문장은 원문에 없으므로 떨어진다 — 이 검사의 존재 이유")
	void fabricatedQuoteRejected() {
		Map<String, Object> fake = row("GPU", "NVIDIA TESLA H100 80GB", 5_000_000, 5_000_000, 1, false);
		fake.put("evidence", "GPU: NVIDIA TESLA H100 80GB HBM2 2장 이상 탑재할 것");
		Verdict v = UnitCostValidator.validate(envelope(true, true, List.of(
				row("System", "ASUS ESC4000 베어본", 20_000_000, 20_000_000, 1, false), fake)), SPEC);
		assertThat(v.rows().get(1).get("evidenceInSpec")).isEqualTo(false);
		assertThat(v.rows().get(1).get("rejectReason")).isEqualTo(UnitCostValidator.WARN_NO_EVIDENCE);
	}
}
