package com.electerior.g2bmaster.analysis;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 분석 작업 큐 — {@code lib/analysis-job-queue.js} 이식.
 *
 * <p>SQL 을 네이티브로 둔 이유는 하나다: <b>{@code FOR UPDATE SKIP LOCKED} 에 JPA 대응이 없다.</b>
 * 잠긴 행을 건너뛰지 못하면 워커 두 개가 서로를 기다리며 큐가 직렬화된다.
 *
 * <h2>Postgres → MySQL 에서 실제로 달라진 것</h2>
 * <ol>
 *   <li><b>청구(claim)의 2단계화.</b> 원본은 CTE 로 후보를 잠그고 그 CTE 를 UPDATE 소스로 썼는데,
 *       MySQL 은 CTE 를 UPDATE 소스로 쓸 수 없고 {@code RETURNING} 도 없다. 같은 트랜잭션 안에서
 *       {@code SELECT ... FOR UPDATE SKIP LOCKED LIMIT 1} → {@code UPDATE ... WHERE id=?} 로 나눈다
 *       (→ {@code docs/migration-notes.md} §8).</li>
 *   <li><b>⚠ {@code ON DUPLICATE KEY UPDATE} 의 대입은 왼쪽부터 순서대로 평가된다.</b>
 *       Postgres 의 {@code DO UPDATE SET} 은 모든 참조가 <em>갱신 전</em> 행을 보지만, MySQL 은
 *       앞선 대입의 결과를 뒤 대입이 본다. 그래서 {@code status} 와 {@code analysis_history_id} 를
 *       <b>그것들을 읽는 모든 대입보다 뒤에</b> 놓았다. 순서를 바꾸면 조용히 다른 상태 기계가 된다.</li>
 *   <li>시각은 전부 {@code NOW(6)} 다. {@code NOW()} 는 초 정밀도라 {@code DATETIME(6)} 컬럼에
 *       들어가면 소수부가 0 으로 잘리고, 리스 만료 비교가 최대 1초 어긋난다.</li>
 * </ol>
 */
@Repository
public class AnalysisJobRepository {

	/** 원본 기본 리스(5분). 실행이 이보다 오래 걸리면 heartbeat 가 연장한다. */
	public static final long DEFAULT_LEASE_MS = 300_000L;

	private static final int MAX_ERROR_LENGTH = 2000;

	private final NamedParameterJdbcTemplate jdbc;
	private final AnalysisHistoryRepository historyRepository;

	public AnalysisJobRepository(NamedParameterJdbcTemplate jdbc, AnalysisHistoryRepository historyRepository) {
		this.jdbc = jdbc;
		this.historyRepository = historyRepository;
	}

	// ── 등록 ────────────────────────────────────────────────────────────────

	/**
	 * 작업 등록 — 원본 {@code enqueueAnalysisJob}.
	 *
	 * <p><b>먼저 이력에서 같은 결과를 찾는다.</b> 있으면 작업을 곧바로 {@code completed} 로 넣는다 —
	 * LLM 호출이 한 번도 일어나지 않는다. 검색 한 번에 수백 건이 등록되는 경로라 이 중복 제거가
	 * 사실상 비용 통제 장치다.
	 *
	 * <p>이미 있는 작업이면 payload 를 갱신하고 상태를 되살린다(실패·취소는 다시 큐로, 이력 없이
	 * 완료로 남은 유령 행도 다시 큐로).
	 */
	@Transactional
	public AnalysisJob enqueue(EnqueueRequest request) {
		AnalysisIdentity identity = request.identity();
		if (identity == null || identity.id().isEmpty()) {
			throw new IllegalArgumentException("분석 작업 엔티티 자연키가 필요합니다.");
		}

		// 이력 조회가 실패해도 등록 자체는 진행한다(원본의 `.catch(() => null)`).
		// 캐시를 못 읽은 것이 새 작업을 막을 이유는 없다 — 최악이라도 한 번 더 추론할 뿐이다.
		AnalysisHistoryRepository.ReusableAnalysis cached = null;
		try {
			cached = historyRepository.findReusable(identity, request.inputHash(),
					request.promptVersion(), request.analysisMode(), request.deepMode());
		}
		catch (RuntimeException ignored) {
			cached = null;
		}

		MapSqlParameterSource params = new MapSqlParameterSource()
				.addValue("entityType", identity.type())
				.addValue("entityId", identity.id())
				.addValue("entityOrd", identity.ord())
				.addValue("inputHash", request.inputHash())
				.addValue("promptVersion", request.promptVersion())
				.addValue("analysisMode", request.analysisMode() == null ? "" : request.analysisMode())
				.addValue("payload", request.payload() == null ? "{}" : request.payload())
				.addValue("status", cached != null ? AnalysisJob.COMPLETED : AnalysisJob.QUEUED)
				.addValue("priority", request.priority())
				.addValue("maxAttempts", Math.max(1, request.maxAttempts()))
				.addValue("historyId", cached == null ? null : cached.id());

		jdbc.update("""
				INSERT INTO analysis_job (
				  entity_type, entity_id, entity_ord, input_hash, prompt_version, analysis_mode,
				  payload, status, priority, max_attempts, analysis_history_id, completed_at
				) VALUES (
				  :entityType, :entityId, :entityOrd, :inputHash, :promptVersion, :analysisMode,
				  :payload, :status, :priority, :maxAttempts, :historyId,
				  CASE WHEN :historyId IS NULL THEN NULL ELSE NOW(6) END
				) AS new
				ON DUPLICATE KEY UPDATE
				  -- ⚠ 대입 순서가 의미를 만든다. 아래 CASE 들은 전부 '갱신 전' status 와
				  --    analysis_history_id 를 읽어야 하므로, 그 둘의 대입을 맨 뒤로 미뤘다.
				  payload      = new.payload,
				  priority     = GREATEST(analysis_job.priority, new.priority),
				  max_attempts = GREATEST(analysis_job.max_attempts, new.max_attempts),
				  attempt_count = CASE
				    WHEN analysis_job.status IN ('failed', 'cancelled') THEN 0
				    ELSE analysis_job.attempt_count END,
				  available_at = CASE
				    WHEN analysis_job.status IN ('failed', 'cancelled') THEN NOW(6)
				    ELSE analysis_job.available_at END,
				  worker_id = CASE
				    WHEN analysis_job.status IN ('failed', 'cancelled')
				      OR (analysis_job.status = 'completed' AND analysis_job.analysis_history_id IS NULL)
				    THEN NULL ELSE analysis_job.worker_id END,
				  lease_expires_at = CASE
				    WHEN analysis_job.status IN ('failed', 'cancelled')
				      OR (analysis_job.status = 'completed' AND analysis_job.analysis_history_id IS NULL)
				    THEN NULL ELSE analysis_job.lease_expires_at END,
				  heartbeat_at = CASE
				    WHEN analysis_job.status IN ('failed', 'cancelled')
				      OR (analysis_job.status = 'completed' AND analysis_job.analysis_history_id IS NULL)
				    THEN NULL ELSE analysis_job.heartbeat_at END,
				  started_at = CASE
				    WHEN analysis_job.status IN ('failed', 'cancelled')
				      OR (analysis_job.status = 'completed' AND analysis_job.analysis_history_id IS NULL)
				    THEN NULL ELSE analysis_job.started_at END,
				  completed_at = CASE
				    WHEN new.analysis_history_id IS NOT NULL THEN NOW(6)
				    WHEN analysis_job.status IN ('failed', 'cancelled')
				      OR (analysis_job.status = 'completed' AND analysis_job.analysis_history_id IS NULL)
				    THEN NULL ELSE analysis_job.completed_at END,
				  last_error = CASE
				    WHEN analysis_job.status IN ('failed', 'cancelled') THEN NULL
				    ELSE analysis_job.last_error END,
				  status = CASE
				    WHEN new.analysis_history_id IS NOT NULL THEN 'completed'
				    WHEN analysis_job.status IN ('failed', 'cancelled') THEN 'queued'
				    WHEN analysis_job.status = 'completed' AND analysis_job.analysis_history_id IS NULL THEN 'queued'
				    ELSE analysis_job.status END,
				  analysis_history_id = COALESCE(new.analysis_history_id, analysis_job.analysis_history_id),
				  updated_at = NOW(6)
				""", params);

		// MySQL 에는 RETURNING 이 없다. 같은 트랜잭션 안에서 자연키로 다시 읽는다
		// (→ migration-notes.md §7). 유일 인덱스가 있으므로 한 행이다.
		return findByIdentity(identity, request.inputHash(), request.promptVersion(), request.analysisMode());
	}

	// ── 워커 ────────────────────────────────────────────────────────────────

	/**
	 * 작업 하나를 청구한다 — 원본 {@code claimAnalysisJob} 의 2단계 이식.
	 *
	 * <p>{@code REQUIRES_NEW} 인 것은 의도다. 이 잠금은 <b>가능한 한 짧게</b> 잡아야 하는데,
	 * 호출부(워커 루프)의 트랜잭션에 얹히면 분석이 끝날 때까지(수 분) 행 잠금이 유지된다.
	 *
	 * @return 청구할 것이 없으면 {@code null}
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public AnalysisJob claim(String workerId, long leaseMs) {
		long lease = Math.max(1000L, leaseMs);
		List<Long> candidate = jdbc.queryForList("""
				SELECT id FROM analysis_job
				 WHERE status = 'queued' AND available_at <= NOW(6)
				 ORDER BY priority DESC, available_at, id
				 LIMIT 1
				 FOR UPDATE SKIP LOCKED
				""", new MapSqlParameterSource(), Long.class);
		if (candidate.isEmpty()) {
			return null;
		}
		Long id = candidate.get(0);

		jdbc.update("""
				UPDATE analysis_job
				   SET status = 'running',
				       worker_id = :workerId,
				       attempt_count = attempt_count + 1,
				       started_at = COALESCE(started_at, NOW(6)),
				       heartbeat_at = NOW(6),
				       lease_expires_at = DATE_ADD(NOW(6), INTERVAL :leaseUs MICROSECOND),
				       updated_at = NOW(6)
				 WHERE id = :id
				""", new MapSqlParameterSource()
						.addValue("workerId", workerId)
						.addValue("leaseUs", lease * 1000L)
						.addValue("id", id));
		return findById(id);
	}

	/**
	 * 리스 연장 — 원본 {@code heartbeatAnalysisJob}.
	 *
	 * <p>{@code worker_id} 와 {@code status='running'} 을 함께 거는 것이 소유권 확인이다.
	 * 리스가 만료돼 다른 워커가 가져간 뒤에는 갱신되지 않고 {@code false} 가 돌아온다.
	 */
	public boolean heartbeat(long id, String workerId, long leaseMs) {
		return jdbc.update("""
				UPDATE analysis_job
				   SET heartbeat_at = NOW(6),
				       lease_expires_at = DATE_ADD(NOW(6), INTERVAL :leaseUs MICROSECOND),
				       updated_at = NOW(6)
				 WHERE id = :id AND worker_id = :workerId AND status = 'running'
				""", new MapSqlParameterSource()
						.addValue("leaseUs", Math.max(1000L, leaseMs) * 1000L)
						.addValue("id", id)
						.addValue("workerId", workerId)) == 1;
	}

	/** 완료 — 원본 {@code completeAnalysisJob}. 소유권을 잃었으면 {@code null}. */
	@Transactional
	public AnalysisJob complete(long id, String workerId, long analysisHistoryId) {
		int affected = jdbc.update("""
				UPDATE analysis_job
				   SET status = 'completed',
				       analysis_history_id = :historyId,
				       completed_at = NOW(6),
				       lease_expires_at = NULL,
				       heartbeat_at = NOW(6),
				       last_error = NULL,
				       updated_at = NOW(6)
				 WHERE id = :id AND worker_id = :workerId AND status = 'running'
				""", new MapSqlParameterSource()
						.addValue("historyId", analysisHistoryId)
						.addValue("id", id)
						.addValue("workerId", workerId));
		return affected == 1 ? findById(id) : null;
	}

	/**
	 * 실패 — 원본 {@code failAnalysisJob}.
	 *
	 * <p>{@code attempt_count < max_attempts} 면 지수 백오프를 붙여 다시 큐에 넣고, 아니면 실패로 굳힌다.
	 * 백오프 값은 호출부(러너)가 {@code retryBaseMs × 2^(attempt-1)} 로 계산해 넘긴다.
	 */
	@Transactional
	public AnalysisJob fail(long id, String workerId, String error, long backoffMs) {
		String message = error == null || error.isBlank() ? "analysis failed" : error;
		if (message.length() > MAX_ERROR_LENGTH) {
			message = message.substring(0, MAX_ERROR_LENGTH);
		}
		int affected = jdbc.update("""
				UPDATE analysis_job
				   SET available_at = CASE WHEN attempt_count < max_attempts
				         THEN DATE_ADD(NOW(6), INTERVAL :backoffUs MICROSECOND) ELSE available_at END,
				       worker_id = CASE WHEN attempt_count < max_attempts THEN NULL ELSE worker_id END,
				       lease_expires_at = NULL,
				       last_error = :error,
				       completed_at = CASE WHEN attempt_count < max_attempts THEN NULL ELSE NOW(6) END,
				       -- status 는 attempt_count 를 읽는 대입들보다 뒤에 와야 한다(위 §2 주석 참고).
				       status = CASE WHEN attempt_count < max_attempts THEN 'queued' ELSE 'failed' END,
				       updated_at = NOW(6)
				 WHERE id = :id AND worker_id = :workerId AND status = 'running'
				""", new MapSqlParameterSource()
						.addValue("backoffUs", Math.max(0L, backoffMs) * 1000L)
						.addValue("error", message)
						.addValue("id", id)
						.addValue("workerId", workerId));
		return affected == 1 ? findById(id) : null;
	}

	/**
	 * 리스가 만료된 {@code running} 행을 회수한다 — 원본 {@code recoverExpiredAnalysisJobs}.
	 *
	 * <p>워커가 죽으면 heartbeat 가 멈추고 리스가 만료된다. 이 쓸기가 없으면 그 작업은
	 * 영원히 {@code running} 으로 남아 아무도 다시 집지 않는다.
	 *
	 * @return 회수한 행 수
	 */
	@Transactional
	public int recoverExpired() {
		return jdbc.update("""
				UPDATE analysis_job
				   SET available_at = CASE WHEN attempt_count < max_attempts THEN NOW(6) ELSE available_at END,
				       worker_id = CASE WHEN attempt_count < max_attempts THEN NULL ELSE worker_id END,
				       lease_expires_at = NULL,
				       last_error = CONCAT_WS(' | ', NULLIF(last_error, ''), 'worker lease expired'),
				       completed_at = CASE WHEN attempt_count < max_attempts THEN NULL ELSE NOW(6) END,
				       status = CASE WHEN attempt_count < max_attempts THEN 'queued' ELSE 'failed' END,
				       updated_at = NOW(6)
				 WHERE status = 'running' AND lease_expires_at < NOW(6)
				""", new MapSqlParameterSource());
	}

	// ── 조회 ────────────────────────────────────────────────────────────────

	public AnalysisJob findById(long id) {
		List<AnalysisJob> rows = jdbc.query("SELECT * FROM analysis_job WHERE id = :id",
				new MapSqlParameterSource("id", id), AnalysisJobRowMapper.INSTANCE);
		return rows.isEmpty() ? null : rows.get(0);
	}

	private AnalysisJob findByIdentity(AnalysisIdentity identity, String inputHash,
			String promptVersion, String analysisMode) {
		List<AnalysisJob> rows = jdbc.query("""
				SELECT * FROM analysis_job
				 WHERE entity_type = :entityType AND entity_id = :entityId AND entity_ord = :entityOrd
				   AND input_hash = :inputHash AND prompt_version = :promptVersion
				   AND analysis_mode = :analysisMode
				""", new MapSqlParameterSource()
						.addValue("entityType", identity.type())
						.addValue("entityId", identity.id())
						.addValue("entityOrd", identity.ord())
						.addValue("inputHash", inputHash)
						.addValue("promptVersion", promptVersion)
						.addValue("analysisMode", analysisMode == null ? "" : analysisMode),
				AnalysisJobRowMapper.INSTANCE);
		return rows.isEmpty() ? null : rows.get(0);
	}

	/**
	 * 상태 일괄 조회 — 원본 {@code getAnalysisStatuses}.
	 *
	 * <p>원본은 {@code jsonb_to_recordset} 으로 요청 목록을 임시 릴레이션으로 만들어 LEFT JOIN 했다.
	 * MySQL 에 대응이 없으므로 <b>행 생성자 IN</b> 으로 옮긴다 — {@code analysis_job_identity_idx} 를
	 * 그대로 타므로 6개 컬럼 전부를 조건에 넣는 것이 오히려 유리하다.
	 * 요청이 없는 항목은 여기서 빠지고, 호출부가 {@code pending} 으로 채운다.
	 */
	public Map<StatusKey, JobStatus> findStatuses(List<StatusKey> keys) {
		if (keys.isEmpty()) {
			return Map.of();
		}
		StringBuilder tuples = new StringBuilder();
		MapSqlParameterSource params = new MapSqlParameterSource();
		for (int i = 0; i < keys.size(); i++) {
			StatusKey key = keys.get(i);
			if (i > 0) {
				tuples.append(',');
			}
			tuples.append("(:t").append(i).append(",:i").append(i).append(",:o").append(i)
					.append(",:h").append(i).append(",:p").append(i).append(",:m").append(i).append(')');
			params.addValue("t" + i, key.entityType())
					.addValue("i" + i, key.entityId())
					.addValue("o" + i, key.entityOrd())
					.addValue("h" + i, key.inputHash())
					.addValue("p" + i, key.promptVersion())
					.addValue("m" + i, key.analysisMode());
		}

		Map<StatusKey, JobStatus> out = new LinkedHashMap<>();
		jdbc.query("""
				SELECT entity_type, entity_id, entity_ord, input_hash, prompt_version, analysis_mode,
				       id, status, analysis_history_id, attempt_count, max_attempts, last_error, updated_at
				  FROM analysis_job
				 WHERE (entity_type, entity_id, entity_ord, input_hash, prompt_version, analysis_mode)
				       IN (%s)
				""".formatted(tuples), params, rs -> {
					StatusKey key = new StatusKey(
							rs.getString("entity_type"), rs.getString("entity_id"), rs.getString("entity_ord"),
							rs.getString("input_hash"), rs.getString("prompt_version"), rs.getString("analysis_mode"));
					Long historyId = rs.getObject("analysis_history_id", Long.class);
					out.put(key, new JobStatus(
							rs.getLong("id"), rs.getString("status"), historyId,
							rs.getInt("attempt_count"), rs.getInt("max_attempts"),
							rs.getString("last_error"),
							rs.getTimestamp("updated_at") == null ? null : rs.getTimestamp("updated_at").toInstant()));
				});
		return out;
	}

	/**
	 * 상태별 건수 — 원본 {@code analysisJobStats}.
	 *
	 * <p>원본에서는 export 만 되고 부르는 곳이 없었다. 여기서는 {@code /api/system/status} 에 실어
	 * 실제로 쓴다 — 큐가 밀렸는지({@code queued} 가 쌓이는지), 워커가 죽었는지({@code running} 이
	 * 줄지 않는지)를 볼 창구가 없으면 분석이 안 되는 이유를 알 방법이 없다.
	 */
	public Map<String, Integer> statusCounts() {
		Map<String, Integer> counts = new LinkedHashMap<>();
		jdbc.query("SELECT status, COUNT(*) AS n FROM analysis_job GROUP BY status ORDER BY status",
				new MapSqlParameterSource(),
				(org.springframework.jdbc.core.RowCallbackHandler)
						rs -> counts.put(rs.getString("status"), rs.getInt("n")));
		return counts;
	}

	// ── 값 타입 ─────────────────────────────────────────────────────────────

	/**
	 * 등록 요청.
	 *
	 * @param payload AI 서비스에 그대로 넘길 요청 본문(JSON 문자열)
	 */
	public record EnqueueRequest(
			AnalysisIdentity identity,
			String inputHash,
			String promptVersion,
			String analysisMode,
			boolean deepMode,
			String payload,
			int priority,
			int maxAttempts) {

		public static EnqueueRequest of(AnalysisIdentity identity, String inputHash, String promptVersion,
				String analysisMode, boolean deepMode, String payload) {
			return new EnqueueRequest(identity, inputHash, promptVersion, analysisMode, deepMode, payload, 0, 3);
		}
	}

	/** 상태 조회 키 — {@code analysis_job} 의 유일 인덱스와 같은 6요소. */
	public record StatusKey(String entityType, String entityId, String entityOrd,
			String inputHash, String promptVersion, String analysisMode) {
	}

	/** 상태 조회 결과. */
	public record JobStatus(long jobId, String status, Long historyId, int attemptCount, int maxAttempts,
			String error, java.time.Instant updatedAt) {
	}

	/**
	 * {@code SELECT *} 결과를 엔티티로 옮긴다.
	 *
	 * <p>같은 트랜잭션에서 네이티브 UPDATE 를 돌린 직후이므로 <b>JPA 로 다시 읽지 않는다</b> —
	 * Hibernate 는 JdbcTemplate 이 바꾼 것을 모르기 때문에 1차 캐시가 낡은 행을 돌려줄 수 있다.
	 * 손매핑이 장황해 보여도 이쪽이 예측 가능하다.
	 */
	private static final class AnalysisJobRowMapper implements org.springframework.jdbc.core.RowMapper<AnalysisJob> {

		static final AnalysisJobRowMapper INSTANCE = new AnalysisJobRowMapper();

		@Override
		public AnalysisJob mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
			return AnalysisJob.fromResultSet(rs);
		}
	}
}
