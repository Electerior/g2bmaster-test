package com.electerior.g2bmaster.pricing;

import com.electerior.g2bmaster.config.OpenApiConfig;
import com.electerior.g2bmaster.pricing.PriceCatalogRequests.IngestRequest;
import com.electerior.g2bmaster.pricing.PriceCatalogRequests.UpdateRequest;
import com.electerior.g2bmaster.pricing.PriceCatalogRequests.UpsertRequest;
import com.electerior.g2bmaster.security.RequireAppAuth;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 가격 데이터베이스 표면 — 카탈로그 검색과 수동 CRUD, AI 리졸버 적재, 이력 조회.
 *
 * <p>읽기(GET)는 공개, 쓰기(POST/PUT/DELETE/ingest)는 {@link RequireAppAuth} 로 앱 키를 요구한다 —
 * 다른 쓰기 표면(저장 공고·시스템 적재)과 같은 규칙이다. 추론(가격 긁기)은 여기서 하지 않고
 * {@code g2bmaster-AI} 의 다중소스 리졸버에 위임한다({@link PriceCatalogService#ingest}).
 */
@RestController
@RequestMapping("/api/price-catalog")
@Tag(name = OpenApiConfig.TAG_PRICE)
public class PriceCatalogController {

	private final PriceCatalogService service;

	public PriceCatalogController(PriceCatalogService service) {
		this.service = service;
	}

	@Operation(summary = "가격 카탈로그 검색",
			description = "소스(danawa·itmaya·enuri)·분류·낱말(name/model/spec AND)로 검색. limit 기본 50, 최대 500.")
	@GetMapping
	public Map<String, Object> search(
			@RequestParam(required = false) String source,
			@RequestParam(required = false) String category,
			@RequestParam(required = false) String q,
			@RequestParam(required = false, defaultValue = "0") int limit) {
		return service.search(source, category, q, limit);
	}

	@Operation(summary = "가격 수동 등록/갱신 (upsert)",
			description = "source·name 필수. 같은 (source,name,model,spec) 이면 갱신되고 이력이 남는다. priceKrw 없으면 가격 미확인.")
	@RequireAppAuth
	@PostMapping
	public Map<String, Object> upsert(@RequestBody(required = false) UpsertRequest body) {
		return service.upsertManual(body);
	}

	@Operation(summary = "가격 항목 수정 (부분)",
			description = "id 기반 PATCH. 준 필드만 바뀐다. priceKrw 가 바뀌면 이력에 남는다.")
	@RequireAppAuth
	@PutMapping("/{id}")
	public Map<String, Object> update(@PathVariable long id, @RequestBody(required = false) UpdateRequest body) {
		return service.updateManual(id, body);
	}

	@Operation(summary = "가격 항목 삭제", description = "이력은 FK CASCADE 로 함께 지워진다. 멱등.")
	@RequireAppAuth
	@DeleteMapping("/{id}")
	public Map<String, Object> delete(@PathVariable long id) {
		return service.delete(id);
	}

	@Operation(summary = "AI 리졸버로 시세 적재",
			description = "query 로 다나와·아이티마야·에누리를 긁어 카탈로그에 upsert 한다. sources 로 소스를 좁힐 수 있다. "
					+ "AI 가 꺼져 있으면 500 이 아니라 aiUnavailable 형태로 알린다.")
	@RequireAppAuth
	@PostMapping("/ingest")
	public Map<String, Object> ingest(@RequestBody(required = false) IngestRequest body) {
		return service.ingest(body);
	}

	@Operation(summary = "가격 변동 이력",
			description = "catalogId, 또는 (source & name [& model & spec]) 자연키로 조회. 최신순.")
	@GetMapping("/history")
	public Map<String, Object> history(
			@RequestParam(required = false) Long catalogId,
			@RequestParam(required = false) String source,
			@RequestParam(required = false) String name,
			@RequestParam(required = false) String model,
			@RequestParam(required = false) String spec,
			@RequestParam(required = false, defaultValue = "0") int limit) {
		return service.history(catalogId, source, name, model, spec, limit);
	}
}
