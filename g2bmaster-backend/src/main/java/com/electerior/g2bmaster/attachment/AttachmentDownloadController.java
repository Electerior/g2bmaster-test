package com.electerior.g2bmaster.attachment;

import com.electerior.g2bmaster.common.ApiException;
import com.electerior.g2bmaster.config.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 나라장터 첨부파일 다운로드 프록시.
 *
 * <p>브라우저가 원본 URL 로 직접 가지 못하는 이유는 두 가지다 — 상류가 CORS 를 주지 않고,
 * 한글 파일명이 {@code Content-Disposition} 없이 내려와 깨진다. 그래서 서버가 대신 받아
 * 파일명을 붙여 흘려보낸다.
 *
 * <p>사용자가 준 URL 을 서버가 요청하는 구조이므로 SSRF 방어가 핵심이고,
 * 그 판정은 {@link AttachmentUrlValidator} 가 한다. 여기서 중요한 것은
 * <b>리다이렉트를 자동으로 따라가지 않는다</b>는 점 — 홉마다 검증을 다시 태워야 하므로
 * {@link HttpClient.Redirect#NEVER} 로 두고 직접 돈다.
 */
@RestController
@RequestMapping("/api")
@Tag(name = OpenApiConfig.TAG_ATTACHMENT)
public class AttachmentDownloadController {

	private static final Logger log = LoggerFactory.getLogger(AttachmentDownloadController.class);

	private final AttachmentUrlValidator validator;
	private final HttpClient httpClient;

	public AttachmentDownloadController(AttachmentUrlValidator validator) {
		this.validator = validator;
		this.httpClient = HttpClient.newBuilder()
				.followRedirects(HttpClient.Redirect.NEVER)
				.connectTimeout(Duration.ofSeconds(10))
				.build();
	}

	@Operation(summary = "첨부파일 다운로드 프록시",
			description = """
					사용자가 준 URL 을 서버가 대신 받는 구조라 SSRF 가드가 핵심이다 — 허용목록 밖이면 403, \
					리다이렉트는 홉마다 다시 검증한다. 한글 파일명 때문에 `Content-Disposition` 은 RFC 5987 \
					형식으로 붙인다.""")
	@GetMapping("/download-attachment")
	public ResponseEntity<InputStreamResource> download(
			@RequestParam("url") String url,
			@RequestParam(value = "name", required = false) String name) {

		URI target = validator.validate(url);

		for (int hop = 0; hop <= AttachmentUrlValidator.MAX_REDIRECTS; hop++) {
			HttpResponse<InputStream> response = fetch(target);
			int status = response.statusCode();

			if (status >= 300 && status < 400) {
				String location = response.headers().firstValue("location").orElse(null);
				if (location == null) {
					throw ApiException.upstream("첨부파일을 가져오지 못했습니다.");
				}
				// 상대 경로 리다이렉트도 있으므로 현재 URI 기준으로 해석한 뒤 다시 검증한다.
				target = validator.validate(target.resolve(location).toString());
				continue;
			}

			if (status != 200) {
				throw ApiException.upstream("첨부파일을 가져오지 못했습니다. (상류 응답 " + status + ")");
			}

			return stream(response, name);
		}

		throw ApiException.upstream("리다이렉트가 너무 많습니다.");
	}

	private HttpResponse<InputStream> fetch(URI target) {
		try {
			HttpRequest request = HttpRequest.newBuilder(target)
					.timeout(Duration.ofSeconds(60))
					.header("User-Agent", "g2bmaster-backend")
					.GET()
					.build();
			return httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw ApiException.upstream("첨부파일 다운로드가 중단되었습니다.");
		}
		catch (Exception e) {
			log.warn("첨부 다운로드 실패: {} — {}", target.getHost(), e.getMessage());
			throw ApiException.upstream("첨부파일을 가져오지 못했습니다.");
		}
	}

	private ResponseEntity<InputStreamResource> stream(HttpResponse<InputStream> response, String name) {
		HttpHeaders headers = new HttpHeaders();

		response.headers().firstValue("content-type")
				.ifPresent(value -> headers.set(HttpHeaders.CONTENT_TYPE, value));
		if (headers.getContentType() == null) {
			headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
		}
		response.headers().firstValueAsLong("content-length")
				.ifPresent(headers::setContentLength);

		// 한글 파일명 때문에 RFC 5987 형식이 필요하다. filename= 만 쓰면 깨진다.
		String filename = (name == null || name.isBlank()) ? "attachment" : name;
		String encoded = java.net.URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
		headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded);

		return ResponseEntity.ok()
				.headers(headers)
				.body(new InputStreamResource(response.body()));
	}
}
