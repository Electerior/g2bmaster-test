package com.electerior.g2bmaster.system;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 테이블별 행수 — {@code GET /api/system/tables}.
 *
 * <h2>⚠ 원본을 그대로 옮기지 않았다 (SQL 인젝션 형태)</h2>
 *
 * <p>원본({@code server.js:4872})은 {@code information_schema} 에서 테이블 이름을 읽어
 * <b>문자열 연결로</b> {@code UNION ALL} 을 만들었다:
 *
 * <pre>{@code
 * const union = names.map(r => `SELECT '${r.table_name}' t, count(*)::int n FROM ${r.table_name}`)
 *                    .join(' UNION ALL ');
 * }</pre>
 *
 * <p>인용도 이스케이프도 없다. 지금은 이름의 출처가 카탈로그라 실제로 악용되지는 않지만,
 * <b>"카탈로그에서 읽었으니 안전하다"는 전제 자체가 위험하다</b> — 테이블 이름은 마이그레이션으로
 * 만들어지고, 언젠가 사용자 입력에서 파생된 이름이 카탈로그에 들어가면 그날 바로 실행된다.
 * 그리고 102개 테이블 전수 {@code COUNT(*)} 는 창고가 커질수록 그 자체로 느려진다.
 *
 * <p><b>선택: {@code information_schema.TABLES.TABLE_ROWS} 한 방 조회.</b>
 * 이름을 SQL 로 되돌려 보내는 일이 아예 없어져 인젝션 형태가 사라지고, 테이블 수와 무관하게
 * 질의 한 번으로 끝난다.
 *
 * <p>대가는 정확도다 — InnoDB 의 {@code TABLE_ROWS} 는 통계 기반 <b>추정치</b>다.
 * 화면 용도(어디에 데이터가 들어왔나, 어느 테이블이 비어 있나)에는 충분하지만 감사에는 쓸 수 없으므로,
 * 응답에 {@code approximate: true} 를 실어 화면이 그렇게 표시할 수 있게 했다.
 */
@Repository
public class SystemTableCountRepository {

	private final NamedParameterJdbcTemplate jdbc;

	public SystemTableCountRepository(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * @return {@code [{t: 테이블명, n: 추정 행수}]}, 행수 내림차순.
	 *         키 이름({@code t}/{@code n})은 원본 응답 계약 그대로다.
	 */
	public List<Map<String, Object>> tableRowCounts() {
		return jdbc.queryForList("""
				SELECT TABLE_NAME AS t, COALESCE(TABLE_ROWS, 0) AS n
				  FROM information_schema.TABLES
				 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE'
				 ORDER BY n DESC, t ASC
				""", new MapSqlParameterSource());
	}
}
