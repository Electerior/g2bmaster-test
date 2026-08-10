package com.electerior.g2bmaster.index;

import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

/**
 * 공고 검색 색인 저장소 — {@code bid_notice} 에 대한 모든 SQL.
 *
 * <p><b>JPA 엔티티가 없는 이유.</b> 이 테이블에 하는 일은 둘뿐이다: (1) 적재기의 배치 upsert,
 * (2) 검색의 동적 SQL. 어느 쪽도 엔티티 수명주기를 타지 않는다 — upsert 를 {@code save()} 로
 * 하면 행마다 SELECT 가 한 번씩 더 나가고(수천 건이면 그게 적재 시간의 대부분이다), 검색은
 * 필터 조합이 열 가지가 넘어 Criteria API 로 쓰면 SQL 을 읽을 수 없게 된다.
 * {@code SavedNoticeRepository} 가 같은 이유로 같은 선택을 했다.
 *
 * <p>대신 스키마 계약은 {@code BidNoticeIndexRepositoryTest} 가 지킨다 — 전 컬럼을 SELECT 해
 * 마이그레이션과 코드가 어긋나면 테스트가 먼저 깨진다.
 */
@Repository
public class BidNoticeIndexRepository {

	/**
	 * 검색 한 페이지의 상한. {@code SearchCriteria.MAX_PER_PAGE} 와 같은 값이며 이유도 같다 —
	 * 이 위로는 응답이 수십 MB가 되어 브라우저가 먼저 죽는다.
	 */
	public static final int MAX_LIMIT = 500;

	/**
	 * 목록에 실어 보내는 컬럼.
	 *
	 * <p>{@code notice_body} 가 <b>통째로는 빠져 있다</b>. 검색 대상 텍스트라 길고(수 KB),
	 * 목록 20건이면 그것만으로 응답이 수백 KB가 된다. 대신 미리보기 300자만 잘라 보내고
	 * 전문은 상세 조회({@link #findOne})에서만 준다.
	 */
	private static final String LIST_COLUMNS = """
			n.id, n.notice_order, n.notice_name, n.category, n.state, n.business_division,
			n.region, n.demand_institution_code, n.demand_institution_name,
			n.notice_institution_code, n.notice_institution_name, n.before_spec_rgst_no,
			n.product_list, n.detail_product_code, n.lowest_bid_rate, n.price_detail,
			n.created_date, n.close_date, n.updated_at, n.officer_name, n.officer_contact,
			n.ai_summary, n.attachment_urls, n.source_url,
			LEFT(COALESCE(n.notice_body, ''), 300) AS body_preview
			""";

	/**
	 * 조인이 없다 — 기관명이 색인 안에 있기 때문이다({@code V8} 마이그레이션 주석 참고).
	 *
	 * <p>원래는 {@code dm_institution} 을 두 번 LEFT JOIN 해 이름을 붙였다. 그 표가 비어
	 * 있고(0행) 채울 API 마저 폐기된 데다, 사전규격은 애초에 기관코드가 없어 조인할 키가
	 * 없었다. 결과는 화면의 발주기관 칸이 코드(5795000)이거나 공백인 것이었다.
	 * 기관명은 공고 응답에 매번 같이 오므로 적재 때 그대로 담는 편이 정확하고 싸다.
	 */
	private static final String LIST_FROM = """
			  FROM bid_notice n
			""";

	/**
	 * upsert 가 갱신하는 컬럼.
	 *
	 * <p><b>{@code ai_summary} 와 {@code id} 가 없는 것이 핵심이다.</b> AI 요약은 별도
	 * 파이프라인이 채우므로 재적재가 지워서는 안 된다. 목록에 없는 컬럼은 최초 INSERT 때만
	 * 값이 들어가고 그 뒤로는 적재기가 건드리지 않는다.
	 *
	 * <p>{@code notice_order} 도 여기 없다 — 아래 {@link #buildUpsertSql()} 이 <b>맨 마지막에</b>
	 * 따로 붙인다. 이유는 그쪽 주석 참고.
	 */
	private static final List<String> UPSERT_COLUMNS = List.of(
			"notice_name", "category", "state", "business_division",
			"demand_institution_code", "demand_institution_name",
			"notice_institution_code", "notice_institution_name", "before_spec_rgst_no",
			"product_list", "detail_product_code", "lowest_bid_rate", "price_detail",
			"created_date", "close_date", "officer_name", "officer_contact",
			"notice_body", "attachment_urls", "source_url");

	private final NamedParameterJdbcTemplate jdbc;

	public BidNoticeIndexRepository(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	// ── 적재 ────────────────────────────────────────────────────────────────

	/**
	 * 배치 upsert. 이미 있는 공고번호는 <b>차수가 같거나 높을 때만</b> 덮어쓴다.
	 *
	 * @return 영향받은 행 수 합계(MySQL 은 INSERT 를 1, UPDATE 를 2로 세므로 건수와 다르다)
	 */
	public int upsertAll(List<BidNoticeRow> rows) {
		if (rows == null || rows.isEmpty()) {
			return 0;
		}
		SqlParameterSource[] batch = rows.stream().map(BidNoticeIndexRepository::bind)
				.toArray(SqlParameterSource[]::new);
		int[] affected = jdbc.batchUpdate(buildUpsertSql(), batch);
		int total = 0;
		for (int count : affected) {
			// executeBatch 는 건별 결과를 모를 때 SUCCESS_NO_INFO(-2)를 준다. 음수를 그대로
			// 더하면 "-2건 색인" 같은 로그가 나오므로 0으로 접는다.
			total += Math.max(count, 0);
		}
		return total;
	}

	/**
	 * upsert SQL.
	 *
	 * <p>두 가지가 이 SQL 의 전부다.
	 *
	 * <p><b>(1) 차수 역행 방지.</b> ERD 의 PK 는 공고번호 하나라 한 공고에 행이 하나뿐이고
	 * 그 행은 최신 차수를 담아야 한다. 그런데 적재는 날짜창을 나눠 병렬로 도는 탓에 000차가
	 * 001차보다 <em>나중에</em> 도착하는 일이 실제로 있다. 그대로 두면 정정 전 내용이 정정 후를
	 * 덮어써 화면이 낡은 마감일시를 보여준다. 그래서 모든 갱신에
	 * {@code IF(new.notice_order >= bid_notice.notice_order, …)} 를 건다.
	 *
	 * <p><b>(2) {@code notice_order} 를 맨 마지막에 갱신한다.</b> MySQL 은
	 * {@code ON DUPLICATE KEY UPDATE} 의 대입을 <b>왼쪽부터 차례로</b> 수행하고, 뒤 대입은 앞
	 * 대입의 결과를 본다. 차수를 먼저 올려 버리면 그 다음 컬럼들의 비교가 이미 새 차수끼리의
	 * 비교가 되어 가드가 통째로 무력해진다 — 항상 참이 된다. 순서가 곧 정확성이다.
	 *
	 * <p><b>(3) {@code region} 만 '빈 값으로 덮지 않기' 규칙이 하나 더 붙는다.</b> 참가가능지역은
	 * 입찰공고 목록이 아니라 <b>별도 오퍼레이션</b>에서 온다. 입찰공고 적재분은 지역을 모르므로
	 * 빈 문자열을 들고 오는데, 그게 지역 적재분이 채워 둔 값을 지우면 지역 필터가 조용히
	 * 비어 버린다. 값이 있을 때만 덮어쓴다.
	 */
	static String buildUpsertSql() {
		String columns = String.join(", ", allInsertColumns());
		String values = allInsertColumns().stream().map(c -> ":" + toCamel(c)).reduce((a, b) -> a + ", " + b)
				.orElseThrow();

		StringBuilder updates = new StringBuilder();
		for (String column : UPSERT_COLUMNS) {
			updates.append("  ").append(column).append(" = IF(")
					.append(guard()).append(", new.").append(column)
					.append(", bid_notice.").append(column).append("),\n");
		}
		// 지역: 가드 + '빈 값이 아닐 것'.
		updates.append("  region = IF(").append(guard())
				.append(" AND new.region <> '', new.region, bid_notice.region),\n");
		updates.append("  updated_at = IF(").append(guard())
				.append(", NOW(6), bid_notice.updated_at),\n");
		// 반드시 마지막 — 위 (2) 참고.
		updates.append("  notice_order = IF(").append(guard())
				.append(", new.notice_order, bid_notice.notice_order)");

		return "INSERT INTO bid_notice (" + columns + ")\nVALUES (" + values + ")\nAS new\n"
				+ "ON DUPLICATE KEY UPDATE\n" + updates;
	}

	/** 차수 역행 방지 조건. 문자열 비교로 충분하다 — 차수는 '000','001' 처럼 폭이 고정이다. */
	private static String guard() {
		return "new.notice_order >= bid_notice.notice_order";
	}

	private static List<String> allInsertColumns() {
		List<String> columns = new ArrayList<>();
		columns.add("id");
		columns.add("notice_order");
		columns.add("region");
		columns.addAll(UPSERT_COLUMNS);
		return columns;
	}

	private static String toCamel(String snake) {
		StringBuilder out = new StringBuilder();
		boolean upper = false;
		for (char c : snake.toCharArray()) {
			if (c == '_') {
				upper = true;
				continue;
			}
			out.append(upper ? Character.toUpperCase(c) : c);
			upper = false;
		}
		return out.toString();
	}

	private static SqlParameterSource bind(BidNoticeRow row) {
		return new MapSqlParameterSource()
				.addValue("id", row.id())
				.addValue("noticeOrder", row.noticeOrder())
				.addValue("noticeName", row.noticeName())
				.addValue("category", row.categoryName())
				.addValue("state", row.stateName())
				.addValue("businessDivision", row.businessDivisionName())
				.addValue("region", row.region() == null ? "" : row.region())
				.addValue("demandInstitutionCode", row.demandInstitutionCode())
				.addValue("demandInstitutionName", row.demandInstitutionName())
				.addValue("noticeInstitutionCode", row.noticeInstitutionCode())
				.addValue("noticeInstitutionName", row.noticeInstitutionName())
				.addValue("beforeSpecRgstNo", row.beforeSpecRgstNo())
				// JSON 컬럼에 null 을 넣을 때는 타입을 명시해야 한다. 안 그러면 드라이버가
				// 문자열 'null' 로 보내 JSON 파싱 오류가 난다.
				.addValue("productList", row.productList(), Types.VARCHAR)
				.addValue("detailProductCode", row.detailProductCode())
				.addValue("lowestBidRate", row.lowestBidRate())
				.addValue("priceDetail", row.priceDetail(), Types.VARCHAR)
				.addValue("createdDate", row.createdDate())
				.addValue("closeDate", row.closeDate())
				.addValue("officerName", row.officerName())
				.addValue("officerContact", row.officerContact())
				.addValue("noticeBody", row.noticeBody())
				.addValue("attachmentUrls", row.attachmentUrls(), Types.VARCHAR)
				.addValue("sourceUrl", row.sourceUrl());
	}

	/**
	 * 참가가능지역만 따로 갱신한다.
	 *
	 * <p>지역은 입찰공고 목록이 아니라 별도 오퍼레이션에서 오므로 upsert 경로를 타지 않는다.
	 * <b>아직 색인에 없는 공고에 대한 갱신은 0행에 걸리고 그대로 버려진다</b> — 정상이다.
	 * 같은 주기에서 입찰공고를 먼저 적재하고 지역을 나중에 적재하므로 대개는 맞물리고,
	 * 어긋난 건은 다음 주기의 겹침 구간에서 다시 시도된다.
	 *
	 * @param regions 공고번호 → 지역 문자열
	 * @return 실제로 갱신된 행 수
	 */
	public int updateRegions(Map<String, String> regions) {
		if (regions == null || regions.isEmpty()) {
			return 0;
		}
		SqlParameterSource[] batch = regions.entrySet().stream()
				.map(entry -> (SqlParameterSource) new MapSqlParameterSource()
						.addValue("id", entry.getKey())
						.addValue("region", entry.getValue()))
				.toArray(SqlParameterSource[]::new);

		// 값이 같으면 updated_at 을 건드리지 않는다 — 매 주기마다 전 행의 갱신 시각이
		// 밀리면 '최근 변경' 정렬이 의미를 잃는다.
		int[] affected = jdbc.batchUpdate("""
				UPDATE bid_notice
				   SET region = :region, updated_at = NOW(6)
				 WHERE id = :id AND region <> :region
				""", batch);
		int total = 0;
		for (int count : affected) {
			total += Math.max(count, 0);
		}
		return total;
	}

	// ── 검색 ────────────────────────────────────────────────────────────────

	/** 한 페이지. {@code where} 는 {@link BidNoticeQueryBuilder} 가 만든 것을 그대로 받는다. */
	public List<Map<String, Object>> search(BidNoticeQueryBuilder.Where where, String orderBy,
			int limit, int offset) {
		MapSqlParameterSource params = new MapSqlParameterSource(where.params())
				.addValue("limit", Math.min(Math.max(limit, 1), MAX_LIMIT))
				.addValue("offset", Math.max(offset, 0));

		String sql = "SELECT " + LIST_COLUMNS + where.relevanceSelect() + LIST_FROM
				+ where.sql() + "\n ORDER BY " + orderBy + "\n LIMIT :limit OFFSET :offset";
		return jdbc.queryForList(sql, params);
	}

	public int count(BidNoticeQueryBuilder.Where where) {
		Integer total = jdbc.queryForObject("SELECT COUNT(*)" + LIST_FROM + where.sql(),
				new MapSqlParameterSource(where.params()), Integer.class);
		return total == null ? 0 : total;
	}

	/**
	 * 상세 한 건 — 본문 전문 포함.
	 *
	 * @return 없으면 {@code null}
	 */
	public Map<String, Object> findOne(String id) {
		List<Map<String, Object>> rows = jdbc.queryForList(
				"SELECT " + LIST_COLUMNS + ", n.notice_body" + LIST_FROM + " WHERE n.id = :id",
				new MapSqlParameterSource("id", id));
		return rows.isEmpty() ? null : new LinkedHashMap<>(rows.get(0));
	}

	/**
	 * 패싯 — 현재 조건에서 각 분류가 몇 건인지.
	 *
	 * <p>{@code column} 은 호출부가 고른 <b>고정 컬럼명</b>만 들어온다({@link BidNoticeSearchService}
	 * 의 상수). 사용자 입력을 여기에 넘기면 SQL 주입이 되므로 절대 넓히지 말 것.
	 */
	public List<Map<String, Object>> facet(BidNoticeQueryBuilder.Where where, String column, int limit) {
		return jdbc.queryForList("SELECT n." + column + " AS value, COUNT(*) AS count"
				+ LIST_FROM + where.sql()
				+ "\n GROUP BY n." + column + "\n ORDER BY count DESC\n LIMIT :facetLimit",
				new MapSqlParameterSource(where.params()).addValue("facetLimit", limit));
	}

	// ── 상태 전이 ───────────────────────────────────────────────────────────

	/**
	 * 마감이 지난 '입찰' 을 '마감' 으로 민다.
	 *
	 * <p>이 스위퍼가 없으면 {@code category} 는 적재 시점의 판정에 굳어 버려, 어제 적재한
	 * 공고가 오늘 마감돼도 계속 '입찰'로 검색된다. 화면의 '마감 전만 보기'가 거짓말을 하게 되는
	 * 지점이 정확히 여기다.
	 *
	 * <p>{@code close_date IS NOT NULL} 을 명시하는 이유: NULL 은 '마감일시 미상'이지 '지났다'가
	 * 아니다. SQL 비교에서 NULL 은 어차피 참이 안 되지만, 의도를 SQL 에 적어 둔다.
	 *
	 * @return 전이된 행 수
	 */
	public int sweepClosed() {
		return jdbc.update("""
				UPDATE bid_notice
				   SET category = '마감', updated_at = NOW(6)
				 WHERE category = '입찰'
				   AND close_date IS NOT NULL
				   AND close_date < NOW(6)
				""", new MapSqlParameterSource());
	}

	// ── 워터마크 ────────────────────────────────────────────────────────────

	/** @return 저장된 워터마크. 처음 도는 출처면 {@code null} */
	public LocalDateTime readWatermark(String source) {
		List<LocalDateTime> found = jdbc.queryForList(
				"SELECT watermark FROM bid_notice_sync_state WHERE source = :source",
				new MapSqlParameterSource("source", source), LocalDateTime.class);
		return found.isEmpty() ? null : found.get(0);
	}

	/** 성공 기록 — 워터마크를 전진시킨다. */
	public void recordSuccess(String source, LocalDateTime watermark, int rowCount, String note) {
		jdbc.update("""
				INSERT INTO bid_notice_sync_state
				  (source, watermark, last_run_at, last_success_at, last_result, last_row_count,
				   consecutive_failures)
				VALUES (:source, :watermark, NOW(6), NOW(6), :note, :rowCount, 0)
				AS new
				ON DUPLICATE KEY UPDATE
				  watermark = new.watermark,
				  last_run_at = new.last_run_at,
				  last_success_at = new.last_success_at,
				  last_result = new.last_result,
				  last_row_count = new.last_row_count,
				  consecutive_failures = 0
				""", new MapSqlParameterSource()
						.addValue("source", source)
						.addValue("watermark", watermark)
						.addValue("rowCount", rowCount)
						.addValue("note", note));
	}

	/**
	 * 실패 기록 — <b>워터마크는 그대로 둔다.</b>
	 *
	 * <p>실패했는데 워터마크를 전진시키면 그 구간의 공고가 영원히 색인에 안 들어온다.
	 * 다음 주기가 같은 구간을 다시 시도하게 두는 것이 유일하게 안전한 선택이다.
	 */
	public void recordFailure(String source, String reason) {
		jdbc.update("""
				INSERT INTO bid_notice_sync_state
				  (source, last_run_at, last_result, consecutive_failures)
				VALUES (:source, NOW(6), :reason, 1)
				AS new
				ON DUPLICATE KEY UPDATE
				  last_run_at = new.last_run_at,
				  last_result = new.last_result,
				  consecutive_failures = bid_notice_sync_state.consecutive_failures + 1
				""", new MapSqlParameterSource()
						.addValue("source", source)
						.addValue("reason", reason));
	}

	/** 운영 화면이 읽는 적재 현황 전체. */
	public List<Map<String, Object>> syncStates() {
		return jdbc.queryForList("""
				SELECT source, watermark, last_run_at, last_success_at, last_result,
				       last_row_count, consecutive_failures
				  FROM bid_notice_sync_state
				 ORDER BY source
				""", new MapSqlParameterSource());
	}

	/** 색인 규모 요약 — 분류별 건수와 가장 최근 적재 시각. */
	public List<Map<String, Object>> indexSummary() {
		return jdbc.queryForList("""
				SELECT category, COUNT(*) AS count, MAX(updated_at) AS last_indexed_at
				  FROM bid_notice
				 GROUP BY category
				""", new MapSqlParameterSource());
	}
}
