/*
 * 입찰 공고 상세 서랍 — 원본 openBidModal(app.js:4186) + runAnalysis(5310) +
 * fetchOpeningResults(4683).
 *
 * 원본은 서랍 DOM 하나를 세 렌더 함수가 나눠 썼다. 그래서 "지금 열려 있는 것이 공고인가
 * 발주계획인가"를 currentBidItem 이 null 인지로 구분했고, 개찰 조회 버튼은 그 전역이
 * 비어 있으면 조용히 아무 일도 하지 않았다. 변종을 컴포넌트로 나누면 그 분기가 사라진다 —
 * 개찰 조회는 이 컴포넌트에만 있으므로 대상이 없을 수가 없다.
 */
import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import {
  fetchBidOpeningResults,
  isAiEnabled,
  summarizeBid,
  type BidAnnounceItem,
  type BidOpeningParticipant,
} from '@/api';
import { TypeBadge } from '@/components/badges/Badge';
import { Drawer, DrawerHeader, DrawerMeta } from '@/components/overlay/Drawer';
import { Spinner } from '@/components/feedback/Spinner';
import { loadCompanyProfile } from '@/domain/storage';
import { collectFileEntries, firstPageUrl } from '../rows';
import {
  buildMetaRows,
  datetimeText,
  dateText,
  ddayValue,
  emailLink,
  moneyText,
  pick,
  telLink,
} from './metaValues';
import { AiFallbackNote, ParsedFiles, SourceLabel, SummaryBody, SummaryState } from './summaryParts';

/** 원본 runAnalysis 의 sourceLabel 분기(app.js:5348). */
const SOURCE_LABEL: Readonly<Record<string, string>> = {
  'auto-file': '📄 첨부파일 자동 분석 + 공고 본문 보완',
  'auto-detail': '📋 공고 원문 자동 분석 (상세 API)',
  manual: '📂 업로드 파일 분석',
  meta: '⚠️ 공고 기본정보 기반 분석 (문서 없음)',
};

function fmtBidAmount(value: unknown): string {
  const n = Number(String(value ?? '').replace(/,/g, ''));
  return Number.isNaN(n) || !n ? '-' : `${n.toLocaleString()}원`;
}

function fmtBidRate(value: unknown): string {
  const n = parseFloat(String(value ?? ''));
  return Number.isNaN(n) ? '-' : `${n.toFixed(3)}%`;
}

interface OpeningPanelProps {
  item: BidAnnounceItem;
}

/**
 * 개찰 경쟁 현황. 버튼을 눌러야 조회한다 — 원본과 같다.
 * 서랍을 열 때마다 자동으로 부르면 아직 개찰 전인 공고에서 매번 헛품을 판다.
 */
function OpeningPanel({ item }: OpeningPanelProps) {
  const [requested, setRequested] = useState(false);
  const query = useQuery({
    queryKey: ['bid-opening', item.bidNtceNo ?? '', item.bidNtceSqNo ?? '000'],
    queryFn: () =>
      fetchBidOpeningResults({
        bidNtceNo: String(item.bidNtceNo ?? ''),
        bidNtceSqNo: String(item.bidNtceSqNo ?? '000'),
        type: pick(item._type, item.bsnsDivNm) || '물품',
      }),
    enabled: requested,
  });

  const participants: BidOpeningParticipant[] = query.data?.participants ?? [];
  const sorted = [...participants].sort((a, b) => Number(a.rank ?? 99) - Number(b.rank ?? 99));

  return (
    <div className="drawer-section tight">
      <div className="drawer-section-label">
        개찰 경쟁 현황
        <button
          type="button"
          className="btn-opening-fetch"
          onClick={() => (requested ? void query.refetch() : setRequested(true))}
        >
          조회
        </button>
      </div>
      <div className="meta">
        {!requested ? (
          '버튼을 누르면 참여업체별 투찰금액을 조회합니다.'
        ) : query.isPending || query.isFetching ? (
          <>
            <Spinner small /> 조회 중...
          </>
        ) : query.error ? (
          <span style={{ color: 'var(--error)' }}>조회 실패: {query.error.message}</span>
        ) : !sorted.length ? (
          '개찰결과 미공개 또는 아직 집계 전입니다.'
        ) : (
          <>
            <div className="opening-table-wrap">
              <table className="opening-table">
                <thead>
                  <tr>
                    <th>순위</th>
                    <th style={{ textAlign: 'left' }}>업체명</th>
                    <th className="num">투찰금액</th>
                    <th className="num">투찰율</th>
                    <th>낙찰</th>
                  </tr>
                </thead>
                <tbody>
                  {sorted.map((p, i) => {
                    const won = String(p.sucsfbidYn ?? '').toUpperCase() === 'Y';
                    return (
                      <tr key={`${p.bdrNm ?? ''}-${i}`} className={won ? 'win' : undefined}>
                        <td className="mid">{p.rank ?? '-'}</td>
                        <td>{p.bdrNm ?? '-'}</td>
                        <td className="num">{fmtBidAmount(p.bidAmt)}</td>
                        <td className="num">{fmtBidRate(p.bidprcRt)}</td>
                        <td className="mid">{won ? '✅ 낙찰' : '-'}</td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
            <div className="opening-note">총 {participants.length}개사 참여</div>
          </>
        )}
      </div>
    </div>
  );
}

interface BidNoticeDrawerProps {
  item: BidAnnounceItem;
  onClose: () => void;
}

export function BidNoticeDrawer({ item, onClose }: BidNoticeDrawerProps) {
  // AI 요약(POST /api/bid-summary)은 백엔드 AI 저장소 + 첨부 파싱에 의존한다. 아직 이식이
  // 안 끝나 호출하면 500 이 난다 — 그래서 g2b.ai.enabled 와 짝인 프론트 플래그로 게이트한다.
  // 꺼져 있으면 아예 부르지 않고(enabled:false), 아래에서 '준비 중' 안내로 대체한다.
  const aiEnabled = isAiEnabled();

  // 서랍을 열면 곧바로 분석이 돌아간다(원본 openBidModal 끝의 runAnalysis()).
  // useQuery 로 두면 같은 공고를 다시 열었을 때 캐시가 살아 있어 두 번 부르지 않는다.
  const summary = useQuery({
    queryKey: ['bid-summary', item.bidNtceNo ?? '', item.bidNtceSqNo ?? item.bidNtceOrd ?? ''],
    enabled: aiEnabled,
    queryFn: () => {
      const fileEntries = collectFileEntries(item);
      // 표준공고서는 파일 URL 패턴에 안 걸리는 경우가 있어 따로 붙인다(원본 app.js:5320).
      const stdUrl = String(item.stdNtceDocUrl ?? '');
      if (stdUrl && !fileEntries.some((f) => f.url === stdUrl)) {
        fileEntries.push({ url: stdUrl, name: '표준공고서' });
      }
      return summarizeBid({
        bidNtceNo: String(item.bidNtceNo ?? ''),
        bidNtceSqNo: String(item.bidNtceSqNo ?? item.bidNtceOrd ?? '000'),
        bidNtceNm: String(item.bidNtceNm ?? ''),
        ntceInsttNm: String(item.ntceInsttNm ?? ''),
        presmptPrce: (item.presmptPrce ?? '') as string | number,
        cntrctCnclsMthdNm: String(item.cntrctCnclsMthdNm ?? ''),
        dtilPrdctClsfcNoNm: String(item.dtilPrdctClsfcNoNm ?? ''),
        _type: String(item._type ?? ''),
        companyProfile: loadCompanyProfile(),
        fileEntries,
        rawFields: item,
      });
    },
    // 요약은 LLM 호출이라 비싸다. 같은 공고를 오가는 동안은 다시 부르지 않는다.
    staleTime: Infinity,
  });

  const rows = buildMetaRows([
    ['품목번호', pick(item.prdctClsfcNo, item.dtilPrdctClsfcNo)],
    ['품목명', pick(item.prdctClsfcNoNm, item.dtilPrdctClsfcNoNm)],
    ['공고번호', pick(item.bidNtceNo)],
    ['공고기관', pick(item.ntceInsttNm)],
    ['수요기관', pick(item.dminsttNm)],
    ['추정가격', moneyText(item.presmptPrce)],
    ['계약방법', pick(item.cntrctCnclsMthdNm)],
    ['낙찰방법', pick(item.sucsfbidMthdNm)],
    ['제한/자격', pick(item._requirementSummary)],
    ['품목분류', pick(item.dtilPrdctClsfcNoNm)],
    ['공고일', dateText(item.bidNtceDt)],
    ['마감일시', datetimeText(item.bidClseDt)],
    ['D-DAY', ddayValue(item.bidClseDt)],
    ['담당자', pick(item.ntceInsttOfclNm)],
    ['전화', telLink(item.ntceInsttOfclTelNo)],
    ['이메일', emailLink(item.ntceInsttOfclEmailAdrs)],
  ]);

  const g2bUrl = firstPageUrl(item, ['bidNtceUrl', 'bidNtceDtlUrl']);
  const data = summary.data;

  return (
    <Drawer open onClose={onClose} label={String(item.bidNtceNm ?? '공고 상세')}>
      <DrawerHeader
        badge={<TypeBadge value={String(item._type ?? '공고')} />}
        onClose={onClose}
      />
      <h2 className="drawer-title">{String(item.bidNtceNm ?? '공고 상세')}</h2>

      {/* 본문 전체를 하나의 스크롤 흐름으로 — 메타·AI분석·개찰이 각자 잘리지 않게. */}
      <div className="drawer-body">
        <DrawerMeta rows={rows} />

        <div className="drawer-section">
          <div className="drawer-section-label">✨ AI 분석</div>
          <div className="drawer-summary">
            {!aiEnabled ? (
              // 백엔드 AI 저장소·첨부 파싱이 이식되기 전까지는 요약을 부르지 않는다 — 부르면 500 이다.
              <div className="summary-text muted">
                AI 요약은 백엔드 연동 준비 중입니다. 준비되면 이 자리에 첨부문서 기반 요약이 표시됩니다.
              </div>
            ) : (
              <SummaryState
                isPending={summary.isPending}
                error={summary.error}
                onRetry={() => void summary.refetch()}
                pendingText="AI 분석 중... 잠시만 기다려 주세요."
              >
                {data ? (
                  <>
                    <SourceLabel>{SOURCE_LABEL[data.source] ?? SOURCE_LABEL.meta}</SourceLabel>
                    {data.noPdf ? (
                      <div className="summary-error">
                        첨부문서를 자동으로 찾지 못해 기본정보로만 분석했습니다.
                      </div>
                    ) : null}
                    <AiFallbackNote flags={data} />
                    <SummaryBody summary={data.summary} />
                    <ParsedFiles parsedFiles={data.parsedFiles} />
                  </>
                ) : null}
              </SummaryState>
            )}
          </div>
        </div>

        <OpeningPanel item={item} />
      </div>

      {g2bUrl ? (
        <div className="drawer-footer">
          <a
            className="btn-g2b-link"
            href={g2bUrl}
            target="_blank"
            rel="noopener noreferrer"
          >
            나라장터에서 전체 보기 ↗
          </a>
        </div>
      ) : null}
    </Drawer>
  );
}
