package com.electerior.g2bmaster.attachment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FileEntryCollectorTest {

	private static Map<String, Object> item(String... kv) {
		Map<String, Object> map = new LinkedHashMap<>();
		for (int i = 0; i < kv.length; i += 2) {
			map.put(kv[i], kv[i + 1]);
		}
		return map;
	}

	@Test
	@DisplayName("번호가 붙은 URL/파일명 짝을 맞춰 뽑는다")
	void pairsNumberedUrlAndName() {
		var entries = FileEntryCollector.collect(item(
				"ntceSpecDocUrl1", "https://www.g2b.go.kr/downloadFile?id=1",
				"ntceSpecFileNm1", "물품규격서.hwp",
				"ntceSpecDocUrl2", "https://www.g2b.go.kr/downloadFile?id=2",
				"ntceSpecFileNm2", "입찰공고문.pdf"));

		assertThat(entries).hasSize(2);
		assertThat(entries.get(0).name()).isEqualTo("물품규격서.hwp");
		assertThat(entries.get(1).name()).isEqualTo("입찰공고문.pdf");
	}

	@Test
	@DisplayName("공고 상세 페이지 링크는 첨부가 아니다")
	void skipsDetailPageLinks() {
		var entries = FileEntryCollector.collect(item(
				"bidNtceDtlUrl", "https://www.g2b.go.kr/pt/menu/selectSubFrame.do?bidno=1",
				"ntceSpecDocUrl1", "https://www.g2b.go.kr/downloadFile?id=1"));

		assertThat(entries).hasSize(1);
		assertThat(entries.getFirst().url()).contains("downloadFile");
	}

	@Test
	@DisplayName("페이지성 필드라도 URL 에 download 가 있으면 첨부로 본다")
	void keepsPageFieldWithDownloadUrl() {
		var entries = FileEntryCollector.collect(item(
				"atchFileUrl1", "https://www.g2b.go.kr/co/downloadAtchFile.do?id=9"));
		assertThat(entries).hasSize(1);
	}

	@Test
	@DisplayName("URL 이 아닌 값과 중복 URL 은 버린다")
	void ignoresNonUrlsAndDuplicates() {
		var entries = FileEntryCollector.collect(item(
				"bidNtceNm", "GPU 서버 구매",
				"ntceSpecDocUrl1", "https://www.g2b.go.kr/downloadFile?id=1",
				"specDocFileUrl1", "https://www.g2b.go.kr/downloadFile?id=1"));

		assertThat(entries).hasSize(1);
	}

	@Test
	@DisplayName("파일명 필드가 없으면 필드명을 이름으로 쓴다 — 이름이 없다고 첨부를 버리지 않는다")
	void fallsBackToFieldName() {
		var entries = FileEntryCollector.collect(item(
				"ntceSpecDocUrl1", "https://www.g2b.go.kr/downloadFile?id=1"));

		assertThat(entries).hasSize(1);
		assertThat(entries.getFirst().name()).isEqualTo("ntceSpecDocUrl1");
	}

	@Test
	@DisplayName("빈 항목은 빈 목록")
	void emptyItem() {
		assertThat(FileEntryCollector.collect(null)).isEmpty();
		assertThat(FileEntryCollector.collect(Map.of())).isEmpty();
	}
}
