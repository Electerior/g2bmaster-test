package com.electerior.g2bmaster.integration.ai;

import com.electerior.g2bmaster.config.G2bProperties;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * g2bmaster-AI 저장소를 향한 HTTP 클라이언트.
 *
 * <p>이 백엔드는 <b>추론을 직접 하지 않는다</b>. LLM 호출, 임베딩, 법령 MCP, 가격 웹검색은
 * 전부 AI 저장소가 소유하고, 여기서는 HTTP 로 넘긴다. 원본에도 이미 이 이음매가 있었다 —
 * {@code lib/analysis-executor.js} 가 자기 서버의 {@code /api/item-summary} 로 POST 하는
 * 구조였고, 저장소를 나누면서 그 대상만 AI 서비스로 바뀐 것이다.
 *
 * <p>타임아웃이 긴 이유(기본 120초)는 LLM 추론이 실제로 그만큼 걸리기 때문이다.
 * 원본 측정치로 규격서 1건 심층 분석이 수십 초, 웹 가격 조회 9개 사이트가 26.2초였다.
 * 나라장터·D2B 클라이언트는 훨씬 짧은 타임아웃을 쓰므로 빌더를 공유하지 않는다.
 */
@Configuration
public class AiClientConfig {

	/**
	 * AI 서비스가 호출자를 가릴 때 보는 헤더. 원본 {@code module_server.py} 가 쓰던 이름 그대로다
	 * (AI 쪽은 {@code Authorization: Bearer} 도 받지만, 그 자리는 외부 LLM API 키와 헷갈리기 쉽다).
	 */
	private static final String SERVICE_SECRET_HEADER = "X-Internal-Secret";

	@Bean
	RestClient aiRestClient(G2bProperties properties) {
		G2bProperties.Ai ai = properties.ai();

		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(Duration.ofSeconds(10));
		factory.setReadTimeout(Duration.ofMillis(ai.timeoutMs()));

		RestClient.Builder builder = RestClient.builder()
				.baseUrl(ai.baseUrl())
				.requestFactory(factory);

		// 값이 없으면 헤더 자체를 붙이지 않는다. 빈 문자열을 보내면 AI 쪽 비교
		// (provided != SERVICE_SECRET)에서 똑같이 떨어지므로 구분해 봐야 이득이 없고,
		// 시크릿을 쓰지 않는 개발 환경의 요청에 빈 헤더만 남는다.
		String secret = ai.serviceSecret();
		if (secret != null && !secret.isBlank()) {
			builder.defaultHeader(SERVICE_SECRET_HEADER, secret.trim());
		}

		return builder.build();
	}
}
