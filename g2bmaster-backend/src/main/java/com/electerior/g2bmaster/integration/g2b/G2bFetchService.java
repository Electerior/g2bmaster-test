package com.electerior.g2bmaster.integration.g2b;

import com.electerior.g2bmaster.common.G2bDates;
import com.electerior.g2bmaster.common.G2bDates.DateWindow;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 나라장터 조회의 상위 전략 ({@code server.js} 의 {@code g2bCallCached} / {@code g2bFetchUrl} /
 * {@code g2bFetchPaged} / {@code g2bFetchAll} 이식).
 *
 * <p>여기 있는 두 장치가 이 통합의 핵심이다.
 * <ol>
 *   <li><strong>날짜창 이분 분할</strong> — 30일 창은 대부분의 오퍼레이션에서 상한을 넘는다.
 *       범위오류·타임아웃·페이지 과다를 만나면 창을 반으로 갈라 재귀한다.</li>
 *   <li><strong>응답 캐시 + 동일요청 병합</strong> — 검색 화면 하나가 같은 URL을 여러 번
 *       두드리고, 페이지네이션·재시도까지 겹치면 쿼터가 순식간에 마른다.</li>
 * </ol>
 */
@Service
public class G2bFetchService {

	private static final Logger log = LoggerFactory.getLogger(G2bFetchService.class);

	/** 캐시 TTL 6시간 — 공고/개찰 자료는 그 안에 의미 있게 바뀌지 않는다. */
	private static final long CACHE_TTL_MS = 6L * 60 * 60 * 1000;

	/** 하드 상한 — 캐시 자체가 DoS 벡터가 되지 않게. */
	private static final int CACHE_MAX_ENTRIES = 2000;

	/**
	 * 창당 최대 페이지 수.
	 *
	 * <p>페이지 상한을 그냥 잘라내면 뒤쪽 공고가 <em>영구 누락</em>된다. 그래서 자르는 대신
	 * 큰 날짜창을 선제적으로 반분해 각 창의 모든 페이지를 수집하고, 하루치가 여전히 큰
	 * 경우에만 페이지 전체를 직접 읽는다.
	 */
	private static final int MAX_PAGES_PER_WINDOW = 6;

	/**
	 * 재귀 깊이 상한.
	 *
	 * <p>이걸 안 걸면 하루 단위까지 내려가고도 계속 실패하는 오퍼레이션에서 분할이 끝나지 않는다
	 * (호출 수가 2^n 으로 는다). 5면 30일 창이 하루 아래까지 내려간다.
	 */
	private static final int MAX_SPLIT_DEPTH = 5;

	private final G2bApiClient client;
	private final G2bErrorTranslator translator;

	/**
	 * 응답 캐시.
	 *
	 * <p>Caffeine 을 pom 에 추가하지 않고 {@link ConcurrentHashMap} + {@link CompletableFuture} 로
	 * 갔다. 이 캐시가 필요로 하는 것은 (a) TTL, (b) 개수 상한, (c) <em>진행 중 요청 병합</em>인데,
	 * 정작 (c)는 Caffeine 에서도 결국 future 를 값으로 넣는 방식이라 얻는 게 적고, 의존성
	 * 하나를 더 지는 값은 못 한다. 값 자체를 future 로 두면 (c)가 map 연산 하나로 끝난다.
	 */
	private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

	/** 삽입 순서 — 상한 초과 시 오래된 것부터 버린다(대략적 FIFO 로 충분하다). */
	private final ConcurrentLinkedDeque<String> insertionOrder = new ConcurrentLinkedDeque<>();

	public G2bFetchService(G2bApiClient client, G2bErrorTranslator translator) {
		this.client = client;
		this.translator = translator;
	}

	// ── 캐시 있는 단건 호출 ────────────────────────────────────────────────

	/**
	 * 캐시를 거친 단건 호출. 같은 키의 호출이 진행 중이면 그 결과를 함께 기다린다.
	 *
	 * <p>실패한 응답은 캐시에 남기지 않는다 — 일시적 429 하나가 6시간 동안 같은 조회를
	 * 계속 실패시키면 안 된다.
	 */
	public G2bResponse callCached(String url, Map<String, ?> params) {
		String key = cacheKey(url, params);
		long now = System.currentTimeMillis();

		CacheEntry existing = cache.get(key);
		if (existing != null && !existing.isExpired(now)) {
			return join(existing.future());
		}

		CompletableFuture<G2bResponse> future = new CompletableFuture<>();
		CacheEntry fresh = new CacheEntry(future, now);
		CacheEntry raced = cache.compute(key, (k, current) ->
				(current != null && !current.isExpired(now)) ? current : fresh);

		if (raced != fresh) {
			return join(raced.future());
		}

		insertionOrder.addLast(key);
		try {
			G2bResponse result = client.call(url, params);
			future.complete(result);
			prune(now);
			return result;
		}
		catch (RuntimeException ex) {
			cache.remove(key, fresh);
			future.completeExceptionally(ex);
			throw ex;
		}
	}

	// ── 페이지 전량 수집 (날짜 조건 없음) ──────────────────────────────────

	/**
	 * {@code g2bFetchPaged} 이식. 첫 페이지의 {@code totalCount} 로 페이지 수를 계산해 나머지를
	 * 모은다. 날짜 이분 분할은 하지 않는다 — 날짜 파라미터가 없는 조회용이다.
	 */
	public List<Map<String, Object>> fetchPaged(String url, Map<String, ?> baseParams, int pageSize, int maxRows) {
		G2bResponse first = callCached(url, withPaging(baseParams, 1, pageSize));
		int total = Math.min(first.totalCount() > 0 ? first.totalCount() : first.items().size(), maxRows);
		int pageCount = pageSize > 0 ? (int) Math.ceil(total / (double) pageSize) : 1;
		if (pageCount <= 1) {
			return new ArrayList<>(first.items());
		}

		List<Map<String, Object>> all = new ArrayList<>(first.items());
		for (int page = 2; page <= pageCount; page++) {
			all.addAll(callCached(url, withPaging(baseParams, page, pageSize)).items());
		}
		return all;
	}

	// ── 페이지 전량 수집 (날짜창 이분 분할) ────────────────────────────────

	/**
	 * {@code g2bFetchUrl} 이식. 하나의 URL에 대해 날짜창을 필요한 만큼 쪼개 가며 전량을 모은다.
	 */
	public List<Map<String, Object>> fetchUrl(String url, Map<String, ?> baseParams, int numOfRows) {
		return fetchUrl(url, baseParams, numOfRows, 0);
	}

	private List<Map<String, Object>> fetchUrl(String url, Map<String, ?> baseParams, int numOfRows, int depth) {
		String from = str(baseParams.get("inqryBgnDt"));
		String to = str(baseParams.get("inqryEndDt"));
		try {
			Map<String, Object> firstParams = withPaging(baseParams, 1, numOfRows);
			firstParams.putIfAbsent("inqryDiv", 1);
			G2bResponse first = callCached(url, firstParams);

			int total = first.totalCount() > 0 ? first.totalCount() : first.items().size();
			int totalPages = numOfRows > 0 ? (int) Math.ceil(total / (double) numOfRows) : 1;
			if (totalPages <= 1) {
				return new ArrayList<>(first.items());
			}

			// 페이지가 너무 많으면 실패하기 전에 미리 창을 반분한다. 페이지를 잘라 버리면
			// 뒤쪽 공고가 영구 누락되므로, 자르지 않고 기간을 줄이는 쪽을 택한다.
			if (totalPages > MAX_PAGES_PER_WINDOW && from != null && to != null
					&& G2bDates.g2bRangeDays(from, to) > 1 && depth < MAX_SPLIT_DEPTH) {
				List<Map<String, Object>> split = fetchBySplit(url, baseParams, numOfRows, depth, from, to);
				if (split != null) {
					return split;
				}
			}

			List<Map<String, Object>> all = new ArrayList<>(first.items());
			for (int page = 2; page <= totalPages; page++) {
				Map<String, Object> params = withPaging(baseParams, page, numOfRows);
				params.putIfAbsent("inqryDiv", 1);
				all.addAll(callCached(url, params).items());
			}
			return all;
		}
		catch (RuntimeException error) {
			// 범위오류·타임아웃만 분할로 되받는다. 인증 실패를 쪼개면 실패를 2배로 늘릴 뿐이다.
			boolean splittable = translator.isRangeError(error) || translator.isTimeoutError(error);
			if (!splittable || depth >= MAX_SPLIT_DEPTH || from == null || to == null) {
				throw error;
			}
			if (G2bDates.g2bRangeDays(from, to) <= 1) {
				throw error;
			}
			List<Map<String, Object>> split = fetchBySplit(url, baseParams, numOfRows, depth, from, to);
			if (split == null) {
				throw error;
			}
			return split;
		}
	}

	/** 창을 반으로 갈라 각각 재귀 수집한다. 더 못 쪼개면 {@code null}. */
	private List<Map<String, Object>> fetchBySplit(String url, Map<String, ?> baseParams, int numOfRows,
			int depth, String from, String to) {
		List<DateWindow> halves = G2bDates.splitG2bRangeHalf(from, to);
		if (halves == null) {
			return null;
		}
		log.debug("나라장터 날짜창 분할 depth={} {}~{} → {}건", depth, from, to, halves.size());
		List<Map<String, Object>> out = new ArrayList<>();
		for (DateWindow window : halves) {
			Map<String, Object> params = new LinkedHashMap<>(baseParams);
			params.put("inqryBgnDt", window.from());
			params.put("inqryEndDt", window.to());
			out.addAll(fetchUrl(url, params, numOfRows, depth + 1));
		}
		return out;
	}

	/**
	 * {@code g2bFetchAll} 이식. 여러 엔드포인트(물품/용역/공사)를 훑어 합친다.
	 *
	 * <p>일부 엔드포인트만 실패하면 나머지 결과는 살린다 — 물품 API 하나가 삐끗했다고 용역·공사
	 * 결과까지 화면에서 사라지면 안 된다. 단 인증·레이트리밋은 즉시 올린다(전체가 못 쓸 상태다).
	 * 전부 실패한 경우에만 첫 오류를 던진다.
	 */
	public List<Map<String, Object>> fetchAll(List<String> urls, Map<String, ?> baseParams, int numOfRows) {
		List<Map<String, Object>> merged = new ArrayList<>();
		RuntimeException firstError = null;
		int failed = 0;

		for (String url : urls) {
			try {
				merged.addAll(fetchUrl(url, baseParams, numOfRows));
			}
			catch (RuntimeException ex) {
				if (translator.isAuthError(ex) || translator.isRateLimitError(ex)) {
					throw ex;
				}
				failed++;
				if (firstError == null) {
					firstError = ex;
				}
			}
		}
		if (firstError != null && failed == urls.size()) {
			throw firstError;
		}
		return merged;
	}

	public List<Map<String, Object>> fetchAll(List<String> urls, Map<String, ?> baseParams) {
		return fetchAll(urls, baseParams, G2bApiClient.LOADER_PAGE_SIZE);
	}

	// ── 캐시 내부 ──────────────────────────────────────────────────────────

	/**
	 * 캐시 키 = SHA-1({url, 정렬된 params}).
	 *
	 * <p>파라미터를 정렬하는 이유: 같은 조회가 호출부에 따라 다른 순서로 조립돼 오는데,
	 * 순서만 다른 걸 다른 요청으로 세면 캐시가 절반도 안 맞는다.
	 */
	static String cacheKey(String url, Map<String, ?> params) {
		StringBuilder sb = new StringBuilder(url).append(' ');
		new TreeMap<String, Object>(params == null ? Map.of() : params)
				.forEach((k, v) -> sb.append(k).append('=').append(v).append(' '));
		try {
			byte[] digest = MessageDigest.getInstance("SHA-1")
					.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-1 미지원", ex);
		}
	}

	/** 만료분을 먼저 버리고, 그래도 넘치면 오래된 순으로 버린다. */
	private void prune(long now) {
		// 삽입 순서 데크는 같은 키가 만료 후 다시 들어올 때마다 자라므로, 캐시가 상한 아래여도
		// 데크만 무한히 커질 수 있다. 그래서 데크 길이도 함께 본다.
		if (cache.size() <= CACHE_MAX_ENTRIES && insertionOrder.size() <= CACHE_MAX_ENTRIES * 2) {
			return;
		}
		cache.entrySet().removeIf(e -> e.getValue().isExpired(now));
		while (cache.size() > CACHE_MAX_ENTRIES) {
			String oldest = insertionOrder.pollFirst();
			if (oldest == null) {
				break;
			}
			cache.remove(oldest);
		}
		insertionOrder.removeIf(key -> !cache.containsKey(key));
	}

	private static G2bResponse join(CompletableFuture<G2bResponse> future) {
		try {
			return future.join();
		}
		catch (CompletionException ex) {
			// 병합된 요청이 실패하면 원래 예외를 그대로 올려야 분류기가 제 일을 한다.
			Throwable cause = ex.getCause();
			throw cause instanceof RuntimeException re ? re : new G2bException("나라장터 호출 실패", cause);
		}
	}

	private static Map<String, Object> withPaging(Map<String, ?> baseParams, int pageNo, int numOfRows) {
		Map<String, Object> params = new LinkedHashMap<>(baseParams);
		params.put("pageNo", pageNo);
		params.put("numOfRows", numOfRows);
		return params;
	}

	private static String str(Object value) {
		return value == null ? null : String.valueOf(value);
	}

	/** 값 자체가 future 라서 '진행 중'과 '완료'가 같은 자리를 쓴다 — 그게 동일요청 병합이다. */
	private record CacheEntry(CompletableFuture<G2bResponse> future, long timestamp) {

		boolean isExpired(long now) {
			return now - timestamp > CACHE_TTL_MS;
		}
	}
}
