package com.electerior.g2bmaster.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * application.yml 의 {@code g2b.*} 설정을 한 곳에 묶는다.
 *
 * <p>기존 모놀리스는 {@code process.env} 를 모듈마다 직접 읽어서, 어떤 환경변수가
 * 필수인지 코드를 전부 뒤져야 알 수 있었다. 여기서는 타입이 붙은 한 덩어리로 만들어
 * 기동 시점에 바인딩 오류가 드러나게 한다.
 */
@ConfigurationProperties(prefix = "g2b")
public record G2bProperties(
		OpenApi openapi,
		D2b d2b,
		Ai ai,
		Cors cors,
		Security security,
		Alert alert,
		Sync sync,
		Index index) {

	/** 나라장터 OpenAPI (조달청 공공데이터). */
	public record OpenApi(
			String serviceKey,
			String baseUrl,
			int timeoutMs,
			int maxRetries,
			int pageSize) {}

	/** 국방전자조달(D2B) OpenAPI. */
	public record D2b(String serviceKey, String baseUrl, int timeoutMs) {}

	/**
	 * g2bmaster-AI 저장소로 넘기는 추론 호출.
	 *
	 * @param baseUrl       AI 서비스 주소. 언어도 프레임워크도 백엔드는 모른다
	 * @param timeoutMs     읽기 타임아웃. <b>AI 자체 데드라인보다 크고 분석 작업 리스보다 작아야 한다</b> —
	 *                      순서가 깨지면 백엔드가 먼저 포기한 작업을 다른 워커가 다시 집어
	 *                      LLM 비용이 두 배로 난다
	 * @param enabled       꺼 두면 AI 없이 되는 기능만으로 동작한다({@code AiClient} 가 호출 전에 막는다)
	 * @param serviceSecret AI 서비스의 호출자 인증 값({@code AI_SERVICE_SECRET}). AI 쪽 미들웨어가
	 *                      이 값을 설정하면 {@code X-Internal-Secret} 또는 Bearer 토큰을 요구하고,
	 *                      없으면 11개 표면이 전부 401 이 된다. 양쪽이 같은 값을 봐야 한다
	 */
	public record Ai(String baseUrl, int timeoutMs, boolean enabled, String serviceSecret) {}

	/** 프론트가 별도 오리진에서 뜨므로 필요한 CORS 허용 목록. */
	public record Cors(List<String> allowedOrigins) {}

	/** 운영/디버그 엔드포인트를 가리는 공유 비밀값. */
	public record Security(String debugSecret, String alertSecret) {}

	/** 키워드 알림 메일. */
	public record Alert(String from, String to, String keywords, String publicBaseUrl) {

		/** 콤마로 구분된 키워드 문자열을 목록으로 편다. */
		public List<String> keywordList() {
			if (keywords == null || keywords.isBlank()) {
				return List.of();
			}
			return List.of(keywords.split(",")).stream()
					.map(String::trim)
					.filter(s -> !s.isEmpty())
					.toList();
		}
	}

	/** 나라장터 주기 동기화 스케줄러 on/off. */
	public record Sync(boolean enabled) {}

	/**
	 * 공고 검색 색인 적재기.
	 *
	 * @param enabled      주기 적재 on/off. 기본은 꺼 둔다 — 여러 인스턴스가 같은 일을
	 *                     동시에 하면 일일 쿼터만 배로 태운다. 운영 인스턴스 하나만 켠다
	 * @param intervalMs   적재 주기. 나라장터 공고는 분 단위로 올라오므로 10분이면 충분하다
	 * @param sweepMs      입찰 → 마감 전이 주기. 적재보다 훨씬 싼 UPDATE 하나라 자주 돌린다
	 * @param backfillDays 워터마크가 없는 첫 회차에 거슬러 올라갈 기간(일)
	 */
	public record Index(boolean enabled, long intervalMs, long sweepMs, int backfillDays) {}
}
