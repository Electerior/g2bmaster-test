package com.electerior.g2bmaster.attachment;

import static org.assertj.core.api.Assertions.assertThat;

import com.electerior.g2bmaster.attachment.SpecFileSelector.Candidate;
import com.electerior.g2bmaster.attachment.SpecFileSelector.Choice;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SpecFileSelectorTest {

	@Test
	@DisplayName("isSpecFile — 파일명에 '규격서'가 있어야 한다")
	void isSpecFile() {
		assertThat(SpecFileSelector.isSpecFile("물품규격서.hwp")).isTrue();
		assertThat(SpecFileSelector.isSpecFile("입찰공고문.pdf")).isFalse();
		assertThat(SpecFileSelector.isSpecFile(null)).isFalse();
	}

	@Test
	@DisplayName("'규격입찰 설명서'는 규격서가 아니다 — 2026-08-03 오선택 사례")
	void explanatoryDocumentOutranksNothing() {
		assertThat(SpecFileSelector.specFilenameRank("규격입찰 설명서.hwp")).isEqualTo(3);
		assertThat(SpecFileSelector.specFilenameRank("물품 규격서.hwp")).isZero();
	}

	@Test
	@DisplayName("파일명 순위 — 낮을수록 규격서 유력")
	void filenameRanking() {
		assertThat(SpecFileSelector.specFilenameRank("과업지시서.hwp")).isZero();
		assertThat(SpecFileSelector.specFilenameRank("시방서.pdf")).isZero();
		assertThat(SpecFileSelector.specFilenameRank("SPEC_v2.docx")).isZero();
		assertThat(SpecFileSelector.specFilenameRank("요구사항 정리.hwp")).isEqualTo(1);
		assertThat(SpecFileSelector.specFilenameRank("입찰공고.pdf")).isEqualTo(2);
		assertThat(SpecFileSelector.specFilenameRank("첨부.hwp")).isEqualTo(1);
		assertThat(SpecFileSelector.specFilenameRank("청렴서약서.hwp")).isEqualTo(3);
	}

	@Test
	@DisplayName("'내역서'는 규격서 취급하지 않는다 — 공사 물량 산출서가 대부분이라 일부러 뺐다")
	void quantityScheduleIsNotSpec() {
		assertThat(SpecFileSelector.specFilenameRank("물량내역서.xlsx")).isNotZero();
	}

	@Test
	@DisplayName("빈 문서는 0점")
	void emptyScoresZero() {
		assertThat(SpecFileSelector.specContentScore("", "규격서.hwp")).isZero();
		assertThat(SpecFileSelector.specContentScore("   ", "규격서.hwp")).isZero();
	}

	@Test
	@DisplayName("짧은 문서는 감점, 표와 단위가 많으면 가점")
	void contentScoring() {
		String thin = "규격 안내";
		String rich = ("| 품명 | 규격 | 수량 | 단위 |\n".repeat(30))
				+ "납품 모델 제조사 성능 인증 재질 치수 용량\n".repeat(20)
				+ "128GB 메모리 2대, 4TB 저장장치 8개, 16코어 CPU 1식\n".repeat(15);

		assertThat(SpecFileSelector.specContentScore(thin, "규격서.hwp"))
				.isLessThan(SpecFileSelector.specContentScore(rich, "규격서.hwp"));
		assertThat(SpecFileSelector.specContentScore(rich, "규격서.hwp")).isPositive();
	}

	@Test
	@DisplayName("같은 내용이라도 파일명이 '설명서'면 크게 감점된다")
	void filenamePenaltyApplies() {
		String body = "규격 사양 수량 단위 납품\n".repeat(50);

		int asSpec = SpecFileSelector.specContentScore(body, "물품규격서.hwp");
		int asExplanation = SpecFileSelector.specContentScore(body, "규격입찰 설명서.hwp");

		assertThat(asSpec - asExplanation).isEqualTo(40);   // +15 vs -25
	}

	@Test
	@DisplayName("chooseSpec — 후보가 없으면 none")
	void chooseNothing() {
		Choice choice = SpecFileSelector.chooseSpec(List.of(), null, 0);
		assertThat(choice.chosen()).isNull();
		assertThat(choice.confidence()).isEqualTo("none");
	}

	@Test
	@DisplayName("chooseSpec — AI 판정이 없으면 최고점을 heuristic 으로 고른다")
	void chooseHeuristicWithoutAi() {
		Choice choice = SpecFileSelector.chooseSpec(
				List.of(new Candidate("a.hwp", "", 10), new Candidate("b.hwp", "", 50)), null, 5);

		assertThat(choice.chosen().name()).isEqualTo("b.hwp");
		assertThat(choice.confidence()).isEqualTo("heuristic");
	}

	@Test
	@DisplayName("chooseSpec — AI 가 맞다고 하면 confirmed")
	void confirmedByAi() {
		Choice choice = SpecFileSelector.chooseSpec(
				List.of(new Candidate("b.hwp", "", 50)), true, 5);
		assertThat(choice.confidence()).isEqualTo("confirmed");
	}

	@Test
	@DisplayName("chooseSpec — AI 가 아니라고 하면 차점자로 내려간다")
	void fallsBackWhenAiRejectsTop() {
		Choice choice = SpecFileSelector.chooseSpec(
				List.of(new Candidate("top.hwp", "", 50), new Candidate("next.hwp", "", 30)), false, 5);

		assertThat(choice.chosen().name()).isEqualTo("next.hwp");
		assertThat(choice.confidence()).isEqualTo("heuristic");
	}

	@Test
	@DisplayName("chooseSpec — 차점자가 기준 미달이면 최고점을 estimated 로 유지")
	void keepsTopWhenNoAlternative() {
		Choice choice = SpecFileSelector.chooseSpec(
				List.of(new Candidate("top.hwp", "", 50), new Candidate("next.hwp", "", 1)), false, 5);

		assertThat(choice.chosen().name()).isEqualTo("top.hwp");
		assertThat(choice.confidence()).isEqualTo("estimated");
	}

	@Test
	@DisplayName("chooseSpec — 최고점이 기준 미달이면 estimated")
	void estimatedWhenBelowMinScore() {
		Choice choice = SpecFileSelector.chooseSpec(List.of(new Candidate("a.hwp", "", 2)), true, 10);
		assertThat(choice.confidence()).isEqualTo("estimated");
	}

	// ── HYBRID: 밀도 · 부품명 유사도 · 결합 선택 ─────────────────────────────────

	@Test
	@DisplayName("density — 표지 한 장은 바닥선 미달, 탭 표 본문은 통과(HWPX 탭 표를 파이프 대신 센다)")
	void densityFloor() {
		String thinCover = "제안요청 설명 자료입니다. 자세한 내용은 붙임 참고.";
		String tabBody = "품명\t규격\t수량\t단위\n".repeat(40);   // HWPX 는 셀을 탭으로 뽑는다

		assertThat(SpecFileSelector.passesDensityFloor(SpecFileSelector.density(thinCover))).isFalse();
		assertThat(SpecFileSelector.passesDensityFloor(SpecFileSelector.density(tabBody))).isTrue();
		// 파이프 전용 내용 점수는 탭 표를 못 세지만, 밀도는 탭 행을 센다.
		assertThat(SpecFileSelector.density(tabBody).tableRowDensity()).isPositive();
	}

	@Test
	@DisplayName("partNameSimilarity — 문서 전반에 퍼진 부품표 > 이름만 흘린 공고문 > 무관 문서(0)")
	void partNameSimilarity() {
		String spread = "CPU 제온 16코어 프로세서\n".repeat(20) + "GPU RTX 5090 그래픽카드\n".repeat(20)
				+ "메모리 DDR5 512GB\n".repeat(20) + "SSD 4TB NVMe 저장장치\n".repeat(20);
		String singleMention = "GPU 서버 구매 공고입니다.\n" + "일반 안내 문구입니다.\n".repeat(60);
		String unrelated = "사무용 책상 1800x900 목재 상판 회의용 의자\n".repeat(30);

		double spreadSim = SpecFileSelector.partNameSimilarity(spread);
		double mentionSim = SpecFileSelector.partNameSimilarity(singleMention);

		assertThat(spreadSim).isGreaterThan(mentionSim);
		assertThat(mentionSim).isGreaterThan(0);
		assertThat(SpecFileSelector.partNameSimilarity(unrelated)).isZero();
		assertThat(SpecFileSelector.partNameSimilarity("", 4)).isZero();
	}

	@Test
	@DisplayName("chooseSpecHybrid — chooseSpec 과 같은 confidence 규칙(none/heuristic/confirmed/estimated/거부-fallback)")
	void hybridParity() {
		assertThat(SpecFileSelector.chooseSpecHybrid(List.of(), null, 0).confidence()).isEqualTo("none");

		Choice heuristic = SpecFileSelector.chooseSpecHybrid(
				List.of(new Candidate("a.hwp", "", 10), new Candidate("b.hwp", "", 50)), null, 5);
		assertThat(heuristic.chosen().name()).isEqualTo("b.hwp");
		assertThat(heuristic.confidence()).isEqualTo("heuristic");

		assertThat(SpecFileSelector.chooseSpecHybrid(List.of(new Candidate("b.hwp", "", 50)), true, 5)
				.confidence()).isEqualTo("confirmed");

		Choice rejected = SpecFileSelector.chooseSpecHybrid(
				List.of(new Candidate("top.hwp", "", 50), new Candidate("next.hwp", "", 30)), false, 5);
		assertThat(rejected.chosen().name()).isEqualTo("next.hwp");
		assertThat(rejected.confidence()).isEqualTo("heuristic");

		assertThat(SpecFileSelector.chooseSpecHybrid(List.of(new Candidate("a.hwp", "", 2)), true, 10)
				.confidence()).isEqualTo("estimated");
	}

	@Test
	@DisplayName("chooseSpecHybrid — 내용 점수가 같으면 부품명 유사도가 높은 쪽을 고른다")
	void hybridSimilarityTieBreak() {
		String withParts = "CPU 프로세서\n".repeat(10) + "GPU 그래픽카드\n".repeat(10)
				+ "메모리 DDR5\n".repeat(10) + "SSD 저장장치\n".repeat(10);
		Candidate parts = new Candidate("parts.hwp", withParts, 20);   // 같은 내용 점수 20
		Candidate plain = new Candidate("plain.hwp", "일반 문서 내용입니다.\n".repeat(40), 20);

		Choice choice = SpecFileSelector.chooseSpecHybrid(List.of(plain, parts), null, 5);
		assertThat(choice.chosen().name()).isEqualTo("parts.hwp");
	}

	@Test
	@DisplayName("chooseSpecHybrid — '규격입찰 설명서' 얇은 미끼를 진짜 규격서가 이긴다(2026-08-03 회귀)")
	void hybridDecoyRegression() {
		String realSpec = "품명\t규격\t수량\t단위\n" + "CPU 제온 16코어 프로세서\n".repeat(10)
				+ "GPU RTX 5090 그래픽카드\n".repeat(10) + "메모리 DDR5 512GB\n".repeat(10)
				+ "SSD 4TB NVMe 저장장치\n".repeat(10);
		Candidate real = Candidate.of("물품규격서.hwp", realSpec);
		Candidate decoy = Candidate.of("규격입찰 설명서.hwp", "규격입찰 설명서\n입찰 규격 사양 안내");

		Choice choice = SpecFileSelector.chooseSpecHybrid(List.of(decoy, real), null, 20);
		assertThat(choice.chosen().name()).isEqualTo("물품규격서.hwp");
		assertThat(choice.confidence()).isEqualTo("heuristic");
	}
}
