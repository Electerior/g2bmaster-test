package com.electerior.g2bmaster.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.electerior.g2bmaster.analysis.CollusionAnalysis.CollusionMatrix;
import com.electerior.g2bmaster.analysis.CollusionAnalysis.Company;
import com.electerior.g2bmaster.analysis.CollusionAnalysis.Pair;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 담합 정황 매트릭스.
 *
 * <p>{@code lib/collusion-analysis.js} 에는 자체 검증이 없었다. 하지만 이 결과는 사람이
 * "이 짝을 들여다볼까"를 정하는 데 쓰이므로, 점수 정의(번갈아 정도 × 건수)가 조용히
 * 바뀌면 안 된다.
 */
class CollusionAnalysisTest {

	private static Map<String, Object> bid(String no, String name, Object... participants) {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("bidNtceNo", no);
		map.put("bidNtceNm", name);
		map.put("opengDate", "2026-07-01");
		List<Object> list = new ArrayList<>(List.of(participants));
		map.put("participants", list);
		return map;
	}

	private static Map<String, Object> participant(String name, String rank, String rate) {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("bdrNm", name);
		map.put("rank", rank);
		map.put("bidprcRt", rate);
		map.put("bidAmt", "1000");
		return map;
	}

	@Test
	void 순위가_뒤섞여_와도_1위와_2위를_고른다() {
		CollusionMatrix matrix = CollusionAnalysis.buildCollusionMatrix(List.of(
				bid("N1", "노트북",
						participant("B사", "2", "88"),
						participant("A사", "1", "90"))));

		assertThat(matrix.pairs()).hasSize(1);
		Pair pair = matrix.pairs().get(0);
		assertThat(pair.cases().get(0).winner()).isEqualTo("A사");
		assertThat(pair.cases().get(0).runnerUp()).isEqualTo("B사");
	}

	@Test
	void 짝_키는_이름_순서에_흔들리지_않는다() {
		// (A,B)와 (B,A)가 다른 짝으로 세어지면 교대 패턴이 통째로 안 보인다.
		CollusionMatrix matrix = CollusionAnalysis.buildCollusionMatrix(List.of(
				bid("N1", "1차", participant("A사", "1", "90"), participant("B사", "2", "91")),
				bid("N2", "2차", participant("B사", "1", "92"), participant("A사", "2", "93"))));

		assertThat(matrix.pairs()).hasSize(1);
		Pair pair = matrix.pairs().get(0);
		assertThat(pair.total()).isEqualTo(2);
		assertThat(pair.aWins()).isEqualTo(1);
		assertThat(pair.bWins()).isEqualTo(1);
		assertThat(pair.alterScore()).isEqualTo(100);            // 완전 교대
		assertThat(pair.suspicionScore()).isEqualByComparingTo("2.0");
	}

	@Test
	void 한_번만_만난_짝은_교대_점수가_0이다() {
		// 공식을 그대로 태우면 1.0(완전 교대)이 나온다 — 한 번 만난 둘은 정의상 교대할 수 없다.
		CollusionMatrix matrix = CollusionAnalysis.buildCollusionMatrix(List.of(
				bid("N1", "1차", participant("A사", "1", "90"), participant("B사", "2", "91"))));

		assertThat(matrix.pairs().get(0).alterScore()).isZero();
		assertThat(matrix.pairs().get(0).suspicionScore()).isEqualByComparingTo("0.0");
	}

	@Test
	void 투찰률_단조_증가를_잡는다() {
		CollusionMatrix matrix = CollusionAnalysis.buildCollusionMatrix(List.of(
				bid("N1", "1차", participant("A사", "1", "80"), participant("B사", "2", "95")),
				bid("N2", "2차", participant("A사", "1", "85"), participant("B사", "2", "96")),
				bid("N3", "3차", participant("A사", "1", "90"), participant("B사", "2", "97"))));

		Company a = matrix.companies().stream()
				.filter(c -> c.name().equals("A사")).findFirst().orElseThrow();
		assertThat(a.wins()).isEqualTo(3);
		assertThat(a.appearances()).isEqualTo(3);
		assertThat(a.isMonotonicallyIncreasing()).isTrue();
		assertThat(a.avgRate()).isEqualByComparingTo("85.0");
	}

	@Test
	void 표본이_3건_미만이면_단조_증가로_보지_않는다() {
		CollusionMatrix matrix = CollusionAnalysis.buildCollusionMatrix(List.of(
				bid("N1", "1차", participant("A사", "1", "80"), participant("B사", "2", "95")),
				bid("N2", "2차", participant("A사", "1", "85"), participant("B사", "2", "96"))));

		Company a = matrix.companies().stream()
				.filter(c -> c.name().equals("A사")).findFirst().orElseThrow();
		assertThat(a.isMonotonicallyIncreasing()).isFalse();
	}

	@Test
	void 참여업체가_없거나_이름이_비면_그_공고를_건너뛴다() {
		CollusionMatrix matrix = CollusionAnalysis.buildCollusionMatrix(List.of(
				bid("N1", "참여없음"),
				bid("N2", "이름없음", participant("", "1", "90"))));

		assertThat(matrix.pairs()).isEmpty();
		assertThat(matrix.companies()).isEmpty();
	}

	@Test
	void 단독_입찰은_업체만_기록하고_짝은_만들지_않는다() {
		CollusionMatrix matrix = CollusionAnalysis.buildCollusionMatrix(List.of(
				bid("N1", "단독", participant("A사", "1", "99"))));

		assertThat(matrix.pairs()).isEmpty();
		assertThat(matrix.companies()).hasSize(1);
		assertThat(matrix.companies().get(0).wins()).isEqualTo(1);
	}

	@Test
	void 투찰률_표본이_없으면_평균은_0이_아니라_null_이다() {
		CollusionMatrix matrix = CollusionAnalysis.buildCollusionMatrix(List.of(
				bid("N1", "단독", participant("A사", "1", ""))));

		assertThat(matrix.companies().get(0).avgRate()).isNull();
		assertThat(matrix.companies().get(0).rateHistory()).isEmpty();
	}

	@Test
	void 짝은_의심도_내림차순으로_정렬된다() {
		CollusionMatrix matrix = CollusionAnalysis.buildCollusionMatrix(List.of(
				bid("N1", "1", participant("A사", "1", "90"), participant("B사", "2", "91")),
				bid("N2", "2", participant("C사", "1", "90"), participant("D사", "2", "91")),
				bid("N3", "3", participant("D사", "1", "90"), participant("C사", "2", "91"))));

		assertThat(matrix.pairs().get(0).a()).isEqualTo("C사");   // 2건 교대가 위로
		assertThat(matrix.pairs().get(0).total()).isEqualTo(2);
	}
}
