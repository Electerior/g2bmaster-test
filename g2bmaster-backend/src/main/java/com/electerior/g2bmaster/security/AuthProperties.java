package com.electerior.g2bmaster.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 앱 인증 설정.
 *
 * <p>기존 모놀리스는 프레임워크 없이 세 갈래를 손으로 구현했다:
 * <ol>
 *   <li>{@code APP_API_KEY} — 쓰기·비용 발생 경로 보호. <b>값이 없으면 인증 자체가 꺼진다</b>(개발 모드).</li>
 *   <li>{@code DEBUG_SECRET} — 운영에서만 디버그 경로를 가린다.</li>
 *   <li>{@code ALERT_SECRET} — 알림 배치 트리거 전용.</li>
 * </ol>
 * "키가 없으면 통과"는 보안 결함처럼 보이지만 의도된 동작이다 — 사내망 온프레미스
 * 단독 실행이 전제이고, 개발자가 키 없이 바로 띄울 수 있어야 한다. 운영 배포에서는
 * {@code APP_API_KEY} 를 반드시 채우는 것으로 이 결정을 보완한다.
 */
@ConfigurationProperties(prefix = "g2b.auth")
public record AuthProperties(String appApiKey) {

	/** 키가 설정돼 있을 때만 인증을 강제한다. */
	public boolean enabled() {
		return appApiKey != null && !appApiKey.isBlank();
	}
}
