package com.electerior.g2bmaster.market;

import com.electerior.g2bmaster.attachment.AttachmentFetcher;
import com.electerior.g2bmaster.attachment.DocumentTextExtractor;
import com.electerior.g2bmaster.attachment.ParsedDocument;
import com.electerior.g2bmaster.attachment.SpecDocumentValidator;
import com.electerior.g2bmaster.attachment.SpecFileSelector;
import com.electerior.g2bmaster.common.ApiException;
import com.electerior.g2bmaster.config.OpenApiConfig;
import com.electerior.g2bmaster.market.MarketIntelRequests.CollusionRequest;
import com.electerior.g2bmaster.market.MarketIntelRequests.CompanyHistoryRequest;
import com.electerior.g2bmaster.market.MarketIntelRequests.OfficerSearchRequest;
import com.electerior.g2bmaster.market.MarketIntelRequests.OpeningResultRequest;
import com.electerior.g2bmaster.integration.ai.AiClient;
import com.electerior.g2bmaster.integration.ai.AiUnavailableException;
import com.electerior.g2bmaster.pricing.DealAnalysisRepository;
import com.electerior.g2bmaster.pricing.DealAnalysisService;
import com.electerior.g2bmaster.pricing.MarketPriceService;
import com.electerior.g2bmaster.saved.SavedNoticeRepository;
import com.electerior.g2bmaster.security.RequireAppAuth;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.security.MessageDigest;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 시장정보 4종 ({@code docs/api-contract.md} §2.C).
 *
 * <p>전부 POST 인 것은 REST 의미론이 아니라 <b>요청 본문에 공고 객체 배열이 실리기 때문</b>이다
 * (담합 분석은 최대 20건). 원본 계약이 그러하므로 GET 으로 바꾸지 않는다.
 *
 * <p>검증 실패 문구는 <b>사용자 대상 계약</b>이다 — 화면에 그대로 렌더링되므로 다듬지 않는다.
 *
 * <p>{@code POST /api/deal-analysis} 는 여기 없다. 첨부 파싱과 단가 추정이 아직 이식되지
 * 않아 의미 있는 응답을 만들 수 없기 때문이고, 껍데기만 두면 "구현됐는데 값이 이상하다"로
 * 오해된다. {@code pricing.DealCalculator} · {@code pricing.MarketPriceService} 는 이미
 * 준비돼 있으니, 첨부 파싱이 들어오는 시점에 그 둘을 엮으면 된다.
 */
@RestController
@RequestMapping("/api")
@Tag(name = OpenApiConfig.TAG_MARKET)
public class MarketIntelController {

	private static final Logger log = LoggerFactory.getLogger(MarketIntelController.class);

	private final MarketIntelService service;
	private final DealAnalysisService dealAnalysis;
	private final DocumentTextExtractor textExtractor;
	private final AttachmentFetcher attachmentFetcher;
	private final AiClient aiClient;
	private final DealAnalysisRepository dealRepository;
	private final SavedNoticeRepository savedRepository;
	private final com.electerior.g2bmaster.notice.BidResultService bidResultService;

	// 입력 해시·결과 저장에 쓰는 정준 직렬화기. 키를 알파벳 순으로 고정해야 같은 입력이 같은
	// 해시를 낸다(맵 순서가 흔들리면 캐시가 매번 빗나간다).
	private final ObjectMapper canonicalMapper = JsonMapper.builder()
			.configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
			.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
			.build();

	public MarketIntelController(MarketIntelService service, DealAnalysisService dealAnalysis,
			DocumentTextExtractor textExtractor, AttachmentFetcher attachmentFetcher,
			AiClient aiClient, DealAnalysisRepository dealRepository,
			SavedNoticeRepository savedRepository,
			com.electerior.g2bmaster.notice.BidResultService bidResultService) {
		this.service = service;
		this.dealAnalysis = dealAnalysis;
		this.textExtractor = textExtractor;
		this.attachmentFetcher = attachmentFetcher;
		this.aiClient = aiClient;
		this.dealRepository = dealRepository;
		this.savedRepository = savedRepository;
		this.bidResultService = bidResultService;
	}

	/**
	 * 공고 하나의 개찰결과(참여업체별 투찰금액).
	 *
	 * <p>미공개·미지원이면 <b>오류가 아니라 빈 배열</b>이다. 개찰 전 공고를 열어 본 것뿐이라
	 * 사용자가 잘못한 것이 없다.
	 */
	@Operation(summary = "개찰결과 (참여업체별 투찰금액)",
			description = "미공개·미지원이면 오류가 아니라 빈 배열이다. 개찰 전 공고를 열어 본 것뿐이다.")
	@PostMapping("/bid-opening-results")
	public Map<String, Object> openingResults(@RequestBody(required = false) OpeningResultRequest request) {
		if (request == null || request.bidNtceNo() == null || request.bidNtceNo().isBlank()) {
			throw ApiException.badRequest("bidNtceNo 필수");
		}
		List<Map<String, Object>> participants = service.fetchOpeningResults(
				request.bidNtceNo(), request.bidNtceSqNo(), request.typeOrDefault());

		Map<String, Object> response = new LinkedHashMap<>();
		response.put("bidNtceNo", request.bidNtceNo());
		response.put("bidNtceSqNo", request.bidNtceSqNo());
		response.put("participants", participants);
		return response;
	}

	/**
	 * 딜 분석 — 시가·딜 계산·규격서를 한 응답으로 엮는다({@code docs/api-contract.md} §2.C).
	 *
	 * <p>계산은 {@link DealAnalysisService}(순수)가 하고, 여기서는 <b>바깥 세계를 붙인다</b>:
	 * 개찰결과 조회, 규격서 첨부 다운로드·파싱. 하나가 실패해도 나머지는 낸다 — 개찰 전이라
	 * 참여업체가 없거나 첨부가 못 열려도 딜 계산 자체는 가능하다.
	 *
	 * <p>부품 단가 추정({@code estimatedUnitCost})은 아직 {@code null} 이다 — 규격서 부품
	 * 추출(AI {@code extract/specs})과 가격 조회({@code price/resolve})가 이식되면 채운다.
	 *
	 * @param body {@code {item, bidPrice?, unitCost?, quantity?, deep?, include?, awards?, forceRefresh?}}.
	 *             {@code deep} 는 <b>기본 켜짐</b>(규격서·부품 단가까지). {@code include} 는 갈래별
	 *             on/off {@code {spec, parts, market, opening}}(각 기본 true). {@code forceRefresh}
	 *             면 저장된 결과를 무시하고 다시 분석한다.
	 */
	@Operation(summary = "딜 분석 (시가·딜·규격서·부품 단가)",
			description = "deep(기본 켜짐)이면 규격서 첨부(HWPX·PDF)를 열어 부품을 뽑고 다나와에서 단가를 "
					+ "추정한다. deep 결과는 입력 해시로 저장돼 같은 입력이면 재사용한다. 갈래는 include 로 끈다.")
	@PostMapping("/deal-analysis")
	@SuppressWarnings("unchecked")
	public Map<String, Object> dealAnalysis(@RequestBody(required = false) Map<String, Object> body) {
		Map<String, Object> req = body == null ? Map.of() : body;
		Object itemRaw = req.get("item");
		if (!(itemRaw instanceof Map)) {
			throw ApiException.badRequest("item(공고 객체) 이 필요합니다.");
		}
		Map<String, Object> item = (Map<String, Object>) itemRaw;

		// deep 은 기본 켜짐 — 명시적으로 false 일 때만 얕은 분석(시가·딜만).
		boolean deep = !Boolean.FALSE.equals(req.get("deep")) && !"false".equals(req.get("deep"));
		Map<String, Object> include = req.get("include") instanceof Map<?, ?> m
				? (Map<String, Object>) m : Map.of();
		boolean wantSpec = deep && flag(include, "spec", true);
		boolean wantParts = deep && flag(include, "parts", true);
		boolean forceRefresh = Boolean.TRUE.equals(req.get("forceRefresh")) || "true".equals(req.get("forceRefresh"));
		// 사용자가 첨부 중 하나를 규격서로 지목했으면 자동 선택을 건너뛴다. 자동 선택은
		// 휴리스틱이고 사람은 공고를 읽었다 — 사람이 고른 것이 이긴다.
		String specFileUrl = String.valueOf(req.getOrDefault("specFileUrl", "")).trim();

		DealAnalysisService.Options options = new DealAnalysisService.Options(
				req.get("bidPrice"), req.get("unitCost"), req.get("quantity"),
				deep, flag(include, "market", true), flag(include, "opening", true));

		String bidNtceNo = String.valueOf(item.getOrDefault("bidNtceNo", "")).trim();

		// ── 저장된 deep 결과 재사용 ──────────────────────────────────────────
		// deep 은 비싸다(첨부 다운로드·LLM 부품 추출·다나와). 입력이 같으면 다시 하지 않는다.
		String inputHash = deep ? hashInput(item, req) : null;
		if (inputHash != null && !forceRefresh) {
			Optional<DealAnalysisRepository.Stored> cached = dealRepository.find(inputHash);
			if (cached.isPresent()) {
				Map<String, Object> stored = readStored(cached.get());
				if (stored != null) {
					stored.put("_fromCache", true);
					stored.put("_analyzedAt", cached.get().createdAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
					return stored;
				}
			}
		}

		// 시가 표본: 프론트가 유사 낙찰을 넘기면 그걸 쓰고, 안 넘기면(수주 데스크가 그렇다)
		// 시가 갈래가 켜져 있을 때 품목명으로 나라장터 낙찰결과를 직접 훑어 표본을 채운다.
		// 예전엔 프론트가 awards 를 안 줘서 항상 "표본 없음"이 떴다 — 그 공백을 여기서 메운다.
		List<MarketPriceService.Award> awards = req.get("awards") instanceof List<?> list
				? DealAnalysisService.awardsFromResults((List<Map<String, Object>>) (List<?>) list)
				: List.of();
		if (awards.isEmpty() && options.includeMarket()) {
			awards = fetchSimilarAwards(item);
		}

		// 규격서: deep + include.spec 일 때만 첨부를 내려받아 파싱한다.
		List<Map<String, String>> specFiles = new ArrayList<>();
		SpecSelection selected = wantSpec ? parseSpecAttachment(item, specFiles, specFileUrl) : null;
		ParsedDocument spec = selected == null ? null : selected.doc();

		// 부품 단가 추정: deep + include.parts 면 시도한다. 규격서 첨부가 있으면 그 텍스트를,
		// 없어도 공고명·품목명으로 추정한다(원본 server.js:2793 과 같다 — GPU 서버는 공고명만으로도
		// ITMAYA 색인에 걸린다). 실패는 삼키고 null — 딜 계산 자체는 막지 않는다.
		Map<String, Object> estimatedUnitCost = wantParts ? estimateUnitCost(selected, item) : null;

		Map<String, Object> result = dealAnalysis.analyze(item, options, awards, spec, specFiles, estimatedUnitCost);

		// 개찰결과(참여업체)는 바깥 조회라 서비스가 아니라 여기서 채운다.
		if (options.includeOpening() && !bidNtceNo.isEmpty()) {
			result.put("opening", fetchOpening(bidNtceNo));
		}

		// deep 결과만 저장한다 — 얕은 분석은 싸므로 눌러앉히지 않는다.
		if (inputHash != null) {
			saveResult(inputHash, bidNtceNo, deep, result);
		}
		result.put("_fromCache", false);
		return result;
	}

	/**
	 * 저장 공고 일괄 딜 분석 — {@code saved_notice} 전부를 deep 분석해 결과를 저장한다.
	 *
	 * <p>결과는 {@code deal_analysis_result} 에 눌러앉으므로, 이후 같은 공고를 열면 저장된 것을
	 * 재사용한다(deal-analysis 의 캐시 히트). 미리 한 번 돌려 두는 것이 목적이다.
	 *
	 * <p><b>오래 걸린다</b> — 공고마다 규격서 첨부를 내려받고 LLM 으로 부품을 뽑고 다나와를
	 * 긁는다. 커맨드로 백그라운드(&)에서 돌리고 넉넉한 타임아웃을 준다. 한 건이 실패해도
	 * 나머지는 계속한다 — 결과에 실패 건을 함께 싣는다.
	 *
	 * @param limit        분석할 최대 건수(최근 저장 순)
	 * @param deep         규격서 부품 단가까지. 기본 켜짐
	 * @param forceRefresh 이미 저장된 결과가 있어도 다시 분석
	 */
	@Operation(summary = "저장 공고 일괄 딜 분석 (backfill)",
			description = "saved_notice 를 deep 분석해 deal_analysis_result 에 저장한다. 오래 걸리므로 "
					+ "백그라운드로 돌릴 것. 이후 같은 공고는 저장된 결과를 재사용한다.")
	@PostMapping("/deal-analysis/backfill")
	@RequireAppAuth
	public Map<String, Object> backfill(
			@org.springframework.web.bind.annotation.RequestParam(name = "limit", required = false, defaultValue = "500") int limit,
			@org.springframework.web.bind.annotation.RequestParam(name = "deep", required = false, defaultValue = "true") boolean deep,
			@org.springframework.web.bind.annotation.RequestParam(name = "forceRefresh", required = false, defaultValue = "false") boolean forceRefresh) {

		List<Map<String, Object>> saved = savedRepository.findAllForAnalysis(limit);
		int analyzed = 0;
		int failed = 0;
		List<Map<String, Object>> errors = new ArrayList<>();

		for (Map<String, Object> row : saved) {
			String bidNtceNo = String.valueOf(row.getOrDefault("bid_ntce_no", "")).trim();
			try {
				Map<String, Object> request = new LinkedHashMap<>();
				request.put("item", savedRowToItem(row));
				request.put("deep", deep);
				request.put("forceRefresh", forceRefresh);
				// 개찰결과는 나라장터 실시간 조회라 일괄에서 매번 태우지 않는다(느리고 쿼터 소모).
				request.put("include", Map.of("opening", false));
				dealAnalysis(request);   // deep 이면 내부에서 deal_analysis_result 에 저장된다
				analyzed++;
			}
			catch (RuntimeException e) {
				failed++;
				log.warn("일괄 딜 분석 실패 {} — {}", bidNtceNo, e.getMessage());
				if (errors.size() < 50) {
					errors.add(Map.of("bidNtceNo", bidNtceNo, "error", String.valueOf(e.getMessage())));
				}
			}
		}

		Map<String, Object> out = new LinkedHashMap<>();
		out.put("total", saved.size());
		out.put("analyzed", analyzed);
		out.put("failed", failed);
		out.put("errors", errors);
		return out;
	}

	/** 저장 공고 한 행을 deal-analysis 의 item 으로. raw(공고 원본 JSON)가 있으면 그것을 쓴다. */
	@SuppressWarnings("unchecked")
	private Map<String, Object> savedRowToItem(Map<String, Object> row) {
		Map<String, Object> item = new LinkedHashMap<>();
		Object raw = row.get("raw");
		if (raw instanceof String s && !s.isBlank()) {
			try {
				item.putAll(canonicalMapper.readValue(s, LinkedHashMap.class));   // 첨부 URL 등 원본 필드
			}
			catch (Exception ignored) {
				// raw 가 깨졌으면 아래 기본 필드로만 분석한다
			}
		}
		item.putIfAbsent("bidNtceNo", row.get("bid_ntce_no"));
		item.putIfAbsent("bidNtceNm", row.get("title"));
		item.putIfAbsent("presmptPrce", row.get("amount"));
		return item;
	}

	/**
	 * AI 로 규격서 부품 단가를 추정한다. <b>{@link SpecDocumentValidator} 를 통과한</b> 규격서
	 * 텍스트가 있으면 그것을, 없어도 공고명·품목명을 함께 넘긴다 — GPU 서버는 공고명만으로도
	 * ITMAYA 색인에 걸린다(원본 estimateText 조합과 같다). AI 가 없거나 실패하면 null —
	 * 딜 계산은 계속된다.
	 *
	 * <p>검증에 걸린 문서는 <b>본문을 빼고</b> 공고 메타만 남긴다. 여기서 막지 않으면 엉뚱한
	 * 문서에서 뽑은 부품이 그대로 단가가 되고, 화면에는 정상적인 숫자로 보인다.
	 */
	private Map<String, Object> estimateUnitCost(SpecSelection selected, Map<String, Object> item) {
		StringBuilder text = new StringBuilder();
		// 규격서로 확인됐거나(ACCEPT), 확인은 안 됐지만 규격을 품고 있을 수 있는(FALLBACK) 문서만
		// 본문을 넘긴다. 계약조건·서약서 같이 구조적으로 부품이 없는 문서는 여기까지 오지 않는다.
		if (selected != null && selected.usableForAi()) {
			text.append(selected.doc().text()).append('\n');
		}
		// 공고 메타 — 규격서가 없거나 부족할 때 GPU 서버 신호(H200·ESC8000 등)가 여기 있을 수 있다.
		for (String key : new String[] {"bidNtceNm", "prdctClsfcNoNm", "dtilPrdctClsfcNoNm", "prdctList"}) {
			Object v = item.get(key);
			if (v != null && !String.valueOf(v).isBlank()) {
				text.append(v).append(' ');
			}
		}
		String specText = text.toString().trim();
		if (specText.isEmpty()) {
			return null;
		}
		try {
			Map<String, Object> payload = new LinkedHashMap<>();
			payload.put("specText", specText);
			payload.put("itemName", String.valueOf(item.getOrDefault("bidNtceNm", "")));
			payload.put("deadlineMs", 30000);
			// 문서종·신뢰 여부는 AI 가 아직 읽지 않는다. 문서종별 프롬프트·스키마 분기(STRATEGY)를
			// 붙일 때 쓰려고 지금부터 실어 보낸다 — 나중에 넣으면 재사용 캐시 키가 통째로 갈린다.
			// specTrusted=false 는 "규격서인지 확인 못 했으니 부품이 없으면 없다고 답하라"는 뜻이다.
			if (selected != null && selected.usableForAi()) {
				payload.put("specTrusted", selected.trusted());
				if (selected.documentClass() != null) {
					payload.put("documentClass", selected.documentClass());
				}
			}
			return aiClient.estimateUnitCost(payload);
		}
		catch (AiUnavailableException e) {
			log.warn("부품 단가 추정 실패(AI) — {}", e.getMessage());
			return null;
		}
	}

	private static boolean flag(Map<String, Object> include, String key, boolean fallback) {
		Object value = include.get(key);
		if (value == null) {
			return fallback;
		}
		return Boolean.TRUE.equals(value) || "true".equals(value);
	}

	/** item + 옵션의 정준 JSON SHA-256. 단가·수량·deep·토글이 바뀌면 해시가 바뀐다. */
	private String hashInput(Map<String, Object> item, Map<String, Object> req) {
		Map<String, Object> key = new LinkedHashMap<>();
		key.put("item", item);
		for (String field : new String[] {"bidPrice", "unitCost", "quantity", "deep", "include"}) {
			key.put(field, req.get(field));
		}
		try {
			byte[] canonical = canonicalMapper.writeValueAsBytes(key);
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical);
			StringBuilder hex = new StringBuilder(64);
			for (byte b : digest) {
				hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
			}
			return hex.toString();
		}
		catch (Exception e) {   // 직렬화·다이제스트 실패 — 캐시를 건너뛴다(분석은 그대로 진행)
			log.warn("딜 분석 입력 해시 실패 — 캐시 건너뜀: {}", e.getMessage());
			return null;
		}
	}

	private Map<String, Object> readStored(DealAnalysisRepository.Stored stored) {
		try {
			return canonicalMapper.readValue(stored.json(), new TypeReference<LinkedHashMap<String, Object>>() {});
		}
		catch (Exception e) {   // 저장 포맷이 깨졌으면 캐시 무시하고 다시 분석
			log.warn("저장된 딜 분석 파싱 실패 — 다시 분석: {}", e.getMessage());
			return null;
		}
	}

	private void saveResult(String inputHash, String bidNtceNo, boolean deep, Map<String, Object> result) {
		try {
			dealRepository.save(inputHash, bidNtceNo, deep, canonicalMapper.writeValueAsString(result));
		}
		catch (Exception e) {   // 저장 실패가 분석 응답을 막지는 않는다
			log.warn("딜 분석 결과 저장 실패: {}", e.getMessage());
		}
	}

	/**
	 * 완제품 비교({@code docs/api-contract.md} §F) — AI 저장소로 위임한다.
	 *
	 * <p>번들이 완제품인지 부품 조달인지 판정하고, 완제품이면 유사 완제품 최저가를 찾는다.
	 * deal-analysis 의 부품 단가 추정과 반대 방향이다.
	 */
	@Operation(summary = "유사 완제품 비교 (완제품/번들 판정)",
			description = "번들이 완본체인지 부품 조달인지 신호로 판정하고, 완본체면 유사 완제품(다나와)을 찾는다.")
	@PostMapping("/prebuilt-comparables")
	public Map<String, Object> prebuiltComparables(@RequestBody(required = false) Map<String, Object> body) {
		try {
			return aiClient.prebuiltComparables(body == null ? Map.of() : body);
		}
		catch (AiUnavailableException e) {
			// AI 가 없으면 판정을 못 한다 — 완제품이 아니라고 단정하지 말고 그 사실을 알린다.
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("eligible", false);
			out.put("reason", "ai-unavailable");
			out.put("isPrebuilt", false);
			out.put("comparables", List.of());
			out.put("searchStatus", "ai-unavailable");
			return out;
		}
	}

	/**
	 * 고른 규격서와 그 검증 결과.
	 *
	 * <p>거부돼도 {@code doc} 은 그대로 들고 있다 — 응답에는 남겨 사용자가 어떤 문서가 뽑혔는지
	 * 볼 수 있게 하고, <b>AI 인계만</b> 막는다.
	 */
	private record SpecSelection(ParsedDocument doc, SpecDocumentValidator.Verdict verdict) {

		/** 본문을 AI 로 넘길 수 있는가 — 규격서로 확인됐거나(ACCEPT) 규격을 품을 수 있는(FALLBACK) 문서. */
		boolean usableForAi() {
			return verdict.usable() && doc != null && !doc.isEmpty();
		}

		/** 규격서로 <b>확인</b>됐는가. {@code false} 면 판단을 LLM 에 맡긴 것이다. */
		boolean trusted() {
			return verdict.accepted();
		}

		String documentClass() {
			return verdict.documentClass();
		}
	}

	/** 내려받아 채점할 첨부 상한. deep 분석의 느린 경로라 넉넉히 두되, 30개 첨부를 다 긁진 않는다. */
	private static final int MAX_SPEC_DOWNLOADS = 6;

	/**
	 * 첨부 수집 전체에 주는 시간. <b>건수 제한만으로는 시간이 묶이지 않는다</b> —
	 * {@code AttachmentFetcher} 가 한 건에 60초까지 쓰므로 6건이면 최악 360초다.
	 * 실측에서 첨부 7건짜리 공고 셋이 300초에도 끝나지 않았고, 화면은 그보다 훨씬 앞서
	 * 포기해 "분석 실패"로 보였다.
	 *
	 * <p>여기서 끊어도 이미 받아 둔 후보로 판정은 진행한다 — 규격서가 앞 순위에 오도록
	 * 파일명 랭크로 정렬해 두었으므로, 뒤쪽을 못 본 손해는 대개 계약예규·서식 쪽이다.
	 */
	private static final long SPEC_FETCH_BUDGET_MS = 90_000L;

	/**
	 * 후보 하나의 선택 확신 등급. {@code llmSaysSpec} 없이 부를 때의
	 * {@link SpecFileSelector#chooseSpecHybrid} 판정과 같은 기준이다.
	 */
	private static String confidenceOf(SpecFileSelector.Candidate candidate) {
		return SpecFileSelector.hybridScore(candidate) >= SPEC_MIN_SCORE ? "heuristic" : "estimated";
	}
	/** 이 점수 미만이면 규격서라고 확신하지 못한다({@code confidence=estimated}). */
	private static final int SPEC_MIN_SCORE = 20;

	/**
	 * 첨부 중 <b>진짜 규격서</b>를 골라 텍스트를 뽑는다. 실패는 삼키고 {@code null} — 딜 계산을 막지 않는다.
	 *
	 * <p>파일명만 보고 첫 후보를 집지 않는다. 파일명 랭크가 높은 것부터 몇 개를 내려받아
	 * <b>변환된 내용으로 규격서다움을 채점</b>({@link SpecFileSelector#specContentScore})하고,
	 * 최고점을 고른다 — 파일명은 힌트, 내용이 판정이다. zip 은 풀어 안의 문서까지 후보에 넣는다.
	 */
	@SuppressWarnings("unchecked")
	private SpecSelection parseSpecAttachment(Map<String, Object> item,
			List<Map<String, String>> specFiles, String specFileUrl) {
		Object attachments = item.get("attachmentUrls");
		if (!(attachments instanceof List<?> list) || list.isEmpty()) {
			return null;
		}
		// {name,url} 중 파서가 지원(HWPX·PDF)하거나 zip 인 것을 파일명 랭크 순으로 본다.
		List<Map<String, Object>> candidates = new ArrayList<>();
		for (Object entry : list) {
			if (entry instanceof Map<?, ?> map) {
				candidates.add((Map<String, Object>) map);
			}
		}
		// 사용자가 지목한 파일이 있으면 후보를 그것 하나로 좁힌다. 지목한 URL 이 첨부 목록에
		// 없으면 무시하고 자동 선택으로 돌아간다 — 임의 URL 을 받아 내려받지 않는다(SSRF).
		boolean userPicked = false;
		if (specFileUrl != null && !specFileUrl.isBlank()) {
			List<Map<String, Object>> picked = candidates.stream()
					.filter(c -> specFileUrl.equals(str(c.get("url"))))
					.toList();
			if (picked.isEmpty()) {
				log.info("지목한 규격서가 첨부 목록에 없다 — 자동 선택으로 진행한다: {}", specFileUrl);
			}
			else {
				candidates = new ArrayList<>(picked);
				userPicked = true;
			}
		}
		// 랭크는 낮을수록 규격서다움 — 오름차순으로 정렬해 유력한 것부터 내려받는다.
		candidates.sort(Comparator.comparingInt(
				(Map<String, Object> m) -> SpecFileSelector.specFilenameRank(str(m.get("name")))));

		List<SpecFileSelector.Candidate> scored = new ArrayList<>();
		Map<SpecFileSelector.Candidate, ParsedDocument> docByCandidate = new java.util.IdentityHashMap<>();
		Map<SpecFileSelector.Candidate, String> urlByCandidate = new java.util.IdentityHashMap<>();

		int downloads = 0;
		long deadline = System.nanoTime() + SPEC_FETCH_BUDGET_MS * 1_000_000L;
		for (Map<String, Object> candidate : candidates) {
			if (downloads >= MAX_SPEC_DOWNLOADS) {
				break;
			}
			if (System.nanoTime() >= deadline) {
				log.info("첨부 수집 시간 예산({}ms) 초과 — 지금까지 받은 {}건으로 판정한다",
						SPEC_FETCH_BUDGET_MS, scored.size());
				break;
			}
			String name = str(candidate.get("name"));
			String url = str(candidate.get("url"));
			boolean archive = textExtractor.isArchive(name);
			if (url.isEmpty() || (!textExtractor.supports(name) && !archive)) {
				continue;
			}
			try {
				byte[] bytes = attachmentFetcher.fetchBytes(url);
				downloads++;
				List<ParsedDocument> docs = archive
						? textExtractor.expandArchive(name, bytes)
						: List.of(textExtractor.extract(name, bytes));
				for (ParsedDocument doc : docs) {
					SpecFileSelector.Candidate c = SpecFileSelector.Candidate.of(doc.filename(), doc.text());
					scored.add(c);
					docByCandidate.put(c, doc);
					urlByCandidate.put(c, url);
				}
			}
			catch (RuntimeException e) {
				log.warn("규격서 파싱 실패 {} — {}", name, e.getMessage());
				// 다음 후보로 — 첨부 하나가 깨졌다고 분석 전체를 막지 않는다.
			}
		}
		if (scored.isEmpty()) {
			return null;
		}

		// 순위대로 걸어 내려가며 검증한다. 최고점 하나만 보면 그것이 공고문일 때 세 번째 후보에
		// 있는 진짜 규격서를 놓친다. 규격서를 찾으면 즉시 멈추고, 못 찾으면 fallback 을 남긴다.
		List<SpecFileSelector.Candidate> ranked = SpecFileSelector.rankHybrid(scored);
		List<SpecDocumentValidator.Reviewable> reviewables = ranked.stream()
				.map(c -> new SpecDocumentValidator.Reviewable(docByCandidate.get(c), confidenceOf(c)))
				.toList();
		SpecDocumentValidator.Walk walk = SpecDocumentValidator.walk(reviewables, userPicked);
		if (!walk.found()) {
			log.info("규격서 후보 {}건이 모두 거부됨 — 공고 메타만으로 진행한다", ranked.size());
			return null;
		}
		SpecFileSelector.Candidate chosen = ranked.get(walk.index());
		SpecDocumentValidator.Verdict verdict = walk.verdict();
		int examined = walk.examined();

		ParsedDocument doc = docByCandidate.get(chosen);
		String url = urlByCandidate.getOrDefault(chosen, "");
		SpecFileSelector.Density density = SpecFileSelector.density(chosen.markdown());
		double similarity = SpecFileSelector.partNameSimilarity(chosen.markdown());
		SpecFileSelector.Choice choice = new SpecFileSelector.Choice(chosen, confidenceOf(chosen));

		Map<String, String> chosenFile = new LinkedHashMap<>();
		chosenFile.put("name", doc.filename());
		chosenFile.put("url", url);
		chosenFile.put("confidence", choice.confidence());   // confirmed|heuristic|estimated
		chosenFile.put("partSimilarity", String.format("%.2f", similarity));
		chosenFile.put("tableRowDensity", String.format("%.2f", density.tableRowDensity()));
		chosenFile.put("documentClass", verdict.documentClass() == null ? "" : verdict.documentClass());
		chosenFile.put("disposition", verdict.disposition().name());   // ACCEPT|FALLBACK
		chosenFile.put("specTrusted", String.valueOf(verdict.accepted()));
		chosenFile.put("validationVia", verdict.via());
		chosenFile.put("validationReasons", String.join(",", verdict.reasons()));
		chosenFile.put("candidatesExamined", String.valueOf(examined));
		chosenFile.put("selectedBy", userPicked ? "user" : "auto");
		specFiles.add(chosenFile);

		if (!verdict.accepted()) {
			// 규격서로 확인되진 않았다. 넘기되 판단은 LLM 에 맡긴다.
			log.info("규격서 미확인 — LLM 판단에 맡김({}): {} — 문서종 {} · 후보 {}건 검토 · 사유 {}",
					verdict.via(), doc.filename(),
					verdict.documentClass() == null ? "미상" : verdict.documentClass(),
					examined, String.join(",", verdict.reasons()));
		}
		else if (!"confirmed".equals(choice.confidence()) && !"heuristic".equals(choice.confidence())) {
			log.info("규격서 확신 낮음({}): {} — 내용 {} · 유사도 {} · 표밀도 {}",
					choice.confidence(), doc.filename(), chosen.score(),
					String.format("%.2f", similarity), String.format("%.2f", density.tableRowDensity()));
		}
		return new SpecSelection(doc, verdict);
	}

	private Map<String, Object> fetchOpening(String bidNtceNo) {
		try {
			List<Map<String, Object>> participants = service.fetchOpeningResults(bidNtceNo, null, "물품");
			Map<String, Object> opening = new LinkedHashMap<>();
			opening.put("participantCount", participants.size());
			opening.put("participants", participants);
			return opening;
		}
		catch (RuntimeException e) {
			log.warn("개찰결과 조회 실패 {} — {}", bidNtceNo, e.getMessage());
			return null;   // 개찰 전이거나 미공개 — 딜 계산은 그대로 낸다
		}
	}

	/**
	 * 유사 낙찰 표본을 나라장터 낙찰결과에서 직접 훑는다 — 프론트(수주 데스크)가 awards 를 안 넘길 때
	 * 시가·예상 낙찰가를 채우기 위한 것이다. 품목명(공고명/품목분류명)으로 최근 구간을 조회한다.
	 *
	 * <p>실패는 삼키고 빈 목록 — 나라장터 키가 없거나 조회가 실패해도 딜 계산 자체는 막지 않는다
	 * (그 경우 예전과 같이 "표본 없음"이 된다). 이름 대조(matchAwards)는 DealAnalysisService 가 한다.
	 */
	private List<MarketPriceService.Award> fetchSimilarAwards(Map<String, Object> item) {
		// 공고명 전체가 아니라 품목 한 낱말로 훑는다 — 제목 전체는 거의 언제나 0건이다.
		String keyword = DealAnalysisService.awardKeyword(
				firstNonBlank(item, "dtilPrdctClsfcNoNm", "prdctClsfcNoNm"),
				firstNonBlank(item, "bidNtceNm", "bizNm"));
		if (keyword.isBlank()) {
			return List.of();
		}
		// 공고의 구분을 그대로 넘긴다. "물품"으로 고정하면 용역·공사 공고는 구조적으로 0건이다.
		String division = awardBidType(item);
		try {
			com.electerior.g2bmaster.notice.SearchCriteria criteria =
					new com.electerior.g2bmaster.notice.SearchCriteria(
							keyword, "", "", null, "100", null, null, "", "", "", "", division, "");
			List<Map<String, Object>> results = bidResultService.search(criteria, null).value();
			return DealAnalysisService.awardsFromResults(results);
		}
		catch (RuntimeException e) {
			log.debug("유사 낙찰 표본 조회 실패 {}({}) — {}", keyword, division, e.getMessage());
			return List.of();   // 키 없음/조회 실패 — 표본 없음으로 남는다(회귀 아님)
		}
	}

	/**
	 * 낙찰결과 조회에 쓸 구분. 공고가 알려 주면 그대로 쓰고, 모르면 {@code 물품} 으로 둔다
	 * (원본 동작 — 딜 분석 사용처의 대부분이 물품이다).
	 */
	private static String awardBidType(Map<String, Object> item) {
		String raw = firstNonBlank(item, "businessDivision", "bidType", "bsnsDivNm", "indstrytyNm");
		for (String known : new String[] {"물품", "용역", "공사", "외자"}) {
			if (raw.contains(known)) {
				return known;
			}
		}
		return "물품";
	}

	private static String firstNonBlank(Map<String, Object> item, String... keys) {
		for (String key : keys) {
			String value = str(item.get(key));
			if (!value.isBlank()) {
				return value;
			}
		}
		return "";
	}

	private static String str(Object value) {
		return value == null ? "" : String.valueOf(value).trim();
	}

	/** 업체 낙찰·참여 이력과 투찰률 추세. 업체명 또는 사업자번호 중 하나는 필수. */
	@Operation(summary = "업체 낙찰·참여 이력",
			description = "업체명(`corpNm`) 또는 사업자번호(`brnNo`) 중 하나는 필수. 투찰률 추세를 함께 준다.")
	@PostMapping("/company-history")
	public Map<String, Object> companyHistory(@RequestBody(required = false) CompanyHistoryRequest request) {
		if (request == null || (isBlank(request.corpNm()) && isBlank(request.brnNo()))) {
			throw ApiException.badRequest("업체명 또는 사업자번호 필요");
		}
		return service.companyHistory(request);
	}

	/** 발주기관 담당자별 공고 묶음. */
	@Operation(summary = "발주기관 담당자별 공고 묶음", description = "발주기관명(`insttNm`) 필수.")
	@PostMapping("/officer-search")
	public Map<String, Object> officerSearch(@RequestBody(required = false) OfficerSearchRequest request) {
		if (request == null || isBlank(request.insttNm())) {
			throw ApiException.badRequest("발주기관명 필요");
		}
		return service.officerSearch(request);
	}

	/** 담합 정황 매트릭스. 최대 {@value MarketIntelService#MAX_COLLUSION_BIDS} 건까지 처리한다. */
	@Operation(summary = "담합 정황 매트릭스",
			description = "본문에 공고 배열을 싣는다(그래서 GET 이 아니다). 최대 20건까지 처리한다.")
	@PostMapping("/collusion-analysis")
	public Map<String, Object> collusionAnalysis(@RequestBody(required = false) CollusionRequest request) {
		if (request == null || request.bids() == null || request.bids().isEmpty()) {
			throw ApiException.badRequest("bids 배열 필수");
		}
		return service.collusionAnalysis(request.bids());
	}

	private static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}
}
