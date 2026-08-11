package com.electerior.g2bmaster.attachment;

import static org.assertj.core.api.Assertions.assertThat;

import com.electerior.g2bmaster.attachment.ParsedDocument.DocumentFormat;
import com.electerior.g2bmaster.attachment.SpecDocumentValidator.Verdict;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SpecDocumentValidatorTest {

	/** 규격서다운 본문. 길이·한글 비율 게이트를 넉넉히 넘긴다. */
	private static final String BODY =
			"\n품명 규격 단위 수량 비고\n서버 128GB 이상 대 2\n저장장치 4TB 이상 개 8\n".repeat(10);

	private static ParsedDocument doc(String text) {
		return new ParsedDocument("첨부.hwpx", DocumentFormat.HWPX, text, 1, false);
	}

	private static SpecFileSelector.Choice choice(String confidence) {
		return new SpecFileSelector.Choice(new SpecFileSelector.Candidate("첨부.hwpx", "", 30), confidence);
	}

	@Test
	@DisplayName("표제가 규격서 계열이면 통과 — 표제가 선택 점수보다 강한 신호다")
	void specTitleAccepted() {
		Verdict v = SpecDocumentValidator.validate(doc("규 격 서" + BODY), choice("estimated"));
		assertThat(v.accepted()).isTrue();
		assertThat(v.documentClass()).isEqualTo(DocumentTitleClassifier.SPEC);
		assertThat(v.reasons()).isEmpty();
	}

	@Test
	@DisplayName("공고문은 신뢰하지 않되 버리지도 않는다 — 본문에 규격을 품는 경우가 있다(실측 8건)")
	void noticeBecomesFallback() {
		Verdict v = SpecDocumentValidator.validate(doc("입찰공고" + BODY), choice("heuristic"));
		assertThat(v.disposition()).isEqualTo(SpecDocumentValidator.Disposition.FALLBACK);
		assertThat(v.accepted()).isFalse();
		assertThat(v.usable()).isTrue();
		assertThat(v.documentClass()).isEqualTo(DocumentTitleClassifier.NOTICE);
		assertThat(v.reasons()).containsExactly(SpecDocumentValidator.REASON_MAY_EMBED_SPEC);
	}

	@Test
	@DisplayName("계약조건·서약서·설명지침·제출서식은 하드 거부 — 실측 15건 중 단가를 낸 적이 없다")
	void structurallyNonSpecRejected() {
		for (String title : new String[] {"물품구매(제조)계약일반조건", "청렴계약이행서약서",
				"입찰유의서", "사용인감계"}) {
			Verdict v = SpecDocumentValidator.validate(doc(title + BODY), choice("confirmed"));
			assertThat(v.disposition())
					.as("%s 는 하드 거부여야 한다", title)
					.isEqualTo(SpecDocumentValidator.Disposition.REJECT);
			assertThat(v.usable()).isFalse();
			assertThat(v.reasons()).containsExactly(SpecDocumentValidator.REASON_WRONG_CLASS);
		}
	}

	@Test
	@DisplayName("표제를 못 읽었으면 신뢰하지 않는다 — 점수가 높아도 ACCEPT 가 아니다")
	void noTitleIsNeverTrusted() {
		String noTitle = "품명 수량 단위" + BODY;
		for (String conf : new String[] {"confirmed", "heuristic"}) {
			Verdict v = SpecDocumentValidator.validate(doc(noTitle), choice(conf));
			assertThat(v.accepted()).as("%s 여도 표제 없이는 신뢰하지 않는다", conf).isFalse();
			assertThat(v.usable()).isTrue();     // 넘기기는 한다 — 판단은 LLM 몫
			assertThat(v.reasons()).containsExactly(SpecDocumentValidator.REASON_NO_TITLE);
		}

		Verdict weak = SpecDocumentValidator.validate(doc(noTitle), choice("estimated"));
		assertThat(weak.disposition()).isEqualTo(SpecDocumentValidator.Disposition.FALLBACK);
		assertThat(weak.via()).isEqualTo("score/no-title");
		assertThat(weak.reasons()).containsExactly(SpecDocumentValidator.REASON_LOW_SCORE);
	}

	@Test
	@DisplayName("부처 고시는 설명지침으로 잡아 하드 거부한다 — 실측에서 규격서 행세를 했다")
	void ministryStandardRejected() {
		String notice = "(중소벤처기업부) 중소기업자간 경쟁제품 중 물품의 구매에 관한\n"
				+ "계약이행능력심사 세부기준\n"
				+ "[시행 2026. 7. 27.] [중소벤처기업부고시 제2026-51호]\n"
				+ "제1조(목적) 이 기준은 판로지원법 제7조에 따라" + BODY;
		Verdict v = SpecDocumentValidator.validate(doc(notice), choice("heuristic"));
		assertThat(v.documentClass()).isEqualTo(DocumentTitleClassifier.GUIDE);
		assertThat(v.usable()).isFalse();
	}

	@Test
	@DisplayName("추출이 사실상 실패한 문서는 fallback 도 아니다 — 읽을 것이 없으면 LLM 도 못 읽는다")
	void extractionFailures() {
		Verdict empty = SpecDocumentValidator.validate(null, choice("heuristic"));
		assertThat(empty.usable()).isFalse();
		assertThat(empty.reasons()).containsExactly(SpecDocumentValidator.REASON_EMPTY);

		Verdict tooShort = SpecDocumentValidator.validate(doc("규격서\n짧다"), choice("heuristic"));
		assertThat(tooShort.usable()).isFalse();
		assertThat(tooShort.reasons()).containsExactly(SpecDocumentValidator.REASON_TOO_SHORT);
	}

	@Test
	@DisplayName("목차 점선만 남은 추출 — 본문을 못 뽑은 것이다")
	void tocDustRejected() {
		String dust = "1. 개요 " + "·".repeat(400) + "\n2. 범위 " + ".".repeat(400) + "\n";
		Verdict v = SpecDocumentValidator.validate(doc(dust), choice("heuristic"));
		assertThat(v.usable()).isFalse();
		assertThat(v.reasons()).containsExactly(SpecDocumentValidator.REASON_TOC_DUST);
	}

	@Test
	@DisplayName("인코딩이 무너진 추출 — 한글도 영숫자도 남지 않았다")
	void encodingCollapseRejected() {
		Verdict v = SpecDocumentValidator.validate(doc("¶§±¤¢£¥×÷¬®°µ¶§±¤¢£¥×÷¬®°µ".repeat(20)),
				choice("heuristic"));
		assertThat(v.usable()).isFalse();
		assertThat(v.reasons()).containsExactly(SpecDocumentValidator.REASON_ENCODING);
	}

	@Test
	@DisplayName("순수 영문 데이터시트를 인코딩 붕괴로 오판하지 않는다 — 제조사 사양서가 이렇게 온다")
	void englishDatasheetSurvives() {
		String english = ("Model RTX A6000, VRAM 48GB GDDR6, Interface PCIe 4.0 x16, "
				+ "Power 300W, Dimensions 267mm x 112mm, Warranty 3 years.\n").repeat(6);
		assertThat(SpecDocumentValidator.isEncodingCollapsed(english)).isFalse();

		Verdict v = SpecDocumentValidator.validate(doc(english), choice("heuristic"));
		assertThat(v.usable()).isTrue();
	}

	// ── fallback 사슬 (walk) ────────────────────────────────────────────────────

	private static SpecDocumentValidator.Reviewable item(String title) {
		return new SpecDocumentValidator.Reviewable(doc(title + BODY), "heuristic");
	}

	@Test
	@DisplayName("최고점이 공고문이어도 뒤에 있는 진짜 규격서를 찾아낸다 — 걸어 내려가는 것이 핵심")
	void walkFindsSpecBehindNotice() {
		SpecDocumentValidator.Walk walk = SpecDocumentValidator.walk(
				List.of(item("입찰공고"), item("물품구매(제조)계약일반조건"), item("규 격 서")));
		assertThat(walk.found()).isTrue();
		assertThat(walk.index()).isEqualTo(2);
		assertThat(walk.verdict().accepted()).isTrue();
		assertThat(walk.verdict().documentClass()).isEqualTo(DocumentTitleClassifier.SPEC);
		assertThat(walk.examined()).isEqualTo(3);
	}

	@Test
	@DisplayName("규격서를 찾으면 즉시 멈춘다 — 뒤 후보를 헛돌지 않는다")
	void walkStopsAtFirstAccept() {
		SpecDocumentValidator.Walk walk = SpecDocumentValidator.walk(
				List.of(item("규 격 서"), item("시방서"), item("사양서")));
		assertThat(walk.index()).isZero();
		assertThat(walk.examined()).isEqualTo(1);
	}

	@Test
	@DisplayName("규격서가 없으면 최상위 fallback 으로 내려간다 — LLM 이 판단할 몫을 남긴다")
	void walkFallsBackToNotice() {
		SpecDocumentValidator.Walk walk = SpecDocumentValidator.walk(
				List.of(item("물품구매(제조)계약일반조건"), item("입찰공고"), item("청렴계약이행서약서")));
		assertThat(walk.found()).isTrue();
		assertThat(walk.index()).isEqualTo(1);   // 계약조건은 건너뛰고 공고문을 집는다
		assertThat(walk.verdict().disposition()).isEqualTo(SpecDocumentValidator.Disposition.FALLBACK);
		assertThat(walk.examined()).isEqualTo(3);
	}

	@Test
	@DisplayName("넘길 것이 하나도 없으면 못 찾았다고 답한다 — 공고 메타로만 진행하는 신호")
	void walkFindsNothing() {
		SpecDocumentValidator.Walk walk = SpecDocumentValidator.walk(
				List.of(item("물품구매(제조)계약일반조건"), item("입찰유의서"), item("사용인감계")));
		assertThat(walk.found()).isFalse();
		assertThat(walk.index()).isEqualTo(-1);
		assertThat(walk.verdict()).isNull();

		assertThat(SpecDocumentValidator.walk(List.of()).found()).isFalse();
		assertThat(SpecDocumentValidator.walk(null).found()).isFalse();
	}

	@Test
	@DisplayName("사람이 지목한 첨부는 문서종으로 거부하지 않는다 — 사람이 공고를 읽었다")
	void userSelectedOverridesClass() {
		List<SpecDocumentValidator.Reviewable> pick = List.of(item("물품구매(제조)계약일반조건"));

		// 자동 선택이면 하드 거부다.
		assertThat(SpecDocumentValidator.walk(pick).found()).isFalse();

		// 사람이 지목하면 넘기되 신뢰하지는 않는다.
		SpecDocumentValidator.Walk walk = SpecDocumentValidator.walk(pick, true);
		assertThat(walk.found()).isTrue();
		assertThat(walk.verdict().disposition()).isEqualTo(SpecDocumentValidator.Disposition.FALLBACK);
		assertThat(walk.verdict().accepted()).isFalse();
		assertThat(walk.verdict().reasons()).contains(SpecDocumentValidator.REASON_USER_SELECTED);
	}

	@Test
	@DisplayName("사람이 골랐어도 추출이 실패한 파일은 넘기지 않는다 — 읽을 것이 없는 건 판단의 문제가 아니다")
	void userSelectionDoesNotOverrideExtraction() {
		List<SpecDocumentValidator.Reviewable> pick =
				List.of(new SpecDocumentValidator.Reviewable(doc("규격서\n짧다"), "heuristic"));
		assertThat(SpecDocumentValidator.walk(pick, true).found()).isFalse();
	}

	@Test
	@DisplayName("잘린 텍스트는 막지 않되 경고로 남긴다 — 뒤쪽 품목표가 날아갔을 수 있다")
	void truncatedIsWarnedNotBlocked() {
		ParsedDocument truncated =
				new ParsedDocument("규격서.hwpx", DocumentFormat.HWPX, "규 격 서" + BODY, 1, true);
		Verdict v = SpecDocumentValidator.validate(truncated, choice("heuristic"));
		assertThat(v.accepted()).isTrue();
		assertThat(v.reasons()).containsExactly(SpecDocumentValidator.REASON_TRUNCATED);
	}
}
