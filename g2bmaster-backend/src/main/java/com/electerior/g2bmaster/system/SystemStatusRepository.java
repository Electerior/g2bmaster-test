package com.electerior.g2bmaster.system;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 운영 현황 조회 — 전부 읽기 전용 집계다.
 *
 * <h2>Postgres → MySQL 에서 손댄 것</h2>
 * <ul>
 *   <li>{@code count(*) FILTER (WHERE ...)} → {@code SUM(조건)}. MySQL 에 FILTER 절이 없다.</li>
 *   <li>{@code to_char(t, 'YYYY-MM-DD HH24:MI')} → {@code DATE_FORMAT(CONVERT_TZ(...), ...)}.
 *       <b>구역 이름이 아니라 고정 오프셋 {@code '+09:00'} 를 쓴다</b> — 이름을 쓰려면 서버에
 *       시간대 테이블({@code mysql_tzinfo_to_sql})이 적재돼 있어야 하고, 없으면
 *       {@code CONVERT_TZ} 가 오류가 아니라 <b>NULL</b> 을 돌려줘 화면의 시각이 조용히 빈다.
 *       한국은 서머타임이 없으므로 고정 오프셋이 정확하다.</li>
 *   <li>{@code ORDER BY ... DESC NULLS LAST} → MySQL 은 NULL 을 가장 작은 값으로 보므로
 *       {@code DESC} 만으로 같은 순서가 된다.</li>
 * </ul>
 */
@Repository
public class SystemStatusRepository {

	/** 한국 표준시 고정 오프셋. 클래스 주석의 이유로 구역 이름을 쓰지 않는다. */
	private static final String KST = "'+09:00'";

	private final NamedParameterJdbcTemplate jdbc;

	public SystemStatusRepository(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** 연결 확인. 실패하면 예외가 올라가고 호출부가 {@code null} 로 흘린다. */
	public void ping() {
		jdbc.getJdbcTemplate().queryForObject("SELECT 1", Integer.class);
	}

	/**
	 * 화면 상단의 창고 규모.
	 *
	 * <p>테이블 이름이 <b>SQL 안에 리터럴로 박혀 있다</b>. 원본과 같은 8개이며,
	 * 여기에 이름을 변수로 끼워 넣지 말 것 — {@code /api/system/tables} 가 그렇게 하다가
	 * 인젝션 형태가 됐다({@link SystemTableCountRepository} 주석 참고).
	 */
	public List<Map<String, Object>> warehouseCounts() {
		return jdbc.queryForList("""
				          SELECT 'dwt_bid_notice' AS t, COUNT(*) AS n FROM dwt_bid_notice
				UNION ALL SELECT 'dwt_bid_result', COUNT(*) FROM dwt_bid_result
				UNION ALL SELECT 'dwt_pre_specification', COUNT(*) FROM dwt_pre_specification
				UNION ALL SELECT 'dwt_contract', COUNT(*) FROM dwt_contract
				UNION ALL SELECT 'dwt_order_plan', COUNT(*) FROM dwt_order_plan
				UNION ALL SELECT 'dwt_procurement_request', COUNT(*) FROM dwt_procurement_request
				UNION ALL SELECT 'dm_institution', COUNT(*) FROM dm_institution
				UNION ALL SELECT 'attachment_cache', COUNT(*) FROM attachment_cache
				""", new MapSqlParameterSource());
	}

	/** 오퍼레이션별 마지막 성공 시점 상위 12건. */
	public List<Map<String, Object>> syncState() {
		return jdbc.queryForList("""
				SELECT operation, inqry_div, last_total_count, consecutive_failures,
				       DATE_FORMAT(CONVERT_TZ(last_success_at, '+00:00', %s), '%%Y-%%m-%%d %%H:%%i') AS last_success
				  FROM sync_state
				 ORDER BY last_success_at DESC
				 LIMIT 12
				""".formatted(KST), new MapSqlParameterSource());
	}

	/** 호출 로그 요약. 실패를 볼 창구가 없어 ghost call 89건이 늦게 발견됐다 — 그 창구다. */
	public Map<String, Object> callSummary() {
		// COALESCE 가 붙은 이유: 빈 테이블에서 SUM 은 0 이 아니라 NULL 을 준다.
		// Postgres 의 count(*) FILTER 는 0 을 주므로, 그대로 옮기면 화면에 0 대신 빈칸이 나온다.
		List<Map<String, Object>> rows = jdbc.queryForList("""
				SELECT COUNT(*) AS total,
				       COALESCE(SUM(result_code = '00'), 0) AS ok,
				       COALESCE(SUM(error_text IS NOT NULL), 0) AS failed,
				       COALESCE(ROUND(AVG(duration_ms)), 0) AS avg_ms
				  FROM api_call_log
				""", new MapSqlParameterSource());
		return rows.isEmpty() ? null : rows.get(0);
	}

	/** 개찰 참가자 수집 커버리지. */
	public Map<String, Object> participantCoverage() {
		List<Map<String, Object>> rows = jdbc.queryForList("""
				SELECT (SELECT COUNT(DISTINCT bid_ntce_no) FROM stg_openg_result_list_info_openg_compt) AS covered,
				       (SELECT COUNT(*) FROM dwt_bid_result
				         WHERE openg_dt IS NOT NULL AND openg_dt < NOW(6)) AS eligible,
				       (SELECT COUNT(*) FROM stg_openg_result_list_info_openg_compt) AS rows_total
				""", new MapSqlParameterSource());
		if (rows.isEmpty()) {
			return null;
		}
		// 원본 응답 키가 `rows` 였다. MySQL 예약어라 별칭을 바꿨으므로 여기서 되돌린다.
		Map<String, Object> out = new LinkedHashMap<>(rows.get(0));
		out.put("rows", out.remove("rows_total"));
		return out;
	}

	/**
	 * 호출 이력 — {@code GET /api/system/calls}.
	 *
	 * <p>{@code onlyFailed}/{@code operation} 유무에 따라 WHERE 가 달라지는 동적 SQL 이다.
	 * 값은 전부 바인딩 파라미터이고, 문자열로 이어 붙이는 것은 <b>고정 술어뿐</b>이다.
	 */
	public List<Map<String, Object>> calls(boolean onlyFailed, String operation, int limit) {
		List<String> conditions = new ArrayList<>();
		MapSqlParameterSource params = new MapSqlParameterSource().addValue("limit", limit);
		if (onlyFailed) {
			conditions.add("(error_text IS NOT NULL OR (result_code IS NOT NULL AND result_code <> '00'))");
		}
		if (operation != null && !operation.isBlank()) {
			conditions.add("operation = :operation");
			params.addValue("operation", operation.trim());
		}
		String where = conditions.isEmpty() ? "" : "WHERE " + String.join(" AND ", conditions);

		return jdbc.queryForList("""
				SELECT operation, service_id, result_code, result_msg, error_text, http_status,
				       item_count, total_count, duration_ms,
				       DATE_FORMAT(CONVERT_TZ(started_at, '+00:00', %s), '%%m-%%d %%H:%%i:%%s') AS started,
				       params_json
				  FROM api_call_log
				 %s
				 ORDER BY started_at DESC
				 LIMIT :limit
				""".formatted(KST, where), params);
	}

	/** 저장 공고 건수 — 현황 화면의 한 칸. */
	public long savedNoticeCount() {
		Long value = jdbc.queryForObject("SELECT COUNT(*) FROM saved_notice",
				new MapSqlParameterSource(), Long.class);
		return value == null ? 0 : value;
	}
}
