package com.electerior.g2bmaster.index;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * upsert SQL 의 모양.
 *
 * <p>여기서 검사하는 셋은 전부 <b>조용히 틀리는</b> 종류의 버그다. SQL 은 정상 실행되고,
 * 로그도 깨끗하고, 며칠 뒤 화면의 값이 이상하다는 제보로만 드러난다. 그래서 실행 결과가
 * 아니라 SQL 문자열 자체를 계약으로 고정한다.
 */
class BidNoticeUpsertSqlTest {

	private static final String SQL = BidNoticeIndexRepository.buildUpsertSql();

	/**
	 * MySQL 은 {@code ON DUPLICATE KEY UPDATE} 의 대입을 왼쪽부터 차례로 수행하고,
	 * 뒤 대입은 앞 대입의 <b>결과</b>를 본다. 차수를 먼저 올리면 그 뒤 컬럼들의 가드가
	 * 전부 '새 차수 >= 새 차수' = 항상 참이 되어 무력해진다.
	 */
	@Test
	@DisplayName("notice_order 갱신이 맨 마지막이다 — 순서가 곧 정확성이다")
	void noticeOrderIsAssignedLast() {
		int orderAssignment = SQL.indexOf("notice_order = IF(");
		assertThat(orderAssignment).isPositive();

		// 그 뒤로는 어떤 컬럼 대입도 없어야 한다.
		String tail = SQL.substring(orderAssignment + "notice_order = IF(".length());
		assertThat(tail).doesNotContain(" = IF(");
	}

	@Test
	@DisplayName("모든 갱신에 차수 역행 방지 가드가 붙는다")
	void everyUpdateIsGuarded() {
		String updates = SQL.substring(SQL.indexOf("ON DUPLICATE KEY UPDATE"));
		long assignments = updates.lines().filter(line -> line.contains(" = IF(")).count();
		long guards = updates.lines()
				.filter(line -> line.contains("new.notice_order >= bid_notice.notice_order"))
				.count();

		assertThat(assignments).isPositive();
		assertThat(guards).isEqualTo(assignments);
	}

	/**
	 * AI 요약은 별도 파이프라인이 채운다. 적재기가 덮으면 재적재 때마다 요약이 사라져,
	 * 사용자에게는 "가끔 요약이 없어지는" 현상으로 보인다.
	 */
	@Test
	@DisplayName("ai_summary 는 갱신하지 않는다 — 재적재해도 살아남아야 한다")
	void aiSummaryIsNeverOverwritten() {
		String updates = SQL.substring(SQL.indexOf("ON DUPLICATE KEY UPDATE"));

		assertThat(updates).doesNotContain("ai_summary");
	}

	/**
	 * 지역은 입찰공고 목록이 아니라 별도 오퍼레이션에서 온다. 입찰공고 적재분은 빈 문자열을
	 * 들고 오는데, 그게 지역 적재분이 채워 둔 값을 지우면 지역 필터가 조용히 빈다.
	 */
	@Test
	@DisplayName("region 은 빈 값으로 덮지 않는다")
	void regionIsNotClobberedByBlank() {
		assertThat(SQL).contains("new.region <> ''");
	}

	@Test
	@DisplayName("INSERT 컬럼과 VALUES 자리표시자 개수가 맞는다")
	void insertColumnsMatchPlaceholders() {
		String columns = SQL.substring(SQL.indexOf('(') + 1, SQL.indexOf(')'));
		String values = SQL.substring(SQL.indexOf("VALUES (") + "VALUES (".length(),
				SQL.indexOf(')', SQL.indexOf("VALUES (")));

		assertThat(columns.split(",")).hasSameSizeAs(values.split(","));
		// ERD 22개 중 ai_summary(적재 제외)와 updated_at(NOW())을 뺀 20개
		// + 공고명 1개 + 기관명 2개(V8 — dm_institution 조인이 성립하지 않아 색인에 담는다).
		assertThat(columns.split(",")).hasSize(23);
	}

	@Test
	@DisplayName("ERD 의 모든 컬럼이 INSERT 에 들어 있다")
	void allErdColumnsPresent() {
		String columns = SQL.substring(SQL.indexOf('(') + 1, SQL.indexOf(')'));

		assertThat(columns).contains("id", "notice_order", "notice_name", "category", "state",
				"business_division", "region",
				"demand_institution_code", "demand_institution_name",
				"notice_institution_code", "notice_institution_name",
				"before_spec_rgst_no", "product_list", "detail_product_code", "lowest_bid_rate",
				"price_detail", "created_date", "close_date", "officer_name", "officer_contact",
				"notice_body", "attachment_urls", "source_url");
	}
}
