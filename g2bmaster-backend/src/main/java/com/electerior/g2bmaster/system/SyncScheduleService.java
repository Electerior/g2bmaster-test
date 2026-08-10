package com.electerior.g2bmaster.system;

import com.electerior.g2bmaster.common.ApiException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 예약 관리 — {@code lib/sync-scheduler.js} 의 {@code listSchedules}/{@code upsertSchedule}/
 * {@code deleteSchedule} 이식.
 *
 * <p>검증을 서비스에 둔 이유: DB 에도 {@code sync_schedule_time_chk} CHECK 제약이 있지만,
 * 제약 위반은 SQL 오류로 올라와 <b>사용자에게 보여줄 한국어 문구가 되지 못한다</b>.
 * 원본이 던지던 문구를 그대로 살린다(화면에 그대로 렌더링되는 계약이다).
 */
@Service
public class SyncScheduleService {

	/** V5 의 {@code sync_schedule_time_chk} 와 같은 패턴이어야 한다. */
	private static final Pattern HHMM = Pattern.compile("^([01][0-9]|2[0-3]):[0-5][0-9]$");

	private static final Set<String> SCOPES = Set.of("curated", "important", "all");

	private static final int MIN_LOOKBACK_DAYS = 1;
	private static final int MAX_LOOKBACK_DAYS = 365;

	private final SyncScheduleRepository repository;

	public SyncScheduleService(SyncScheduleRepository repository) {
		this.repository = repository;
	}

	public static boolean isHhmm(String value) {
		return value != null && HHMM.matcher(value).matches();
	}

	public List<Map<String, Object>> list() {
		return repository.findAllByOrderByTimeOfDayAscScopeAsc().stream()
				.map(SyncScheduleService::present)
				.toList();
	}

	/**
	 * 신규/수정.
	 *
	 * <p>{@code id} 가 있으면 수정, 없으면 {@code (시각, 범위)} 로 upsert 한다 —
	 * 같은 슬롯을 두 번 만들지 않는다(유니크 인덱스와 짝).
	 */
	@Transactional
	public Map<String, Object> upsert(ScheduleRequest request) {
		String timeOfDay = request.timeOfDay() == null ? "" : request.timeOfDay().trim();
		if (!isHhmm(timeOfDay)) {
			throw ApiException.badRequest("시각은 HH:MM (24시간) 형식이어야 합니다.");
		}
		String scope = request.scope() == null || request.scope().isBlank()
				? "important" : request.scope().trim();
		if (!SCOPES.contains(scope)) {
			throw ApiException.badRequest("scope 값이 올바르지 않습니다.");
		}
		// 원본: Math.min(Math.max(Number(lookbackDays) || 1, 1), 365) — 범위를 벗어나면 자른다(오류 아님).
		int lookbackDays = clampLookbackDays(request.lookbackDays() == null ? 3 : request.lookbackDays());
		boolean withAttachments = request.withAttachments() == null || request.withAttachments();
		boolean enabled = request.enabled() == null || request.enabled();

		SyncSchedule schedule;
		if (request.id() != null) {
			schedule = repository.findById(request.id())
					.orElseThrow(() -> ApiException.badRequest("예약을 찾을 수 없습니다."));
			schedule.setTimeOfDay(timeOfDay);
			schedule.setScope(scope);
		}
		else {
			schedule = repository.findByTimeOfDayAndScope(timeOfDay, scope)
					.orElseGet(() -> new SyncSchedule(timeOfDay, scope, lookbackDays, withAttachments, enabled));
		}
		schedule.setLookbackDays(lookbackDays);
		schedule.setWithAttachments(withAttachments);
		schedule.setEnabled(enabled);
		return present(repository.save(schedule));
	}

	@Transactional
	public void delete(long id) {
		repository.deleteById(id);
	}

	/** 원본 {@code Math.min(Math.max(n || 1, 1), 365)} — 범위를 벗어나면 오류가 아니라 자른다. */
	public static int clampLookbackDays(int lookbackDays) {
		return Math.min(Math.max(lookbackDays < MIN_LOOKBACK_DAYS ? MIN_LOOKBACK_DAYS : lookbackDays,
				MIN_LOOKBACK_DAYS), MAX_LOOKBACK_DAYS);
	}

	/** 응답 키는 원본(snake_case 컬럼명 그대로)과 맞춘다 — 프론트가 그 이름을 읽는다. */
	private static Map<String, Object> present(SyncSchedule schedule) {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("id", schedule.getId());
		out.put("time_of_day", schedule.getTimeOfDay());
		out.put("scope", schedule.getScope());
		out.put("lookback_days", schedule.getLookbackDays());
		out.put("with_attachments", schedule.isWithAttachments());
		out.put("enabled", schedule.isEnabled());
		out.put("last_run_at", schedule.getLastRunAt());
		out.put("last_result", schedule.getLastResult());
		return out;
	}

	/**
	 * 예약 생성/수정 요청.
	 *
	 * <p>박싱 타입인 것은 의도다 — {@code null} 은 "안 보냈다"이고 기본값으로 채운다.
	 * {@code boolean} 으로 두면 미전송이 곧 {@code false} 가 되어 예약이 조용히 꺼진다.
	 */
	public record ScheduleRequest(
			Long id, String timeOfDay, String scope,
			Integer lookbackDays, Boolean withAttachments, Boolean enabled) {
	}
}
