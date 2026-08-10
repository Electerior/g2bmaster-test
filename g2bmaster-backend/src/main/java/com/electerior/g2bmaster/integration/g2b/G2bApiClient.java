package com.electerior.g2bmaster.integration.g2b;

import com.electerior.g2bmaster.common.Numbers;
import com.electerior.g2bmaster.config.G2bProperties;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 나라장터 OpenAPI HTTP 클라이언트 ({@code lib/g2b-loader.js} 의 {@code callG2b} +
 * {@code server.js} 의 {@code g2bCall} 이식).
 *
 * <p>이 클래스는 "한 번의 HTTP 호출"만 책임진다. 캐시·날짜창 이분 분할 같은 상위 전략은
 * {@link G2bFetchService} 에 있다. 다만 <em>모든</em> G2B 호출이 여기를 지나야 하는 것들
 * (동시성 상한·오류 봉투 판별·서비스키 비노출)은 여기에 있다.
 */
@Component
public class G2bApiClient {

	private static final Logger log = LoggerFactory.getLogger(G2bApiClient.class);

	/**
	 * G2B는 오류를 <strong>다른 루트 키</strong>에 담는다.
	 * <pre>
	 *   정상 {"response":{"header":{...},"body":{...}}}
	 *   오류 {"nkoneps.com.response.ResponseError":{"header":{"resultCode":"07",...}}}
	 * </pre>
	 * {@code response} 만 보면 오류가 '데이터 없음'으로 조용히 지나가, 적재가 비었는데도
	 * 성공으로 기록된다(실측: 240건 중 89건이 이렇게 새고 있었다). 키 이름이 배포마다
	 * 조금씩 다르므로 접미사로 훑는다.
	 */
	private static final Pattern ERROR_ENVELOPE_KEY = Pattern.compile("ResponseError$", Pattern.CASE_INSENSITIVE);

	/** 일괄 적재용 페이지 크기. 콜 수를 줄이는 게 목적이라 상한(999)까지 쓴다. */
	public static final int LOADER_PAGE_SIZE = 999;

	/** 일괄 적재는 응답이 크고 느리다. 검색용 타임아웃(20초)을 쓰면 정상 응답이 잘린다. */
	private static final Duration LOADER_TIMEOUT = Duration.ofSeconds(60);

	/** 응답 본문을 예외에 실어 보낼 때의 상한. 전체를 담으면 로그가 메가바이트로 부푼다. */
	private static final int BODY_SNIPPET_LIMIT = 500;

	private static final ObjectMapper MAPPER = JsonMapper.builder()
			.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
			.build();

	private final G2bProperties.OpenApi config;
	private final G2bErrorTranslator translator;

	/** 검색·상세 조회용(짧은 타임아웃). */
	private final RestClient client;

	/** 일괄 적재용(긴 타임아웃). */
	private final RestClient loaderClient;

	/**
	 * 전역 G2B 동시성 제한.
	 *
	 * <p>data.go.kr 는 짧은 순간의 과다요청에 429를 던진다(딜레이더 배치 + 검색 페이징이 겹치면
	 * 순간 수십 건). 모든 G2B 호출이 이 한 곳을 지나므로 여기서 동시 실행 수를 캡한다.
	 * 캐시의 in-flight 병합은 '같은' 요청만 합치지, 서로 다른 페이지/공고 폭주는 못 막는다.
	 * 이걸 빼면 429 폭풍이 그대로 재현된다.
	 */
	private final Semaphore concurrencyGate;

	/** 서비스키는 원본 값이 URL 인코딩되어 있어 한 번 디코드해 두고, 쿼리 조립 때 다시 인코딩한다. */
	private final String serviceKey;

	private final int maxRetries;

	public G2bApiClient(G2bProperties properties, G2bErrorTranslator translator) {
		this.config = properties.openapi();
		this.translator = translator;
		this.serviceKey = decodeServiceKey(config.serviceKey());
		this.maxRetries = config.maxRetries() > 0 ? config.maxRetries() : 3;
		this.client = buildClient(Duration.ofMillis(config.timeoutMs() > 0 ? config.timeoutMs() : 20_000));
		this.loaderClient = buildClient(LOADER_TIMEOUT);
		this.concurrencyGate = new Semaphore(maxConcurrency(), true);
	}

	/** 설정 파일이 아니라 환경변수로 받는다 — 원본 {@code G2B_MAX_CONCURRENCY} 와 이름을 맞춘다. */
	private static int maxConcurrency() {
		String raw = System.getProperty("G2B_MAX_CONCURRENCY", System.getenv("G2B_MAX_CONCURRENCY"));
		int parsed = Numbers.toInt(raw, 0);
		return parsed > 0 ? parsed : 4;
	}

	private static RestClient buildClient(Duration readTimeout) {
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
				HttpClient.newBuilder()
						.connectTimeout(Duration.ofSeconds(10))
						.followRedirects(HttpClient.Redirect.NORMAL)
						.build());
		factory.setReadTimeout(readTimeout);
		// 응답을 String 으로 받아 우리가 직접 파싱한다. G2B 는 오류 시 content-type 을
		// text/xml 로 바꿔 보내는 일이 있어, 메시지 컨버터에 맡기면 파싱 예외가 먼저 터진다.
		return RestClient.builder().requestFactory(factory).build();
	}

	// ── 호출 ────────────────────────────────────────────────────────────────

	/** 검색 경로용 단건 호출. */
	public G2bResponse call(String url, Map<String, ?> params) {
		return execute(client, url, params);
	}

	/** 일괄 적재 경로용 단건 호출(긴 타임아웃). */
	public G2bResponse callForLoad(String url, Map<String, ?> params) {
		return execute(loaderClient, url, params);
	}

	/** 오퍼레이션 카탈로그 항목으로 호출. */
	public G2bResponse callForLoad(G2bOperation operation, Map<String, ?> params) {
		return callForLoad(operation.url(config.baseUrl()), params);
	}

	/** 검색 기본 페이지 크기(설정값, 기본 100). */
	public int searchPageSize() {
		return config.pageSize() > 0 ? config.pageSize() : 100;
	}

	public String baseUrl() {
		return config.baseUrl();
	}

	/**
	 * 페이지를 끝까지 훑는다.
	 *
	 * <p>종료 조건이 셋인 이유: {@code totalCount} 는 신뢰할 수 없다. 일부 오퍼레이션은 0을
	 * 주면서 항목은 채워 보내고, 반대로 실제 페이지 수보다 큰 값을 주기도 한다. 그래서
	 * (1) 빈 페이지, (2) 누적이 총건수 도달, (3) 페이지 크기보다 짧은 응답 — 어느 하나라도
	 * 걸리면 멈춘다.
	 */
	public List<Map<String, Object>> fetchAllPages(String url, Map<String, ?> baseParams, int pageSize,
			int maxRows) {
		List<Map<String, Object>> all = new ArrayList<>();
		int page = 1;
		int fetched = 0;
		while (true) {
			Map<String, Object> params = new LinkedHashMap<>(baseParams);
			params.put("numOfRows", pageSize);
			params.put("pageNo", page);
			G2bResponse res = callForLoad(url, params);

			if (res.items().isEmpty()) {
				break;
			}
			all.addAll(res.items());
			fetched += res.items().size();

			if (fetched >= maxRows
					|| (res.totalCount() > 0 && fetched >= res.totalCount())
					|| res.items().size() < pageSize) {
				break;
			}
			page += 1;
		}
		return all;
	}

	public List<Map<String, Object>> fetchAllPages(String url, Map<String, ?> baseParams) {
		return fetchAllPages(url, baseParams, LOADER_PAGE_SIZE, Integer.MAX_VALUE);
	}

	// ── 내부: 재시도 + 동시성 ──────────────────────────────────────────────

	private G2bResponse execute(RestClient restClient, String url, Map<String, ?> params) {
		for (int attempt = 1; ; attempt++) {
			try {
				return executeOnce(restClient, url, params);
			}
			catch (RuntimeException error) {
				// 레이트리밋·타임아웃만 물러섰다 재시도한다. 그 외(인증·파라미터 오류)는 즉시 포기 —
				// 안 풀릴 요청을 세 번 더 보내면 남은 일일 쿼터만 태운다.
				boolean retryable = translator.isRateLimitError(error) || translator.isTimeoutError(error);
				if (!retryable || attempt >= maxRetries) {
					throw error;
				}
				sleep(2000L * attempt);
			}
		}
	}

	private G2bResponse executeOnce(RestClient restClient, String url, Map<String, ?> params) {
		acquireSlot();
		try {
			// 기본 상태 처리(4xx/5xx 를 예외로 바꾸는 것)를 끈다. 429/401 응답의 <em>본문</em>이
			// 일일 쿼터 소진인지 순간 제한인지를 가르는 유일한 단서라, 본문째 받아야 한다.
			ResponseEntity<String> entity = restClient.get()
					.uri(buildUri(url, params))
					.retrieve()
					.onStatus(status -> true, (request, response) -> { })
					.toEntity(String.class);

			int status = entity.getStatusCode().value();
			String body = entity.getBody();
			if (status >= 400) {
				throw typed("나라장터 HTTP " + status + ": " + url, null, status, snippet(body), null);
			}
			return parse(url, body);
		}
		catch (G2bException ex) {
			throw ex;
		}
		catch (RuntimeException ex) {
			// 여기서 만드는 메시지에는 절대 쿼리스트링(=서비스키)을 넣지 않는다.
			throw typed("나라장터 호출 실패: " + url, null, httpStatusOf(ex), null, ex);
		}
		finally {
			concurrencyGate.release();
		}
	}

	private void acquireSlot() {
		try {
			concurrencyGate.acquire();
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new G2bException("나라장터 호출 대기 중 인터럽트", ex);
		}
	}

	private static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new G2bException("나라장터 재시도 대기 중 인터럽트", ex);
		}
	}

	// ── 내부: URI 조립 ─────────────────────────────────────────────────────

	/**
	 * 쿼리스트링을 직접 조립한다.
	 *
	 * <p>스프링의 {@code UriComponentsBuilder.encode()} 는 쿼리값의 {@code +} 를 RFC3986
	 * sub-delim 으로 보고 그대로 둔다. 그런데 서비스키(base64)에는 {@code +} 가 흔하고,
	 * data.go.kr 은 그걸 공백으로 읽어 인증 실패를 낸다. 그래서 {@link URLEncoder} 로
	 * 강제 인코딩한 뒤, 공백 표기만 {@code %20} 으로 되돌린다.
	 */
	private URI buildUri(String url, Map<String, ?> params) {
		StringBuilder query = new StringBuilder();
		appendParam(query, "serviceKey", serviceKey);
		appendParam(query, "type", "json");
		for (Map.Entry<String, ?> e : params.entrySet()) {
			if (e.getValue() == null || "serviceKey".equals(e.getKey()) || "type".equals(e.getKey())) {
				continue;
			}
			appendParam(query, e.getKey(), String.valueOf(e.getValue()));
		}
		return URI.create(url + "?" + query);
	}

	private static void appendParam(StringBuilder query, String key, String value) {
		if (!query.isEmpty()) {
			query.append('&');
		}
		query.append(encode(key)).append('=').append(encode(value));
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
	}

	/**
	 * 설정에 들어 있는 키가 이미 URL 인코딩된 형태일 수 있어 한 번 디코드한다.
	 * 이중 인코딩된 키는 {@code %2B} 가 {@code %252B} 가 되어 인증 실패로 이어진다.
	 */
	private static String decodeServiceKey(String raw) {
		if (raw == null || raw.isBlank()) {
			return "";
		}
		try {
			return URLDecoder.decode(raw, StandardCharsets.UTF_8);
		}
		catch (IllegalArgumentException ex) {
			// 인코딩되지 않은 원문에 '%'가 섞여 있으면 디코드가 실패한다. 그땐 원문 그대로 쓴다.
			return raw;
		}
	}

	// ── 내부: 응답 해석 ────────────────────────────────────────────────────

	@SuppressWarnings("unchecked")
	G2bResponse parse(String url, String rawBody) {
		if (rawBody == null || rawBody.isBlank()) {
			throw typed("나라장터 응답이 비어 있습니다: " + url, null, null, null, null);
		}

		Map<String, Object> data;
		try {
			data = MAPPER.readValue(rawBody, Map.class);
		}
		catch (RuntimeException ex) {
			// 키가 등록되지 않았을 때 data.go.kr 은 JSON 이 아니라 XML 오류를 돌려준다.
			// 본문을 예외에 실어 보내야 분류기가 인증/쿼터를 구분할 수 있다.
			throw typed("나라장터 응답을 해석할 수 없습니다: " + url, null, null, snippet(rawBody), ex);
		}

		// (1) 오류 봉투 먼저. 이 검사를 뒤로 미루면 오류가 '데이터 없음'으로 둔갑한다.
		for (Map.Entry<String, Object> entry : data.entrySet()) {
			if (ERROR_ENVELOPE_KEY.matcher(entry.getKey()).find()) {
				Map<String, Object> header = asMap(asMap(entry.getValue()).get("header"));
				String code = str(header.get("resultCode"));
				String msg = str(header.get("resultMsg"));
				throw typed(errorMessage(code, msg), code, null, snippet(rawBody), null);
			}
		}

		Map<String, Object> response = asMap(data.get("response"));
		Map<String, Object> header = asMap(response.get("header"));
		Map<String, Object> body = asMap(response.get("body"));
		String code = str(header.get("resultCode"));
		String msg = str(header.get("resultMsg"));

		// (2) 정상 봉투인데 결과코드가 00이 아닌 경우도 오류다.
		if (code != null && !"00".equals(code)) {
			throw typed(errorMessage(code, msg), code, null, snippet(rawBody), null);
		}

		return new G2bResponse(
				normalizeItems(body.get("items")),
				Numbers.toInt(body.get("totalCount"), 0),
				Numbers.toInt(body.get("pageNo"), 1),
				Numbers.toInt(body.get("numOfRows"), 0),
				code,
				msg);
	}

	/**
	 * {@code items} 정규화.
	 *
	 * <p>세 가지 모양이 실제로 온다: 배열, {@code {item: [...]}}(구형 XML→JSON 변환),
	 * {@code {item: {...}}}(항목이 1건일 때 배열이 접힌 것). 마지막 경우를 놓치면 1건짜리
	 * 결과가 통째로 사라진다.
	 */
	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> normalizeItems(Object items) {
		if (items == null) {
			return List.of();
		}
		if (items instanceof List<?> list) {
			return list.stream().filter(Map.class::isInstance).map(o -> (Map<String, Object>) o).toList();
		}
		if (items instanceof Map<?, ?> map) {
			Object item = map.get("item");
			if (item == null) {
				return List.of();
			}
			if (item instanceof List<?> list) {
				return list.stream().filter(Map.class::isInstance).map(o -> (Map<String, Object>) o).toList();
			}
			if (item instanceof Map<?, ?> single) {
				return List.of((Map<String, Object>) single);
			}
		}
		return List.of();
	}

	/**
	 * 오류 문구는 {@code G2B [07]: ...} 형태를 유지한다 —
	 * {@link G2bErrorTranslator} 의 범위오류 정규식이 이 형태를 본다.
	 */
	private static String errorMessage(String code, String msg) {
		return "G2B [" + (code == null ? "?" : code) + "]: " + (msg == null ? "unknown error" : msg);
	}

	private G2bException typed(String message, String resultCode, Integer httpStatus, String body, Throwable cause) {
		G2bException probe = new G2bException(message, resultCode, httpStatus, body, cause);
		if (translator.isAuthError(probe)) {
			return new G2bAuthException(message, resultCode, httpStatus, body, cause);
		}
		if (translator.isRateLimitError(probe)) {
			return translator.isQuotaError(probe)
					? new G2bQuotaExceededException(message, resultCode, httpStatus, body, cause)
					: new G2bRateLimitException(message, resultCode, httpStatus, body, cause);
		}
		if (translator.isRangeError(probe)) {
			return new G2bRangeException(message, resultCode, httpStatus, body, cause);
		}
		if (translator.isTimeoutError(probe)) {
			return new G2bTimeoutException(message, cause);
		}
		// 분류에 실패한 것들만 남긴다. 여기서도 파라미터는 남기지 않는다 — 서비스키가 섞일 위험.
		log.debug("나라장터 미분류 오류: {}", message);
		return probe;
	}

	private static Integer httpStatusOf(Throwable ex) {
		for (Throwable t = ex; t != null && t != t.getCause(); t = t.getCause()) {
			if (t instanceof org.springframework.web.client.RestClientResponseException rre) {
				return rre.getStatusCode().value();
			}
		}
		return null;
	}

	private static String snippet(String body) {
		if (body == null) {
			return null;
		}
		return body.length() <= BODY_SNIPPET_LIMIT ? body : body.substring(0, BODY_SNIPPET_LIMIT);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> asMap(Object value) {
		return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
	}

	private static String str(Object value) {
		return value == null ? null : String.valueOf(value);
	}
}
