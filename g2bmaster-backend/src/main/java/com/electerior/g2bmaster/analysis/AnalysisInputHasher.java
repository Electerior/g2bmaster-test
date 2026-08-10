package com.electerior.g2bmaster.analysis;

import com.ibm.icu.text.Collator;
import com.ibm.icu.util.ULocale;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * 분석 입력 해시 — {@code lib/analysis-history.js} 의 {@code analysisInputHash} 이식.
 *
 * <p><b>이 클래스는 Node 원본과 바이트 단위로 같은 값을 내야 한다.</b> 이 해시는
 * {@code analysis_history.input_hash} 와 {@code analysis_job.input_hash} 의 값이고,
 * 재사용 판정의 전부다. 어긋나면 이관 순간 기존 분석 캐시가 통째로 고아가 되어
 * 모든 공고를 다시 추론한다(1건당 1~4분). {@code AnalysisInputHasherTest} 가
 * Node 로 뽑은 고정값(fixture)을 들고 있으니 이 파일을 고치면 반드시 그 테스트가 지킨다.
 *
 * <p><b>첨부파일 정렬만 콜레이터를 쓴다.</b> 원본이
 * {@code `${url}|${name}`.localeCompare(...)} 로 정렬하는데 이것이 ICU 대조다.
 * {@code java.text.Collator} 로는 재현되지 않는다 — 실측(하이픈·밑줄·공백·대소문자·한자가
 * 섞인 첨부파일명 24종)에서 14자리가 어긋났고, ICU4J 루트 콜레이터는 전부 일치했다.
 *
 * <p>⚠ 원본은 <b>런타임 기본 로케일</b>의 대조를 쓴다. 즉 {@code LANG=ko_KR} 로 뜬 호스트에서는
 * 다른 순서가 나오고 해시도 달라진다 — 원본에 있는 잠복 버그다. 여기서는
 * {@link ULocale#ROOT} 로 못 박아 호스트 설정에 흔들리지 않게 했다. 이관 대상 캐시는
 * 루트/영문 로케일 호스트에서 생성된 것을 전제로 한다.
 */
public final class AnalysisInputHasher {

	/** 원본 {@code /^(1|true|yes|y|on|deep|full)$/i}. */
	private static final Pattern DEEP = Pattern.compile("^(1|true|yes|y|on|deep|full)$", Pattern.CASE_INSENSITIVE);

	/**
	 * 첨부 정렬용 콜레이터.
	 *
	 * <p>{@code freeze()} 한 ICU 콜레이터는 불변이며 <b>스레드 안전</b>하다. 얼리지 않으면
	 * 내부 상태를 고쳐 쓰기 때문에 워커 여러 개가 동시에 해시를 뜨는 순간 조용히 깨진다.
	 */
	private static final Collator FILE_COLLATOR = Collator.getInstance(ULocale.ROOT).freeze();

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private AnalysisInputHasher() {
	}

	/** 자연키를 먼저 풀고 해시한다. */
	public static String hash(JsonNode input) {
		return hash(input, AnalysisIdentity.resolve(input));
	}

	/**
	 * 이미 알고 있는 자연키로 해시한다.
	 *
	 * @param identity {@code null} 이면 payload 의 {@code identity} 가 JSON null 이 된다(원본과 같다)
	 */
	public static String hash(JsonNode input, AnalysisIdentity identity) {
		return sha256Hex(JsCanonicalJson.stringify(payload(input, identity)));
	}

	/** 정규화 전 payload. 테스트에서 직렬화 결과를 눈으로 보기 위해 열어 둔다. */
	static ObjectNode payload(JsonNode input, AnalysisIdentity identity) {
		JsonNode in = input == null ? NODES.objectNode() : input;
		ObjectNode payload = NODES.objectNode();

		if (identity == null) {
			payload.putNull("identity");
		}
		else {
			ObjectNode id = payload.putObject("identity");
			id.put("type", identity.type());
			id.put("id", identity.id());
			id.put("ord", identity.ord());
		}

		// 원본의 `input.X || ''` — 거짓값이면 빈 문자열, 아니면 타입을 그대로 보존한다.
		// (amount 는 숫자로 오면 숫자로, 문자열로 오면 문자열로 해시에 들어간다.)
		putOrEmpty(payload, "title", in.path("title"));
		putOrEmpty(payload, "insttNm", in.path("insttNm"));
		putOrEmpty(payload, "itemName", in.path("itemName"));
		putOrEmpty(payload, "amount", in.path("amount"));
		putOrEmpty(payload, "cntrctMthdNm", in.path("cntrctMthdNm"));
		putOrEmpty(payload, "type", in.path("type"));
		putOrEmpty(payload, "companyProfile", in.path("companyProfile"));
		payload.set("files", files(in.path("fileEntries")));
		payload.set("rawFields", in.path("rawFields").isObject() ? in.path("rawFields") : NODES.objectNode());
		putOrEmpty(payload, "analysisMode", in.path("analysisMode"));
		payload.put("deep", isDeep(in.path("deep")));
		putOrEmpty(payload, "context", in.path("context"));
		putOrEmpty(payload, "specText", in.path("specText"));
		putOrEmpty(payload, "preferFile", in.path("preferFile"));
		return payload;
	}

	/** 원본 {@code deep} 판정 — {@code null}/부재는 빈 문자열로 보고, 공백을 걷어낸 뒤 대조한다. */
	public static boolean isDeep(JsonNode node) {
		return DEEP.matcher(JsValues.text(node).trim()).matches();
	}

	// ── 내부 ────────────────────────────────────────────────────────────────

	private static void putOrEmpty(ObjectNode payload, String name, JsonNode value) {
		payload.set(name, JsValues.truthy(value) ? value : NODES.stringNode(""));
	}

	/**
	 * {@code fileEntries} → {@code [{name, url}]}. 이름/URL 만 남기고 정렬한다.
	 *
	 * <p>정렬 키가 {@code url + "|" + name} 인 것은 원본 그대로다. 같은 URL 이 다른 이름으로
	 * 두 번 들어오는 일이 실제로 있어(첨부 목록 중복) URL 이 앞선다.
	 */
	private static ArrayNode files(JsonNode fileEntries) {
		List<String[]> entries = new ArrayList<>();
		if (fileEntries.isArray()) {
			for (JsonNode file : fileEntries) {
				entries.add(new String[] {
						JsValues.firstText(file.path("name")),
						JsValues.firstText(file.path("url")),
				});
			}
		}
		// List.sort 는 안정 정렬이다 — JS Array.prototype.sort 도 ES2019 부터 안정이라 같다.
		entries.sort(Comparator.comparing(e -> e[1] + "|" + e[0], FILE_COLLATOR::compare));

		ArrayNode out = NODES.arrayNode(entries.size());
		for (String[] entry : entries) {
			ObjectNode file = out.addObject();
			file.put("name", entry[0]);
			file.put("url", entry[1]);
		}
		return out;
	}

	private static String sha256Hex(String value) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest(value.getBytes(StandardCharsets.UTF_8));
			StringBuilder hex = new StringBuilder(digest.length * 2);
			for (byte b : digest) {
				hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
			}
			return hex.toString();
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 을 쓸 수 없습니다.", e);   // JRE 필수 알고리즘이다
		}
	}
}
