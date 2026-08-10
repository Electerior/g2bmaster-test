package com.electerior.g2bmaster.integration.d2b;

import com.electerior.g2bmaster.config.G2bProperties;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 국방전자조달(D2B) OpenAPI 호출 ({@code server.js} 의 {@code d2bCall} 이식).
 *
 * <p>나라장터 클라이언트와 분리한 이유는 두 API가 <b>실패 모드가 다르기 때문</b>이다.
 * D2B는 서비스키가 없어도 응답하고(실측), 대신 오퍼레이션마다 지원 파라미터가 달라 조용히
 * 빈 응답을 준다. 나라장터의 재시도·이분분할 전략을 그대로 씌우면 의미 없는 재호출만 늘어난다.
 *
 * <p>키 가드를 두지 않는 것도 의도다 — 과거에 "키 없으면 호출 안 함" 가드가 국방 공고를
 * 통째로 숨겨 검색에서 누락시킨 적이 있다.
 */
@Component
public class D2bClient {

	/** 공고목록 서비스 경로. 설정의 base-url 뒤에 붙는다. */
	private static final String BID_SERVICE = "/BidPblancInfoService";

	/** D2B 는 페이징이 없는 오퍼레이션이 섞여 있어 한 번에 최대치를 받는다. */
	private static final int PAGE_SIZE = 999;

	private static final ObjectMapper MAPPER = JsonMapper.builder()
			.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
			.build();

	private final RestClient client;
	private final String baseUrl;
	private final String serviceKey;

	public D2bClient(G2bProperties properties) {
		G2bProperties.D2b config = properties.d2b();
		this.baseUrl = trimTrailingSlash(config.baseUrl()) + BID_SERVICE;
		this.serviceKey = config.serviceKey() == null ? "" : config.serviceKey();

		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
				HttpClient.newBuilder()
						.connectTimeout(Duration.ofSeconds(10))
						.followRedirects(HttpClient.Redirect.NORMAL)
						.build());
		factory.setReadTimeout(Duration.ofMillis(config.timeoutMs() > 0 ? config.timeoutMs() : 20_000));
		this.client = RestClient.builder().requestFactory(factory).build();
	}

	/**
	 * 오퍼레이션 하나를 호출한다. 실패는 {@link D2bException} 으로 올린다 —
	 * 호출부가 "이 출처만 부분 실패"로 처리할 수 있어야 한다.
	 */
	public List<Map<String, Object>> call(String operation, Map<String, String> params) {
		String url = baseUrl + "/" + operation;
		try {
			ResponseEntity<String> entity = client.get()
					.uri(buildUri(url, params))
					.retrieve()
					.onStatus(status -> true, (request, response) -> { })
					.toEntity(String.class);

			if (entity.getStatusCode().value() >= 400) {
				throw new D2bException("D2B HTTP " + entity.getStatusCode().value() + ": " + operation);
			}
			return parse(operation, entity.getBody());
		}
		catch (D2bException ex) {
			throw ex;
		}
		catch (RuntimeException ex) {
			// 메시지에 쿼리스트링(=서비스키)을 넣지 않는다.
			throw new D2bException("D2B 호출 실패: " + operation, ex);
		}
	}

	// ── 내부 ────────────────────────────────────────────────────────────────

	@SuppressWarnings("unchecked")
	private List<Map<String, Object>> parse(String operation, String rawBody) {
		if (rawBody == null || rawBody.isBlank()) {
			throw new D2bException("D2B 응답이 비어 있습니다: " + operation);
		}
		Map<String, Object> data;
		try {
			data = MAPPER.readValue(rawBody, Map.class);
		}
		catch (RuntimeException ex) {
			throw new D2bException("D2B 응답을 해석할 수 없습니다: " + operation, ex);
		}

		Map<String, Object> response = asMap(data.get("response"));
		Map<String, Object> header = asMap(response.get("header"));
		String code = header.get("resultCode") == null ? null : String.valueOf(header.get("resultCode"));
		if (code != null && !"00".equals(code)) {
			throw new D2bException("D2B [" + code + "]: " + header.get("resultMsg"));
		}
		return normalizeItems(asMap(response.get("body")).get("items"));
	}

	/**
	 * {@code items} 정규화 — 배열 / {@code {item:[...]}} / {@code {item:{...}}} 세 모양이 온다.
	 * 마지막(1건일 때 배열이 접힌 것)을 놓치면 단건 공고가 통째로 사라진다.
	 */
	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> normalizeItems(Object items) {
		if (items instanceof List<?> list) {
			return list.stream().filter(Map.class::isInstance).map(o -> (Map<String, Object>) o).toList();
		}
		if (items instanceof Map<?, ?> map) {
			Object item = map.get("item");
			if (item instanceof List<?> list) {
				return list.stream().filter(Map.class::isInstance).map(o -> (Map<String, Object>) o).toList();
			}
			if (item instanceof Map<?, ?> single) {
				return List.of((Map<String, Object>) single);
			}
		}
		return List.of();
	}

	private URI buildUri(String url, Map<String, String> params) {
		Map<String, String> all = new LinkedHashMap<>();
		all.put("serviceKey", serviceKey);
		all.put("numOfRows", String.valueOf(PAGE_SIZE));
		all.put("pageNo", "1");
		all.put("type", "json");
		if (params != null) {
			all.putAll(params);
		}
		StringBuilder query = new StringBuilder();
		all.forEach((key, value) -> {
			if (!query.isEmpty()) {
				query.append('&');
			}
			query.append(encode(key)).append('=').append(encode(value == null ? "" : value));
		});
		return URI.create(url + "?" + query);
	}

	/** {@code +} 가 공백으로 읽히는 것을 막는다 — 서비스키(base64)에 흔하다. */
	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
	}

	private static String trimTrailingSlash(String value) {
		String url = value == null ? "" : value;
		return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> asMap(Object value) {
		return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
	}
}
