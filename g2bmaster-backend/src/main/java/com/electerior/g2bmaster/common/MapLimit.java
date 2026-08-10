package com.electerior.g2bmaster.common;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;

/**
 * 동시 실행 수를 제한한 병렬 맵 ({@code lib/map-limit.js} 이식).
 *
 * <p>검색 한 번이 "검색어 × 날짜창 × 엔드포인트" 만큼의 상류 호출로 부풀기 때문에, 이걸
 * 순차로 돌리면 한 화면이 수 분씩 걸린다. 반대로 전부 동시에 던지면 나라장터가 429를 준다.
 * 그래서 원본과 같은 상한(4~8)을 유지한다.
 *
 * <p>실제 HTTP 동시성의 최종 방어선은 여기가 아니라 {@code G2bApiClient} 의 세마포어(기본 4)다.
 * 여기서 세는 것은 "동시에 진행 중인 작업 수"이고, 그 안에서 여러 페이지 호출이 또 일어난다.
 *
 * <p>첫 실패는 {@code Promise.all} 과 동일하게 그대로 올린다 — 인증 실패·쿼터 소진은
 * 나머지 작업을 계속해 봐야 똑같이 실패하고 쿼터만 더 태운다.
 */
public final class MapLimit {

	private MapLimit() {
	}

	public static <T, R> List<R> map(List<T> items, int limit, Function<T, R> mapper) {
		if (items == null || items.isEmpty()) {
			return List.of();
		}
		int width = Math.max(1, Math.min(limit, items.size()));
		if (width == 1) {
			List<R> out = new ArrayList<>(items.size());
			for (T item : items) {
				out.add(mapper.apply(item));
			}
			return out;
		}

		// 플랫폼 스레드 고정 풀을 쓴다. 작업당 수 초의 블로킹 I/O 이지만 동시 실행 수가
		// 한 자리라, 가상 스레드를 풀링해서 얻는 이득보다 코드가 단순한 쪽이 낫다.
		try (ExecutorService pool = Executors.newFixedThreadPool(width)) {
			List<Future<R>> futures = new ArrayList<>(items.size());
			for (T item : items) {
				futures.add(pool.submit(() -> mapper.apply(item)));
			}
			List<R> out = new ArrayList<>(items.size());
			for (Future<R> future : futures) {
				out.add(await(future));
			}
			return out;
		}
	}

	/** 여러 결과 목록을 한 줄로 편다 — 호출부가 {@code .flat()} 을 매번 쓰던 자리. */
	public static <T, R> List<R> flatMap(List<T> items, int limit, Function<T, List<R>> mapper) {
		List<R> out = new ArrayList<>();
		for (List<R> chunk : map(items, limit, mapper)) {
			if (chunk != null) {
				out.addAll(chunk);
			}
		}
		return out;
	}

	private static <R> R await(Future<R> future) {
		try {
			return future.get();
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("병렬 조회 대기 중 인터럽트", ex);
		}
		catch (ExecutionException ex) {
			// 원래 예외를 그대로 올려야 상류 오류 분류기(G2bErrorTranslator)가 제 일을 한다.
			Throwable cause = ex.getCause();
			if (cause instanceof RuntimeException runtime) {
				throw runtime;
			}
			if (cause instanceof Error error) {
				throw error;
			}
			throw new IllegalStateException("병렬 조회 실패", cause);
		}
	}
}
