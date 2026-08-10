package com.electerior.g2bmaster.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import com.electerior.g2bmaster.system.SyncScheduleService;
import java.time.LocalTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 예약 시각 판정 — 원본 {@code lib/sync-scheduler.js} 하단 self-check 블록의 이식.
 */
class SyncSchedulerTest {

	@Test
	@DisplayName("hhmm — 한 자리 시각은 0으로 채운다")
	void hhmm() {
		assertThat(SyncSchedulerService.hhmm(LocalTime.of(9, 5))).isEqualTo("09:05");
		assertThat(SyncSchedulerService.hhmm(LocalTime.of(23, 59))).isEqualTo("23:59");
		assertThat(SyncSchedulerService.hhmm(LocalTime.of(0, 0))).isEqualTo("00:00");
		// 초는 판정에 들어가지 않는다 — 판정 단위가 '분'인 것이 중복 실행 방지의 전제다.
		assertThat(SyncSchedulerService.hhmm(LocalTime.of(12, 30, 59))).isEqualTo("12:30");
	}

	@Test
	@DisplayName("isHhmm — 정상 시각 통과, 잘못된 시각 거부")
	void isHhmm() {
		assertThat(SyncScheduleService.isHhmm("00:00")).isTrue();
		assertThat(SyncScheduleService.isHhmm("23:59")).isTrue();
		assertThat(SyncScheduleService.isHhmm("24:00")).isFalse();
		assertThat(SyncScheduleService.isHhmm("9:05")).isFalse();
		assertThat(SyncScheduleService.isHhmm("12:60")).isFalse();
		assertThat(SyncScheduleService.isHhmm("")).isFalse();
		assertThat(SyncScheduleService.isHhmm(null)).isFalse();
	}

	@Test
	@DisplayName("lookbackDays — 1..365 로 자른다 (오류가 아니라 클램프)")
	void clampLookbackDays() {
		assertThat(SyncScheduleService.clampLookbackDays(0)).isEqualTo(1);
		assertThat(SyncScheduleService.clampLookbackDays(-10)).isEqualTo(1);
		assertThat(SyncScheduleService.clampLookbackDays(3)).isEqualTo(3);
		assertThat(SyncScheduleService.clampLookbackDays(365)).isEqualTo(365);
		assertThat(SyncScheduleService.clampLookbackDays(1000)).isEqualTo(365);
	}
}
