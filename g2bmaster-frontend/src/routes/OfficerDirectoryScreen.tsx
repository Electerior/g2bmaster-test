/*
 * 담당자 조회 — 원본 searchOfficer(app.js:1915) + renderOfficerPanel(1952).
 * 발주기관의 공고 담당자와 연락처, 그 담당자가 낸 공고 목록.
 *
 * 카드마다 공고는 5건까지만 보여주고 나머지는 '외 N건 더 있음' 으로 접는다 — 이 화면의
 * 목적은 "누구에게 연락할 것인가"이지 공고 목록을 읽는 것이 아니다.
 */
import { useEffect } from 'react';
import { useQuery } from '@tanstack/react-query';
import { searchOfficers, type OfficerRecord } from '@/api';
import { StatusBar } from '@/components/table/StatusBar';
import { PanelNotice } from '@/components/feedback/Spinner';
import { toG2bDate } from '@/domain/format';
import { useSearchCriteria } from '@/features/search/useSearchCriteria';
import '@/features/officer/officer.css';

const MAX_BIDS = 5;

/** 구분 → 클래스. 한글 클래스명(.ofc-type-물품)은 원본이 쓰던 방식이지만 도구가 싫어한다. */
const TYPE_CLASS: Readonly<Record<string, string>> = {
  물품: 'ofc-type-goods',
  용역: 'ofc-type-service',
  공사: 'ofc-type-works',
};

/** 예산은 만 원 단위로 줄여 적는다 — 카드 폭에 원 단위가 들어가지 않는다. 원본 fmtAmt. */
function fmtBudget(value: unknown): string {
  const n = Number(String(value ?? '').replace(/,/g, ''));
  if (Number.isNaN(n) || !n) return '-';
  return `${(n / 10000).toLocaleString(undefined, { maximumFractionDigits: 0 })}만`;
}

function fmtDate(value: unknown): string {
  return value ? String(value).slice(0, 10) : '';
}

function OfficerCard({ officer }: { officer: OfficerRecord }) {
  const bids = officer.bids ?? [];
  const shown = bids.slice(0, MAX_BIDS);
  return (
    <div className="officer-card">
      <div className="ofc-header">
        <div className="ofc-name">{officer.name || '(이름 미공개)'}</div>
        <div className="ofc-instt">{officer.insttNm || officer.dminsttNm}</div>
      </div>
      <div className="ofc-contacts">
        {officer.tel ? (
          <a className="ofc-contact ofc-tel" href={`tel:${officer.tel}`}>
            {officer.tel}
          </a>
        ) : null}
        {officer.email ? (
          <a className="ofc-contact ofc-email" href={`mailto:${officer.email}`}>
            {officer.email}
          </a>
        ) : null}
      </div>
      {bids.length ? (
        <>
          <table className="ofc-bids-table">
            <thead>
              <tr>
                <th>공고명</th>
                <th>구분</th>
                <th>예산</th>
                <th>공고일</th>
              </tr>
            </thead>
            <tbody>
              {shown.map((bid, i) => (
                <tr key={`${bid.bidNtceNo}-${i}`}>
                  <td className="ofc-bid-nm" title={bid.bidNtceNm}>
                    {bid.bidNtceNm || '-'}
                  </td>
                  <td>
                    <span className={`ofc-bid-type ${TYPE_CLASS[bid.type] ?? ''}`}>{bid.type}</span>
                  </td>
                  <td className="ofc-bid-amt">{fmtBudget(bid.asignBdgtAmt)}</td>
                  <td className="ofc-bid-dt">{fmtDate(bid.bidNtceDt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {bids.length > MAX_BIDS ? (
            <p className="ofc-more">외 {bids.length - MAX_BIDS}건 더 있음</p>
          ) : null}
        </>
      ) : null}
    </div>
  );
}

export function OfficerDirectoryScreen() {
  const { criteria } = useSearchCriteria();

  useEffect(() => {
    document.title = '담당자 조회 — G2B Masters';
  }, []);

  const insttNm = criteria.officerInsttNm.trim();
  const body = {
    insttNm,
    ...(criteria.fromDate ? { fromDate: toG2bDate(criteria.fromDate) } : {}),
    ...(criteria.toDate ? { toDate: toG2bDate(criteria.toDate) } : {}),
  };

  const officers = useQuery({
    queryKey: ['officer-search', body],
    queryFn: () => searchOfficers(body),
    enabled: Boolean(insttNm),
  });

  if (!insttNm) {
    return (
      <section className="panel" aria-label="담당자 조회">
        <StatusBar error message="발주기관명을 입력하고 Enter 또는 검색 버튼을 누르세요." />
      </section>
    );
  }

  const data = officers.data;
  const list = data?.officers ?? [];

  return (
    <section className="panel" aria-label="담당자 조회">
      <StatusBar
        error={Boolean(officers.error)}
        message={
          officers.error ? (
            `오류: ${officers.error.message}`
          ) : !data ? (
            <>
              <strong>{insttNm}</strong> 담당자 조회 중…
            </>
          ) : (
            <>
              <strong>{insttNm}</strong>
              {' | '}담당자 <strong>{list.length.toLocaleString()}</strong>명 · 공고{' '}
              <strong>{(data.totalBids ?? 0).toLocaleString()}</strong>건
            </>
          )
        }
      />

      {officers.isPending ? (
        <PanelNotice>담당자를 불러오는 중...</PanelNotice>
      ) : !data ? (
        <PanelNotice empty>담당자를 불러오지 못했습니다.</PanelNotice>
      ) : list.length === 0 ? (
        <div className="officer-empty">
          <div className="officer-empty-icon">🔍</div>
          <p>
            <strong>{data.insttNm}</strong>의 담당자 정보를 찾지 못했습니다.
          </p>
          <p className="officer-empty-sub">기관명을 더 구체적으로 입력하거나 기간을 늘려보세요.</p>
        </div>
      ) : (
        <div className="officer-panel-inner">
          <div className="officer-summary">
            <span className="officer-summary-label">📋 {data.insttNm} 담당자</span>
            <span className="officer-count">{list.length}명</span>
          </div>
          <div className="officer-cards">
            {list.map((officer, i) => (
              <OfficerCard key={`${officer.name}-${i}`} officer={officer} />
            ))}
          </div>
        </div>
      )}
    </section>
  );
}
