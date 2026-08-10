package com.electerior.g2bmaster.analysis;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 분석 결과 이력 저장소 — {@code lib/analysis-history.js} 의 DB 접근부 이식.
 *
 * <p>JPA 가 아니라 네이티브 SQL 인 이유는 두 가지다:
 * <ol>
 *   <li>재사용 조회의 WHERE 절이 <b>엔티티 타입마다 다르다</b>(자연키가 셋이다).
 *       원본도 {@code identityPredicate()} 로 동적 조립한다.</li>
 *   <li>저장이 upsert 다. 원본 {@code ON CONFLICT DO NOTHING} 은 MySQL 에서
 *       {@code ON DUPLICATE KEY UPDATE id = id}(무연산) 로 옮긴다 —
 *       {@code INSERT IGNORE} 를 쓰면 FK 위반·값 잘림까지 삼켜서 위험하다
 *       (→ {@code docs/migration-notes.md} §9).</li>
 * </ol>
 */
@Repository
public class AnalysisHistoryRepository {

	private final NamedParameterJdbcTemplate jdbc;

	public AnalysisHistoryRepository(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * 재사용 가능한 분석 결과 — 원본 {@code findReusableAnalysis}.
	 *
	 * <p>키는 자연키 + 입력해시 + 프롬프트 버전 + 분석모드 + deep 여부다. 프롬프트 버전이
	 * 키에 들어 있어서 AI 저장소가 프롬프트를 고치면 캐시가 자연스럽게 무효화된다.
	 *
	 * @return 없으면 {@code null}
	 */
	public ReusableAnalysis findReusable(AnalysisIdentity identity, String inputHash,
			String promptVersion, String analysisMode, boolean deepMode) {
		if (identity == null || isBlank(inputHash) || isBlank(promptVersion)) {
			return null;
		}
		MapSqlParameterSource params = new MapSqlParameterSource()
				.addValue("id", identity.id())
				.addValue("ord", identity.ord())
				.addValue("inputHash", inputHash)
				.addValue("promptVersion", promptVersion)
				.addValue("analysisMode", analysisMode == null ? "" : analysisMode)
				.addValue("deepMode", deepMode);

		String predicate = switch (identity.type()) {
			case AnalysisIdentity.PROCUREMENT_REQUEST -> "prcrmnt_req_no = :id";
			case AnalysisIdentity.PRE_SPEC -> "bf_spec_rgst_no = :id";
			// 입찰공고만 차수가 키에 들어간다. NULL 을 빈 문자열과 같게 보는 것도 원본 그대로다.
			default -> "bid_ntce_no = :id AND COALESCE(bid_ntce_ord, '') = :ord";
		};

		List<ReusableAnalysis> rows = jdbc.query("""
				SELECT id, result, created_at, llm_model
				  FROM analysis_history
				 WHERE %s
				   AND input_hash = :inputHash
				   AND prompt_version = :promptVersion
				   AND COALESCE(analysis_mode, '') = :analysisMode
				   AND deep_mode = :deepMode
				 ORDER BY created_at DESC, id DESC
				 LIMIT 1
				""".formatted(predicate), params, (rs, rowNum) -> new ReusableAnalysis(
						rs.getLong("id"),
						rs.getString("result"),
						toInstant(rs.getTimestamp("created_at")),
						rs.getString("llm_model")));
		return rows.isEmpty() ? null : rows.get(0);
	}

	/**
	 * 분석 결과 저장 — 원본 {@code saveAnalysisHistory}.
	 *
	 * <p>충돌하면(같은 재사용 키가 이미 있으면) 아무것도 하지 않고 <b>기존 행을 다시 찾아 돌려준다</b>.
	 * 검색과 내보내기가 같은 분석을 동시에 끝내는 일이 실제로 있어서, 그때 두 행이 생기면
	 * 재사용 판정이 흔들린다. V6 의 {@code uk_analysis_history_reuse_*} 가 이 충돌을 만든다.
	 *
	 * @return 새로 만든 행이든 이미 있던 행이든, 그 id 와 생성 시각
	 */
	public ReusableAnalysis save(AnalysisHistory record) {
		MapSqlParameterSource params = new MapSqlParameterSource()
				.addValue("bidNtceNo", record.getBidNtceNo())
				.addValue("bidNtceOrd", record.getBidNtceOrd() == null ? "" : record.getBidNtceOrd())
				.addValue("bfSpecRgstNo", record.getBfSpecRgstNo())
				.addValue("prcrmntReqNo", record.getPrcrmntReqNo())
				.addValue("title", record.getTitle())
				.addValue("insttNm", record.getInsttNm())
				.addValue("amount", record.getAmount())
				.addValue("source", record.getSource())
				.addValue("specName", record.getSpecName())
				.addValue("specConfidence", record.getSpecConfidence())
				.addValue("nestedTables", record.getNestedTables())
				.addValue("blockingExcluded", record.isBlockingExcluded())
				.addValue("summary", record.getSummary())
				.addValue("result", record.getResult() == null ? "{}" : record.getResult())
				.addValue("deepMode", record.isDeepMode())
				.addValue("analysisMode", record.getAnalysisMode() == null ? "" : record.getAnalysisMode())
				.addValue("llmModel", record.getLlmModel())
				.addValue("inputHash", record.getInputHash())
				.addValue("promptVersion", record.getPromptVersion());

		KeyHolder keys = new GeneratedKeyHolder();
		int affected = jdbc.update("""
				INSERT INTO analysis_history (
				  bid_ntce_no, bid_ntce_ord, bf_spec_rgst_no, prcrmnt_req_no,
				  title, instt_nm, amount, source, spec_name, spec_confidence,
				  nested_tables, blocking_excluded, summary, result, deep_mode,
				  analysis_mode, llm_model, input_hash, prompt_version
				) VALUES (
				  :bidNtceNo, :bidNtceOrd, :bfSpecRgstNo, :prcrmntReqNo,
				  :title, :insttNm, :amount, :source, :specName, :specConfidence,
				  :nestedTables, :blockingExcluded, :summary, :result, :deepMode,
				  :analysisMode, :llmModel, :inputHash, :promptVersion
				)
				ON DUPLICATE KEY UPDATE id = id
				""", params, keys, new String[] {"id"});

		// affected 가 1 이면 실제 삽입, 0 이면 무연산(이미 있음).
		// (MySQL 은 ON DUPLICATE KEY 로 값이 바뀌면 2 를 주지만 여기 UPDATE 는 무연산이라 0 이다.)
		if (affected == 1 && keys.getKey() != null) {
			return new ReusableAnalysis(keys.getKey().longValue(), record.getResult(), null, record.getLlmModel());
		}
		return findReusable(identityOf(record), record.getInputHash(), record.getPromptVersion(),
				record.getAnalysisMode(), record.isDeepMode());
	}

	private static AnalysisIdentity identityOf(AnalysisHistory record) {
		if (record.getPrcrmntReqNo() != null) {
			return new AnalysisIdentity(AnalysisIdentity.PROCUREMENT_REQUEST, record.getPrcrmntReqNo(), "");
		}
		if (record.getBfSpecRgstNo() != null) {
			return new AnalysisIdentity(AnalysisIdentity.PRE_SPEC, record.getBfSpecRgstNo(), "");
		}
		return new AnalysisIdentity(AnalysisIdentity.BID_NOTICE, record.getBidNtceNo(), record.getBidNtceOrd());
	}

	private static Instant toInstant(Timestamp value) {
		return value == null ? null : value.toInstant();
	}

	private static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

	/**
	 * 재사용 판정 결과.
	 *
	 * @param id        {@code analysis_history.id} — 작업 완료 기록에 이 값이 필요하다
	 * @param result    분석 응답 전문(JSON 문자열)
	 * @param createdAt 생성 시각. 새로 삽입한 직후에는 다시 읽지 않으므로 {@code null} 일 수 있다
	 * @param llmModel  어떤 모델이 만든 결과인지
	 */
	public record ReusableAnalysis(long id, String result, Instant createdAt, String llmModel) {
	}
}
