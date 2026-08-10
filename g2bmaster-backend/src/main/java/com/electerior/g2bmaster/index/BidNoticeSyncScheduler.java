package com.electerior.g2bmaster.index;

import com.electerior.g2bmaster.config.G2bProperties;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 색인 적재 주기 실행기.
 *
 * <p>{@code SyncSchedulerService}(사용자가 화면에서 잡는 예약)와 다른 물건이다. 이쪽은
 * 사람이 설정하지 않는 <b>상시 배경 작업</b>이고, 목적이 하나다 — 검색이 상류를 안 치도록
 * 색인을 최신으로 유지하는 것.
 *
 * <p><b>두 주기가 따로 도는 이유.</b> 적재({@code intervalMs})는 HTTP 호출 수십 번짜리 무거운
 * 일이고, 마감 전이({@code sweepMs})는 인덱스를 타는 UPDATE 한 번이다. 같은 주기에 묶으면
 * 마감 전이가 적재만큼 드물어져서, 방금 마감된 공고가 최대 10분간 '입찰'로 검색된다.
 * 싼 것을 자주 돌리는 편이 화면의 정확도를 훨씬 크게 올린다.
 */
@Service
public class BidNoticeSyncScheduler {

	private static final Logger log = LoggerFactory.getLogger(BidNoticeSyncScheduler.class);

	private final BidNoticeIngestService ingestService;
	private final BidNoticeIndexRepository repository;
	private final G2bProperties.Index config;

	/**
	 * 겹침 방지.
	 *
	 * <p>{@code fixedDelay} 는 이전 실행이 끝난 뒤부터 세므로 한 스케줄러 안에서는 겹치지
	 * 않는다. 그런데 수동 실행({@code POST /api/search/notices/sync})이 같은 서비스를 부르기
	 * 때문에, 운영자가 버튼을 누른 순간 주기 실행과 겹칠 수 있다. 그러면 같은 구간을 두 번
	 * 읽어 쿼터만 태운다 — 뒤에 온 쪽이 그냥 물러난다.
	 */
	private final AtomicBoolean running = new AtomicBoolean(false);

	public BidNoticeSyncScheduler(BidNoticeIngestService ingestService,
			BidNoticeIndexRepository repository, G2bProperties properties) {
		this.ingestService = ingestService;
		this.repository = repository;
		this.config = properties.index();
	}

	/**
	 * 주기 적재.
	 *
	 * <p>{@code fixedDelayString} 인 것에 뜻이 있다 — {@code fixedRate} 로 두면 적재가 주기보다
	 * 오래 걸릴 때(백필 첫 회차가 그렇다) 다음 실행이 밀려 쌓이고, 결국 나라장터에 동시
	 * 요청 폭풍을 낸다. 끝난 다음에 세는 것이 옳다.
	 */
	@Scheduled(fixedDelayString = "${g2b.index.interval-ms:600000}", initialDelay = 30_000)
	public void ingest() {
		if (!config.enabled()) {
			return;
		}
		runIngest("주기");
	}

	/**
	 * 수동 실행 — 운영 엔드포인트가 부른다.
	 *
	 * <p>{@code enabled} 와 무관하게 동작한다. 스위치는 "저절로 돌 것인가"를 정하는 것이지
	 * "운영자가 시켜도 안 돈다"는 뜻이 아니다.
	 *
	 * @return 이미 돌고 있어 물러났으면 {@code null}
	 */
	public BidNoticeIngestService.IngestResult runNow(int backfillDays) {
		if (!running.compareAndSet(false, true)) {
			return null;
		}
		try {
			// 그대로 넘긴다. 0 이면 평시 증분이고, 양수면 그만큼 되읽으라는 지시다.
			return ingestService.ingestAll(backfillDays);
		}
		finally {
			running.set(false);
		}
	}

	private void runIngest(String trigger) {
		if (!running.compareAndSet(false, true)) {
			log.info("{} 적재를 건너뜁니다 — 이전 회차가 아직 돌고 있습니다.", trigger);
			return;
		}
		long startedAt = System.nanoTime();
		try {
			// 주기 실행은 언제나 증분(0)이다. 여기에 백필 일수를 넘기면 매 회차가 며칠씩
			// 되읽어 증분의 의미가 사라지고 일일 쿼터만 태운다. 첫 회차의 기본 백필은
			// 적재기가 설정(g2b.index.backfill-days)에서 직접 읽는다.
			BidNoticeIngestService.IngestResult result = ingestService.ingestAll(0);
			long failed = result.sources().stream().filter(source -> !source.ok()).count();
			log.info("{} 적재 완료 — {}건 색인, 마감 전이 {}건, 실패 출처 {}개, {}ms",
					trigger, result.totalIndexed(), result.sweptToClosed(), failed,
					(System.nanoTime() - startedAt) / 1_000_000);
		}
		catch (RuntimeException ex) {
			// ingestAll 은 출처별로 예외를 삼키므로 여기까지 오는 것은 DB 장애 같은 공통 실패다.
			// 스케줄러 스레드에서 예외가 새면 이후 실행이 통째로 멈추므로 반드시 잡는다.
			log.error("{} 적재가 통째로 실패했습니다: {}", trigger, ex.getMessage(), ex);
		}
		finally {
			running.set(false);
		}
	}

	/**
	 * 마감 전이만 따로.
	 *
	 * <p>적재가 꺼져 있어도 돈다. 색인이 더 이상 갱신되지 않더라도, 이미 들어와 있는 공고의
	 * 마감 여부는 시간만 지나면 저절로 바뀌기 때문이다.
	 */
	@Scheduled(fixedDelayString = "${g2b.index.sweep-ms:300000}", initialDelay = 60_000)
	public void sweep() {
		try {
			int swept = repository.sweepClosed();
			if (swept > 0) {
				log.info("마감 전이 {}건", swept);
			}
		}
		catch (RuntimeException ex) {
			// DB 가 아직 없거나 잠깐 끊긴 경우. 5분 뒤에 다시 온다.
			log.warn("마감 전이 실패: {}", ex.getMessage());
		}
	}
}
