package com.electerior.g2bmaster.config;

import com.electerior.g2bmaster.security.AppAuthInterceptor;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.tags.Tag;
import java.util.List;
import org.springdoc.core.customizers.GlobalOperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger UI({@code /swagger-ui.html}) 와 OpenAPI 문서({@code /v3/api-docs}) 설정.
 *
 * <p>이 저장소에서 API 계약의 원본은 {@code docs/api-contract.md} 다 — 프론트와 함께 읽는
 * 문서이고 아직 이식하지 않은 엔드포인트까지 적혀 있다. Swagger 는 그것을 대체하지 않고
 * <b>지금 이 프로세스가 실제로 제공하는 것</b>을 보여 준다. 둘의 차이가 곧 이식 진도다.
 *
 * <p>태그 이름과 순서를 {@code api-contract.md} §2 의 절 이름에 맞춰 두었다. 두 문서를
 * 나란히 놓고 볼 일이 많은데 순서가 다르면 대조가 어렵다.
 */
@Configuration
public class OpenApiConfig {

	/** {@code X-API-Key: <키>} 갈래. */
	public static final String APP_KEY_SCHEME = "appKey";

	/** {@code Authorization: Bearer <키>} 갈래. */
	public static final String APP_KEY_BEARER_SCHEME = "appKeyBearer";

	public static final String TAG_SEARCH = "입찰 검색";
	public static final String TAG_INDEX_SEARCH = "공고 통합 검색";
	public static final String TAG_TREND = "트렌드";
	public static final String TAG_MARKET = "시장 정보";
	public static final String TAG_ANALYSIS = "AI 분석";
	public static final String TAG_ATTACHMENT = "첨부·파일";
	public static final String TAG_SAVED = "저장 공고";
	public static final String TAG_PRICE = "가격 DB";
	public static final String TAG_SYSTEM = "시스템·운영";

	@Bean
	OpenAPI g2bmasterOpenApi() {
		return new OpenAPI()
				.info(new Info()
						.title("g2bmaster-backend")
						.version("v1")
						.description("""
								나라장터(G2B) 입찰정보 백엔드. **이 문서에 보이는 것이 지금 뜬 프로세스가 \
								실제로 제공하는 전부다.**

								- 프론트와 공유하는 계약 전문(아직 이식하지 않은 것 포함)은 `docs/api-contract.md`
								- 무엇이 아직 안 옮겨졌는지는 `docs/porting-status.md`
								- 추론(LLM·임베딩·법령·가격)은 이 서버가 하지 않고 `g2bmaster-AI` 로 위임한다 \
								(`docs/ai-boundary.md`). `AI_ENABLED=false` 면 위임 경로만 빠지고 나머지는 그대로 동작한다.

								응답에 통일된 봉투는 없다 — 원본 모놀리스의 계약을 그대로 지키기 위한 \
								의도된 선택이다(`api-contract.md` §1.1). 오류만 `{code, message}` 로 통일돼 있다."""))
				.tags(List.of(
						new Tag().name(TAG_SEARCH).description("발주계획·사전규격·입찰공고·개찰결과 팬아웃 검색"),
						new Tag().name(TAG_INDEX_SEARCH).description(
								"로컬 색인(bid_notice) 단독 조회. 계획·사전규격·입찰·마감을 한 테이블에서 검색한다 "
										+ "— 나라장터를 호출하지 않으므로 응답 시간이 일정하다"),
						new Tag().name(TAG_TREND).description("물품·용역·공사 일자별 집계"),
						new Tag().name(TAG_MARKET).description("개찰결과·업체 이력·담당자·담합 매트릭스"),
						new Tag().name(TAG_ANALYSIS).description("분석 작업 큐 상태. 추론 자체는 g2bmaster-AI 가 한다"),
						new Tag().name(TAG_ATTACHMENT).description("첨부 다운로드 프록시"),
						new Tag().name(TAG_SAVED).description("저장 공고 CRUD"),
						new Tag().name(TAG_PRICE).description(
								"가격 카탈로그 — 다나와·아이티마야·에누리 단가 검색·수동 등록/수정·AI 적재·이력"),
						new Tag().name(TAG_SYSTEM).description("운영 현황·오퍼레이션 카탈로그·예약·적재")))
				.components(new Components()
						.addSecuritySchemes(APP_KEY_SCHEME, new SecurityScheme()
								.type(SecurityScheme.Type.APIKEY)
								.in(SecurityScheme.In.HEADER)
								.name("X-API-Key")
								.description("""
										`APP_API_KEY` 값. **키를 설정하지 않고 띄우면 인증이 아예 꺼진다**(개발 모드) — \
										자물쇠가 달린 경로도 헤더 없이 호출된다."""))
						.addSecuritySchemes(APP_KEY_BEARER_SCHEME, new SecurityScheme()
								.type(SecurityScheme.Type.HTTP)
								.scheme("bearer")
								.description("""
										같은 키를 `Authorization: Bearer <키>` 로 보내는 갈래. 프론트가 두 형식을 \
										섞어 쓰고 있어 서버가 둘 다 받는다.""")));
	}

	/**
	 * 자물쇠를 {@code @RequireAppAuth} 가 붙은 경로에만 단다.
	 *
	 * <p>대상 판정을 {@link AppAuthInterceptor#requiresAuth} 로 <b>인터셉터와 같은 함수</b>에
	 * 물려 두었다. 손으로 {@code @SecurityRequirement} 를 붙이면 인증을 새로 건 경로에서
	 * 빠뜨리기 쉽고, 그 결과는 "문서에는 열려 있다고 적힌 401" 이다.
	 *
	 * <p>요구사항을 두 개의 항목으로 나눠 넣은 것은 OpenAPI 에서 <b>OR</b> 을 뜻한다 —
	 * 헤더 둘 중 하나만 있으면 통과하는 실제 동작과 같다.
	 */
	@Bean
	GlobalOperationCustomizer appAuthSecurityRequirements() {
		return (operation, handlerMethod) -> {
			if (!AppAuthInterceptor.requiresAuth(handlerMethod)) {
				return operation;
			}
			return operation
					.addSecurityItem(new SecurityRequirement().addList(APP_KEY_SCHEME))
					.addSecurityItem(new SecurityRequirement().addList(APP_KEY_BEARER_SCHEME));
		};
	}
}
