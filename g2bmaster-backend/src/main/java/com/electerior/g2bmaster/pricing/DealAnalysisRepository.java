package com.electerior.g2bmaster.pricing;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * deep 딜 분석 결과 저장소. 입력 해시가 같으면 저장된 결과를 돌려준다.
 *
 * <p>JPA 대신 네이티브 upsert 인 이유는 {@code SavedNoticeRepository} 와 같다 — 저장은
 * {@code ON DUPLICATE KEY UPDATE} 한 방이어야 갱신이 유실되지 않는다.
 */
@Repository
public class DealAnalysisRepository {

	private final NamedParameterJdbcTemplate jdbc;

	public DealAnalysisRepository(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** 저장된 결과(있으면). {@code result_json} 원문과 저장 시각을 함께 준다. */
	public Optional<Stored> find(String inputHash) {
		var rows = jdbc.query(
				"SELECT result_json, created_at FROM deal_analysis_result WHERE input_hash = :h",
				new MapSqlParameterSource("h", inputHash),
				(rs, n) -> new Stored(
						rs.getString("result_json"),
						rs.getObject("created_at", LocalDateTime.class)));
		return rows.stream().findFirst();
	}

	/** 저장(신규/갱신). deep 결과만 넣는다 — 얕은 분석은 싸므로 저장하지 않는다. */
	public void save(String inputHash, String bidNtceNo, boolean deep, String resultJson) {
		jdbc.update("""
				INSERT INTO deal_analysis_result (input_hash, bid_ntce_no, deep, result_json)
				VALUES (:h, :bid, :deep, :json)
				ON DUPLICATE KEY UPDATE
				  bid_ntce_no = VALUES(bid_ntce_no),
				  deep        = VALUES(deep),
				  result_json = VALUES(result_json)
				""",
				new MapSqlParameterSource(Map.of(
						"h", inputHash,
						"bid", bidNtceNo == null ? "" : bidNtceNo,
						"deep", deep ? 1 : 0,
						"json", resultJson)));
	}

	/** 저장된 한 건. {@code json} 은 deal-analysis 응답 원문. */
	public record Stored(String json, LocalDateTime createdAt) {}
}
