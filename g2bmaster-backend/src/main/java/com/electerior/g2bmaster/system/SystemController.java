package com.electerior.g2bmaster.system;

import com.electerior.g2bmaster.common.ApiException;
import com.electerior.g2bmaster.config.OpenApiConfig;
import com.electerior.g2bmaster.integration.g2b.G2bOperation;
import com.electerior.g2bmaster.integration.g2b.G2bOperationCatalog;
import com.electerior.g2bmaster.security.RequireAppAuth;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 운영 콘솔 — {@code /api/system/*}.
 *
 * <p>인증 정책이 갈린다: <b>조회는 열려 있고 쓰기만 앱 키</b>가 필요하다. 원본 그대로이며,
 * 사내망 온프레미스 전제에서 현황 화면을 열어 두는 것이 의도다.
 */
@RestController
@RequestMapping("/api/system")
@Tag(name = OpenApiConfig.TAG_SYSTEM)
public class SystemController {

	/** 원본 {@code Math.min(Number(limit) || 100, 500)}. */
	private static final int DEFAULT_CALL_LIMIT = 100;
	private static final int MAX_CALL_LIMIT = 500;

	private static final List<String> SCOPES = List.of("curated", "important", "all");

	private final SystemStatusService statusService;
	private final SystemStatusRepository statusRepository;
	private final SystemTableCountRepository tableCountRepository;
	private final SyncScheduleService scheduleService;
	private final G2bOperationCatalog operationCatalog;

	public SystemController(SystemStatusService statusService, SystemStatusRepository statusRepository,
			SystemTableCountRepository tableCountRepository, SyncScheduleService scheduleService,
			G2bOperationCatalog operationCatalog) {
		this.statusService = statusService;
		this.statusRepository = statusRepository;
		this.tableCountRepository = tableCountRepository;
		this.scheduleService = scheduleService;
		this.operationCatalog = operationCatalog;
	}

	/** 현황. 어떤 조각이 실패해도 200 이고 그 자리에 {@code null} 이 온다. */
	@Operation(summary = "운영 현황",
			description = "어떤 조각이 실패해도 200 이고 그 자리에 `null` 이 온다.")
	@GetMapping("/status")
	public Map<String, Object> status() {
		return statusService.status();
	}

	/**
	 * 호출 이력. 실패만 보려면 {@code onlyFailed=1}.
	 *
	 * <p>DB 가 죽었을 때 503 에 {@code calls: []} 를 함께 주는 것도 원본 계약이다 —
	 * 프론트가 빈 배열을 그대로 렌더링할 수 있어야 한다.
	 */
	@Operation(summary = "나라장터 호출 이력",
			description = "실패만 보려면 `onlyFailed=1`. `limit` 은 기본 100, 최대 500.")
	@GetMapping("/calls")
	public Map<String, Object> calls(
			@RequestParam(name = "onlyFailed", required = false) String onlyFailed,
			@RequestParam(name = "operation", required = false) String operation,
			@RequestParam(name = "limit", required = false) Integer limit) {
		int max = limit == null || limit <= 0 ? DEFAULT_CALL_LIMIT : Math.min(limit, MAX_CALL_LIMIT);
		try {
			return Map.of("calls", statusRepository.calls("1".equals(onlyFailed), operation, max));
		}
		catch (DataAccessException e) {
			throw ApiException.unavailable(e.getMostSpecificCause().getMessage());
		}
	}

	/**
	 * 189종 오퍼레이션 카탈로그.
	 *
	 * <p>어디까지 적재할지는 코드가 아니라 화면에서 정해야 한다는 것이 이 엔드포인트의 존재 이유다.
	 */
	@Operation(summary = "오퍼레이션 카탈로그 (189종)",
			description = "`curated` · `important` · `all` 세 범위로 나뉜다. 어디까지 적재할지는 코드가 아니라 화면에서 정한다.")
	@GetMapping("/operations")
	public Map<String, Object> operations() {
		Map<String, Set<String>> inScope = new LinkedHashMap<>();
		for (String scope : SCOPES) {
			Set<String> ops = new LinkedHashSet<>();
			operationCatalog.selectOperations(scope).forEach(operation -> ops.add(operation.op()));
			inScope.put(scope, ops);
		}

		List<Map<String, Object>> all = new ArrayList<>();
		for (G2bOperation operation : operationCatalog.selectOperations("all")) {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("op", operation.op());
			row.put("service", operation.service());
			row.put("table", operation.table());
			row.put("windowed", operation.windowed());
			row.put("inqryDivs", operation.inqryDivs());
			row.put("scopes", SCOPES.stream().filter(s -> inScope.get(s).contains(operation.op())).toList());
			all.add(row);
		}

		Map<String, Integer> counts = new LinkedHashMap<>();
		inScope.forEach((scope, ops) -> counts.put(scope, ops.size()));
		return Map.of("counts", counts, "operations", all);
	}

	/**
	 * 테이블별 행수.
	 *
	 * <p>{@code approximate} 가 붙는 이유는 {@link SystemTableCountRepository} 주석 참고 —
	 * 원본의 전수 {@code COUNT(*)} 를 인젝션 형태 때문에 옮기지 않았다.
	 */
	@Operation(summary = "테이블별 행수",
			description = "InnoDB 통계 기반 **추정치**다 — 응답의 `approximate: true` 가 그 뜻이다.")
	@GetMapping("/tables")
	public Map<String, Object> tables() {
		try {
			return Map.of(
					"tables", tableCountRepository.tableRowCounts(),
					"approximate", true,
					"note", "InnoDB 통계 기반 추정치입니다. 정확한 건수가 필요하면 해당 테이블을 직접 세십시오.");
		}
		catch (DataAccessException e) {
			throw ApiException.unavailable(e.getMostSpecificCause().getMessage());
		}
	}

	// ── 자동 갱신 예약 ───────────────────────────────────────────────────────

	@Operation(summary = "자동 갱신 예약 목록")
	@GetMapping("/schedules")
	public Map<String, Object> schedules() {
		try {
			return Map.of("schedules", scheduleService.list());
		}
		catch (DataAccessException e) {
			throw ApiException.unavailable(e.getMostSpecificCause().getMessage());
		}
	}

	@Operation(summary = "자동 갱신 예약 등록·수정",
			description = "시각은 `HH:MM` (24시간) 형식이어야 한다. 어긋나면 400.")
	@PostMapping("/schedules")
	@RequireAppAuth
	public Map<String, Object> upsertSchedule(@RequestBody SyncScheduleService.ScheduleRequest request) {
		return Map.of("schedule", scheduleService.upsert(request));
	}

	@Operation(summary = "자동 갱신 예약 삭제")
	@DeleteMapping("/schedules/{id}")
	@RequireAppAuth
	public Map<String, Object> deleteSchedule(@PathVariable("id") long id) {
		scheduleService.delete(id);
		return Map.of("deleted", true);
	}

	// ── 적재 (미이식) ────────────────────────────────────────────────────────

	/**
	 * 적재 진행 상황.
	 *
	 * <p>적재기({@code lib/g2b-loader.js})는 아직 이식되지 않았다. 진행 중인 작업이 있을 수 없으므로
	 * {@code {running:false}} 는 <b>거짓말이 아니라 사실</b>이다. 그래서 이 조회만 살려 둔다 —
	 * 화면이 이 값을 폴링하는데 404 를 주면 콘솔이 통째로 오류 상태가 된다.
	 */
	@Operation(summary = "적재 진행 상황",
			description = "적재기가 미이식이라 진행 중인 작업이 있을 수 없다 — `{running:false, ported:false}` 는 사실이다.")
	@GetMapping("/backfill")
	public Map<String, Object> backfill() {
		return Map.of("running", false, "ported", false,
				"note", "적재기가 아직 이식되지 않았습니다. 적재는 CLI 로 수행하십시오.");
	}

	/**
	 * 적재 시작 — <b>미구현(501)</b>.
	 *
	 * <p>가짜 성공(202)을 주지 않는 것이 중요하다. 화면은 202 를 받으면 진행률을 폴링하기 시작하고,
	 * 아무 일도 일어나지 않는 것을 "느린 적재"로 오해한다. 운영자가 그 상태로 몇 시간을 기다리는
	 * 쪽이 명확한 501 보다 훨씬 나쁘다.
	 */
	@Operation(summary = "적재 시작 — 미이식(501)",
			description = """
					가짜 성공(202)을 주지 않는다. 화면은 202 를 받으면 진행률을 폴링하기 시작하고, \
					아무 일도 없는 것을 "느린 적재"로 오해한다. 응답 코드는 `NOT_PORTED`.""")
	@PostMapping("/backfill")
	@RequireAppAuth
	public void startBackfill() {
		throw new ApiException(HttpStatus.NOT_IMPLEMENTED,
				"적재기가 아직 이식되지 않았습니다. 적재는 원본 저장소의 CLI(npm run backfill)로 수행하십시오.",
				"NOT_PORTED");
	}

	/** 적재 중단 — <b>미구현(501)</b>. 시작할 수 없으므로 멈출 것도 없다. */
	@Operation(summary = "적재 중단 — 미이식(501)", description = "시작할 수 없으므로 멈출 것도 없다.")
	@DeleteMapping("/backfill")
	@RequireAppAuth
	public void cancelBackfill() {
		throw new ApiException(HttpStatus.NOT_IMPLEMENTED,
				"적재기가 아직 이식되지 않았습니다. 중단할 적재가 존재하지 않습니다.",
				"NOT_PORTED");
	}
}
