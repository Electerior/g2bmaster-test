package com.electerior.g2bmaster.index;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 검색 조건 조립.
 *
 * <p>SQL 문자열을 직접 들여다보는 테스트다. 보통은 구현 세부라 보지 않지만, 여기서는
 * <b>SQL 의 모양 자체가 계약</b>이다 — 한 글자 검색어가 MATCH 로 새면 결과가 조용히 0건이 되고,
 * LIKE 이스케이프가 빠지면 사용자가 친 {@code _} 가 와일드카드가 된다. 둘 다 화면에서는
 * '검색이 좀 이상하다'로만 보여서 추적이 어렵다.
 */
class BidNoticeQueryBuilderTest {

	@Test
	@DisplayName("조건이 없으면 WHERE 절도 없다")
	void empty() {
		BidNoticeQueryBuilder.Where where = new BidNoticeQueryBuilder().build();

		assertThat(where.sql()).isEmpty();
		assertThat(where.params()).isEmpty();
		assertThat(where.fullText()).isFalse();
		assertThat(where.relevanceSelect()).isEqualTo(", 0 AS relevance");
	}

	@Test
	@DisplayName("AND 낱말은 MATCH … BOOLEAN MODE 의 '+' 가 된다")
	void andTermsBecomeRequired() {
		BidNoticeQueryBuilder.Where where = new BidNoticeQueryBuilder()
				.keywords(List.of("서버", "구매"), List.of(), List.of())
				.build();

		assertThat(where.sql()).contains("MATCH(n.notice_name, n.notice_body) AGAINST (:ftQuery IN BOOLEAN MODE)");
		assertThat(where.params().get("ftQuery")).isEqualTo("+\"서버\" +\"구매\"");
		assertThat(where.fullText()).isTrue();
	}

	@Test
	@DisplayName("OR 는 기호 없이, NOT 은 '-' 로 붙는다")
	void orAndNotTerms() {
		BidNoticeQueryBuilder.Where where = new BidNoticeQueryBuilder()
				.keywords(List.of("서버"), List.of("스토리지"), List.of("임대"))
				.build();

		assertThat(where.params().get("ftQuery")).isEqualTo("+\"서버\" \"스토리지\" -\"임대\"");
	}

	/**
	 * MySQL 불리언 모드에서 {@code -foo} 만 있는 식은 <b>아무 행도 돌려주지 않는다</b>.
	 * 사용자가 기대하는 것은 "그것만 뺀 전체"이므로 MATCH 를 쓰지 않고 LIKE 로 뺀다.
	 */
	@Test
	@DisplayName("제외 낱말만 있으면 MATCH 를 쓰지 않는다 — 그러면 0건이 되기 때문")
	void notOnlyAvoidsMatch() {
		BidNoticeQueryBuilder.Where where = new BidNoticeQueryBuilder()
				.keywords(List.of(), List.of(), List.of("임대"))
				.build();

		assertThat(where.sql()).doesNotContain("MATCH");
		assertThat(where.sql()).contains("NOT (n.notice_name LIKE");
		assertThat(where.fullText()).isFalse();
	}

	/**
	 * ngram 토큰 크기가 2라 한 글자는 MATCH 가 통째로 버린다 — 0건이 아니라 '조용히 무시'다.
	 */
	@Test
	@DisplayName("한 글자 검색어는 MATCH 가 아니라 LIKE 로 떨어진다")
	void singleCharacterFallsBackToLike() {
		BidNoticeQueryBuilder.Where where = new BidNoticeQueryBuilder()
				.keywords(List.of("차"), List.of(), List.of())
				.build();

		assertThat(where.sql()).doesNotContain("MATCH");
		assertThat(where.sql()).contains("n.notice_name LIKE");
		assertThat(where.params()).containsValue("%차%");
	}

	@Test
	@DisplayName("한 글자와 두 글자가 섞이면 각자 제 경로로 간다")
	void mixedLengthTerms() {
		BidNoticeQueryBuilder.Where where = new BidNoticeQueryBuilder()
				.keywords(List.of("차", "서버"), List.of(), List.of())
				.build();

		assertThat(where.params().get("ftQuery")).isEqualTo("+\"서버\"");
		assertThat(where.params()).containsValue("%차%");
		assertThat(where.fullText()).isTrue();
	}

	/**
	 * 사용자가 친 {@code +}·{@code (} 가 연산자로 읽히면 MySQL 이 문법 오류를 내고 검색이 500 이 된다.
	 */
	@Test
	@DisplayName("불리언 연산자 문자는 따옴표 안에 갇힌다")
	void operatorCharactersAreQuoted() {
		BidNoticeQueryBuilder.Where where = new BidNoticeQueryBuilder()
				.keywords(List.of("A+B", "(주)한국"), List.of(), List.of())
				.build();

		assertThat(where.params().get("ftQuery")).isEqualTo("+\"A+B\" +\"(주)한국\"");
	}

	@Test
	@DisplayName("낱말 안의 큰따옴표는 지운다 — 이스케이프 문법이 없다")
	void embeddedQuotesRemoved() {
		BidNoticeQueryBuilder.Where where = new BidNoticeQueryBuilder()
				.keywords(List.of("\"서버\""), List.of(), List.of())
				.build();

		assertThat(String.valueOf(where.params().get("ftQuery"))).isEqualTo("+\"서버\"");
	}

	@Test
	@DisplayName("LIKE 와일드카드는 무력화된다 — 사용자가 친 _ 는 한 글자 와일드카드가 아니다")
	void likeWildcardsEscaped() {
		BidNoticeQueryBuilder.Where where = new BidNoticeQueryBuilder()
				.region("A_B%C")
				.build();

		assertThat(where.params()).containsValue("%A!_B!%C%");
		assertThat(where.sql()).contains("ESCAPE '!'");
	}

	/**
	 * 지역 제한이 없는 공고(=전국)는 어떤 지역으로 좁혀도 함께 나와야 한다.
	 * 서울 업체가 참가할 수 있는 전국 공고가 빠지는 편이 훨씬 큰 손해다.
	 */
	@Test
	@DisplayName("지역 필터는 '전국(빈 값)' 공고를 함께 남긴다")
	void regionKeepsNationwide() {
		BidNoticeQueryBuilder.Where where = new BidNoticeQueryBuilder().region("경기도").build();

		assertThat(where.sql()).contains("OR n.region = ''");
	}

	/**
	 * 조달청이 대행 공고하는 건은 공고기관이 조달청이고 수요기관이 실제 발주처다.
	 * 한쪽만 보면 그 건이 통째로 빠진다.
	 */
	@Test
	@DisplayName("발주기관명은 공고기관·수요기관 둘 다에서 찾는다")
	void institutionNameChecksBothSides() {
		BidNoticeQueryBuilder.Where where = new BidNoticeQueryBuilder()
				.institutionName("평택시")
				.build();

		assertThat(where.sql())
				.contains("n.notice_institution_name LIKE")
				.contains("n.demand_institution_name LIKE");
		// dm_institution 조인은 더 이상 쓰지 않는다(V8) — 조인 별칭이 남아 있으면 SQL 이 깨진다.
		assertThat(where.sql()).doesNotContain("ni.instt_nm").doesNotContain("di.instt_nm");
		assertThat(where.params()).containsValue("%평택시%");
	}

	@Test
	@DisplayName("세부품명번호는 접두 일치라 상위 분류로도 훑을 수 있다")
	void detailProductCodeIsPrefix() {
		BidNoticeQueryBuilder.Where where = new BidNoticeQueryBuilder().detailProductCode("4110").build();

		assertThat(where.params()).containsValue("4110%");
	}

	/** 스위퍼는 주기적으로 도는 것이라, 방금 마감된 건이 아직 '입찰'로 남아 있을 수 있다. */
	@Test
	@DisplayName("'마감 전만'은 category 가 아니라 마감일시를 본다")
	void activeOnlyChecksCloseDateNotCategory() {
		BidNoticeQueryBuilder.Where where = new BidNoticeQueryBuilder().activeOnly(true).build();

		assertThat(where.sql()).contains("n.close_date IS NULL OR n.close_date >= NOW(6)");
		assertThat(where.sql()).doesNotContain("category");
	}

	@Test
	@DisplayName("필터가 여럿이면 AND 로 이어진다")
	void filtersCombineWithAnd() {
		BidNoticeQueryBuilder.Where where = new BidNoticeQueryBuilder()
				.category(NoticeCategory.입찰)
				.businessDivision(BusinessDivision.공사)
				.state(NoticeState.취소)
				.build();

		assertThat(where.sql()).startsWith("\n WHERE ");
		assertThat(where.sql()).contains("AND");
		assertThat(where.params()).containsValues("입찰", "공사", "취소");
	}

	@Test
	@DisplayName("null 필터는 조건을 만들지 않는다")
	void nullFiltersSkipped() {
		BidNoticeQueryBuilder.Where where = new BidNoticeQueryBuilder()
				.category(null)
				.region("  ")
				.institutionName(null)
				.estimatedPriceBetween(null, null)
				.build();

		assertThat(where.sql()).isEmpty();
	}

	@Test
	@DisplayName("금액 구간은 price_detail JSON 에서 뽑는다")
	void amountRangeReadsJson() {
		BidNoticeQueryBuilder.Where where = new BidNoticeQueryBuilder()
				.estimatedPriceBetween(1_000_000L, 5_000_000L)
				.build();

		assertThat(where.sql()).contains("JSON_EXTRACT(n.price_detail, '$.estimatedPrice')");
		assertThat(where.params()).containsEntry("minAmount", 1_000_000L)
				.containsEntry("maxAmount", 5_000_000L);
	}

	@Test
	@DisplayName("낱말 수 상한을 넘기지 않는다")
	void termsCapped() {
		List<String> many = List.of("가가", "나나", "다다", "라라", "마마", "바바", "사사", "아아", "자자", "차차");

		BidNoticeQueryBuilder.Where where = new BidNoticeQueryBuilder()
				.keywords(many, List.of(), List.of())
				.build();

		assertThat(String.valueOf(where.params().get("ftQuery")).split("\\+")).hasSizeLessThanOrEqualTo(9);
	}
}
