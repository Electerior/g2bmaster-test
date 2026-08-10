package com.electerior.g2bmaster.integration.ai;

import com.electerior.g2bmaster.config.G2bProperties;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * g2bmaster-AI 저장소의 HTTP 표면.
 *
 * <p>응답을 {@code Map} 으로 받는 것은 게으름이 아니라 경계 설계다. 요약 마크다운,
 * 법령 판단, 품목 추출 결과의 <b>스키마는 AI 저장소가 소유</b>하며 프롬프트 버전과 함께
 * 바뀐다. 여기서 Java record 로 굳히면 AI 쪽 변경마다 백엔드를 다시 배포해야 한다.
 * 백엔드가 실제로 해석하는 소수의 필드({@code _analysisHistoryId}, {@code aiFallback} 등)만
 * 꺼내 쓰고 나머지는 프론트로 그대로 통과시킨다.
 *
 * <p>두 저장소가 합의해야 하는 계약은 네 가지다:
 * <ol>
 *   <li>{@code ITEM_SUMMARY_PROMPT_VERSION} — 분석 재사용 키의 일부. 백엔드가 하드코딩하지 말고
 *       {@link #promptVersion()} 으로 <b>AI 서비스에서 읽어와야 한다</b>.</li>
 *   <li>{@code analysisInputHash} 정규화 — Node 원본과 바이트 단위로 같아야 한다.
 *       다르면 이관 순간 분석 캐시가 통째로 고아가 된다. 계산은 백엔드({@code AnalysisInputHasher})가
 *       하므로 AI 저장소는 이 해시를 구현하지 않는다.</li>
 *   <li>{@code aiDisabled} / {@code aiFallback} 응답 필드 — 폴백은 성공이 아니다.
 *       결과 이력 적재는 백엔드가 하므로 AI 응답에 {@code _analysisHistoryId} 는 없어도 된다.</li>
 *   <li>워커 용량 — 내보내기 작업의 ETA 계산에 쓰인다.</li>
 * </ol>
 */
@Component
public class AiClient {

	private static final Logger log = LoggerFactory.getLogger(AiClient.class);

	private final RestClient client;
	private final boolean enabled;

	public AiClient(RestClient aiRestClient, G2bProperties properties) {
		this.client = aiRestClient;
		this.enabled = properties.ai().enabled();
	}

	public boolean isEnabled() {
		return enabled;
	}

	/**
	 * 공고·사전규격·발주계획 1건 심층 분석. 원본 {@code lib/analysis-executor.js} 의 이식.
	 *
	 * <p>원본과 같이 {@code aiDisabled}/{@code aiFallback} 응답은 <b>성공으로 치지 않는다</b> —
	 * 작업 큐가 그것을 완료로 기록하면 폴백 결과가 캐시에 눌러앉아 영원히 재분석되지 않는다.
	 */
	public Map<String, Object> itemSummary(Map<String, Object> payload) {
		Map<String, Object> body = post("/api/item-summary", payload);
		if (Boolean.TRUE.equals(body.get("aiDisabled")) || Boolean.TRUE.equals(body.get("aiFallback"))) {
			throw new AiUnavailableException(
					String.valueOf(body.getOrDefault("aiError", "AI 분석을 사용할 수 없습니다.")));
		}
		// 결과 이력 적재는 AnalysisJobRunner 가 한다 — analysis_history 는 백엔드 소유 테이블이라
		// AI 서비스가 그 PK 를 만들 수 없다. AI 응답에서 요구하는 것은 폴백이 아니라는 사실뿐이다.
		return body;
	}

	/** 공고 요약(영업 관점). 실패해도 호출부가 폴백을 만들 수 있어야 한다. */
	public Map<String, Object> bidSummary(Map<String, Object> payload) {
		return post("/api/bid-summary", payload);
	}

	/** 조항 위법성 검토 — 법령 MCP 는 AI 저장소가 소유한다. */
	public Map<String, Object> reviewClauses(Map<String, Object> payload) {
		return post("/api/legal/review-clauses", payload);
	}

	/** 콜드메일 초안 작성. */
	public Map<String, Object> outreachDraft(Map<String, Object> payload) {
		return post("/api/legal/outreach-draft", payload);
	}

	/** 서약서 수정본 생성 워크플로. */
	public Map<String, Object> pledgeRevision(Map<String, Object> payload) {
		return post("/api/pledge/revision-workflow", payload);
	}

	/** 웹 가격 조회(studyweb + LLM 추출). */
	public Map<String, Object> resolvePrice(Map<String, Object> payload) {
		return post("/api/price/resolve", payload);
	}

	/**
	 * 규격서 텍스트 → 부품 추출(LLM) → 부품별 가격(다나와) → 원가 추정.
	 *
	 * <p>deal-analysis 의 {@code estimatedUnitCost} 갈래를 채운다. 못 찾으면 예외가 아니라
	 * {@code {matched:false, reason}} 로 온다 — 규격서에 부품이 없을 수 있다.
	 */
	public Map<String, Object> estimateUnitCost(Map<String, Object> payload) {
		return post("/api/estimate-unit-cost", payload);
	}

	/**
	 * 번들이 완제품인지 부품 조달인지 판정하고, 완제품이면 유사 완제품을 찾는다.
	 * 부품 단가 추정과 반대 방향이다 — 완제품 PC 만 남긴다.
	 */
	public Map<String, Object> prebuiltComparables(Map<String, Object> payload) {
		return post("/api/prebuilt-comparables", payload);
	}

	/** URL 하나를 직접 지목한 가격 조회. */
	public Map<String, Object> resolvePriceByUrl(Map<String, Object> payload) {
		return post("/api/price/url", payload);
	}

	/** 텍스트 임베딩 — 유사도 계산 자체는 백엔드가 한다(MySQL 에 벡터 타입이 없다). */
	@SuppressWarnings("unchecked")
	public List<List<Double>> embed(List<String> texts, String model) {
		Map<String, Object> body = post("/api/embed", Map.of("texts", texts, "model", model == null ? "" : model));
		Object vectors = body.get("vectors");
		return vectors instanceof List<?> list ? (List<List<Double>>) list : List.of();
	}

	/**
	 * 분석 재사용 키에 들어가는 프롬프트 버전. AI 저장소가 프롬프트를 고치면 여기서 바뀌고,
	 * 그 결과 기존 캐시가 자연스럽게 무효화된다.
	 */
	public String promptVersion() {
		Map<String, Object> body = get("/api/ai/prompt-version");
		return String.valueOf(body.getOrDefault("promptVersion", ""));
	}

	/** LLM 워커 용량 — 내보내기 ETA 계산용. 원본은 {@code llm-worker-pool} 을 직접 읽었다. */
	public int capacity() {
		try {
			Map<String, Object> body = get("/api/ai/capacity");
			Object value = body.get("capacity");
			return value instanceof Number n ? n.intValue() : 0;
		}
		catch (AiUnavailableException e) {
			return 0;   // 용량을 모르면 ETA 를 못 낼 뿐, 작업 자체는 진행할 수 있다.
		}
	}

	/** 가용 모델 목록 / 도달 여부 — 시스템 화면의 상태 표시용. */
	public Map<String, Object> models() {
		return get("/api/llm/models");
	}

	// ── 내부 ────────────────────────────────────────────────────────────────

	@SuppressWarnings("unchecked")
	private Map<String, Object> post(String path, Object payload) {
		requireEnabled();
		try {
			Map<String, Object> body = client.post().uri(path).body(payload).retrieve().body(Map.class);
			return body == null ? Map.of() : body;
		}
		catch (RestClientException e) {
			log.warn("AI 호출 실패: POST {} — {}", path, e.getMessage());
			throw new AiUnavailableException("AI 서비스에 연결하지 못했습니다: " + e.getMessage(), e);
		}
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> get(String path) {
		requireEnabled();
		try {
			Map<String, Object> body = client.get().uri(path).retrieve().body(Map.class);
			return body == null ? Map.of() : body;
		}
		catch (RestClientException e) {
			log.warn("AI 호출 실패: GET {} — {}", path, e.getMessage());
			throw new AiUnavailableException("AI 서비스에 연결하지 못했습니다: " + e.getMessage(), e);
		}
	}

	private void requireEnabled() {
		if (!enabled) {
			throw new AiUnavailableException("AI 기능이 꺼져 있습니다 (g2b.ai.enabled=false).");
		}
	}
}
