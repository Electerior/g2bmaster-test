package com.electerior.g2bmaster.trend;

import com.electerior.g2bmaster.common.ApiException;
import com.electerior.g2bmaster.config.OpenApiConfig;
import com.electerior.g2bmaster.notice.SearchCriteria;
import com.electerior.g2bmaster.trend.TrendProfiles.KeywordGroup;
import com.electerior.g2bmaster.trend.TrendProfiles.TrendProfile;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 트렌드 3종 ({@code docs/api-contract.md} §2.B).
 *
 * <p>원본은 라우트 세 개가 각각 팩토리로 만든 핸들러를 부르는 구조였는데, 여기서는
 * <b>경로 변수 하나</b>로 합쳤다. 세 라우트의 차이는 프로파일(엔드포인트·구분명·키워드 그룹)
 * 뿐이고, 그 차이는 이미 {@link TrendProfiles} 가 데이터로 들고 있다.
 */
@RestController
@RequestMapping("/api/trends")
@Tag(name = OpenApiConfig.TAG_TREND)
public class TrendController {

	private final BidTrendService trendService;

	public TrendController(BidTrendService trendService) {
		this.trendService = trendService;
	}

	/**
	 * @param kind {@code product} · {@code service} · {@code construction}
	 */
	@Operation(summary = "일자별 트렌드 집계",
			description = "`kind` 는 `product` · `service` · `construction`. 그 외에는 404.")
	@GetMapping("/{kind}")
	public Map<String, Object> trend(@PathVariable String kind, @ModelAttribute SearchCriteria criteria) {
		TrendProfile profile = TrendProfiles.of(kind);
		if (profile == null) {
			throw ApiException.notFound("지원하지 않는 트렌드 종류입니다: " + kind);
		}
		return trendService.buildTrend(profile, criteria);
	}

	/**
	 * 화면이 검색어 자동완성에 쓰는 키워드 그룹 목록.
	 *
	 * <p>그룹 라벨을 그대로 치면 키워드 목록으로 확장된다는 것을 사용자가 알 방법이
	 * 화면 말고는 없어서, 목록 자체를 API로 내려 준다.
	 */
	@Operation(summary = "키워드 그룹 목록",
			description = "화면의 검색어 자동완성용. 그룹 라벨을 그대로 치면 키워드 목록으로 확장된다.")
	@GetMapping("/{kind}/keyword-groups")
	public Map<String, Object> keywordGroups(@PathVariable String kind) {
		TrendProfile profile = TrendProfiles.of(kind);
		if (profile == null) {
			throw ApiException.notFound("지원하지 않는 트렌드 종류입니다: " + kind);
		}
		List<Map<String, Object>> groups = profile.keywordGroups().stream()
				.map(TrendController::describe)
				.toList();
		Map<String, Object> response = new LinkedHashMap<>();
		response.put("kind", profile.key());
		response.put("typeName", profile.typeName());
		response.put("groups", groups);
		return response;
	}

	private static Map<String, Object> describe(KeywordGroup group) {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("label", group.label());
		map.put("keywords", group.keywords());
		return map;
	}
}
