package com.electerior.g2bmaster.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.electerior.g2bmaster.common.ApiException;
import com.electerior.g2bmaster.integration.ai.AiClient;
import com.electerior.g2bmaster.integration.ai.AiUnavailableException;
import com.electerior.g2bmaster.pricing.PriceCatalogRepository.CatalogUpsert;
import com.electerior.g2bmaster.pricing.PriceCatalogRequests.IngestRequest;
import com.electerior.g2bmaster.pricing.PriceCatalogRequests.UpsertRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 가격 카탈로그 서비스의 로직만 검증한다 — DB 없이(리포지토리·AiClient 는 목).
 *
 * <p>이 저장소엔 MySQL 테스트 하네스가 없고(유일한 부트 테스트도 H2+Flyway off), V10 은
 * MySQL 전용 기능(STORED 생성컬럼·CHECK)을 쓴다. 그래서 SQL 은 실 MySQL 로 따로 확인하고,
 * 여기서는 적재 필터·검증·null 단가 같은 <b>분기</b>를 목으로 못박는다.
 */
class PriceCatalogServiceTest {

	private PriceCatalogRepository repo;
	private AiClient aiClient;
	private PriceCatalogService service;

	@BeforeEach
	void setUp() {
		repo = mock(PriceCatalogRepository.class);
		aiClient = mock(AiClient.class);
		service = new PriceCatalogService(repo, aiClient);
	}

	@Test
	@DisplayName("upsertManual — source 가 화이트리스트 밖이면 400")
	void rejectsUnknownSource() {
		assertThatThrownBy(() -> service.upsertManual(
				new UpsertRequest("coupang", "GPU", "RTX 5090", null, null, 1L, null, null)))
				.isInstanceOf(ApiException.class);
	}

	@Test
	@DisplayName("upsertManual — name 이 비면 400")
	void rejectsBlankName() {
		assertThatThrownBy(() -> service.upsertManual(
				new UpsertRequest("danawa", "GPU", "  ", null, null, 1L, null, null)))
				.isInstanceOf(ApiException.class);
	}

	@Test
	@DisplayName("upsertManual — 유효하면 upsert + 이력 append, {ok,id} 반환")
	void upsertManualHappyPath() {
		when(repo.upsert(any())).thenReturn(42L);

		Map<String, Object> out = service.upsertManual(
				new UpsertRequest("danawa", "GPU", "RTX 5090", "RTX 5090", "32GB", 7_000_000L, "https://d", "메모"));

		assertThat(out).containsEntry("ok", true).containsEntry("id", 42L);
		verify(repo).insertHistory(eq(42L), eq(7_000_000L), any(), any());
	}

	@Test
	@DisplayName("ingest — ai 가 꺼져 있으면 리졸버를 부르지 않고 aiUnavailable")
	void ingestAiDisabled() {
		when(aiClient.isEnabled()).thenReturn(false);

		Map<String, Object> out = service.ingest(new IngestRequest("RTX 5090", null, null));

		assertThat(out).containsEntry("aiUnavailable", true).containsEntry("ingested", 0);
		verify(aiClient, never()).resolvePrice(anyMap());
		verify(repo, never()).upsert(any());
	}

	@Test
	@DisplayName("ingest — 응답이 폴백이면 적재하지 않는다(폴백은 성공이 아니다)")
	void ingestSkipsFallback() {
		when(aiClient.isEnabled()).thenReturn(true);
		when(aiClient.resolvePrice(anyMap())).thenReturn(Map.of(
				"aiFallback", true,
				"quotes", List.of(Map.of("source", "danawa", "name", "x", "priceKrw", 1))));

		Map<String, Object> out = service.ingest(new IngestRequest("q", null, null));

		assertThat(out).containsEntry("aiUnavailable", true).containsEntry("ingested", 0);
		verify(repo, never()).upsert(any());
	}

	@Test
	@DisplayName("ingest — AiUnavailableException 은 삼키고 aiUnavailable 로 알린다")
	void ingestCatchesAiUnavailable() {
		when(aiClient.isEnabled()).thenReturn(true);
		when(aiClient.resolvePrice(anyMap())).thenThrow(new AiUnavailableException("down"));

		Map<String, Object> out = service.ingest(new IngestRequest("q", null, null));

		assertThat(out).containsEntry("aiUnavailable", true);
	}

	@Test
	@DisplayName("ingest — 소스 화이트리스트 밖(coupang)은 버리고, 가격 없으면 null(0 아님), 소스별로 센다")
	void ingestFiltersAndCounts() {
		when(aiClient.isEnabled()).thenReturn(true);
		when(aiClient.resolvePrice(anyMap())).thenReturn(Map.of("quotes", List.of(
				Map.of("source", "danawa", "name", "RTX 5090", "model", "RTX 5090", "priceKrw", 7_000_000, "url", "https://d"),
				Map.of("source", "enuri", "name", "RTX 5090", "price_krw", 6_900_000),   // 별칭 price_krw
				Map.of("source", "itmaya", "name", "ESC8000", "basis", "stale"),          // 가격 없음 → null
				Map.of("source", "coupang", "name", "버릴 것", "priceKrw", 1))));           // 화이트리스트 밖
		when(repo.upsert(any())).thenReturn(1L, 2L, 3L);

		Map<String, Object> out = service.ingest(new IngestRequest("RTX 5090", "GPU", null));

		assertThat(out).containsEntry("ingested", 3);
		@SuppressWarnings("unchecked")
		Map<String, Integer> perSource = (Map<String, Integer>) out.get("perSource");
		assertThat(perSource).containsOnlyKeys("danawa", "enuri", "itmaya");

		ArgumentCaptor<CatalogUpsert> captor = ArgumentCaptor.forClass(CatalogUpsert.class);
		verify(repo, times(3)).upsert(captor.capture());
		verify(repo, times(3)).insertHistory(anyLong(), any(), any(), any());
		Map<String, CatalogUpsert> bySource = new LinkedHashMap<>();
		captor.getAllValues().forEach(u -> bySource.put(u.source(), u));
		assertThat(bySource.get("danawa").priceKrw()).isEqualTo(7_000_000L);
		assertThat(bySource.get("enuri").priceKrw()).isEqualTo(6_900_000L);   // 별칭 인식
		assertThat(bySource.get("itmaya").priceKrw()).isNull();               // 미확인 ≠ 0
		assertThat(bySource.get("danawa").category()).isEqualTo("GPU");
	}

	@Test
	@DisplayName("ingest — sources 로 좁히면 그 소스만 적재")
	void ingestRespectsRequestedSources() {
		when(aiClient.isEnabled()).thenReturn(true);
		when(aiClient.resolvePrice(anyMap())).thenReturn(Map.of("quotes", List.of(
				Map.of("source", "danawa", "name", "a", "priceKrw", 1),
				Map.of("source", "enuri", "name", "b", "priceKrw", 2))));
		when(repo.upsert(any())).thenReturn(1L);

		Map<String, Object> out = service.ingest(new IngestRequest("q", null, List.of("danawa")));

		assertThat(out).containsEntry("ingested", 1);
		@SuppressWarnings("unchecked")
		Map<String, Integer> perSource = (Map<String, Integer>) out.get("perSource");
		assertThat(perSource).containsOnlyKeys("danawa");
	}

	@Test
	@DisplayName("ingest — query 가 비면 400")
	void ingestRejectsBlankQuery() {
		assertThatThrownBy(() -> service.ingest(new IngestRequest("   ", null, null)))
				.isInstanceOf(ApiException.class);
	}

	@Test
	@DisplayName("history — catalogId 도 (source,name) 도 없으면 400")
	void historyNeedsIdentity() {
		assertThatThrownBy(() -> service.history(null, null, null, null, null, 0))
				.isInstanceOf(ApiException.class);
	}

	@Test
	@DisplayName("history — 자연키로 못 찾으면 빈 목록(오류 아님)")
	void historyMissingReturnsEmpty() {
		when(repo.findId("danawa", "없는것", null, null)).thenReturn(null);

		Map<String, Object> out = service.history(null, "danawa", "없는것", null, null, 0);

		assertThat(out).containsEntry("count", 0);
		assertThat((List<?>) out.get("items")).isEmpty();
	}
}
