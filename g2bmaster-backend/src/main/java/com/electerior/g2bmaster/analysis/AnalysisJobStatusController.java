package com.electerior.g2bmaster.analysis;

import com.electerior.g2bmaster.common.ApiException;
import com.electerior.g2bmaster.config.OpenApiConfig;
import com.electerior.g2bmaster.security.RequireAppAuth;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/analysis-jobs/status} — 검색 결과 카드의 분석 상태 폴링 창구.
 *
 * <p>프론트가 한 화면 분량(최대 500건)을 한 번에 물어본다. 건별 조회로 두면 카드 하나당 요청이
 * 하나씩 나가 폴링이 그대로 부하가 된다.
 *
 * <p>오류 규약은 원본 그대로다 — 잘못된 항목이 <b>하나라도</b> 있으면 400, DB 가 죽으면 503.
 * 부분 성공을 만들지 않는 것은 프론트가 인덱스로 결과를 맞추기 때문이다.
 */
@RestController
@RequestMapping("/api/analysis-jobs")
@Tag(name = OpenApiConfig.TAG_ANALYSIS)
public class AnalysisJobStatusController {

	/** 원본 {@code items.slice(0, 500)} — 초과분은 오류가 아니라 잘라낸다. */
	private static final int MAX_ITEMS = 500;

	/** 원본 {@code lib/search-analysis.js} 의 {@code ANALYSIS_MODE}. */
	private static final String DEFAULT_ANALYSIS_MODE = "deep";

	private final AnalysisJobRepository jobRepository;
	private final AnalysisProperties properties;

	public AnalysisJobStatusController(AnalysisJobRepository jobRepository, AnalysisProperties properties) {
		this.jobRepository = jobRepository;
		this.properties = properties;
	}

	@Operation(summary = "분석 작업 상태 일괄 조회",
			description = """
					한 화면 분량(최대 500건)을 한 번에 묻는다. 큐에 없는 항목은 `pending` 이다. \
					잘못된 항목이 하나라도 있으면 400, DB 가 죽으면 503 — 부분 성공을 만들지 않는 것은 \
					프론트가 인덱스로 결과를 맞추기 때문이다.""")
	@PostMapping("/status")
	@RequireAppAuth
	public Map<String, Object> status(@RequestBody(required = false) StatusRequest request) {
		List<StatusItem> items = request == null || request.items() == null ? List.of() : request.items();
		if (items.size() > MAX_ITEMS) {
			items = items.subList(0, MAX_ITEMS);
		}
		if (items.isEmpty() || items.stream().anyMatch(item -> !item.isValid())) {
			throw ApiException.badRequest("유효한 분석 상태 식별자가 필요합니다.");
		}

		String fallbackPromptVersion = properties.promptVersion() == null ? "" : properties.promptVersion();
		List<AnalysisJobRepository.StatusKey> keys = new ArrayList<>(items.size());
		for (StatusItem item : items) {
			keys.add(item.toKey(fallbackPromptVersion, DEFAULT_ANALYSIS_MODE));
		}

		Map<AnalysisJobRepository.StatusKey, AnalysisJobRepository.JobStatus> found;
		try {
			found = jobRepository.findStatuses(keys);
		}
		catch (DataAccessException e) {
			// 원본과 같은 503. 상태를 못 읽는 것이 화면을 죽일 이유는 없고, 프론트는 재시도한다.
			throw ApiException.unavailable(e.getMostSpecificCause().getMessage());
		}

		List<Map<String, Object>> out = new ArrayList<>(keys.size());
		for (int i = 0; i < keys.size(); i++) {
			AnalysisJobRepository.StatusKey key = keys.get(i);
			AnalysisJobRepository.JobStatus job = found.get(key);
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("entityType", key.entityType());
			row.put("entityId", key.entityId());
			row.put("entityOrd", key.entityOrd());
			row.put("inputHash", key.inputHash());
			row.put("promptVersion", key.promptVersion());
			row.put("analysisMode", key.analysisMode());
			// 큐에 없는 것은 'pending' 이다 — 아직 등록되지 않았거나, 등록이 도는 중이다.
			row.put("status", job == null ? "pending" : job.status());
			row.put("jobId", job == null ? null : job.jobId());
			row.put("historyId", job == null ? null : job.historyId());
			row.put("attemptCount", job == null ? 0 : job.attemptCount());
			row.put("maxAttempts", job == null ? 0 : job.maxAttempts());
			row.put("error", job == null ? null : job.error());
			row.put("updatedAt", job == null ? null : job.updatedAt());
			out.add(row);
		}
		return Map.of("items", out);
	}

	/** 요청 본문. */
	public record StatusRequest(List<StatusItem> items) {
	}

	/**
	 * 상태 조회 항목.
	 *
	 * <p>{@code entityType}·{@code entityId}·{@code inputHash} 는 필수다(원본 검증과 같다).
	 * 나머지는 기본값으로 채운다 — 프론트가 매번 프롬프트 버전을 들고 다니지 않아도 되게 하려는 것이다.
	 */
	public record StatusItem(
			String entityType, String entityId, String entityOrd,
			String inputHash, String promptVersion, String analysisMode) {

		boolean isValid() {
			return AnalysisIdentity.ENTITY_TYPES.contains(entityType == null ? "" : entityType)
					&& entityId != null && !entityId.isBlank()
					&& inputHash != null && !inputHash.isBlank();
		}

		AnalysisJobRepository.StatusKey toKey(String fallbackPromptVersion, String defaultMode) {
			return new AnalysisJobRepository.StatusKey(
					entityType,
					entityId.trim(),
					entityOrd == null ? "" : entityOrd,
					inputHash.trim(),
					promptVersion == null || promptVersion.isBlank() ? fallbackPromptVersion : promptVersion,
					analysisMode == null || analysisMode.isBlank() ? defaultMode : analysisMode);
		}
	}
}
