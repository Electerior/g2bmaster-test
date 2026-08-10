package com.electerior.g2bmaster.schedule;

import com.electerior.g2bmaster.config.G2bProperties;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 자동 갱신 예약 실행기 — {@code lib/sync-scheduler.js} 의 {@code startScheduler} 이식.
 *
 * <p>원본의 {@code setInterval(…, 60_000)} 을 {@link Scheduled}{@code (fixedRate = 60_000)} 으로
 * 바꾼 것이 전부다. 판정은 <b>분 단위</b>다 — 지금 시각을 {@code HH:MM} 문자열로 만들어
 * {@code sync_schedule.time_of_day} 와 문자열 비교한다. 시각을 분해해 비교하지 않는 이유는
 * 컬럼이 {@code CHAR(5)} 이고, 문자열 그대로 두는 편이 인덱스도 타고 오해도 적기 때문이다.
 *
 * <p><b>전체가 {@code g2b.sync.enabled} 에 걸려 있다</b>(기본 꺼짐). 여러 인스턴스를 띄우고
 * 하나만 켜는 운용이 가능하지만, 전부 켜도 {@link SyncScheduleClaimRepository} 의
 * {@code FOR UPDATE SKIP LOCKED} 청구가 중복 실행을 막는다.
 *
 * <p>⚠ <b>실행 본체는 아직 없다.</b> 적재기({@code lib/g2b-loader.js})가 이식되지 않았으므로
 * 여기서는 청구하고 로그를 남기고 결과를 표시할 뿐이다. 가짜 성공("ok")을 남기지 않고
 * '미이식' 사유를 그대로 적는다 — 화면의 {@code last_result} 는 운영자가 읽는 값이고,
 * 거기에 'ok' 가 찍히면 돌지도 않은 적재를 돌았다고 믿게 된다.
 */
@Service
public class SyncSchedulerService {

	private static final Logger log = LoggerFactory.getLogger(SyncSchedulerService.class);

	private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");

	/** 적재기가 붙기 전까지 남길 결과 문구. 운영자가 화면에서 읽는다. */
	static final String NOT_PORTED = "미실행: 적재기가 아직 이식되지 않았습니다.";

	private final SyncScheduleClaimRepository claimRepository;
	private final boolean enabled;

	public SyncSchedulerService(SyncScheduleClaimRepository claimRepository, G2bProperties properties) {
		this.claimRepository = claimRepository;
		this.enabled = properties.sync().enabled();
	}

	/** 원본 {@code hhmm(d)} — 서버 로컬 시각의 {@code HH:MM}. 한 자리는 0 으로 채운다. */
	public static String hhmm(LocalTime time) {
		return time.format(HHMM);
	}

	@Scheduled(fixedRate = 60_000)
	public void tick() {
		if (!enabled) {
			return;
		}
		String now = hhmm(LocalTime.now());
		List<Long> due;
		try {
			due = claimRepository.claimDue(now);
		}
		catch (RuntimeException e) {
			// DB 가 없으면 조용히 넘어간다(원본과 같다). 1분 뒤에 다시 온다.
			log.warn("예약 조회 실패 ({}): {}", now, e.getMessage());
			return;
		}
		for (Long id : due) {
			runOne(id);
		}
	}

	/**
	 * 예약 하나 실행.
	 *
	 * <p>적재기가 붙으면 이 메서드 안에서 {@code loadOperation} 을 부르고 그 결과를 남기면 된다.
	 * 청구·표시·오류 처리는 이미 제자리에 있다.
	 */
	private void runOne(long id) {
		log.warn("예약 {} 이 실행 시각에 도달했지만 적재기가 이식되지 않아 아무것도 하지 않습니다.", id);
		try {
			claimRepository.markResult(id, NOT_PORTED);
		}
		catch (RuntimeException e) {
			log.warn("예약 {} 결과 기록 실패: {}", id, e.getMessage());
		}
	}
}
