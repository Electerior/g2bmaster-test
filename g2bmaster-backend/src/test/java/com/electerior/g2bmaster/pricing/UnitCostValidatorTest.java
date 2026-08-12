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

	/** 이 행이 어느 기종에 속하고 그 기종을 몇 대 납품하는지 — AI 가 붙여 보내는 축이다. */
	private static Map<String, Object> unit(Map<String, Object> row, String unitId, int unitQty) {
		row.put("unitId", unitId);
		row.put("unitQty", unitQty);
		return row;
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
	@DisplayName("같은 카테고리의 서로 다른 부품은 함께 산다 — 한 서버에 스토리지가 두 종일 수 있다")
	void sameCategoryDistinctPartsBothSurvive() {
		// 예전 구현은 여기서 두 SSD 를 모두 죽였다(카테고리 축 충돌). 실측 회귀.
		Map<String, Object> os = row("SSD", "960GB NVMe ENT", 300_000, 300_000, 2, false);
		os.put("evidence", "M.2 NVMe PCIe 5.0x4 (128GT/s) , TLC , Include DRAM(4GB) , 4TB");
		Map<String, Object> data = row("SSD", "4TB NVMe", 2_000_000, 2_000_000, 4, false);
		data.put("evidence", "GPU memory: 96GB GDDR7 With ECC");   // 원문의 다른 줄을 가리킨다
		Verdict v = UnitCostValidator.validate(envelope(true, true, List.of(
				row("System", "ASUS ESC4000 베어본", 6_000_000, 6_000_000, 1, false), os, data)), SPEC);

		assertThat(v.rows()).allSatisfy(r -> assertThat(r.get("acceptedForCost")).isEqualTo(true));
		assertThat(v.warnings()).doesNotContain(UnitCostValidator.WARN_DUPLICATE_ROW);
		assertThat(v.confidence()).isEqualTo(Confidence.CONFIRMED);
		assertThat(v.confirmedMid()).isEqualByComparingTo(BigDecimal.valueOf(14_600_000));
	}

	@Test
	@DisplayName("같은 자리를 가리키는 두 읽기는 하나만 남는다 — 규격서와 더 맞는 쪽이 이긴다")
	void sameAnchorKeepsBetterMatch() {
		Map<String, Object> wrong = row("Memory", "512GB DDR5 PC5 5600", 30_000_000, 30_000_000, 1, false);
		wrong.put("evidence", "128GB Error Correcting Code,Registered DDR5-6400Mhz");
		Map<String, Object> right = row("RAM", "128GB Registered DDR5-6400", 3_000_000, 3_000_000, 1, false);
		right.put("evidence", "128GB Error Correcting Code,Registered DDR5-6400Mhz (64GBx2)");
		Verdict v = UnitCostValidator.validate(envelope(true, true, List.of(
				row("System", "ASUS ESC4000 베어본", 20_000_000, 20_000_000, 1, false), wrong, right)), SPEC);

		assertThat(v.rows().get(1).get("rejectReason")).isEqualTo(UnitCostValidator.WARN_DUPLICATE_ROW);
		assertThat(v.rows().get(2).get("acceptedForCost")).isEqualTo(true);
		assertThat(v.warnings()).contains(UnitCostValidator.WARN_DUPLICATE_ROW);
		// 진 행과 이긴 행이 같은 묶음 번호를 갖는다 — 화면이 짝지어 비교·선택하게 한다.
		// 사유만 붙이면 "무엇의 중복인지" 알 수 없어 사람이 고를 수가 없다.
		assertThat(v.rows().get(1).get("duplicateGroup")).isEqualTo(v.rows().get(2).get("duplicateGroup"));
		assertThat(v.rows().get(0).get("duplicateGroup")).isNull();
		// 진 행은 중복이므로 근거 비율의 분모에서도 빠진다 — 정답을 골라 놓고 비율이 깎이면 안 된다.
		assertThat(v.confirmedMid()).isEqualByComparingTo(BigDecimal.valueOf(23_000_000));
		assertThat(v.evidenceRatio()).isEqualTo(1.0);
	}

	@Test
	@DisplayName("한 줄에 부품이 둘이면 둘 다 산다 — 자리가 같다고 부품이 같은 것은 아니다")
	void oneLineTwoPartsBothSurvive() {
		String spec = "네트워크: 10GbE 2port, 1GbE 4port\n파워: 2000W 이중화\n";
		Map<String, Object> ten = row("네트워크", "10GbE 2port", 400_000, 400_000, 1, false);
		ten.put("evidence", "네트워크: 10GbE 2port, 1GbE 4port");
		Map<String, Object> one = row("네트워크", "1GbE 4port", 200_000, 200_000, 1, false);
		one.put("evidence", "네트워크: 10GbE 2port, 1GbE 4port");   // 같은 줄을 인용한다

		Verdict v = UnitCostValidator.validate(envelope(true, true, List.of(ten, one)), spec);
		assertThat(v.rows()).allSatisfy(r -> assertThat(r.get("acceptedForCost")).isEqualTo(true));
		assertThat(v.warnings()).doesNotContain(UnitCostValidator.WARN_DUPLICATE_ROW);
		assertThat(v.confirmedMid()).isEqualByComparingTo(BigDecimal.valueOf(600_000));
	}

	@Test
	@DisplayName("같은 줄에서 같은 부품을 두 번 뽑았으면 접는다")
	void sameLineSamePartFolds() {
		String spec = "네트워크: 10GbE 2port, 1GbE 4port\n";
		Map<String, Object> a = row("네트워크", "10GbE 2port", 400_000, 400_000, 1, false);
		a.put("evidence", "네트워크: 10GbE 2port, 1GbE 4port");
		Map<String, Object> b = row("NIC", "10GbE 2port", 450_000, 450_000, 1, false);
		b.put("evidence", "네트워크: 10GbE 2port, 1GbE 4port");

		Verdict v = UnitCostValidator.validate(envelope(true, true, List.of(a, b)), spec);
		assertThat(v.warnings()).contains(UnitCostValidator.WARN_DUPLICATE_ROW);
		assertThat(v.confirmedMid()).isEqualByComparingTo(BigDecimal.valueOf(400_000));
	}

	@Test
	@DisplayName("인용이 없어도 같은 모델이면 접는다 — 색인과 웹이 같은 부품을 각각 실어 보낸다")
	void sameModelSignatureFolds() {
		Verdict v = UnitCostValidator.validate(envelope(true, true, List.of(
				row("System", "ASUS ESC4000 베어본", 20_000_000, 20_000_000, 1, false),
				row("GPU", "NVIDIA RTX PRO 6000 Blackwell 96GB", 22_000_000, 22_000_000, 1, false),
				row("VGA", "RTX PRO 6000 96GB Blackwell", 20_000_000, 20_000_000, 1, false))),
				SPEC);
		assertThat(v.warnings()).contains(UnitCostValidator.WARN_DUPLICATE_ROW);
		// 고를 수 없을 때는 싼 쪽이 남는다 — 검증기가 총액을 부풀리는 방향으로 실수하면 안 된다.
		assertThat(v.confirmedMid()).isEqualByComparingTo(BigDecimal.valueOf(40_000_000));
	}

	@Test
	@DisplayName("토큰 둘 중 하나만 맞는 라벨은 근거가 아니다 — 3,016만원 오류가 이 모양이었다")
	void halfMatchedLabelIsNotEvidence() {
		assertThat(UnitCostValidator.hasEvidence("512GB DDR5", SPEC)).isFalse();
		assertThat(UnitCostValidator.hasEvidence("128GB DDR5", SPEC)).isTrue();
		// 모델코드 하나뿐인 라벨은 그 하나가 맞으면 통과한다(1/1).
		assertThat(UnitCostValidator.hasEvidence("삼성 64GBx2 모듈", SPEC)).isTrue();
	}

	@Test
	@DisplayName("규격서가 128GB 를 요구하면 8GB 는 근거가 아니다 — 낱말 중간에 걸리면 안 된다")
	void smallerCapacityDoesNotMatchInsideBiggerOne() {
		// 예전에는 정규화가 공백까지 지워 specNorm 이 "…128gb…" 였고, 라벨의 `8gb` 가
		// 그 안에 들어 있다는 이유로 통과했다(실행으로 재현). 낱말 경계를 지키면 떨어진다.
		assertThat(UnitCostValidator.hasEvidence("8GB DDR5", SPEC)).isFalse();
		assertThat(UnitCostValidator.hasEvidence("16GB DDR5", SPEC)).isFalse();
		// 반대로 `4GB` 는 규격서에 정말로 적혀 있다("Include DRAM(4GB)") — 대조는 문자 그대로다.
		assertThat(UnitCostValidator.hasEvidence("4GB DDR5", SPEC)).isTrue();
		// 단위 접미만 다른 것은 같은 값이다 — 규격서 "DDR5-6400Mhz" 에 라벨 "6400" 은 붙는다.
		assertThat(UnitCostValidator.hasEvidence("128GB DDR5-6400", SPEC)).isTrue();

		Verdict v = UnitCostValidator.validate(envelope(true, true, List.of(
				row("System", "ASUS ESC4000 베어본", 6_000_000, 6_000_000, 1, false),
				row("RAM", "8GB DDR5", 50_000, 50_000, 1, false))), SPEC);
		assertThat(v.rows().get(1).get("rejectReason")).isEqualTo(UnitCostValidator.WARN_NO_EVIDENCE);
		assertThat(v.confirmedMid()).isEqualByComparingTo(BigDecimal.valueOf(6_000_000));
	}

	@Test
	@DisplayName("숫자 없는 부품은 '근거 없음'이 아니라 '판정 불가'다 — 규격서에 적혀 있어도 대조할 것이 없다")
	void labelWithoutIdentifierIsUnverifiableNotRejected() {
		String spec = "사무용 컴퓨터 구매 규격서\n- 본체: 미들타워 케이스, 정격 800W 파워서플라이\n"
				+ "- CPU: Intel Core i5-14400\n";
		Verdict v = UnitCostValidator.validate(envelope(true, true, List.of(
				row("CPU", "Intel Core i5-14400", 250_000, 250_000, 1, false),
				row("케이스", "미들타워 케이스", 60_000, 60_000, 1, false))), spec);

		Map<String, Object> caseRow = v.rows().get(1);
		assertThat(caseRow.get("evidenceBasis")).isEqualTo("unverifiable");
		// 빼지 않는다 — 여기서 빼면 규격서에 적힌 케이스·쿨러가 통째로 사라진다.
		assertThat(caseRow.get("acceptedForCost")).isEqualTo(true);
		assertThat(caseRow.get("rejectReason")).isNull();
		assertThat(v.warnings()).contains(UnitCostValidator.WARN_UNVERIFIABLE_LABEL);
		assertThat(v.confirmedMid()).isEqualByComparingTo(BigDecimal.valueOf(310_000));
	}

	@Test
	@DisplayName("1대에 17개 이상 들어가는 부품은 없다 — 발주 대수를 곱한 행은 총액에서 뺀다")
	void multipliedQuantityIsRejected() {
		// 실측: 30대 규격서에서 LLM 이 부품 qty 를 30 으로 보내 단가가 30배가 됐는데
		// 판정은 CONFIRMED, 경고 0건이었다. 검증축(모델명)과 오차축(수량)이 어긋나 있었다.
		String spec = "사무용 PC 30대 구매 규격서\n- CPU: Intel Core i5-14400\n- 메모리: 16GB DDR5\n";
		Verdict v = UnitCostValidator.validate(envelope(true, true, List.of(
				row("CPU", "Intel Core i5-14400", 250_000, 250_000, 30, false),
				row("RAM", "16GB DDR5", 90_000, 90_000, 30, false))), spec);

		assertThat(v.rows()).allSatisfy(r ->
				assertThat(r.get("rejectReason")).isEqualTo(UnitCostValidator.WARN_QTY_OUT_OF_RANGE));
		assertThat(v.warnings()).contains(UnitCostValidator.WARN_QTY_OUT_OF_RANGE);
		// 30배 부푼 단가를 내느니 내지 않는다.
		assertThat(v.confidence()).isEqualTo(Confidence.UNTRUSTED);
		assertThat(v.confirmedMid()).isNull();
	}

	@Test
	@DisplayName("수량 0 과 음수 단가는 총액에 넣지 않는다")
	void brokenValuesAreRejected() {
		String spec = "GPU: NVIDIA RTX PRO 6000 96GB\nCPU: AMD EPYC 9354\n";
		Verdict v = UnitCostValidator.validate(envelope(true, true, List.of(
				row("GPU", "NVIDIA RTX PRO 6000 96GB", 20_000_000, 20_000_000, 0, false),
				row("CPU", "AMD EPYC 9354", -5_000_000, -5_000_000, 1, false))), spec);

		assertThat(v.rows().get(0).get("rejectReason")).isEqualTo(UnitCostValidator.WARN_QTY_OUT_OF_RANGE);
		assertThat(v.rows().get(1).get("rejectReason")).isEqualTo(UnitCostValidator.WARN_BAD_PRICE);
		assertThat(v.confidence()).isEqualTo(Confidence.UNTRUSTED);
		assertThat(v.confirmedMid()).isNull();
	}

	@Test
	@DisplayName("행이 대표값(중앙값)을 실어 오면 그것을 쓴다 — high 는 검색 잡음의 최고가다")
	void representativeMidWinsOverMidpoint() {
		// 실측: "i5-14400" 검색이 223,000~1,853,730원으로 잡혔다(뒤쪽은 그 CPU 가 들어간 완성 PC).
		// 중간값 103만원을 CPU 한 개 단가로 쓰면 원가가 네 배로 부푼다.
		Map<String, Object> withMid = row("CPU", "Intel Core i5-14400", 223_000, 1_853_730, 1, false);
		withMid.put("mid", 250_000);
		Verdict v = UnitCostValidator.validate(envelope(true, true, List.of(withMid)),
				"CPU: Intel Core i5-14400 이상\n");
		assertThat(v.confirmedMid()).isEqualByComparingTo(BigDecimal.valueOf(250_000));

		// 대표값이 없으면 예전대로 low·high 의 중간으로 물러난다.
		Verdict fallback = UnitCostValidator.validate(envelope(true, true, List.of(
				row("CPU", "Intel Core i5-14400", 200_000, 300_000, 1, false))),
				"CPU: Intel Core i5-14400 이상\n");
		assertThat(fallback.confirmedMid()).isEqualByComparingTo(BigDecimal.valueOf(250_000));
	}

	@Test
	@DisplayName("기종이 다르면 같은 부품이 들어가도 중복이 아니다 — 단가는 혼합, 총액은 기종별 대수로")
	void samePartInDifferentUnitsBothSurvive() {
		String spec = "1. 사무용 PC(A형) 20대 — CPU Intel Core i5-14400, 메모리 16GB DDR5\n"
				+ "2. 설계용 PC(B형) 5대 — CPU Intel Core i5-14400, 메모리 64GB DDR5\n";
		Verdict v = UnitCostValidator.validate(envelope(true, true, List.of(
				unit(row("CPU", "Intel Core i5-14400", 250_000, 250_000, 1, false), "A", 20),
				unit(row("RAM", "16GB DDR5", 90_000, 90_000, 1, false), "A", 20),
				unit(row("CPU", "Intel Core i5-14400", 250_000, 250_000, 1, false), "B", 5),
				unit(row("RAM", "64GB DDR5", 400_000, 400_000, 1, false), "B", 5))), spec);

		assertThat(v.rows()).allSatisfy(r -> assertThat(r.get("acceptedForCost")).isEqualTo(true));
		assertThat(v.warnings()).doesNotContain(UnitCostValidator.WARN_DUPLICATE_ROW);
		assertThat(v.totalUnits()).isEqualTo(25);
		// A형 34만 × 20대 + B형 65만 × 5대
		assertThat(v.confirmedTotal()).isEqualByComparingTo(BigDecimal.valueOf(10_050_000));
		// 단가는 1대 값이어야 한다 — DealCalculator 가 여기에 수량을 다시 곱하기 때문이다.
		assertThat(v.confirmedMid()).isEqualByComparingTo(BigDecimal.valueOf(402_000));
	}

	@Test
	@DisplayName("같은 기종 안에서는 여전히 접힌다 — 기종 축이 중복 판정을 없애지는 않는다")
	void samePartInSameUnitStillFolds() {
		Verdict v = UnitCostValidator.validate(envelope(true, true, List.of(
				unit(row("GPU", "NVIDIA RTX PRO 6000 Blackwell 96GB", 22_000_000, 22_000_000, 1, false), "A", 2),
				unit(row("VGA", "RTX PRO 6000 96GB Blackwell", 20_000_000, 20_000_000, 1, false), "A", 2))),
				SPEC);
		assertThat(v.warnings()).contains(UnitCostValidator.WARN_DUPLICATE_ROW);
		assertThat(v.confirmedMid()).isEqualByComparingTo(BigDecimal.valueOf(20_000_000));
		assertThat(v.confirmedTotal()).isEqualByComparingTo(BigDecimal.valueOf(40_000_000));
	}

	@Test
	@DisplayName("모델명을 특정해도 경합으로 죽지 않는다 — 규격서는 모델코드를 적지 않는다")
	void namingTheModelDoesNotKillTheRow() {
		// 실측 회귀: 사양("10GbE 2port")일 땐 둘 다 살았는데, 사양→모델 탐색이 성공해
		// "Intel X710-DA2 10GbE 2port" 가 되자 x710·da2 가 인용문에 없다는 이유로 삭제됐다.
		// 탐색이 잘 될수록 손해를 보는 구조였다.
		String spec = "네트워크: 10GbE 2port, 1GbE 4port\n";
		Map<String, Object> ten = row("네트워크", "Intel X710-DA2 10GbE 2port", 700_000, 700_000, 1, false);
		ten.put("evidence", "네트워크: 10GbE 2port, 1GbE 4port");
		Map<String, Object> one = row("네트워크", "Intel I350-T4 1GbE 4port", 300_000, 300_000, 1, false);
		one.put("evidence", "네트워크: 10GbE 2port, 1GbE 4port");

		Verdict v = UnitCostValidator.validate(envelope(true, true, List.of(ten, one)), spec);
		assertThat(v.rows()).allSatisfy(r -> assertThat(r.get("acceptedForCost")).isEqualTo(true));
		assertThat(v.warnings()).doesNotContain(UnitCostValidator.WARN_DUPLICATE_ROW);
		assertThat(v.confirmedMid()).isEqualByComparingTo(BigDecimal.valueOf(1_000_000));
	}

	@Test
	@DisplayName("breakdown 이 중첩으로 오면 조용히 미확정이 되지 않고 경고를 남긴다")
	void nestedBreakdownWarns() {
		Map<String, Object> parent = row("System", "완제품 베이스", null, null, 1, false);
		parent.put("children", List.of(row("GPU", "NVIDIA RTX PRO 6000", 20_000_000, 20_000_000, 1, false)));
		Verdict v = UnitCostValidator.validate(envelope(true, true, List.of(parent)), SPEC);
		assertThat(v.warnings()).contains(UnitCostValidator.WARN_NESTED_BREAKDOWN);
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
	@DisplayName("베어본이 없어도 근거가 있으면 확정한다 — 경고로 알리되 등급을 죽이지 않는다")
	void noBaseWarnsButDoesNotBlock() {
		// `hasBase` 는 ITMAYA GPU서버 색인이 잡혔을 때만 켜진다. 확정 조건에 걸어 두면 그
		// 카탈로그 밖 품목(사무용 PC·네트워크 장비)은 근거가 100% 여도 영원히 미확정이 된다.
		//
		// 예전에는 이 경고가 `warnings` 에 들어 있다는 것만으로 등급이 PARTIAL 로 깎였다 —
		// 주석은 "등급을 가르지 않는다"고 적혀 있었지만 코드는 CONFIRMED 를 막고 있었다.
		// 숫자가 틀렸다는 뜻이 아닌 경고는 등급에서 뺀다(UnitCostValidator.INFORMATIONAL).
		Verdict v = UnitCostValidator.validate(envelope(true, false, List.of(
				row("GPU", "NVIDIA RTX PRO 6000 Blackwell 96GB", 20_000_000, 20_000_000, 1, false))),
				SPEC);
		assertThat(v.confidence()).isEqualTo(Confidence.CONFIRMED);
		assertThat(v.confirmedMid()).isEqualByComparingTo(BigDecimal.valueOf(20_000_000));
		assertThat(v.warnings()).contains(UnitCostValidator.WARN_NO_BASE);
		assertThat(v.hasBase()).isFalse();
	}

	@Test
	@DisplayName("인용이 어긋나도 모델명이 원문에 있으면 인정한다 — 인용은 근거를 더하지 없애지 않는다")
	void tokenFallbackWhenQuoteMisses() {
		Map<String, Object> gpu = row("GPU", "NVIDIA RTX PRO 6000 Blackwell 96GB", 20_000_000,
				20_000_000, 1, false);
		gpu.put("evidence", "GPU는 NVIDIA RTX PRO 6000 Blackwell 96GB 를 쓴다");   // 원문에 없는 문장
		Verdict v = UnitCostValidator.validate(envelope(true, true, List.of(
				row("System", "ASUS ESC4000 베어본", 6_000_000, 6_000_000, 1, false), gpu)), SPEC);
		assertThat(v.rows().get(1).get("evidenceInSpec")).isEqualTo(true);
		assertThat(v.rows().get(1).get("evidenceBasis")).isEqualTo("token");
		assertThat(v.rows().get(1).get("acceptedForCost")).isEqualTo(true);
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
	@DisplayName("근거 문장이 원문과 맞으면 토큰이 어긋나도 인정한다 — basis 는 quote 로 남는다")
	void quoteBacksRowWhenTokensMiss() {
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
