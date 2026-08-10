package com.electerior.g2bmaster.system;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 예약 CRUD.
 *
 * <p>모양이 안정적이고 잠금 힌트가 필요 없으므로 JPA 로 둔다. 스케줄러가 실행 대상을
 * 집을 때만 네이티브 잠금 SQL 이 필요한데, 그쪽은
 * {@code com.electerior.g2bmaster.schedule.SyncScheduleClaimRepository} 가 담당한다.
 */
public interface SyncScheduleRepository extends JpaRepository<SyncSchedule, Long> {

	List<SyncSchedule> findAllByOrderByTimeOfDayAscScopeAsc();

	/** 유니크 인덱스 {@code (time_of_day, scope)} 와 짝 — 같은 슬롯을 두 번 만들지 않는다. */
	Optional<SyncSchedule> findByTimeOfDayAndScope(String timeOfDay, String scope);
}
