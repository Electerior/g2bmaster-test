package com.electerior.g2bmaster.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 낙찰결과 표본을 훑을 검색어 추출.
 *
 * <p>공고명 전체를 검색어로 쓰면 거의 언제나 0건이라는 실측에서 나온 기능이다 —
 * {@code "2026년 충남 청년 Job Planning Day 운영 용역"} → 0건 / {@code "서버"} → 2건.
 */
class AwardKeywordTest {

	@Test
	@DisplayName("품목분류명이 있으면 그것이 곧 품목명이다 — 공고명을 보지 않는다")
	void productClassNameWins() {
		assertThat(DealAnalysisService.awardKeyword("컴퓨터서버", "2026년 어쩌고 사업 서버 구매"))
				.isEqualTo("컴퓨터서버");
		assertThat(DealAnalysisService.awardKeyword("노트북컴퓨터", null)).isEqualTo("노트북컴퓨터");
	}

	@Test
	@DisplayName("공고명에서는 조달 관용어 직전 낱말을 캔다 — 품목이 거기 온다")
	void keywordFromNoticeName() {
		assertThat(DealAnalysisService.awardKeyword(null, "광학감시시스템(BRAHE) 자료처리용 서버"))
				.isEqualTo("서버");
		assertThat(DealAnalysisService.awardKeyword("", "[앵커사업] 공동활용 MLOps 플랫폼 구축 서버 구매"))
				.isEqualTo("서버");
		assertThat(DealAnalysisService.awardKeyword("", "2027학년도 인천은송중학교 신입생 교복 학교주관 구매"))
				.isEqualTo("교복");
	}

	@Test
	@DisplayName("괄호는 기호만 지우고 내용은 살린다 — 품목이 괄호 안에 있는 공고가 있다")
	void bracketsKeepContent() {
		assertThat(DealAnalysisService.awardKeyword("", "실험실습기자재(컴퓨터서버 2점, 공과대학) 교체 구매"))
				.isEqualTo("컴퓨터서버");
	}

	@Test
	@DisplayName("연도·수량·순번은 검색어가 되지 않는다")
	void numericTokensDropped() {
		assertThat(DealAnalysisService.awardKeyword("", "2026년 노트북 50대 구매")).isEqualTo("노트북");
		assertThat(DealAnalysisService.awardKeyword("", "3차 프린터 구입")).isEqualTo("프린터");
	}

	@Test
	@DisplayName("낱말 끝 조사를 떼어 검색어를 넓힌다")
	void trimsParticles() {
		assertThat(DealAnalysisService.awardKeyword("", "연구용 서버용 구매")).isEqualTo("서버");
	}

	@Test
	@DisplayName("캘 것이 없으면 빈 문자열 — 호출부가 조회를 건너뛴다")
	void emptyWhenNothingUsable() {
		assertThat(DealAnalysisService.awardKeyword(null, null)).isEmpty();
		assertThat(DealAnalysisService.awardKeyword("", "2026년 구매 사업")).isEmpty();
	}
}
