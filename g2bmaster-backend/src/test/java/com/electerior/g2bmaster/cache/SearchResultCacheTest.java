package com.electerior.g2bmaster.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.electerior.g2bmaster.cache.SearchResultCache.Cached;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 검색 캐시 — 적중·병합·실패 미캐싱.
 *
 * <p>이 셋은 전부 "상류 호출량을 줄이기 위한" 장치라, 결과만 보면 있으나 없으나 똑같아
 * 보인다. 호출 횟수를 직접 세지 않으면 조용히 사라져도 아무도 모른다.
 */
class SearchResultCacheTest {

	private SearchResultCache cache;

	@BeforeEach
	void setUp() {
		cache = new SearchResultCache();
	}

	@Test
	void 두_번째_요청은_로더를_다시_돌리지_않는다() {
		AtomicInteger calls = new AtomicInteger();

		Cached<String> first = cache.getOrFetch("k", () -> {
			calls.incrementAndGet();
			return "값";
		});
		Cached<String> second = cache.getOrFetch("k", () -> {
			calls.incrementAndGet();
			return "다른값";
		});

		assertThat(calls.get()).isEqualTo(1);
		assertThat(first.cached()).isFalse();
		assertThat(second.cached()).isTrue();
		assertThat(second.value()).isEqualTo("값");
	}

	@Test
	void 진행_중인_요청에_합류하면_로더가_한_번만_돈다() throws Exception {
		// 검색 화면을 두 사람이 동시에 열면 같은 조회가 겹친다. 합치지 않으면 상류 호출이 두 배다.
		AtomicInteger calls = new AtomicInteger();
		CountDownLatch started = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);

		try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
			var slow = pool.submit(() -> cache.getOrFetch("k", () -> {
				calls.incrementAndGet();
				started.countDown();
				await(release);
				return List.of("a");
			}));
			assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

			var joined = pool.submit(() -> cache.getOrFetch("k", () -> {
				calls.incrementAndGet();
				return List.of("b");
			}));

			release.countDown();
			assertThat(slow.get(5, TimeUnit.SECONDS).value()).containsExactly("a");
			assertThat(joined.get(5, TimeUnit.SECONDS).value()).containsExactly("a");
		}
		assertThat(calls.get()).isEqualTo(1);
	}

	@Test
	void 실패는_캐시하지_않는다() {
		// 일시적 429 하나가 두 시간 동안 같은 검색을 계속 실패시키면 안 된다.
		AtomicInteger calls = new AtomicInteger();

		assertThatThrownBy(() -> cache.getOrFetch("k", () -> {
			calls.incrementAndGet();
			throw new IllegalStateException("상류 실패");
		})).isInstanceOf(IllegalStateException.class);

		Cached<String> retry = cache.getOrFetch("k", () -> {
			calls.incrementAndGet();
			return "복구";
		});

		assertThat(calls.get()).isEqualTo(2);
		assertThat(retry.value()).isEqualTo("복구");
		assertThat(retry.cached()).isFalse();
	}

	@Test
	void 키가_다르면_따로_캐시된다() {
		cache.getOrFetch("a", () -> "1");
		cache.getOrFetch("b", () -> "2");

		assertThat(cache.size()).isEqualTo(2);
		assertThat(cache.<String>getOrFetch("a", () -> "x").value()).isEqualTo("1");
		assertThat(cache.<String>getOrFetch("b", () -> "x").value()).isEqualTo("2");
	}

	@Test
	void 우회_경로는_캐시를_남기지_않는다() {
		// fileScan=true 같은 경로는 매번 최신 목록이 필요하다.
		Cached<String> result = cache.fetchUncached(() -> "신선");

		assertThat(result.cached()).isFalse();
		assertThat(result.value()).isEqualTo("신선");
		assertThat(cache.size()).isZero();
	}

	private static void await(CountDownLatch latch) {
		try {
			if (!latch.await(5, TimeUnit.SECONDS)) {
				throw new IllegalStateException("래치 대기 시간 초과");
			}
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(ex);
		}
	}
}
