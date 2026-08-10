package com.electerior.g2bmaster.analysis;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 영속 분석 작업 큐의 한 행.
 *
 * <p><b>엔티티는 읽기 전용 경로에만 쓴다.</b> 큐 조작(청구·리스 갱신·완료·실패·회수)은
 * {@code FOR UPDATE SKIP LOCKED} 와 조건부 갱신이 필요해 JPA 로 표현할 수 없다 —
 * 그쪽은 {@link AnalysisJobRepository} 의 네이티브 SQL 이 담당한다. 여기서 엔티티를 두는 이유는
 * 단건 조회와 상태 집계를 굳이 손으로 매핑하지 않기 위해서다.
 *
 * <p>{@code identity} 5요소 + {@code analysis_mode} 가 유일키다
 * ({@code analysis_job_identity_idx}). 같은 공고라도 프롬프트 버전이나 입력이 바뀌면
 * 다른 작업이 되는 것이 의도다.
 */
@Entity
@Table(name = "analysis_job")
public class AnalysisJob {

	public static final String QUEUED = "queued";
	public static final String RUNNING = "running";
	public static final String COMPLETED = "completed";
	public static final String FAILED = "failed";
	public static final String CANCELLED = "cancelled";

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "entity_type", nullable = false, length = 64)
	private String entityType;

	@Column(name = "entity_id", nullable = false, length = 64)
	private String entityId;

	@Column(name = "entity_ord", nullable = false, length = 64)
	private String entityOrd = "";

	@Column(name = "input_hash", nullable = false, length = 64)
	private String inputHash;

	@Column(name = "prompt_version", nullable = false, length = 64)
	private String promptVersion;

	@Column(name = "analysis_mode", nullable = false, length = 64)
	private String analysisMode;

	@Column(name = "payload", nullable = false, columnDefinition = "json")
	private String payload;

	@Column(name = "status", nullable = false, length = 64)
	private String status;

	@Column(name = "priority", nullable = false)
	private short priority;

	@Column(name = "attempt_count", nullable = false)
	private int attemptCount;

	@Column(name = "max_attempts", nullable = false)
	private int maxAttempts;

	@Column(name = "worker_id", columnDefinition = "text")
	private String workerId;

	@Column(name = "available_at", nullable = false)
	private Instant availableAt;

	@Column(name = "lease_expires_at")
	private Instant leaseExpiresAt;

	@Column(name = "heartbeat_at")
	private Instant heartbeatAt;

	@Column(name = "started_at")
	private Instant startedAt;

	@Column(name = "completed_at")
	private Instant completedAt;

	@Column(name = "last_error", columnDefinition = "text")
	private String lastError;

	@Column(name = "analysis_history_id")
	private Long analysisHistoryId;

	@Column(name = "created_at", insertable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", insertable = false, updatable = false)
	private Instant updatedAt;

	protected AnalysisJob() {
	}

	/**
	 * 네이티브 조회 결과를 엔티티로 옮긴다 — {@link AnalysisJobRepository} 전용.
	 *
	 * <p>필드 정의 바로 옆에 두는 이유는 컬럼 하나를 추가하고 매핑을 빠뜨리는 사고를 줄이기 위해서다.
	 */
	static AnalysisJob fromResultSet(java.sql.ResultSet rs) throws java.sql.SQLException {
		AnalysisJob job = new AnalysisJob();
		job.id = rs.getLong("id");
		job.entityType = rs.getString("entity_type");
		job.entityId = rs.getString("entity_id");
		job.entityOrd = rs.getString("entity_ord");
		job.inputHash = rs.getString("input_hash");
		job.promptVersion = rs.getString("prompt_version");
		job.analysisMode = rs.getString("analysis_mode");
		job.payload = rs.getString("payload");
		job.status = rs.getString("status");
		job.priority = rs.getShort("priority");
		job.attemptCount = rs.getInt("attempt_count");
		job.maxAttempts = rs.getInt("max_attempts");
		job.workerId = rs.getString("worker_id");
		job.availableAt = instant(rs, "available_at");
		job.leaseExpiresAt = instant(rs, "lease_expires_at");
		job.heartbeatAt = instant(rs, "heartbeat_at");
		job.startedAt = instant(rs, "started_at");
		job.completedAt = instant(rs, "completed_at");
		job.lastError = rs.getString("last_error");
		job.analysisHistoryId = rs.getObject("analysis_history_id", Long.class);
		job.createdAt = instant(rs, "created_at");
		job.updatedAt = instant(rs, "updated_at");
		return job;
	}

	private static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
		java.sql.Timestamp value = rs.getTimestamp(column);
		return value == null ? null : value.toInstant();
	}

	public Long getId() {
		return id;
	}

	public String getEntityType() {
		return entityType;
	}

	public String getEntityId() {
		return entityId;
	}

	public String getEntityOrd() {
		return entityOrd;
	}

	public String getInputHash() {
		return inputHash;
	}

	public String getPromptVersion() {
		return promptVersion;
	}

	public String getAnalysisMode() {
		return analysisMode;
	}

	public String getPayload() {
		return payload;
	}

	public String getStatus() {
		return status;
	}

	public short getPriority() {
		return priority;
	}

	public int getAttemptCount() {
		return attemptCount;
	}

	public int getMaxAttempts() {
		return maxAttempts;
	}

	public String getWorkerId() {
		return workerId;
	}

	public Instant getAvailableAt() {
		return availableAt;
	}

	public Instant getLeaseExpiresAt() {
		return leaseExpiresAt;
	}

	public Instant getHeartbeatAt() {
		return heartbeatAt;
	}

	public Instant getStartedAt() {
		return startedAt;
	}

	public Instant getCompletedAt() {
		return completedAt;
	}

	public String getLastError() {
		return lastError;
	}

	public Long getAnalysisHistoryId() {
		return analysisHistoryId;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}
}
