package com.electerior.g2bmaster.saved;

import com.electerior.g2bmaster.config.OpenApiConfig;
import com.electerior.g2bmaster.security.RequireAppAuth;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * 저장 공고 4개 라우트 — 전부 앱 키가 필요하다.
 *
 * <p>클래스에 {@link RequireAppAuth} 를 걸어 메서드마다 빠뜨릴 여지를 없앴다.
 * 원본은 핸들러 첫 줄마다 {@code requireAppAuth(req, res)} 를 손으로 넣는 방식이었다.
 */
@RestController
@RequestMapping("/api/saved-notices")
@RequireAppAuth
@Tag(name = OpenApiConfig.TAG_SAVED)
public class SavedNoticeController {

	private final SavedNoticeService service;

	public SavedNoticeController(SavedNoticeService service) {
		this.service = service;
	}

	/** 저장(신규/갱신). 본문 스키마가 프론트 소유라 {@code JsonNode} 로 받는다. */
	@Operation(summary = "공고 저장 (신규/갱신)",
			description = "본문 스키마는 프론트 소유라 서버가 강제하지 않는다. 견적 품목명까지 검색 텍스트에 넣는다.")
	@PostMapping
	public Map<String, Object> save(@RequestBody(required = false) JsonNode body) {
		return Map.of("ok", true, "bidNtceNo", service.save(body));
	}

	@Operation(summary = "저장 공고 목록·검색")
	@GetMapping
	public Map<String, Object> list(
			@RequestParam(name = "q", required = false) String query,
			@RequestParam(name = "limit", required = false) Integer limit) {
		return service.search(query, limit);
	}

	/**
	 * 단건 전문.
	 *
	 * <p>차수({@code ord})가 경로가 아니라 질의 파라미터인 것은 원본 계약이다. 차수는 대개
	 * 빈 문자열이고, 경로 세그먼트로 두면 빈 값을 표현할 수 없다.
	 */
	@Operation(summary = "저장 공고 단건 전문",
			description = "차수(`ord`)가 경로가 아니라 질의 파라미터인 것은 원본 계약이다 — 대개 빈 문자열이라 경로로 표현할 수 없다.")
	@GetMapping("/{no}")
	public Map<String, Object> findOne(
			@PathVariable("no") String bidNtceNo,
			@RequestParam(name = "ord", required = false, defaultValue = "") String ord) {
		return service.findOne(bidNtceNo, ord);
	}

	@Operation(summary = "저장 공고 삭제")
	@DeleteMapping("/{no}")
	public Map<String, Object> delete(
			@PathVariable("no") String bidNtceNo,
			@RequestParam(name = "ord", required = false, defaultValue = "") String ord) {
		return Map.of("ok", true, "deleted", service.delete(bidNtceNo, ord));
	}
}
