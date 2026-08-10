/*
 * 검색창 전체 — 원본 index.html 의 `<section class="search-box">` + app.js 의 배선.
 *
 * 원본과의 구조적 차이 둘.
 *  1. 조건은 URL 이 갖는다. 입력이 확정되는 순간 URL 이 바뀌고, 화면들은 그 URL 을 보고
 *     조회한다 — '검색' 버튼은 "아직 확정되지 않은 입력을 확정한다"는 뜻이 된다.
 *  2. 앱 내 뒤로/앞으로 스택(viewHistory/viewForwardHistory, 각 10개)을 **삭제**했다.
 *     조건이 URL 에 있으므로 브라우저 뒤로/앞으로가 같은 일을 더 정확히 한다(spec §2).
 */
import { useEffect, useRef, useState } from 'react';
import { useLocation } from 'react-router-dom';
import { useNotReady } from '@/components/feedback/notReadyContext';
import { fmtInputDate } from '@/domain/format';
import { searchModeLayout } from '@/domain/searchModes';
import type { SearchMode } from '@/domain/searchModes';
import { isTransitRoute } from '@/routes/routePaths';
import { useSearchCriteria, type SearchCriteria } from './useSearchCriteria';
import { SearchModeTabs } from './SearchModeTabs';
import { activeSearchMode } from './searchRoutes';
import { TagInput } from './TagInput';
import './search.css';

/** 원본 setQuickDateRange(app.js:1044) — 오늘부터 N일 전까지. */
function quickRange(days: number): { fromDate: string; toDate: string } {
  const today = new Date();
  const from = new Date(today);
  from.setDate(from.getDate() - days);
  return { fromDate: fmtInputDate(from), toDate: fmtInputDate(today) };
}

const QUICK_DATES = [
  { days: 0, label: '오늘' },
  { days: 7, label: '1주일' },
  { days: 14, label: '2주일' },
  { days: 30, label: '1달' },
] as const;

/** 원본 init() 이 화면을 띄우자마자 걸던 기본 범위. */
const DEFAULT_RANGE_DAYS = 30;

/** 원본 setSearchMode 가 '모두 포함' 입력의 placeholder 를 모드에 따라 바꿨다(app.js:1091). */
function andPlaceholder(mode: SearchMode): string {
  return mode === 'item'
    ? '품목명 입력 후 Enter — 예: 신선한빵, 컴퓨터서버, 정수기'
    : '키워드 입력 후 Enter — 모두 일치해야 함';
}

/** × 버튼이 달린 한 줄 입력. 발주기관·업체명·사업자번호·담당자 기관이 같은 모양을 쓴다. */
interface ClearableInputProps {
  badgeClass: string;
  badgeLabel: string;
  value: string;
  placeholder: string;
  onChange: (value: string) => void;
  onSubmit: () => void;
  clearTitle: string;
  maxLength?: number;
  narrow?: boolean;
}

function ClearableInput({
  badgeClass,
  badgeLabel,
  value,
  placeholder,
  onChange,
  onSubmit,
  clearTitle,
  maxLength,
  narrow = false,
}: ClearableInputProps) {
  return (
    <>
      <span className={`bool-badge ${badgeClass}`}>{badgeLabel}</span>
      <div className={narrow ? 'instt-wrap instt-wrap-brn' : 'instt-wrap'}>
        <input
          className="instt-input"
          value={value}
          placeholder={placeholder}
          autoComplete="off"
          maxLength={maxLength}
          aria-label={badgeLabel}
          onChange={(e) => onChange(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') {
              e.preventDefault();
              onSubmit();
            }
          }}
        />
        {/* 원본은 값이 있을 때만 .visible 을 붙였다 — 빈 칸 옆의 × 는 누를 것이 없다. */}
        {value ? (
          <button
            type="button"
            className="btn-clear-instt"
            title={clearTitle}
            aria-label={clearTitle}
            onClick={() => onChange('')}
          >
            ×
          </button>
        ) : null}
      </div>
    </>
  );
}

export function SearchHeader() {
  const location = useLocation();
  const { criteria, setCriteria } = useSearchCriteria();
  const mode = activeSearchMode(location.pathname, criteria.mode);
  const layout = searchModeLayout(mode);
  const { notify } = useNotReady();

  /*
   * 첨부 전수조사(파일 내 검색 · 입찰 불가 조항)와 유사도 확장은 백엔드에서 작업 중이다.
   * 로컬 색인에는 첨부 본문도 임베딩도 아직 없다.
   *
   * 조작부는 **지우지 않고 남긴다.** 지우면 다음 웨이브에서 되살릴 자리를 잃고, 사용자도
   * 그런 기능이 있었다는 사실을 모르게 된다. 대신 손대면 준비 중임을 알린다.
   */
  const fileScanPending = { label: '첨부문서 전수조사', notify };
  const similarityPending = { label: '유사도 확장', notify };

  // 한 줄 입력은 확정 전까지 URL 에 올리지 않는다 — 글자마다 히스토리가 쌓이면 뒤로 가기가
  // 못 쓰게 된다. 확정(Enter · 검색 · 지우기)될 때만 URL 로 올라간다.
  const [insttDraft, setInsttDraft] = useState(criteria.insttNm);
  const [corpDraft, setCorpDraft] = useState(criteria.corpNm);
  const [brnDraft, setBrnDraft] = useState(criteria.brnNo);
  const [officerDraft, setOfficerDraft] = useState(criteria.officerInsttNm);

  // 프리셋·뒤로 가기처럼 URL 이 밖에서 바뀌면 입력칸도 따라가야 한다.
  const syncedRef = useRef('');
  const signature = [
    criteria.insttNm,
    criteria.corpNm,
    criteria.brnNo,
    criteria.officerInsttNm,
  ].join('|');
  if (syncedRef.current !== signature) {
    syncedRef.current = signature;
    if (insttDraft !== criteria.insttNm) setInsttDraft(criteria.insttNm);
    if (corpDraft !== criteria.corpNm) setCorpDraft(criteria.corpNm);
    if (brnDraft !== criteria.brnNo) setBrnDraft(criteria.brnNo);
    if (officerDraft !== criteria.officerInsttNm) setOfficerDraft(criteria.officerInsttNm);
  }

  // 원본은 init() 에서 기본 30일 범위를 넣고 시작했다. 조건이 URL 로 올라간 뒤에도 그
  // 기본값은 살아 있어야 한다 — 없으면 첫 조회가 전 기간을 훑는다.
  // replace 로 넣어 히스토리에 빈 URL 을 남기지 않는다.
  const seededRef = useRef(false);
  useEffect(() => {
    // '/' 와 옛 공고 표 주소는 다른 화면으로 넘기는 중간 경유지다. 여기서 파라미터를 심으면
    // 곧이어 일어나는 리다이렉트가 그것을 지우는데, '심었다'는 표시는 남아 목적지에서 다시
    // 심지 않는다 — 그러면 기본 조회 기간 없이 색인 전체를 훑는 질의가 나간다.
    // 목적지에 도착한 뒤에 심는다.
    if (isTransitRoute(location.pathname) || seededRef.current) return;
    seededRef.current = true;
    if (!criteria.fromDate && !criteria.toDate) {
      setCriteria(quickRange(DEFAULT_RANGE_DAYS), { replace: true });
    }
  }, [location.pathname, criteria.fromDate, criteria.toDate, setCriteria]);

  /**
   * 확정되지 않은 한 줄 입력을 URL 로 올린다. 원본 doSearch 의 앞부분(flushPendingTagInputs
   * + 입력값 읽기)과 같은 일이다.
   *
   * 반드시 **한 번의** setCriteria 로 끝내야 한다. 조건은 URL 이고, setCriteria 는 이번 렌더의
   * criteria 를 읽어 URL 전체를 다시 쓴다 — 한 이벤트에서 두 번 부르면 두 번째가 첫 번째를
   * 지운다. 그래서 칩 확정 같은 추가 변경도 patch 로 받아 여기서 합친다.
   */
  const commitDrafts = (patch: Partial<SearchCriteria> = {}) => {
    setCriteria({
      insttNm: insttDraft.trim(),
      corpNm: corpDraft.trim(),
      // 사업자번호는 숫자만 남긴다 — 원본과 같다(하이픈을 넣어도 조회된다).
      brnNo: brnDraft.replace(/[^0-9]/g, ''),
      officerInsttNm: officerDraft.trim(),
      ...patch,
    });
  };

  const activeQuickDays = QUICK_DATES.find((q) => {
    const range = quickRange(q.days);
    return range.fromDate === criteria.fromDate && range.toDate === criteria.toDate;
  })?.days;

  return (
    <>
      <section className="search-box" aria-label="공고 검색 조건">
        <SearchModeTabs mode={criteria.mode} onModeChange={(next) => setCriteria({ mode: next })} />

        <div className="bool-search">
          {layout.keywordRows ? (
            <>
              <TagInput
                kind="and"
                badgeLabel="모두 포함"
                placeholder={andPlaceholder(mode)}
                values={criteria.andTerms}
                onChange={(andTerms) => setCriteria({ andTerms })}
                onSubmit={(andTerms) => commitDrafts(andTerms ? { andTerms } : {})}
              />
              <TagInput
                kind="or"
                badgeLabel="하나 이상"
                placeholder="키워드 입력 후 Enter — 하나라도 포함되면 검색"
                values={criteria.orTerms}
                onChange={(orTerms) => setCriteria({ orTerms })}
                onSubmit={(orTerms) => commitDrafts(orTerms ? { orTerms } : {})}
                similarity={{
                  checked: criteria.simOr,
                  onChange: (simOr) => setCriteria({ simOr }),
                  title: '공고 제목을 대상으로 의미가 비슷한 말까지 찾습니다',
                  notReady: similarityPending,
                }}
              />
              <TagInput
                kind="not"
                badgeLabel="제외"
                placeholder="키워드 입력 후 Enter — 포함된 항목 제외"
                values={criteria.notTerms}
                onChange={(notTerms) => setCriteria({ notTerms })}
                onSubmit={(notTerms) => commitDrafts(notTerms ? { notTerms } : {})}
              />
              <TagInput
                kind="file"
                badgeLabel="파일 내"
                placeholder="첨부 PDF·HWPX 본문 검색 — 백엔드 작업 중"
                values={criteria.fileKeywords}
                onChange={(fileKeywords) => setCriteria({ fileKeywords })}
                onSubmit={(fileKeywords) => commitDrafts(fileKeywords ? { fileKeywords } : {})}
                similarity={{
                  checked: criteria.simFile,
                  onChange: (simFile) => setCriteria({ simFile }),
                  title: '규격서 본문을 대상으로 의미가 비슷한 말까지 찾습니다',
                  notReady: similarityPending,
                }}
                notReady={fileScanPending}
              />
            </>
          ) : null}

          {layout.insttRow ? (
            <div className="bool-row">
              <ClearableInput
                badgeClass="badge-instt"
                badgeLabel="발주기관"
                value={insttDraft}
                placeholder="발주기관명 입력 후 Enter — 기관명만으로 검색"
                onChange={setInsttDraft}
                onSubmit={commitDrafts}
                clearTitle="기관 초기화"
              />
            </div>
          ) : null}

          {layout.officerRow ? (
            <div className="bool-row">
              <ClearableInput
                badgeClass="badge-officer"
                badgeLabel="발주기관"
                value={officerDraft}
                placeholder="발주기관명 입력 후 Enter — 예: 한국전자통신연구원"
                onChange={setOfficerDraft}
                onSubmit={commitDrafts}
                clearTitle="초기화"
              />
            </div>
          ) : null}

          {layout.corpRow ? (
            <div className="bool-row">
              <ClearableInput
                badgeClass="badge-corp"
                badgeLabel="업체명"
                value={corpDraft}
                placeholder="업체명 입력 후 Enter"
                onChange={setCorpDraft}
                onSubmit={commitDrafts}
                clearTitle="업체명 초기화"
              />
              <ClearableInput
                badgeClass="badge-brn"
                badgeLabel="사업자번호"
                value={brnDraft}
                placeholder="000-00-00000 (선택)"
                onChange={setBrnDraft}
                onSubmit={commitDrafts}
                clearTitle="사업자번호 초기화"
                maxLength={12}
                narrow
              />
            </div>
          ) : null}
        </div>

        {layout.blockingCheck ? (
          /*
           * 첨부문서 전수조사에 얹혀 있던 기능이라 함께 대기 중이다. 체크 상태는 조건에
           * 반영되지 않으므로 켜 둔 것처럼 보이면 안 된다 — 언제나 꺼진 채로 그린다.
           */
          <label
            className="filter-check search-blocking-check not-ready-control"
            title="경쟁 제한 조항 자동 제외: 백엔드에서 작업 중입니다"
          >
            <input
              type="checkbox"
              checked={false}
              readOnly
              onChange={() => notify('입찰 불가 조항 자동 제외')}
            />{' '}
            입찰 불가 조항 자동 제외
          </label>
        ) : null}

        {layout.searchUi ? (
          <div className="search-footer">
            <div className="date-row">
              <label htmlFor="from-date">기간</label>
              <input
                id="from-date"
                type="date"
                value={criteria.fromDate}
                onChange={(e) => setCriteria({ fromDate: e.target.value })}
              />
              <span>~</span>
              <input
                id="to-date"
                type="date"
                aria-label="종료일"
                value={criteria.toDate}
                onChange={(e) => setCriteria({ toDate: e.target.value })}
              />
              <div className="quick-dates" aria-label="빠른 기간 선택">
                {QUICK_DATES.map((quick) => (
                  <button
                    key={quick.days}
                    type="button"
                    className={
                      activeQuickDays === quick.days ? 'quick-date active' : 'quick-date'
                    }
                    onClick={() => setCriteria(quickRange(quick.days))}
                  >
                    {quick.label}
                  </button>
                ))}
              </div>
              <button
                type="button"
                className="btn-text"
                onClick={() => setCriteria({ fromDate: '', toDate: '' })}
              >
                초기화
              </button>
            </div>
            {/* 인자 없이 불러야 한다 — 그대로 넘기면 MouseEvent 가 patch 로 들어간다. */}
            <button type="button" className="btn-search" onClick={() => commitDrafts()}>
              검색
            </button>
          </div>
        ) : null}
      </section>
    </>
  );
}
