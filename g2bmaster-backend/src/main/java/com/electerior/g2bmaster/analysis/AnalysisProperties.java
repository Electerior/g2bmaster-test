package com.electerior.g2bmaster.analysis;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 분석 작업 큐/워커 설정.
 *
 * <p>{@code G2bProperties} 에 넣지 않은 이유는 소유 경계다 — 이 값들은 큐의 동작 파라미터이고
 * 나라장터 연동과 아무 상관이 없다. 프롬프트나 모델 파라미터가 여기 없는 것도 의도다
 * (그쪽은 AI 저장소 소관 → {@code docs/ai-boundary.md} §2).
 *
 * @param runnerEnabled  워커 루프 on/off. <b>기본 꺼짐</b> — 켠 인스턴스만 실제로 추론을 태운다.
 *                       여러 인스턴스가 동시에 켜져도 {@code SKIP LOCKED} 로 안전하다.
 * @param concurrency    동시에 도는 워커 루프 수
 * @param pollMs         큐가 비었을 때 다시 물어보는 간격
 * @param leaseMs        작업 리스. 이 시간 안에 heartbeat 가 없으면 회수 대상이 된다
 * @param retryBaseMs    재시도 백오프 기준값. 실제 대기는 {@code retryBaseMs × 2^(attempt-1)}
 * @param promptVersion  {@code null}/빈 값이면 AI 서비스의 {@code /api/ai/prompt-version} 을 읽는다.
 *                       <b>하드코딩은 금지다</b> — 프롬프트가 바뀐 것을 백엔드가 모르면 낡은 결과를
 *                       계속 재사용한다(→ {@code docs/ai-boundary.md} §6.1). 설정값은 AI 서비스가
 *                       꺼져 있을 때의 비상 수단으로만 둔다.
 */
@ConfigurationProperties(prefix = "g2b.analysis")
public record AnalysisProperties(
		boolean runnerEnabled,
		int concurrency,
		long pollMs,
		long leaseMs,
		long retryBaseMs,
		String promptVersion) {

	public AnalysisProperties {
		concurrency = Math.max(1, concurrency == 0 ? 1 : concurrency);
		pollMs = pollMs <= 0 ? 500 : Math.max(10, pollMs);
		leaseMs = leaseMs <= 0 ? AnalysisJobRepository.DEFAULT_LEASE_MS : Math.max(1000, leaseMs);
		retryBaseMs = Math.max(0, retryBaseMs == 0 ? 1000 : retryBaseMs);
	}
}
