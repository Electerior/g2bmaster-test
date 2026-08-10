package com.electerior.g2bmaster.cache;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * 검색 결과 캐시 — 2시간 / 500건 / 진행 중 요청 병합.
 *
 * <p>상류 호출 캐시({@code G2bFetchService})와 <b>같은 관용구</b>를 쓴다: 값 자체를
 * {@link CompletableFuture} 로 두면 "진행 중"과 "완료"가 같은 자리를 차지해, 동일 키의
 * 두 번째 요청이 map 연산 하나로 첫 요청에 합류한다. 캐싱 방식이 저장소 안에 두 가지로
 * 갈라지면 어느 쪽이 왜 다른지 아무도 설명하지 못하게 된다.
 *
 * <p>TTL 이 상류 캐시(6시간)보다 짧은 이유: 이 캐시가 담는 것은 <b>필터링·스코어링까지 끝난
 * 결과</b>다. 마감 임박 점수는 시간이 지나면 틀려지므로 원자료보다 빨리 버려야 한다.
 *
 * <p>실패는 캐시하지 않는다 — 일시적 429 하나가 두 시간 동안 같은 검색을 계속 실패시키면 안 된다.
 */
@Component
public class SearchResultCache {

	/** 2시간. */
	private static final long TTL_MS = 2L * 60 * 60 * 1000;

	/** 하드 상한 — 캐시 자체가 DoS 벡터가 되지 않게. */
	private static final int MAX_ENTRIES = 500;

	private final ConcurrentHashMap<String, Entry> cache = new ConcurrentHashMap<>();

	/** 삽입 순서 — 상한 초과 시 오래된 것부터 버린다(대략적 FIFO 로 충분하다). */
	private final ConcurrentLinkedDeque<String> insertionOrder = new ConcurrentLinkedDeque<>();

	/**
	 * 캐시 조회 결과.
	 *
	 * @param value  값
	 * @param cached 이미 완료돼 있던 값을 그대로 쓴 경우 {@code true}.
	 *               다른 요청에 합류해 <em>기다린</em> 경우는 {@code false} 다 — 응답의
	 *               {@code _cached} 는 "상류를 안 쳤다"가 아니라 "즉시 답했다"는 뜻이기 때문.
	 */
	public record Cached<T>(T value, boolean cached) {}

	/**
	 * 캐시에 있으면 그대로, 없으면 {@code loader} 로 채운다.
	 *
	 * <p>{@code loader} 는 같은 키에 대해 동시에 두 번 실행되지 않는다.
	 */
	public <T> Cached<T> getOrFetch(String key, Supplier<T> loader) {
		long now = System.currentTimeMillis();

		Entry existing = cache.get(key);
		if (existing != null && !existing.isExpired(now)) {
			boolean wasComplete = existing.future().isDone();
			return new Cached<>(cast(join(existing.future())), wasComplete);
		}

		CompletableFuture<Object> future = new CompletableFuture<>();
		Entry fresh = new Entry(future, now);
		Entry raced = cache.compute(key, (k, current) ->
				(current != null && !current.isExpired(now)) ? current : fresh);

		if (raced != fresh) {
			boolean wasComplete = raced.future().isDone();
			return new Cached<>(cast(join(raced.future())), wasComplete);
		}

		insertionOrder.addLast(key);
		try {
			T value = loader.get();
			future.complete(value);
			prune(now);
			return new Cached<>(value, false);
		}
		catch (RuntimeException ex) {
			cache.remove(key, fresh);
			future.completeExceptionally(ex);
			throw ex;
		}
	}

	/** 캐시를 건너뛰는 경로({@code fileScan=true} 등)가 쓰는 통로. 항상 {@code cached=false}. */
	public <T> Cached<T> fetchUncached(Supplier<T> loader) {
		return new Cached<>(loader.get(), false);
	}

	/** 테스트·운영 도구용. */
	public void clear() {
		cache.clear();
		insertionOrder.clear();
	}

	public int size() {
		return cache.size();
	}

	// ── 내부 ────────────────────────────────────────────────────────────────

	/** 만료분을 먼저 버리고, 그래도 넘치면 오래된 순으로 버린다. */
	private void prune(long now) {
		// 삽입 순서 데크는 같은 키가 만료 후 다시 들어올 때마다 자라므로, 캐시가 상한 아래여도
		// 데크만 무한히 커질 수 있다. 그래서 데크 길이도 함께 본다.
		if (cache.size() <= MAX_ENTRIES && insertionOrder.size() <= MAX_ENTRIES * 2) {
			return;
		}
		cache.entrySet().removeIf(e -> e.getValue().isExpired(now));
		while (cache.size() > MAX_ENTRIES) {
			String oldest = insertionOrder.pollFirst();
			if (oldest == null) {
				break;
			}
			cache.remove(oldest);
		}
		insertionOrder.removeIf(key -> !cache.containsKey(key));
	}

	private static Object join(CompletableFuture<Object> future) {
		try {
			return future.join();
		}
		catch (CompletionException ex) {
			// 합류한 요청이 실패하면 원래 예외를 그대로 올려야 상류 오류 분류기가 제 일을 한다.
			Throwable cause = ex.getCause();
			if (cause instanceof RuntimeException runtime) {
				throw runtime;
			}
			throw ex;
		}
	}

	@SuppressWarnings("unchecked")
	private static <T> T cast(Object value) {
		return (T) value;
	}

	/** 값 자체가 future 라서 '진행 중'과 '완료'가 같은 자리를 쓴다 — 그게 동일요청 병합이다. */
	private record Entry(CompletableFuture<Object> future, long timestamp) {

		boolean isExpired(long now) {
			return now - timestamp > TTL_MS;
		}
	}
}
