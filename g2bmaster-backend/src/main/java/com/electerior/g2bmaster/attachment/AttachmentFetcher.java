package com.electerior.g2bmaster.attachment;

import com.electerior.g2bmaster.common.ApiException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 첨부 URL 을 바이트로 받아 온다 — SSRF 가드와 <b>홉마다 재검증</b>을 지킨다.
 *
 * <p>{@link AttachmentDownloadController} 는 브라우저로 <b>스트리밍</b>하고, 여기는 서버가
 * 내용을 <b>읽어야 하는</b> 경로(규격서 파싱 등)를 위해 전체 바이트를 돌려준다. 둘 다 같은
 * {@link AttachmentUrlValidator} 를 쓰므로 허용목록 판정은 한 벌이다 — 원본에서 SSRF 목록이
 * 두 벌로 갈려 사설 IP 처리가 달랐던 문제(porting-status.md)를 되풀이하지 않는다.
 */
@Component
public class AttachmentFetcher {

	private static final Logger log = LoggerFactory.getLogger(AttachmentFetcher.class);

	/** 파싱 대상 상한. 규격서가 이보다 크면 첨부를 잘못 고른 것에 가깝다. */
	private static final int MAX_BYTES = 60 * 1024 * 1024;

	private final AttachmentUrlValidator validator;
	private final HttpClient httpClient;

	public AttachmentFetcher(AttachmentUrlValidator validator) {
		this.validator = validator;
		this.httpClient = HttpClient.newBuilder()
				.followRedirects(HttpClient.Redirect.NEVER)   // 리다이렉트를 우리가 잡아 재검증한다
				.connectTimeout(Duration.ofSeconds(10))
				.build();
	}

	/**
	 * @return 다운로드한 바이트. 실패·상류 오류·크기 초과는 {@link ApiException} 으로 올린다
	 */
	public byte[] fetchBytes(String url) {
		URI target = validator.validate(url);
		for (int hop = 0; hop <= AttachmentUrlValidator.MAX_REDIRECTS; hop++) {
			HttpResponse<byte[]> response = send(target);
			int status = response.statusCode();
			if (status >= 300 && status < 400) {
				String location = response.headers().firstValue("location").orElse(null);
				if (location == null) {
					throw ApiException.upstream("첨부파일을 가져오지 못했습니다.");
				}
				target = validator.validate(target.resolve(location).toString());   // 홉마다 재검증
				continue;
			}
			if (status != 200) {
				throw ApiException.upstream("첨부파일을 가져오지 못했습니다. (상류 응답 " + status + ")");
			}
			byte[] body = response.body();
			if (body != null && body.length > MAX_BYTES) {
				throw ApiException.badRequest("첨부파일이 너무 큽니다.");
			}
			return body == null ? new byte[0] : body;
		}
		throw ApiException.upstream("리다이렉트가 너무 많습니다.");
	}

	private HttpResponse<byte[]> send(URI target) {
		try {
			HttpRequest request = HttpRequest.newBuilder(target)
					.timeout(Duration.ofSeconds(60))
					.header("User-Agent", "g2bmaster-backend")
					.GET()
					.build();
			return httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
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
}
