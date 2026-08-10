/*
 * 일자별 공고 표 — 원본 trendDayOptions(app.js:2223) · trendDayTable(2270) ·
 * trendDayNotices(2315).
 *
 * 건수 칸과 금액 칸이 **같은 확장 행**을 연다. 원본은 두 버튼이 같은 data-day 를 갖고
 * 위임 리스너가 `.trend-day-detail[data-day-detail=…]` 을 찾아 토글했다. 여기서는 열려 있는
 * 날짜 하나를 state 로 들고 있으면 끝난다 — 한 번에 하나만 열리는 것도 원본과 같다.
 */
import { Fragment, useState } from 'react';
import type { TrendDayBucket, TrendNotice } from '@/api';
import { calcDday, fmtDisplayDatetime, trendFullMoney, trendMoney } from '@/domain/format';
import {
  trendDayRows,
  type TrendDayLimit,
  type TrendDaySort,
} from './trendDays';
import './trend.css';

interface TrendDayTableProps {
  byDay: readonly TrendDayBucket[];
  limit: TrendDayLimit;
  sort: TrendDaySort;
  onLimitChange: (limit: TrendDayLimit) => void;
  onSortChange: (sort: TrendDaySort) => void;
  /** 공고명을 누르면 입찰 공고 화면으로 넘어간다. */
  onOpenNotice: (notice: TrendNotice) => void;
}

function DayNotices({
  notices,
  moreCount,
  onOpenNotice,
}: {
  notices: readonly TrendNotice[];
  moreCount: number;
  onOpenNotice: (notice: TrendNotice) => void;
}) {
  if (!notices.length) {
    return <div className="panel-empty trend-day-empty">해당 날짜 공고명이 없습니다.</div>;
  }
  return (
    <div className="trend-day-notices">
      {notices.map((notice, i) => {
        const dday = calcDday(notice.bidClseDt);
        // 같은 제목이 며칠에 걸쳐 반복되는 건은 묶여서 온다 — 그때는 묶음 금액을 보여준다.
        const sameTitleCount = Number(notice._sameTitleCount ?? 1);
        const amount = sameTitleCount > 1 ? notice._sameTitleAmount : notice.presmptPrce;
        const meta = [
          notice.ntceInsttNm || '-',
          trendFullMoney(amount),
          dday ? dday.text : fmtDisplayDatetime(String(notice.bidClseDt || '')),
        ]
          .filter(Boolean)
          .join(' · ');
        return (
          <div className="trend-day-notice" key={`${notice.bidNtceNo}-${i}`}>
            <button type="button" className="trend-open-btn" onClick={() => onOpenNotice(notice)}>
              {notice.bidNtceNm || '-'}
            </button>
            {sameTitleCount > 1 ? (
              <span className="trend-day-dup">{sameTitleCount.toLocaleString()}건 묶음</span>
            ) : null}
            <p>{meta}</p>
          </div>
        );
      })}
      {moreCount > 0 ? (
        <div className="trend-day-more">
          외 {moreCount.toLocaleString()}건은 목록에서 생략되었습니다.
        </div>
      ) : null}
    </div>
  );
}

export function TrendDayTable({
  byDay,
  limit,
  sort,
  onLimitChange,
  onSortChange,
  onOpenNotice,
}: TrendDayTableProps) {
  const [openDay, setOpenDay] = useState<string | null>(null);
  const rows = trendDayRows(byDay, limit, sort);

  const toolbar = (
    <div className="trend-day-toolbar">
      <div className="trend-day-toolbar-title">
        <strong>표시 옵션</strong>
        <span>
          일자 기준: 공고일 · 전체 {byDay.length.toLocaleString()}개 중{' '}
          {rows.length.toLocaleString()}개 표시
        </span>
      </div>
      <label>
        <span>표시</span>
        <select
          className="trend-day-control"
          value={limit}
          onChange={(e) => onLimitChange(e.target.value as TrendDayLimit)}
        >
          <option value="21">최근 21개 공고일</option>
          <option value="60">최근 60개 공고일</option>
          <option value="all">전체 공고일</option>
        </select>
      </label>
      <label>
        <span>정렬</span>
        <select
          className="trend-day-control"
          value={sort}
          onChange={(e) => onSortChange(e.target.value as TrendDaySort)}
        >
          <option value="date">날짜순</option>
          <option value="count">건수 많은순</option>
          <option value="amount">금액 큰순</option>
        </select>
      </label>
    </div>
  );

  if (!rows.length) {
    return (
      <>
        {toolbar}
        <div className="panel-empty">일자별 데이터가 없습니다.</div>
      </>
    );
  }

  return (
    <div className="trend-day-table-wrap">
      {toolbar}
      <table className="trend-day-table">
        <thead>
          <tr>
            <th>일자</th>
            <th>건수</th>
            <th>금액 합계</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => {
            const day = String(row.name || '');
            const count = Number(row.count || 0);
            const amount = Number(row.amount || 0);
            const notices = row.notices ?? [];
            const moreCount = Math.max(0, count - notices.length);
            const open = openDay === day;
            const toggle = () => setOpenDay(open ? null : day);
            return (
              <Fragment key={day}>
                <tr>
                  <td>{day}</td>
                  <td>
                    <button
                      type="button"
                      className="trend-day-toggle"
                      aria-expanded={open}
                      onClick={toggle}
                    >
                      {count.toLocaleString()}건
                    </button>
                  </td>
                  <td>
                    <button
                      type="button"
                      className="trend-day-toggle trend-day-amount"
                      aria-expanded={open}
                      title={trendFullMoney(amount)}
                      onClick={toggle}
                    >
                      <strong>{trendFullMoney(amount)}</strong>
                      <small>{trendMoney(amount)}</small>
                    </button>
                  </td>
                </tr>
                {open ? (
                  <tr className="trend-day-detail">
                    <td colSpan={3}>
                      <DayNotices
                        notices={notices}
                        moreCount={moreCount}
                        onOpenNotice={onOpenNotice}
                      />
                    </td>
                  </tr>
                ) : null}
              </Fragment>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
