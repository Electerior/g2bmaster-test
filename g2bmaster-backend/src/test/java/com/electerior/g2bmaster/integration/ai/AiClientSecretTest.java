package com.electerior.g2bmaster.integration.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.electerior.g2bmaster.config.G2bProperties;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * AI 서비스의 호출자 인증({@code AI_SERVICE_SECRET})이 실제 요청에 실리는지 본다.
 *
 * <p>이것이 회귀 테스트인 이유: AI 저장소는 {@code app/main.py} 의
 * {@code service_secret_guard} 미들웨어로 이 값을 요구할 수 있는데, 백엔드에 대응 설정이
 * 없던 동안에는 <b>시크릿을 켜는 순간 11개 표면이 전부 401</b> 이 됐다. 즉 AI 서비스의
 * 인증을 사실상 쓸 수 없었고, 그 사실이 기동 시점에는 전혀 드러나지 않았다.
 *
 * <p>가짜 AI 서버를 실제로 띄워 확인한다. 헤더 이름 하나가 어긋나도 잡히기 때문이다 —
 * 클라이언트 빌더를 들여다보는 방식으로는 그 오타가 통과한다.
 */
class AiClientSecretTest {

	private HttpServer server;

	private final AtomicReference<String> seenSecret = new AtomicReference<>();

	private final AtomicReference<String> seenAuthorization = new AtomicReference<>();

	@BeforeEach
	void startFakeAiService() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/api/ai/prompt-version", exchange -> {
			seenSecret.set(exchange.getRequestHeaders().getFirst("X-Internal-Secret"));
			seenAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
			byte[] body = "{\"promptVersion\":\"item-summary-2026-08-04-v4\"}".getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, body.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(body);
			}
		});
		server.start();
	}

	@AfterEach
	void stopFakeAiService() {
		server.stop(0);
	}

	@Test
	void 시크릿이_설정되면_X_Internal_Secret_헤더로_보낸다() {
		AiClient client = clientWithSecret("s3cr3t");

		assertThat(client.promptVersion()).isEqualTo("item-summary-2026-08-04-v4");
		assertThat(seenSecret.get()).isEqualTo("s3cr3t");
	}

	@Test
	void 시크릿이_비어_있으면_헤더를_붙이지_않는다() {
		AiClient client = clientWithSecret("");

		assertThat(client.promptVersion()).isEqualTo("item-summary-2026-08-04-v4");
		assertThat(seenSecret.get()).isNull();
		// 빈 Bearer 토큰도 남기지 않는다 — AI 쪽은 두 형식을 모두 보므로 어느 쪽이든 빈 값이
		// 흘러가면 시크릿을 켠 서버에서 401 의 원인이 무엇인지 흐려진다.
		assertThat(seenAuthorization.get()).isNull();
	}

	private AiClient clientWithSecret(String secret) {
		G2bProperties properties = properties(secret);
		RestClient restClient = new AiClientConfig().aiRestClient(properties);
		return new AiClient(restClient, properties);
	}

	private G2bProperties properties(String secret) {
		String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
		return new G2bProperties(
				new G2bProperties.OpenApi("", "", 20000, 3, 100),
				new G2bProperties.D2b("", "", 20000),
				new G2bProperties.Ai(baseUrl, 5000, true, secret),
				new G2bProperties.Cors(List.of()),
				new G2bProperties.Security("", ""),
				new G2bProperties.Alert("", "", "", ""),
				new G2bProperties.Sync(false),
				new G2bProperties.Index(false, 600_000, 300_000, 7));
	}
}
