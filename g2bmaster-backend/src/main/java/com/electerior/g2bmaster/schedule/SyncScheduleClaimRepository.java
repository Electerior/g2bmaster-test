package com.electerior.g2bmaster.schedule;

import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 실행할 예약을 <b>배타적으로</b> 집는다.
 *
 * <h2>왜 잠금이 필요한가 — 원본은 단일 인스턴스 전제였다</h2>
 *
 * <p>원본 {@code lib/sync-scheduler.js} 는 {@code dueSchedules()} 로 목록을 읽고,
 * 작업을 돌린 <b>뒤에</b> {@code markRun()} 으로 {@code last_run_at} 을 찍는다.
 * 조회와 표시 사이에 창(window)이 열려 있어서, 인스턴스가 둘이면 둘 다 같은 예약을 읽고
 * 둘 다 적재를 시작한다. 적재는 나라장터 API 호출량을 쓰는 작업이라 중복 실행이 곧 할당량 소모다
 * (원본이 단일 인스턴스에서만 안전했던 것은 설계가 아니라 우연이다).
 *
 * <p>그래서 <b>집는 순간 표시까지 한 트랜잭션에서</b> 끝낸다:
 * <ol>
 *   <li>{@code SELECT ... FOR UPDATE SKIP LOCKED} — 다른 복제본이 이미 집은 행은 건너뛴다.
 *       {@code SKIP LOCKED} 가 없으면 두 번째 복제본이 잠금을 <b>기다렸다가</b> 이어서 실행해
 *       중복이 그대로 재현된다.</li>
 *   <li>같은 트랜잭션에서 {@code last_run_at = NOW(6)} 을 찍는다. 커밋 뒤에는 다른 복제본이
 *       읽어도 '이미 이번 분에 돌았다'로 걸러진다.</li>
 * </ol>
 *
 * <p>실행 결과({@code last_result})는 나중에 따로 기록한다 — 작업이 몇 분씩 걸리는데
 * 그동안 행 잠금을 붙들고 있을 이유가 없다.
 */
@Repository
public class SyncScheduleClaimRepository {

	private final NamedParameterJdbcTemplate jdbc;

	public SyncScheduleClaimRepository(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * 지금(HH:MM) 실행할 예약을 집는다.
	 *
	 * <p>중복 방지의 핵심은 {@code last_run_at} 을 <b>분 단위로 잘라</b> 비교하는 것이다.
	 * 서버가 잠깐 멈췄다 떠도 같은 분 안이면 실행되고, 한 번 돈 뒤에는 다음 분까지 다시 돌지 않는다.
	 *
	 * <p>{@code REQUIRES_NEW} 로 잠금 구간을 호출부와 분리한다 — 작업 실행이 이 트랜잭션에
	 * 얹히면 잠금이 작업 시간만큼 유지된다.
	 *
	 * @param hhmm 서버 로컬 시각 {@code HH:MM}
	 * @return 이 인스턴스가 소유하게 된 예약 id 들
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public List<Long> claimDue(String hhmm) {
		List<Long> due = jdbc.queryForList("""
				SELECT id FROM sync_schedule
				 WHERE enabled = 1
				   AND time_of_day = :hhmm
				   AND (last_run_at IS NULL
				        OR DATE_FORMAT(last_run_at, '%Y-%m-%d %H:%i') < DATE_FORMAT(NOW(6), '%Y-%m-%d %H:%i'))
				 ORDER BY id
				 FOR UPDATE SKIP LOCKED
				""", new MapSqlParameterSource("hhmm", hhmm), Long.class);

		if (!due.isEmpty()) {
			// 같은 트랜잭션 안에서 곧바로 표시한다. 이 UPDATE 가 커밋되기 전에는
			// 다른 복제본이 위 SELECT 에서 이 행들을 건너뛰고 있다.
			jdbc.update("UPDATE sync_schedule SET last_run_at = NOW(6) WHERE id IN (:ids)",
					new MapSqlParameterSource("ids", due));
		}
		return due;
	}

	/** 실행 결과를 남긴다. 원본과 같이 300자에서 자른다. */
	public void markResult(long id, String result) {
		String text = result == null ? "" : result;
		jdbc.update("UPDATE sync_schedule SET last_result = :result WHERE id = :id",
				new MapSqlParameterSource()
						.addValue("result", text.length() > 300 ? text.substring(0, 300) : text)
						.addValue("id", id));
	}
}
