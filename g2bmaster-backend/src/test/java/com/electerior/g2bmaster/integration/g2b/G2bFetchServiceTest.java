package com.electerior.g2bmaster.integration.g2b;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 캐시·동일요청 병합·날짜창 이분 분할 검증(네트워크 없음, 클라이언트는 대역).
 *
 * <p>이 셋은 전부 "쿼터를 태우지 않기 위한" 장치다. 조회 결과만 보면 있으나 없으나 똑같아
 * 보이므로, 여기서 호출 횟수를 직접 세지 않으면 조용히 사라져도 아무도 모른다.
 */
class G2bFetchServiceTest {

	private static final String URL = "https://apis.data.go.kr/1230000/ad/Svc/getList";

	private G2bApiClient client;
	private G2bFetchService service;

	@BeforeEach
	void setUp() {
		client = mock(G2bApiClient.class);
		service = new G2bFetchService(client, new G2bErrorTranslator());
	}

	private static G2bResponse page(int totalCount, int itemCount) {
		List<Map<String, Object>> items = new java.util.ArrayList<>();
		for (int i = 0; i < itemCount; i++) {
			items.add(Map.of("bidNtceNo", "N" + i));
		}
		return new G2bResponse(items, totalCount, 1, itemCount, "00", "정상");
	}

	@Test
	void 캐시키는_파라미터_순서에_흔들리지_않는다() {
		// 같은 조회가 호출부에 따라 다른 순서로 조립돼 온다. 순서만 다른 걸 다른 요청으로 세면
		// 캐시 적중률이 절반 아래로 떨어진다.
		Map<String, Object> a = new LinkedHashMap<>();
		a.put("inqryDiv", 1);
		a.put("pageNo", 1);
		Map<String, Object> b = new LinkedHashMap<>();
		b.put("pageNo", 1);
		b.put("inqryDiv", 1);
		assertThat(G2bFetchService.cacheKey(URL, a)).isEqualTo(G2bFetchService.cacheKey(URL, b));
		assertThat(G2bFetchService.cacheKey(URL, a)).isNotEqualTo(G2bFetchService.cacheKey(URL + "2", a));
	}

	@Test
	void 같은_요청은_상류를_한_번만_친다() {
		when(client.call(anyString(), any())).thenReturn(page(1, 1));
		Map<String, Object> params = Map.of("inqryDiv", 1);

		service.callCached(URL, params);
		service.callCached(URL, params);
		service.callCached(URL, params);

		verify(client, times(1)).call(anyString(), any());
	}

	@Test
	void 실패는_캐시에_남기지_않는다() {
		// 일시적 429 하나가 6시간 동안 같은 조회를 계속 실패시키면 안 된다.
		when(client.call(anyString(), any()))
				.thenThrow(new G2bRateLimitException("나라장터 HTTP 429"))
				.thenReturn(page(1, 1));
		Map<String, Object> params = Map.of("inqryDiv", 1);

		assertThatThrownBy(() -> service.callCached(URL, params)).isInstanceOf(G2bRateLimitException.class);
		assertThat(service.callCached(URL, params).items()).hasSize(1);
		verify(client, times(2)).call(anyString(), any());
	}

	@Test
	void 동시에_들어온_같은_요청은_한_호출을_나눠_쓴다() throws Exception {
		// in-flight 병합이 없으면 검색 화면 하나가 같은 URL을 동시에 여러 번 두드린다.
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		AtomicInteger calls = new AtomicInteger();
		when(client.call(anyString(), any())).thenAnswer(invocation -> {
			calls.incrementAndGet();
			entered.countDown();
			release.await(5, TimeUnit.SECONDS);
			return page(1, 1);
		});

		Map<String, Object> params = Map.of("inqryDiv", 1);
		Thread first = new Thread(() -> service.callCached(URL, params));
		first.start();
		assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

		Thread second = new Thread(() -> service.callCached(URL, params));
		second.start();
		Thread.sleep(50);   // 두 번째가 대기 상태에 들어갈 틈

		release.countDown();
		first.join(5000);
		second.join(5000);

		assertThat(calls.get()).isEqualTo(1);
	}

	@Test
	void 페이지가_여섯을_넘으면_날짜창을_미리_반분한다() {
		// 페이지 상한에서 잘라 버리면 뒤쪽 공고가 영구 누락된다. 자르는 대신 기간을 줄인다.
		when(client.call(anyString(), any())).thenAnswer(invocation -> {
			Map<?, ?> params = invocation.getArgument(1);
			String from = String.valueOf(params.get("inqryBgnDt"));
			// 한 달 창은 페이지가 넘치게, 반분된 창은 한 페이지로 끝나게 응답한다.
			boolean wholeMonth = "202601010000".equals(from) && "202601312359".equals(params.get("inqryEndDt"));
			return wholeMonth ? page(9999, 999) : page(3, 3);
		});

		Map<String, Object> params = new LinkedHashMap<>();
		params.put("inqryBgnDt", "202601010000");
		params.put("inqryEndDt", "202601312359");
		List<Map<String, Object>> rows = service.fetchUrl(URL, params, 999);

		// 반분된 두 창에서 3건씩.
		assertThat(rows).hasSize(6);
	}

	@Test
	void 범위오류를_만나면_창을_갈라_다시_시도한다() {
		when(client.call(anyString(), any())).thenAnswer(invocation -> {
			Map<?, ?> params = invocation.getArgument(1);
			if ("202601010000".equals(params.get("inqryBgnDt"))
					&& "202601312359".equals(params.get("inqryEndDt"))) {
				throw new G2bRangeException("G2B [07]: 입력범위값 초과");
			}
			return page(2, 2);
		});

		Map<String, Object> params = new LinkedHashMap<>();
		params.put("inqryBgnDt", "202601010000");
		params.put("inqryEndDt", "202601312359");
		assertThat(service.fetchUrl(URL, params, 999)).hasSize(4);
	}

	@Test
	void 인증오류는_분할하지_않고_즉시_올린다() {
		// 안 풀릴 요청을 2의 n승으로 늘리면 남은 쿼터만 태운다.
		AtomicInteger calls = new AtomicInteger();
		when(client.call(anyString(), any())).thenAnswer(invocation -> {
			calls.incrementAndGet();
			throw new G2bAuthException("SERVICE KEY IS NOT REGISTERED ERROR");
		});

		Map<String, Object> params = new LinkedHashMap<>();
		params.put("inqryBgnDt", "202601010000");
		params.put("inqryEndDt", "202601312359");
		assertThatThrownBy(() -> service.fetchUrl(URL, params, 999)).isInstanceOf(G2bAuthException.class);
		assertThat(calls.get()).isEqualTo(1);
	}

	@Test
	void 하루짜리_창에서는_더_쪼개지_않고_포기한다() {
		// 이분 분할의 바닥. 여기서 안 멈추면 무한 재귀다.
		when(client.call(anyString(), any())).thenThrow(new G2bRangeException("G2B [07]: 입력범위값 초과"));
		Map<String, Object> params = new LinkedHashMap<>();
		params.put("inqryBgnDt", "202601010000");
		params.put("inqryEndDt", "202601012359");
		assertThatThrownBy(() -> service.fetchUrl(URL, params, 999)).isInstanceOf(G2bRangeException.class);
	}

	@Test
	void 일부_엔드포인트만_실패하면_나머지_결과는_살린다() {
		// 물품 API 하나가 삐끗했다고 용역·공사 결과까지 화면에서 사라지면 안 된다.
		when(client.call(anyString(), any())).thenAnswer(invocation -> {
			String url = invocation.getArgument(0);
			if (url.contains("Thng")) {
				throw new G2bException("상류 오류");
			}
			return page(2, 2);
		});

		List<Map<String, Object>> rows = service.fetchAll(
				List.of(URL + "Thng", URL + "Servc", URL + "Cnstwk"), Map.of("inqryDiv", 1), 999);
		assertThat(rows).hasSize(4);
	}

	@Test
	void 전부_실패하면_첫_오류를_던진다() {
		when(client.call(anyString(), any())).thenThrow(new G2bException("상류 오류"));
		assertThatThrownBy(() -> service.fetchAll(List.of(URL + "A", URL + "B"), Map.of(), 999))
				.isInstanceOf(G2bException.class)
				.hasMessage("상류 오류");
	}
}
