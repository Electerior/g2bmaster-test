package com.electerior.g2bmaster.system;

import com.electerior.g2bmaster.analysis.AnalysisJobRepository;
import com.electerior.g2bmaster.integration.ai.AiClient;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 시스템 현황 — 창고·적재기·참가자 수집·검색·LLM 을 한 화면에 모은다.
 *
 * <p><b>모든 조각이 실패해도 null 로 흘러야 한다.</b> 이 화면은 "무엇이 죽었는지 보러 오는 곳"이라
 * DB 가 죽었을 때 화면까지 500 이 되면 정확히 필요할 때 못 쓴다. 원본도 조각마다
 * {@code .catch(() => null)} 을 달아 같은 성질을 만들었다.
 *
 * <p>그래서 여기서는 예외를 <b>삼키되 로그로 남긴다</b> — 원본은 조용히 삼켜서 왜 비었는지
 * 알 방법이 없었다.
 */
@Service
public class SystemStatusService {

	private static final Logger log = LoggerFactory.getLogger(SystemStatusService.class);

	/** 원본 {@code DEPLOYMENT_PLATFORM} — 사내망 온프레미스 단독 실행이 전제다. */
	private static final String PLATFORM = "onprem";

	private final SystemStatusRepository repository;
	private final AnalysisJobRepository jobRepository;
	private final AiClient aiClient;

	public SystemStatusService(SystemStatusRepository repository, AnalysisJobRepository jobRepository,
			AiClient aiClient) {
		this.repository = repository;
		this.jobRepository = jobRepository;
		this.aiClient = aiClient;
	}

	public Map<String, Object> status() {
		boolean dbUp = softly("db.ping", () -> {
			repository.ping();
			return Boolean.TRUE;
		}) != null;

		Map<String, Object> db = new LinkedHashMap<>();
		db.put("ok", dbUp);
		// DB 가 죽었으면 뒤이은 집계를 시도조차 하지 않는다 — 커넥션 타임아웃이 조각마다
		// 쌓이면 화면이 뜨는 데 수십 초가 걸린다(그러라고 만든 화면이 아니다).
		db.put("tables", dbUp ? softly("db.tables", repository::warehouseCounts) : null);
		db.put("savedNotices", dbUp ? softly("db.savedNotices", repository::savedNoticeCount) : null);

		Map<String, Object> loader = new LinkedHashMap<>();
		loader.put("sync", dbUp ? softly("loader.sync", repository::syncState) : null);
		loader.put("calls", dbUp ? softly("loader.calls", repository::callSummary) : null);

		Map<String, Object> out = new LinkedHashMap<>();
		out.put("db", db);
		out.put("loader", loader);
		// 원본에 없던 칸이다. 분석 큐가 밀렸는지 볼 창구가 없으면 "분석이 왜 안 되지"를
		// 로그로만 쫓아야 한다 — 적재 실패를 못 보던 것과 같은 문제다(ghost call 89건).
		out.put("analysisQueue", dbUp ? softly("analysisQueue", jobRepository::statusCounts) : null);
		out.put("participants", dbUp ? softly("participants", repository::participantCoverage) : null);
		out.put("llm", llm());
		out.put("search", search());
		out.put("platform", PLATFORM);
		return out;
	}

	/**
	 * LLM 도달 여부는 AI 저장소에 물어본다.
	 *
	 * <p>백엔드가 LM Studio 를 직접 찌르지 않는 것이 경계다(→ {@code docs/ai-boundary.md} §2).
	 * AI 가 꺼져 있거나 못 닿으면 {@code reachable: false} — 오류가 아니다.
	 */
	private Map<String, Object> llm() {
		if (!aiClient.isEnabled()) {
			return Map.of("reachable", false, "enabled", false);
		}
		Map<String, Object> models = softly("llm.models", aiClient::models);
		if (models == null) {
			return Map.of("reachable", false, "enabled", true);
		}
		Map<String, Object> out = new LinkedHashMap<>(models);
		out.putIfAbsent("reachable", true);
		out.put("enabled", true);
		return out;
	}

	/**
	 * 검색 계층 설명.
	 *
	 * <p>원본은 여기서 로컬 임베딩 모델 디렉터리 존재 여부까지 확인했다. 임베딩 생성은
	 * AI 저장소로 넘어갔으므로 백엔드가 볼 수 있는 것은 "AI 가 켜져 있는가"뿐이다.
	 */
	private Map<String, Object> search() {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("modes", List.of("hybrid", "dense", "sparse"));
		out.put("fusion", "normalized-score");
		out.put("sparseWeight", 0.5);
		out.put("embedding", Map.of(
				"delegated", true,
				"enabled", aiClient.isEnabled(),
				"note", "임베딩 생성은 g2bmaster-AI 가 담당한다. 유사도 계산만 백엔드에 남아 있다."));
		return out;
	}

	/** 실패를 {@code null} 로 바꾸되 로그는 남긴다. */
	private <T> T softly(String leg, Callable<T> call) {
		try {
			return call.call();
		}
		catch (Exception e) {
			log.warn("시스템 현황 조각 '{}' 실패: {}", leg, e.getMessage());
			return null;
		}
	}
}
