package com.electerior.g2bmaster.analysis;

import com.electerior.g2bmaster.integration.ai.AiClient;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 분석 워커 루프 — {@code lib/analysis-job-runner.js} 이식.
 *
 * <p>구조는 원본 그대로다: 청구 → heartbeat 시작 → 실행 → 완료/실패 → heartbeat 정지.
 * <b>추론은 하지 않는다</b> — {@link AiClient#itemSummary} 로 넘기고, 돌아온 결과를
 * {@code analysis_history} 에 적재한 뒤 그 id 로 작업을 완료 처리한다
 * (→ {@code docs/ai-boundary.md} §1·§6.3).
 *
 * <p><b>결과 이력을 백엔드가 저장하는 이유.</b> AI 서비스는 MySQL 을 모른다. 초기 계약 초안은
 * AI 응답이 {@code _analysisHistoryId} 를 담아 오도록 했지만, 그 id 는 백엔드 소유 테이블의
 * PK 라서 AI 쪽에서 만들 방법이 없었다(저장용 엔드포인트도 없다). 소유권 원칙(§1)에 맞춰
 * 적재를 이쪽으로 가져왔다.
 *
 * <p><b>기본은 꺼져 있다</b>({@code g2b.analysis.runner-enabled=false}). 켜지 않으면 큐에 작업이
 * 쌓이기만 하고 아무것도 소비되지 않는다 — AI 저장소가 아직 없는 현 단계의 정상 상태다.
 *
 * <p>여러 인스턴스가 동시에 켜져도 안전하다. 청구가 {@code FOR UPDATE SKIP LOCKED} 라
 * 같은 행을 둘이 집지 못한다.
 */
@Component
public class AnalysisJobRunner {

	private static final Logger log = LoggerFactory.getLogger(AnalysisJobRunner.class);

	private static final ObjectMapper MAPPER = JsonMapper.builder().build();

	/** 원본 {@code deep} 판정용. {@code AnalysisInputHasher} 의 것과 같은 정규식이다. */
	private static final Pattern DEEP_VALUE = Pattern.compile("^(1|true|yes|y|on|deep|full)$",
			Pattern.CASE_INSENSITIVE);

	private final AnalysisJobRepository jobRepository;
	private final AnalysisHistoryRepository historyRepository;
	private final AiClient aiClient;
	private final AnalysisProperties properties;

	/** 소유권 확인용 워커 식별자. 프로세스마다 달라야 리스 회수가 제대로 동작한다. */
	private final String workerId = "analysis-" + ProcessHandle.current().pid()
			+ "-" + UUID.randomUUID().toString().substring(0, 8);

	private final AtomicBoolean running = new AtomicBoolean(false);

	private ExecutorService workers;
	private ScheduledExecutorService heartbeats;

	public AnalysisJobRunner(AnalysisJobRepository jobRepository, AnalysisHistoryRepository historyRepository,
			AiClient aiClient, AnalysisProperties properties) {
		this.jobRepository = jobRepository;
		this.historyRepository = historyRepository;
		this.aiClient = aiClient;
		this.properties = properties;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void startIfEnabled() {
		if (!properties.runnerEnabled()) {
			log.info("분석 워커가 꺼져 있습니다 (g2b.analysis.runner-enabled=false). 큐는 쌓이기만 합니다.");
			return;
		}
		if (!aiClient.isEnabled()) {
			// 켜 두고 AI 를 끄면 모든 작업이 max_attempts 까지 실패한 뒤 failed 로 굳는다.
			// 조용히 큐를 태워 없애는 것보다 뜨지 않는 편이 낫다.
			log.warn("분석 워커를 켜려 했으나 AI 가 꺼져 있습니다 (g2b.ai.enabled=false). 시작하지 않습니다.");
			return;
		}
		start();
	}

	void start() {
		if (!running.compareAndSet(false, true)) {
			return;
		}
		int concurrency = properties.concurrency();
		workers = Executors.newFixedThreadPool(concurrency, Thread.ofPlatform().name("analysis-worker-", 0).factory());
		heartbeats = Executors.newScheduledThreadPool(1, Thread.ofPlatform().name("analysis-heartbeat-", 0).factory());
		for (int i = 0; i < concurrency; i++) {
			int index = i;
			workers.submit(() -> loop(index));
		}
		log.info("분석 워커 {}개 시작 (workerId={}, lease={}ms)", concurrency, workerId, properties.leaseMs());
	}

	@PreDestroy
	public void stop() {
		if (!running.compareAndSet(true, false)) {
			return;
		}
		if (heartbeats != null) {
			heartbeats.shutdownNow();
		}
		if (workers != null) {
			workers.shutdown();   // 진행 중인 작업은 끝까지 둔다 — 중간 절단은 리스 만료 회수에 맡기는 게 낫다.
		}
	}

	// ── 루프 ────────────────────────────────────────────────────────────────

	private void loop(int index) {
		if (index == 0) {
			sweepExpired();
		}
		while (running.get()) {
			AnalysisJob job;
			try {
				job = jobRepository.claim(workerId, properties.leaseMs());
			}
			catch (RuntimeException e) {
				log.warn("분석 작업 청구 실패: {}", e.getMessage());
				sleep(properties.pollMs());
				continue;
			}
			if (job == null) {
				// 큐가 비었을 때가 만료 회수를 돌리기 가장 좋은 시점이다(원본과 같은 판단).
				if (index == 0) {
					sweepExpired();
				}
				sleep(properties.pollMs());
				continue;
			}
			execute(job);
		}
	}

	private void execute(AnalysisJob job) {
		long id = job.getId();
		// heartbeat 주기는 리스의 1/3 이다. 한 번 놓쳐도 리스가 살아 있어야 회수되지 않는다.
		long interval = Math.max(1000L, properties.leaseMs() / 3);
		ScheduledFuture<?> beat = heartbeats.scheduleAtFixedRate(() -> {
			try {
				jobRepository.heartbeat(id, workerId, properties.leaseMs());
			}
			catch (RuntimeException e) {
				log.warn("분석 작업 {} heartbeat 실패: {}", id, e.getMessage());
			}
		}, interval, interval, TimeUnit.MILLISECONDS);

		try {
			Map<String, Object> payload = readPayload(job.getPayload());
			Map<String, Object> result = aiClient.itemSummary(payload);

			// 결과 이력은 **백엔드가 저장한다**(docs/ai-boundary.md §1: 내구성 있는 상태는 백엔드 소유).
			// AI 서비스는 MySQL 을 모르므로 analysis_history 의 id 를 만들 수 없다.
			// 원본 모놀리스에서는 server.js 가 DB 와 추론을 둘 다 가져서 한 곳에서 처리됐다.
			long historyId = saveHistory(job, payload, result);
			if (historyId <= 0) {
				throw new IllegalStateException("분석 이력을 저장하지 못했습니다.");
			}
			if (jobRepository.complete(id, workerId, historyId) == null) {
				throw new IllegalStateException("분석 작업 " + id + " 완료 소유권을 잃었습니다.");
			}
		}
		catch (RuntimeException e) {
			// 지수 백오프 — 원본 retryBaseMs × 2^(attempt-1).
			// attempt_count 는 청구 시점에 이미 1 이상으로 올라가 있다.
			long backoff = properties.retryBaseMs() * (1L << Math.max(0, job.getAttemptCount() - 1));
			log.warn("분석 작업 {} 실패({}/{}): {}", id, job.getAttemptCount(), job.getMaxAttempts(), e.getMessage());
			try {
				jobRepository.fail(id, workerId, e.getMessage(), backoff);
			}
			catch (RuntimeException failure) {
				log.warn("분석 작업 {} 실패 기록조차 실패: {}", id, failure.getMessage());
			}
		}
		finally {
			beat.cancel(false);
		}
	}

	private void sweepExpired() {
		try {
			int recovered = jobRepository.recoverExpired();
			if (recovered > 0) {
				log.info("리스 만료 분석 작업 {}건을 회수했습니다.", recovered);
			}
		}
		catch (RuntimeException e) {
			log.warn("만료 작업 회수 실패: {}", e.getMessage());
		}
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> readPayload(String payload) {
		if (payload == null || payload.isBlank()) {
			return Map.of();
		}
		return MAPPER.readValue(payload, Map.class);
	}

	/**
	 * AI 응답을 {@code analysis_history} 에 적재하고 그 id 를 돌려준다.
	 *
	 * <p>원본 {@code server.js} 의 {@code saveAnalysisHistory(...)} 호출을 그대로 옮긴 것이다.
	 * 자연키·입력해시·프롬프트버전은 <b>작업 행</b>에서, 화면 표시용 메타는 <b>요청 payload</b>에서,
	 * 분석 산출물은 <b>AI 응답</b>에서 가져온다.
	 *
	 * <p>AI 응답의 스키마는 AI 저장소가 소유한다. 여기서는 적재에 필요한 소수 필드만 꺼내고
	 * 없으면 기본값으로 둔다 — AI 쪽이 필드를 늘려도 백엔드를 다시 배포할 필요가 없어야 한다.
	 */
	private long saveHistory(AnalysisJob job, Map<String, Object> payload, Map<String, Object> result) {
		AnalysisIdentity identity = new AnalysisIdentity(
				job.getEntityType(), job.getEntityId(), job.getEntityOrd() == null ? "" : job.getEntityOrd());

		AnalysisHistory history = AnalysisHistory.of(identity);
		history.setInputHash(job.getInputHash());
		history.setPromptVersion(job.getPromptVersion());
		history.setAnalysisMode(job.getAnalysisMode());

		history.setTitle(text(payload.get("title")));
		history.setInsttNm(text(payload.get("insttNm")));
		history.setAmount(digitsOrNull(payload.get("amount")));
		history.setDeepMode(isDeepValue(payload.get("deep")));

		history.setSource(text(result.get("source")));
		history.setSummary(text(result.get("summary")));
		history.setSpecName(nullIfBlank(text(result.get("specName"))));
		history.setSpecConfidence(nullIfBlank(text(result.get("specConfidence"))));
		history.setNestedTables(intOrZero(result.get("nestedTables")));
		history.setBlockingExcluded(blockingExcluded(result));
		history.setLlmModel(text(result.get("llmModel")));
		history.setResult(MAPPER.writeValueAsString(result));

		AnalysisHistoryRepository.ReusableAnalysis saved = historyRepository.save(history);
		return saved == null ? 0L : saved.id();
	}

	/** 원본: {@code !!analysisPayload?.documentSignals?.bidBlockingClauses?.excluded}. */
	@SuppressWarnings("unchecked")
	private static boolean blockingExcluded(Map<String, Object> result) {
		Object signals = result.get("documentSignals");
		if (!(signals instanceof Map<?, ?> signalMap)) {
			return false;
		}
		Object clauses = ((Map<String, Object>) signalMap).get("bidBlockingClauses");
		if (!(clauses instanceof Map<?, ?> clauseMap)) {
			return false;
		}
		return Boolean.TRUE.equals(((Map<String, Object>) clauseMap).get("excluded"));
	}

	private static String text(Object value) {
		return value == null ? "" : String.valueOf(value);
	}

	/** 원본 {@code /^(1|true|yes|y|on|deep|full)$/i} — {@link AnalysisInputHasher#isDeep} 와 같은 판정이다. */
	private static boolean isDeepValue(Object value) {
		return DEEP_VALUE.matcher(text(value).trim()).matches();
	}

	private static String nullIfBlank(String value) {
		return value == null || value.isBlank() ? null : value;
	}

	/** 원본: {@code Number(String(amount).replace(/[^\d]/g, '')) || null}. */
	private static Long digitsOrNull(Object value) {
		String digits = text(value).replaceAll("[^0-9]", "");
		if (digits.isEmpty()) {
			return null;
		}
		try {
			long parsed = Long.parseLong(digits);
			return parsed == 0L ? null : parsed;
		}
		catch (NumberFormatException e) {
			return null;   // 자릿수가 long 을 넘는 값은 금액이 아니다
		}
	}

	private static int intOrZero(Object value) {
		return value instanceof Number number ? number.intValue() : 0;
	}

	private static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
