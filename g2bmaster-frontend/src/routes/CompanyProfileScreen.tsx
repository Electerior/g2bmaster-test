/*
 * 낙찰자 조회 — 원본 searchCompany(app.js:2006) + renderCompanyProfile(2048).
 * 원본에서는 탭이 아니라 검색 모드(corp)라 결과 영역을 가로채는 방식이었다.
 *
 * 조회는 POST 인데도 useMutation 이 아니라 useQuery 를 쓴다: 부수효과가 없는 읽기이고,
 * 업체명이 URL 에 있으므로 그 주소를 열면 결과가 그려져 있어야 하기 때문이다.
 */
import { useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { fetchCompanyHistory, type CompanyParticipation } from '@/api';
import { StatusBar } from '@/components/table/StatusBar';
import { PanelNotice } from '@/components/feedback/Spinner';
import { toG2bDate } from '@/domain/format';
import { useSearchCriteria } from '@/features/search/useSearchCriteria';
import { BidRateChart } from '@/features/company/BidRateChart';
import '@/features/company/company.css';

function fmtAmount(value: unknown): string {
  const n = Number(String(value ?? '').replace(/,/g, ''));
  return Number.isNaN(n) || !n ? '-' : `${n.toLocaleString()}원`;
}

function fmtRate(value: unknown): string {
  const n = parseFloat(String(value ?? ''));
  return Number.isNaN(n) ? '-' : `${n.toFixed(3)}%`;
}

/** 'YYYYMMDD…' 또는 'YYYY-MM-DD…' → 'YYYY-MM-DD'. 원본 renderCompanyProfile 의 fmtDt. */
function fmtDate(value: unknown): string {
  const text = String(value ?? '');
  if (!text) return '-';
  return text.slice(0, 10).replace(/(\d{4})(\d{2})(\d{2})/, '$1-$2-$3');
}

/** wins 는 서버가 Record<string, unknown> 으로 준다 — 읽는 필드만 좁혀 쓴다. */
function field(row: Record<string, unknown>, ...keys: string[]): string {
  for (const key of keys) {
    const value = row[key];
    if (value != null && String(value).trim() !== '') return String(value);
  }
  return '-';
}

type CorpTab = 'wins' | 'part';

export function CompanyProfileScreen() {
  const { criteria } = useSearchCriteria();
  const [tab, setTab] = useState<CorpTab>('wins');

  useEffect(() => {
    document.title = '낙찰자 조회 — G2B Masters';
  }, []);

  const corpNm = criteria.corpNm.trim();
  const body = {
    corpNm,
    ...(criteria.brnNo ? { brnNo: criteria.brnNo } : {}),
    ...(criteria.fromDate ? { fromDate: toG2bDate(criteria.fromDate) } : {}),
    ...(criteria.toDate ? { toDate: toG2bDate(criteria.toDate) } : {}),
  };

  const history = useQuery({
    queryKey: ['company-history', body],
    queryFn: () => fetchCompanyHistory(body),
    enabled: Boolean(corpNm),
  });

  // 업체명이 없으면 조회 자체가 성립하지 않는다 — 원본과 같은 문구로 알린다.
  if (!corpNm) {
    return (
      <section className="panel" aria-label="낙찰자 조회">
        <StatusBar error message="업체명을 입력하고 Enter 또는 검색 버튼을 누르세요." />
      </section>
    );
  }

  const data = history.data;
  const stats = data?.stats;
  const wins = data?.wins ?? [];
  const participations: CompanyParticipation[] = data?.participations ?? [];

  return (
    <section className="panel" aria-label="낙찰자 조회">
      <StatusBar
        error={Boolean(history.error)}
        message={
          history.error ? (
            `오류: ${history.error.message}`
          ) : !data ? (
            <>
              <strong>{corpNm}</strong> 업체 이력 조회 중…
            </>
          ) : (
            <>
              <strong>{corpNm}</strong>
              {' | '}낙찰 <strong>{(stats?.winCount ?? 0).toLocaleString()}</strong>건 · 참여{' '}
              <strong>{(stats?.participationCount ?? 0).toLocaleString()}</strong>건
              {stats?.winRate != null ? (
                <>
                  {' '}
                  · 낙찰률 <strong>{stats.winRate}%</strong>
                </>
              ) : null}
              {stats?.avgBidRate ? (
                <>
                  {' '}
                  · 평균 투찰률 <strong>{parseFloat(stats.avgBidRate).toFixed(3)}%</strong>
                </>
              ) : null}
            </>
          )
        }
      />

      {history.isPending ? (
        <PanelNotice>업체 이력을 불러오는 중...</PanelNotice>
      ) : !data ? (
        <PanelNotice empty>업체 이력을 불러오지 못했습니다.</PanelNotice>
      ) : (
        <div className="corp-panel">
          <div className="corp-profile">
            <div className="corp-profile-header">
              <h2 className="corp-profile-name">{data.corpNm}</h2>
              <div className="corp-profile-stats">
                <div className="corp-stat">
                  <span className="corp-stat-label">낙찰</span>
                  <strong className="corp-stat-val">
                    {(stats?.winCount ?? 0).toLocaleString()}건
                  </strong>
                </div>
                <div className="corp-stat">
                  <span className="corp-stat-label">참여 이력</span>
                  <strong className="corp-stat-val">
                    {(stats?.participationCount ?? 0).toLocaleString()}건
                  </strong>
                </div>
                {stats?.winRate != null ? (
                  <div className="corp-stat">
                    <span className="corp-stat-label">낙찰률</span>
                    <strong className="corp-stat-val">{stats.winRate}%</strong>
                  </div>
                ) : null}
                {stats?.avgBidRate ? (
                  <div className="corp-stat">
                    <span className="corp-stat-label">평균 투찰률</span>
                    <strong className="corp-stat-val">
                      {parseFloat(stats.avgBidRate).toFixed(3)}%
                    </strong>
                  </div>
                ) : null}
              </div>
              <p className="corp-profile-note">
                ※ 3개월 개찰결과 기준 · 나라장터 API 1페이지(최대 500건×3업종) 범위 내 조회
              </p>
            </div>

            <BidRateChart series={stats?.bidRateSeries ?? []} />

            <div className="corp-tabs">
              <button
                type="button"
                className={tab === 'wins' ? 'corp-tab active' : 'corp-tab'}
                onClick={() => setTab('wins')}
              >
                낙찰 이력 {wins.length}건
              </button>
              <button
                type="button"
                className={tab === 'part' ? 'corp-tab active' : 'corp-tab'}
                onClick={() => setTab('part')}
              >
                참여 이력 {participations.length}건
              </button>
            </div>

            <div className="corp-tab-panel">
              {tab === 'wins' ? (
                <table className="corp-table">
                  <thead>
                    <tr>
                      <th>공고번호</th>
                      <th>공고명</th>
                      <th>수요기관</th>
                      <th>낙찰금액</th>
                      <th>낙찰률</th>
                      <th>개찰일</th>
                    </tr>
                  </thead>
                  <tbody>
                    {wins.length === 0 ? (
                      <tr>
                        <td colSpan={6} className="td-empty">
                          낙찰 이력이 없습니다
                        </td>
                      </tr>
                    ) : (
                      wins.map((win, i) => (
                        <tr key={`${field(win, 'bidNtceNo')}-${i}`}>
                          <td>{field(win, 'bidNtceNo')}</td>
                          <td className="td-nm">{field(win, 'bidNtceNm')}</td>
                          <td>{field(win, 'dminsttNm', 'ntceInsttNm')}</td>
                          <td className="td-amt">{fmtAmount(win.sucsfbidAmt)}</td>
                          <td>{fmtRate(win.sucsfbidRate)}</td>
                          <td>{fmtDate(win.rlOpengDt ?? win.fnlSucsfDate)}</td>
                        </tr>
                      ))
                    )}
                  </tbody>
                </table>
              ) : (
                <table className="corp-table">
                  <thead>
                    <tr>
                      <th>공고번호</th>
                      <th>공고명</th>
                      <th>수요기관</th>
                      <th>순위</th>
                      <th>투찰금액</th>
                      <th>투찰률</th>
                      <th>결과</th>
                      <th>개찰일</th>
                    </tr>
                  </thead>
                  <tbody>
                    {participations.length === 0 ? (
                      <tr>
                        <td colSpan={8} className="td-empty">
                          참여 이력 데이터가 없습니다 (최근 30건 개찰 결과 기준)
                        </td>
                      </tr>
                    ) : (
                      participations.map((p, i) => (
                        <tr key={`${p.bidNtceNo}-${i}`} className={p._won ? 'row-won' : undefined}>
                          <td>{p.bidNtceNo || '-'}</td>
                          <td className="td-nm">{p.bidNtceNm || '-'}</td>
                          <td>{p.ntceInsttNm || p.dminsttNm || '-'}</td>
                          <td>{String(p.rank || '-')}</td>
                          <td className="td-amt">{fmtAmount(p.bidAmt)}</td>
                          <td>{fmtRate(p.bidprcRt)}</td>
                          <td>{p._won ? <span className="won-badge">낙찰</span> : '미낙찰'}</td>
                          <td>{fmtDate(p.opengDt)}</td>
                        </tr>
                      ))
                    )}
                  </tbody>
                </table>
              )}
            </div>
          </div>
        </div>
      )}
    </section>
  );
}
