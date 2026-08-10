package com.electerior.g2bmaster.system;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 자동 갱신 예약 한 건 — 하루 중 '몇 시 몇 분'에 어떤 범위를 적재할지.
 *
 * <p>cron 표현식을 쓰지 않은 이유는 원본 주석 그대로다: 필요한 건 시각뿐이고, cron 문법은
 * 화면에서 편집하기도 검증하기도 번거롭다. 하루에 여러 번 돌리려면 행을 여러 개 두면 된다.
 *
 * <p>{@code time_of_day} 는 <b>서버 로컬 시각</b>이다(UTC 가 아니다). {@code DATETIME(6)} 컬럼들이
 * UTC 인 것과 다르므로 헷갈리지 말 것 — 사람이 "매일 아침 6시"라고 말할 때의 그 6시다.
 */
@Entity
@Table(name = "sync_schedule")
public class SyncSchedule {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "time_of_day", nullable = false, columnDefinition = "char(5)")
	private String timeOfDay;

	@Column(name = "scope", nullable = false, length = 64)
	private String scope = "important";

	@Column(name = "lookback_days", nullable = false)
	private int lookbackDays = 3;

	@Column(name = "with_attachments", nullable = false)
	private boolean withAttachments = true;

	@Column(name = "enabled", nullable = false)
	private boolean enabled = true;

	@Column(name = "last_run_at")
	private Instant lastRunAt;

	@Column(name = "last_result", columnDefinition = "text")
	private String lastResult;

	@Column(name = "created_at", insertable = false, updatable = false)
	private Instant createdAt;

	protected SyncSchedule() {
	}

	public SyncSchedule(String timeOfDay, String scope, int lookbackDays, boolean withAttachments, boolean enabled) {
		this.timeOfDay = timeOfDay;
		this.scope = scope;
		this.lookbackDays = lookbackDays;
		this.withAttachments = withAttachments;
		this.enabled = enabled;
	}

	public Long getId() {
		return id;
	}

	public String getTimeOfDay() {
		return timeOfDay;
	}

	public void setTimeOfDay(String timeOfDay) {
		this.timeOfDay = timeOfDay;
	}

	public String getScope() {
		return scope;
	}

	public void setScope(String scope) {
		this.scope = scope;
	}

	public int getLookbackDays() {
		return lookbackDays;
	}

	public void setLookbackDays(int lookbackDays) {
		this.lookbackDays = lookbackDays;
	}

	public boolean isWithAttachments() {
		return withAttachments;
	}

	public void setWithAttachments(boolean withAttachments) {
		this.withAttachments = withAttachments;
	}

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public Instant getLastRunAt() {
		return lastRunAt;
	}

	public String getLastResult() {
		return lastResult;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
