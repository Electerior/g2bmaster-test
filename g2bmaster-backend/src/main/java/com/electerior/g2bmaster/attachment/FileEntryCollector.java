package com.electerior.g2bmaster.attachment;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 나라장터 항목에서 첨부파일 {url, name} 쌍을 뽑아낸다 — {@code lib/attachment-entries.js} 이식.
 *
 * <p>나라장터 API 는 첨부를 배열로 주지 않고 <b>번호가 붙은 평평한 필드</b>로 준다:
 * {@code ntceSpecDocUrl1..10} 과 {@code ntceSpecFileNm1..10} 이 짝을 이루는 식이고,
 * 오퍼레이션마다 접두어가 다르다({@code specDocFileUrl*}, {@code atchFileUrl*}, …).
 * 그 규칙을 한 곳에 모아 둔 것이 이 클래스다.
 *
 * <p>URL 처럼 생겼다고 다 첨부가 아니다 — 공고 상세 <b>페이지</b> 링크
 * ({@code bidNtceDtlUrl} 등)가 섞여 들어오면 다운로드 시도가 HTML 을 받아온다.
 * 그래서 "파일다움"과 "페이지다움"을 따로 판정한다.
 */
public final class FileEntryCollector {

	private static final Pattern HTTP_URL = Pattern.compile("^https?://.*", Pattern.CASE_INSENSITIVE);
	private static final Pattern DOWNLOAD_HINT =
			Pattern.compile("downloadfile|filedown|untyatchfile", Pattern.CASE_INSENSITIVE);
	private static final Pattern TRAILING_INDEX = Pattern.compile("(\\d+)$");
	private static final Pattern WHITESPACE = Pattern.compile("\\s");

	/** 첨부 한 건. */
	public record FileEntry(String url, String name) {}

	private FileEntryCollector() {
	}

	public static List<FileEntry> collect(Map<String, Object> item) {
		if (item == null || item.isEmpty()) {
			return List.of();
		}
		List<FileEntry> entries = new ArrayList<>();
		Set<String> seen = new LinkedHashSet<>();

		for (Map.Entry<String, Object> field : item.entrySet()) {
			String key = field.getKey();
			String url = field.getValue() == null ? "" : String.valueOf(field.getValue()).trim();
			if (!HTTP_URL.matcher(url).matches()) {
				continue;
			}

			String lowerKey = key.toLowerCase(Locale.ROOT);
			String compactUrl = WHITESPACE.matcher(url).replaceAll("");

			boolean fileLike = lowerKey.contains("fileurl")
					|| lowerKey.contains("docurl")
					|| lowerKey.contains("atch")
					|| DOWNLOAD_HINT.matcher(compactUrl).find();

			boolean pageOnly = lowerKey.contains("infourl")
					|| lowerKey.contains("dtlurl")
					|| lowerKey.contains("ntceurl")
					|| lowerKey.contains("lnkurl");

			// 페이지 링크라도 URL 자체에 download 가 들어 있으면 첨부로 본다 — 실제로 그런 필드가 있다.
			if (!fileLike || (pageOnly && !url.toLowerCase(Locale.ROOT).contains("download"))) {
				continue;
			}
			if (!seen.add(url)) {
				continue;
			}
			entries.add(new FileEntry(url, resolveName(item, key)));
		}
		return entries;
	}

	/**
	 * URL 필드에 대응하는 파일명 필드를 찾는다.
	 *
	 * <p>후보를 여러 개 두는 이유는 오퍼레이션마다 짝 이름 규칙이 다르기 때문이다.
	 * 하나도 못 찾으면 필드명 자체를 이름으로 쓴다 — 이름이 없다고 첨부를 버리면
	 * 정작 규격서를 놓친다.
	 */
	private static String resolveName(Map<String, Object> item, String key) {
		var matcher = TRAILING_INDEX.matcher(key);
		String index = matcher.find() ? matcher.group(1) : null;

		List<String> candidates = new ArrayList<>();
		candidates.add(key.replaceAll("(?i)Url$", "Nm"));
		candidates.add(key.replaceAll("(?i)DocUrl", "FileNm"));
		candidates.add(key.replaceAll("(?i)FileUrl", "FileNm"));
		if (index != null) {
			candidates.add("ntceSpecFileNm" + index);
			candidates.add("specDocFileNm" + index);
			candidates.add("fileNm" + index);
			candidates.add("atchFileNm" + index);
		}

		for (String candidate : candidates) {
			if (candidate.isEmpty() || candidate.equals(key)) {
				continue;
			}
			Object value = item.get(candidate);
			if (value != null && !String.valueOf(value).isBlank()) {
				return String.valueOf(value);
			}
		}
		return key;
	}
}
