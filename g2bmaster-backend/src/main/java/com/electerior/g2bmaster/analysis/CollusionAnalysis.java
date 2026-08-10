package com.electerior.g2bmaster.analysis;

import com.electerior.g2bmaster.common.Numbers;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 담합 정황 매트릭스 ({@code lib/collusion-analysis.js} 이식).
 *
 * <p>공고별 개찰결과(참여업체·순위·투찰률)를 모아 두 가지를 본다.
 * <ol>
 *   <li><b>짝(pair)</b> — 같은 두 업체가 1·2위를 반복하는가, 그리고 승수가 반반으로
 *       갈리는가. 번갈아 이기는 패턴은 들러리 입찰의 전형이다.</li>
 *   <li><b>업체(company)</b> — 투찰률이 단조 증가하는가. 낙찰가를 계단식으로 올리는
 *       움직임을 잡는다.</li>
 * </ol>
 *
 * <p><b>여기서 나오는 점수는 고발 근거가 아니라 "들여다볼 순서"다.</b> 표본이 2~3건이면
 * 우연으로 만들어지는 패턴이므로, {@code suspicionScore} 에 건수를 곱해 둔 것도 그 이유다.
 */
public final class CollusionAnalysis {

	private CollusionAnalysis() {
	}

	/** 짝 하나에 딸린 개별 사례. */
	public record PairCase(
			Object bidNtceNo,
			Object bidNtceNm,
			Object winner,
			Object runnerUp,
			Object winBidprcRt,
			Object runBidprcRt,
			Object opengDate) {}

	/** 업체 하나에 딸린 개별 사례. {@code role} 은 {@code 낙찰} 또는 {@code 2위}. */
	public record CompanyCase(
			String role,
			Object bidNtceNo,
			Object bidNtceNm,
			Object bidAmt,
			Object bidprcRt,
			Object opengDate) {}

	/**
	 * 1·2위를 함께한 두 업체.
	 *
	 * @param alterScore     번갈아 이긴 정도(0~100). 승수가 반반이면 100
	 * @param suspicionScore {@code 건수 × 번갈아 정도} — 건수가 적으면 자동으로 낮아진다
	 */
	public record Pair(
			String a,
			String b,
			int total,
			int aWins,
			int bWins,
			List<PairCase> cases,
			int alterScore,
			BigDecimal suspicionScore) {}

	/** 업체 요약. {@code avgRate} 는 표본이 없으면 null(0 아님). */
	public record Company(
			String name,
			int wins,
			int runnerUp,
			int appearances,
			BigDecimal avgRate,
			List<BigDecimal> rateHistory,
			boolean isMonotonicallyIncreasing,
			List<CompanyCase> cases) {}

	/** 최종 결과. */
	public record CollusionMatrix(List<Pair> pairs, List<Company> companies) {}

	/**
	 * 개찰결과가 붙은 공고 목록에서 매트릭스를 만든다.
	 *
	 * <p>각 항목은 {@code participants} 키에 참여업체 목록을 갖고 있어야 한다.
	 * 참여업체가 없거나 1위 업체명이 비면 그 공고는 통째로 건너뛴다 — 이름 없는 참여자를
	 * 짝으로 묶으면 서로 다른 업체가 한 덩어리가 된다.
	 */
	public static CollusionMatrix buildCollusionMatrix(List<Map<String, Object>> bids) {
		Map<String, PairRecord> pairs = new LinkedHashMap<>();
		Map<String, CompanyRecord> companies = new LinkedHashMap<>();

		for (Map<String, Object> bid : bids == null ? List.<Map<String, Object>>of() : bids) {
			List<Map<String, Object>> participants = sortedParticipants(bid);
			if (participants.isEmpty()) {
				continue;
			}
			Map<String, Object> winner = participants.get(0);
			if (str(winner.get("bdrNm")).isEmpty()) {
				continue;
			}
			recordCompany(companies, str(winner.get("bdrNm")), "낙찰", bid, winner);

			Map<String, Object> runnerUp = participants.size() > 1 ? participants.get(1) : null;
			if (runnerUp == null || str(runnerUp.get("bdrNm")).isEmpty()) {
				continue;
			}
			recordCompany(companies, str(runnerUp.get("bdrNm")), "2위", bid, runnerUp);
			recordPair(pairs, bid, winner, runnerUp);
		}

		List<Pair> pairList = new ArrayList<>(pairs.values().stream().map(CollusionAnalysis::summarizePair).toList());
		pairList.sort(Comparator.comparing(Pair::suspicionScore).reversed()
				.thenComparing(Comparator.comparingInt(Pair::total).reversed()));

		List<Company> companyList = new ArrayList<>(companies.entrySet().stream()
				.map(e -> summarizeCompany(e.getKey(), e.getValue())).toList());
		companyList.sort(Comparator.comparingInt(Company::wins).reversed());

		return new CollusionMatrix(List.copyOf(pairList), List.copyOf(companyList));
	}

	// ── 내부 ────────────────────────────────────────────────────────────────

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> sortedParticipants(Map<String, Object> bid) {
		Object raw = bid == null ? null : bid.get("participants");
		if (!(raw instanceof List<?> list)) {
			return List.of();
		}
		List<Map<String, Object>> participants = new ArrayList<>();
		for (Object element : list) {
			if (element instanceof Map<?, ?> map) {
				participants.add((Map<String, Object>) map);
			}
		}
		// 순위가 비었거나 숫자가 아니면 99 — 정렬 끝으로 밀어 1·2위 판정에서 빠지게 한다.
		participants.sort(Comparator.comparingInt(p -> rank(p.get("rank"))));
		return participants;
	}

	private static int rank(Object value) {
		BigDecimal parsed = Numbers.toNumber(value);
		return parsed == null ? 99 : parsed.intValue();
	}

	private static void recordCompany(Map<String, CompanyRecord> records, String name, String role,
			Map<String, Object> bid, Map<String, Object> participant) {
		CompanyRecord record = records.computeIfAbsent(name, k -> new CompanyRecord());
		if ("낙찰".equals(role)) {
			record.wins++;
		}
		else {
			record.runnerUp++;
		}
		record.appearances++;
		BigDecimal rate = Numbers.toNumber(participant.get("bidprcRt"));
		if (rate != null && !str(participant.get("bidprcRt")).isEmpty()) {
			record.bidRates.add(rate);
		}
		record.cases.add(new CompanyCase(role,
				bid.get("bidNtceNo"), bid.get("bidNtceNm"),
				participant.get("bidAmt"), participant.get("bidprcRt"), bid.get("opengDate")));
	}

	private static void recordPair(Map<String, PairRecord> records, Map<String, Object> bid,
			Map<String, Object> winner, Map<String, Object> runnerUp) {
		String winnerName = str(winner.get("bdrNm"));
		String runnerName = str(runnerUp.get("bdrNm"));
		// 이름을 정렬해 키를 만든다 — (A,B) 와 (B,A) 가 다른 짝으로 세어지면 안 된다.
		String a = winnerName.compareTo(runnerName) <= 0 ? winnerName : runnerName;
		String b = winnerName.compareTo(runnerName) <= 0 ? runnerName : winnerName;

		PairRecord record = records.computeIfAbsent(a + "|" + b, k -> new PairRecord(a, b));
		record.total++;
		if (winnerName.equals(record.a)) {
			record.aWins++;
		}
		else {
			record.bWins++;
		}
		record.cases.add(new PairCase(bid.get("bidNtceNo"), bid.get("bidNtceNm"),
				winnerName, runnerName,
				winner.get("bidprcRt"), runnerUp.get("bidprcRt"), bid.get("opengDate")));
	}

	/**
	 * 짝 요약.
	 *
	 * <p>{@code total < 2} 면 번갈아 점수를 0으로 둔다 — 한 번 만난 두 업체는 정의상
	 * 번갈아 이길 수 없는데, 공식을 그대로 태우면 1.0(완전 교대)이 나온다.
	 */
	private static Pair summarizePair(PairRecord pair) {
		double alter = pair.total >= 2
				? 1 - Math.abs(pair.aWins - pair.bWins) / (double) pair.total
				: 0;
		BigDecimal suspicion = BigDecimal.valueOf(pair.total * alter * 10)
				.setScale(0, RoundingMode.HALF_UP)
				.divide(BigDecimal.TEN, 1, RoundingMode.HALF_UP);
		return new Pair(pair.a, pair.b, pair.total, pair.aWins, pair.bWins,
				List.copyOf(pair.cases), (int) Math.round(alter * 100), suspicion);
	}

	private static Company summarizeCompany(String name, CompanyRecord record) {
		List<BigDecimal> rates = record.bidRates;
		BigDecimal avgRate = null;
		if (!rates.isEmpty()) {
			BigDecimal sum = rates.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
			avgRate = sum.divide(BigDecimal.valueOf(rates.size()), 10, RoundingMode.HALF_UP)
					.setScale(1, RoundingMode.HALF_UP);
		}
		boolean monotonic = rates.size() >= 3;
		for (int i = 1; monotonic && i < rates.size(); i++) {
			monotonic = rates.get(i).compareTo(rates.get(i - 1)) >= 0;
		}
		return new Company(name, record.wins, record.runnerUp, record.appearances,
				avgRate, List.copyOf(rates), monotonic, List.copyOf(record.cases));
	}

	private static String str(Object value) {
		return value == null ? "" : String.valueOf(value);
	}

	private static final class PairRecord {

		private final String a;
		private final String b;
		private int total;
		private int aWins;
		private int bWins;
		private final List<PairCase> cases = new ArrayList<>();

		private PairRecord(String a, String b) {
			this.a = a;
			this.b = b;
		}
	}

	private static final class CompanyRecord {

		private int wins;
		private int runnerUp;
		private int appearances;
		private final List<BigDecimal> bidRates = new ArrayList<>();
		private final List<CompanyCase> cases = new ArrayList<>();
	}
}
