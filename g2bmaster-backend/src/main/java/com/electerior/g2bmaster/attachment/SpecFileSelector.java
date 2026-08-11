package com.electerior.g2bmaster.attachment;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 첨부파일 중 "진짜 규격서"를 고르는 휴리스틱 — {@code lib/files.js} 의 순수 절반 이식.
 *
 * <p>공고 하나에 첨부가 수십 개 붙고 그중 규격서는 보통 하나다. 잘못 고르면 이후 분석이
 * 통째로 엉뚱한 문서를 읽는다. 파일명 순위로 후보를 좁히고, 변환된 마크다운 내용으로
 * 최종 점수를 매긴다 — <b>내용 점수가 최종 판정</b>이고 파일명은 힌트일 뿐이다.
 *
 * <p>텍스트 추출 자체(HWP/PDF/ZIP 파싱)는 아직 이식하지 않았다. 이 클래스는 추출이
 * 끝난 뒤의 선택 로직만 담당하므로 파서와 독립적으로 쓸 수 있고 테스트도 쉽다.
 */
public final class SpecFileSelector {

	/**
	 * 이름에 '규격'이 들어가도 규격서가 아닌 것들. <b>순서가 중요해서 가장 먼저 걸러야 한다.</b>
	 *
	 * <p>실제 사고 사례(2026-08-03, R26BK01661233): "규격입찰 설명서"(입찰 절차 안내문)가
	 * '규격'에 걸려 최우선으로 뽑혔고, 규격·사양·납품 키워드가 많아 내용 점수까지 높아
	 * 진짜 규격서를 제치고 선택됐다.
	 */
	private static final Pattern NOT_SPEC = Pattern.compile(
			"설명서|유의서|유의사항|안내문|위임장|청렴|서약|제출서식|견적서식|인감|사업자등록|평가표|배점표|참가자격");

	private static final Pattern STRONG_SPEC = Pattern.compile(
			"규격서|사양서|시방서|과업지시서|과업내용서|물품규격|상세규격|제안요청서|spec",
			Pattern.CASE_INSENSITIVE);

	private static final Pattern WEAK_SPEC = Pattern.compile("규격|사양|요구사항");
	private static final Pattern NOTICE_LIKE = Pattern.compile("공고|제안|안내");

	private static final Pattern SPEC_KEYWORD = Pattern.compile(
			"규격|사양|수량|단위|납품|모델|제조사|과업|요구사항|성능|시방|품명|재질|치수|용량|인증");
	private static final Pattern TABLE_ROW = Pattern.compile("^.*\\|.*\\|.*$", Pattern.MULTILINE);
	private static final Pattern UNIT = Pattern.compile(
			"\\d+\\s*(?:GB|TB|EA|개|대|식|mm|cm|kg|인치|W|㎡|장|매|세트|core|코어)",
			Pattern.CASE_INSENSITIVE);

	// ── HYBRID 판정 (밀도 + 부품명 유사도) ──────────────────────────────────────
	// 기존 specContentScore/chooseSpec 과 그 튜닝 상수는 건드리지 않는다. 아래는 그 위에 얹는
	// 별도 신호다. 규격서 자동판정은 백엔드 소유(offline, ai.enabled=false 에서도 동작)라
	// 부품명 어휘를 HTTP 로 가져오지 않고 여기 인라인한다 — g2bmaster-AI 의 세 어휘
	// (estimate.py PART_CATEGORIES · spec_parser.py _KINDS · prebuilt.py _COMPONENT_SIGNALS)의
	// 합집합을 카테고리별 정규식 하나로 포팅했다. \b 는 ASCII 약어에만 — Java \b 는 한글에서 오작동한다.

	/** PC 부품 어휘. "이 텍스트가 부품을 나열하는가"를 카테고리 단위로 센다. */
	private static final List<Pattern> PART_CATEGORY = List.of(
			Pattern.compile("\\bCPU\\b|프로세서|processor|라이젠|ryzen|코어\\s*i|core\\s*i|\\bxeon\\b|제온|\\bepyc\\b|스레드리퍼|threadripper", Pattern.CASE_INSENSITIVE),
			Pattern.compile("\\bGPU\\b|그래픽\\s*카드|\\bVGA\\b|\\bVRAM\\b|RTX|GTX|\\bRX\\b|지포스|geforce|라데온|radeon|quadro|tesla|엔비디아|nvidia", Pattern.CASE_INSENSITIVE),
			Pattern.compile("메모리|\\bRAM\\b|\\bDIMM\\b|\\bDDR\\d", Pattern.CASE_INSENSITIVE),
			Pattern.compile("\\bSSD\\b|\\bNVMe\\b|\\bHDD\\b|저장\\s*장치|하드\\s*디스크|\\bM\\.?2\\b", Pattern.CASE_INSENSITIVE),
			Pattern.compile("메인\\s*보드|마더\\s*보드|main\\s*board|mother\\s*board|\\bM/?B\\b", Pattern.CASE_INSENSITIVE),
			Pattern.compile("파워|전원\\s*공급|\\bPSU\\b|power\\s*supply", Pattern.CASE_INSENSITIVE),
			Pattern.compile("케이스|\\bcase\\b|미들\\s*타워|미니\\s*타워|빅\\s*타워", Pattern.CASE_INSENSITIVE),
			Pattern.compile("쿨러|쿨링|냉각|히트\\s*싱크|heatsink|수랭|공랭", Pattern.CASE_INSENSITIVE),
			Pattern.compile("네트워크|랜\\s*카드|\\bNIC\\b|\\bLAN\\b|이더넷|ethernet|\\bSFP\\b", Pattern.CASE_INSENSITIVE));

	/**
	 * 탭으로 나뉜 표 행(셀 3개 이상). HWPX 는 표 셀을 <b>탭</b>으로 뽑으므로
	 * ({@link DocumentTextExtractor} 참조) 파이프 전용 {@link #TABLE_ROW} 이 지배적 형식에서
	 * 0 점을 준다 — 밀도 사전선별에서만 이 탭 패턴을 함께 본다(튜닝된 내용 점수는 건드리지 않음).
	 */
	private static final Pattern TAB_ROW = Pattern.compile("^[^\\t\\n]*\\t[^\\t\\n]*\\t.*$", Pattern.MULTILINE);
	private static final Pattern NON_BLANK_LINE = Pattern.compile("^.*\\S.*$", Pattern.MULTILINE);

	/** 이보다 짧으면 규격서가 아니다(표지·서식 한 장). */
	public static final int DENSITY_MIN_CHARS = 400;
	/** 부품명 유사도(0..1)를 내용 점수에 더할 때의 가중치. */
	public static final int SIMILARITY_WEIGHT = 30;
	/** 유사도 측정 시 텍스트를 나누는 구획 수. */
	public static final int SIMILARITY_SECTIONS = 4;

	/** 규격서 후보 하나. {@code score} 는 {@link #specContentScore} 결과를 담는다. */
	public record Candidate(String name, String markdown, int score) {

		public static Candidate of(String name, String markdown) {
			return new Candidate(name, markdown, specContentScore(markdown, name));
		}
	}

	/** 선택 결과. {@code confidence} 는 {@code confirmed | heuristic | estimated | none}. */
	public record Choice(Candidate chosen, String confidence) {}

	private SpecFileSelector() {
	}

	/**
	 * 파일명에 "규격서"가 들어가는가.
	 *
	 * <p>필드나 메타데이터가 아니라 <b>파일명만</b> 보고, 반드시 "규격서"를 포함해야 한다 —
	 * 원본에 명시된 규칙이다. 공고문·제안요청서와 구분하기 위한 것.
	 */
	public static boolean isSpecFile(String name) {
		return name != null && name.contains("규격서");
	}

	/**
	 * 파일명 기반 유력도 순위. <b>낮을수록 규격서일 가능성이 높다</b>(다운로드 순서용).
	 *
	 * <p>'내역서'는 일부러 뺐다. 실제 DB 표본에서 "물량내역서", "공내역서[…소방공사]" 처럼
	 * 공사 비용·수량 산출 스프레드시트가 대부분이라 기술 규격과 무관했고,
	 * 공사 공고에서 진짜 규격서보다 먼저 뽑힐 위험이 있었다.
	 */
	public static int specFilenameRank(String name) {
		String n = name == null ? "" : name;
		if (NOT_SPEC.matcher(n).find()) {
			return 3;
		}
		if (STRONG_SPEC.matcher(n).find()) {
			return 0;
		}
		if (WEAK_SPEC.matcher(n).find()) {
			return 1;   // 약한 힌트 — 후보이되 가점은 없다
		}
		if (NOTICE_LIKE.matcher(n).find()) {
			return 2;
		}
		return 1;
	}

	/**
	 * 변환된 마크다운이 규격서다운 정도(상대 점수).
	 *
	 * <p>가중치와 상한은 실제 공고로 튜닝된 값이다. 정리하지 말 것.
	 */
	public static int specContentScore(String markdown, String name) {
		String text = markdown == null ? "" : markdown;
		if (text.isBlank()) {
			return 0;
		}
		int score = 0;

		score += Math.min(count(SPEC_KEYWORD, text), 40) * 2;
		score += Math.min(count(TABLE_ROW, text), 60) * 3;      // 마크다운 표 행
		score += Math.min(count(UNIT, text), 30) * 2;

		if (text.length() < 400) {
			score -= 20;   // 너무 짧으면 규격서가 아니다 (표지·안내문 한 장짜리)
		}
		else {
			score += Math.min(text.length() / 500, 20);
		}

		int rank = specFilenameRank(name);
		score += switch (rank) {
			case 0 -> 15;
			case 2 -> -8;
			default -> rank >= 3 ? -25 : 0;
		};
		return score;
	}

	/**
	 * 후보 중 최종 선택.
	 *
	 * @param candidates 점수가 매겨진 후보들
	 * @param llmSaysSpec 최고점 후보에 대한 LLM 판정. AI 를 안 쓰거나 실패했으면 {@code null}
	 * @param minScore    이 점수 미만이면 확신할 수 없다고 본다
	 */
	public static Choice chooseSpec(List<Candidate> candidates, Boolean llmSaysSpec, int minScore) {
		if (candidates == null || candidates.isEmpty()) {
			return new Choice(null, "none");
		}
		List<Candidate> sorted = new ArrayList<>(candidates);
		sorted.sort(Comparator.comparingInt(Candidate::score).reversed());

		Candidate top = sorted.getFirst();
		if (top.score() < minScore) {
			return new Choice(top, "estimated");
		}
		if (llmSaysSpec == null) {
			return new Choice(top, "heuristic");   // AI 없이도 결론은 낸다
		}
		if (llmSaysSpec) {
			return new Choice(top, "confirmed");
		}

		// LLM 이 최고점 후보를 규격서가 아니라고 했다 — 차점자로 내려간다.
		return sorted.stream()
				.skip(1)
				.filter(c -> c.score() >= minScore)
				.findFirst()
				.map(next -> new Choice(next, "heuristic"))
				.orElseGet(() -> new Choice(top, "estimated"));
	}

	// ── HYBRID: 밀도 · 부품명 유사도 · 결합 선택 ─────────────────────────────────

	/** 규격서다움을 값싸게 사전선별하기 위한 밀도 지표. */
	public record Density(int textLength, double tableRowDensity, double unitTokenDensity) {}

	/**
	 * 표 밀도(파이프 <b>또는</b> 탭 행 / 비어있지 않은 줄)와 단위 토큰 밀도(500자당 단위 수).
	 * 표는 HWPX(탭)·PDF/마크다운(파이프) 양쪽을 함께 세어 형식 편향을 없앤다.
	 */
	public static Density density(String markdown) {
		String text = markdown == null ? "" : markdown;
		if (text.isBlank()) {
			return new Density(0, 0, 0);
		}
		int nonBlank = Math.max(1, count(NON_BLANK_LINE, text));
		int tableRows = count(TABLE_ROW, text) + count(TAB_ROW, text);
		double tableRowDensity = (double) tableRows / nonBlank;
		double unitTokenDensity = count(UNIT, text) / Math.max(1.0, text.length() / 500.0);
		return new Density(text.length(), tableRowDensity, unitTokenDensity);
	}

	/**
	 * 명백한 비-규격서(표지·서약서·안내문 한 장)만 걸러 내는 <b>느슨한</b> 바닥선. 애매하면 통과시키고
	 * 최종 판단은 점수·fallback 에 맡긴다 — 여기서 진짜 규격서를 떨구면 다음 후보로도 못 돌아온다.
	 */
	public static boolean passesDensityFloor(Density d) {
		if (d == null || d.textLength() < DENSITY_MIN_CHARS) {
			return false;
		}
		// 표도 없고 단위도 드물고 본문도 짧으면 규격서가 아니다.
		return !(d.tableRowDensity() == 0 && d.unitTokenDensity() < 0.5 && d.textLength() < 1500);
	}

	/** {@link #partNameSimilarity(String, int)} 를 기본 {@value #SIMILARITY_SECTIONS} 구획으로. */
	public static double partNameSimilarity(String markdown) {
		return partNameSimilarity(markdown, SIMILARITY_SECTIONS);
	}

	/**
	 * 텍스트를 {@code sections} 구획으로 나눠 각 구획이 건드리는 PC 부품 카테고리 비율의 평균(0..1).
	 *
	 * <p>부품이 문서 전반에 퍼져 있으면(진짜 규격서) 높고, 한 번만 스치면("GPU 서버 구매" 공고문)
	 * 낮다. 그래서 이름만 흘린 공고와 실제 부품표를 가른다.
	 */
	public static double partNameSimilarity(String markdown, int sections) {
		String text = markdown == null ? "" : markdown;
		if (text.isBlank() || sections <= 0) {
			return 0;
		}
		int len = text.length();
		int chunk = Math.max(1, (int) Math.ceil((double) len / sections));
		double sum = 0;
		for (int i = 0; i < sections; i++) {
			int start = Math.min(i * chunk, len);
			int end = Math.min(start + chunk, len);
			if (start >= end) {
				continue;
			}
			String slice = text.substring(start, end);
			int hit = 0;
			for (Pattern p : PART_CATEGORY) {
				if (p.matcher(slice).find()) {
					hit++;
				}
			}
			sum += (double) hit / PART_CATEGORY.size();
		}
		return sum / sections;
	}

	/**
	 * 후보를 선택 순서대로 정렬한다 — 밀도 바닥선 통과분이 앞, 미통과분이 뒤,
	 * 각 구간 안에서는 {@link #hybridScore} 내림차순.
	 *
	 * <p>{@link #chooseSpecHybrid} 가 쓰는 바로 그 순서다. 검증 층
	 * ({@link SpecDocumentValidator})이 최고점 하나만 보지 않고 <b>걸어 내려가며</b>
	 * 쓸 만한 문서를 찾을 수 있도록 노출한다 — 최고점이 공고문이어도 세 번째 후보가
	 * 진짜 규격서일 수 있다.
	 */
	public static List<Candidate> rankHybrid(List<Candidate> candidates) {
		if (candidates == null || candidates.isEmpty()) {
			return List.of();
		}
		Map<Candidate, Integer> hybrid = new IdentityHashMap<>();
		List<Candidate> passing = new ArrayList<>();
		List<Candidate> failing = new ArrayList<>();
		for (Candidate c : candidates) {
			hybrid.put(c, hybridScore(c));
			(passesDensityFloor(density(c.markdown())) ? passing : failing).add(c);
		}
		Comparator<Candidate> byHybrid = Comparator.comparingInt((Candidate c) -> hybrid.get(c)).reversed();
		passing.sort(byHybrid);
		failing.sort(byHybrid);
		// 밀도 통과분을 먼저, 미통과분을 뒤로 — 미통과분도 fallback 으로 남긴다.
		List<Candidate> ranked = new ArrayList<>(passing);
		ranked.addAll(failing);
		return ranked;
	}

	/** 내용 점수 + 부품명 유사도 보너스. 규격서 확신을 하나의 정수로 합친다. */
	public static int hybridScore(Candidate candidate) {
		if (candidate == null) {
			return Integer.MIN_VALUE;
		}
		return candidate.score()
				+ (int) Math.round(partNameSimilarity(candidate.markdown()) * SIMILARITY_WEIGHT);
	}

	/**
	 * {@link #chooseSpec} 의 하이브리드판. 밀도 바닥선을 통과한 후보를 앞세우고(떨구지 않음),
	 * {@link #hybridScore} 로 정렬해 고른다. LLM 이 최고점을 부정하면 전체 목록을 걸어 내려가
	 * 다음 유효 후보를 찾는다(강화된 fallback).
	 */
	public static Choice chooseSpecHybrid(List<Candidate> candidates, Boolean llmSaysSpec, int minScore) {
		if (candidates == null || candidates.isEmpty()) {
			return new Choice(null, "none");
		}
		List<Candidate> ranked = rankHybrid(candidates);
		Map<Candidate, Integer> hybrid = new IdentityHashMap<>();
		for (Candidate c : ranked) {
			hybrid.put(c, hybridScore(c));
		}

		Candidate top = ranked.getFirst();
		if (hybrid.get(top) < minScore) {
			return new Choice(top, "estimated");
		}
		if (llmSaysSpec == null) {
			return new Choice(top, "heuristic");
		}
		if (llmSaysSpec) {
			return new Choice(top, "confirmed");
		}
		// LLM 이 최고점을 규격서가 아니라 함 — 다음 유효 후보로 내려간다.
		return ranked.stream()
				.skip(1)
				.filter(c -> hybrid.get(c) >= minScore)
				.findFirst()
				.map(next -> new Choice(next, "heuristic"))
				.orElseGet(() -> new Choice(top, "estimated"));
	}

	private static int count(Pattern pattern, String text) {
		Matcher matcher = pattern.matcher(text);
		int n = 0;
		while (matcher.find()) {
			n++;
		}
		return n;
	}
}
