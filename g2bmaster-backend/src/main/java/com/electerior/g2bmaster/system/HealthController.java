package com.electerior.g2bmaster.system;

import com.electerior.g2bmaster.config.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /healthz} — 배포 스크립트와 로드밸런서가 보는 경로.
 *
 * <p>Actuator 의 {@code /actuator/health} 와 별개로 유지하는 이유는 기존 배포 설정과
 * 모니터링(`scripts/ops_watch.js`)이 이 경로를 참조하기 때문이다. 응답 본문
 * {@code {ok:true, platform:'onprem'}} 도 원본 그대로다.
 *
 * <p>DB 가 죽어도 200 을 준다 — 원본과 같은 판단이다. 앱 프로세스가 살아 있는지와
 * 의존 컴포넌트가 건강한지는 다른 질문이고, 후자는 {@code /api/system/status} 가 답한다.
 */
@RestController
@Tag(name = OpenApiConfig.TAG_SYSTEM)
public class HealthController {

	@Operation(summary = "생존 확인",
			description = "**DB 가 죽어도 200 이다** — 프로세스가 살아 있는지와 의존 컴포넌트가 건강한지는 다른 질문이고, 후자는 `/api/system/status` 가 답한다.")
	@GetMapping("/healthz")
	public Map<String, Object> healthz() {
		return Map.of("ok", true, "platform", "onprem");
	}
}
