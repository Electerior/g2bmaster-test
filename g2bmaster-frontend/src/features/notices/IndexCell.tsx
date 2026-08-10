/*
 * 공고 통합 검색 표의 셀 렌더러.
 *
 * 기존 `components/table/Cell.tsx` 와 나눠 둔 이유는 취향이 아니라 **필드 의미가 다르기**
 * 때문이다. 같은 이름의 것이 서로 다른 값을 담는다:
 *   - `dday`  : 기존은 'YYYYMMDDHHmm' 문자열을 파싱해 계산한다. 색인은 서버가 센 **일수**다.
 *   - 날짜    : 기존은 구분자 없는 G2B 문자열, 색인은 ISO-8601.
 *   - 금액    : 기존은 콤마 섞인 문자열, 색인은 숫자.
 * 한 switch 에 밀어 넣으면 이 차이가 조용히 뭉개지고, 그때 화면은 오류 없이 그냥 틀린 값을
 * 보여 준다. 포맷 어휘(CellFmt)만 공유하고 해석은 각자 한다.
 */
import type { ReactNode } from 'react';
import type { NoticeIndexItem } from '@/api/search';
import { TypeBadge } from '@/components/badges/Badge';
import { useNotReady } from '@/components/feedback/notReadyContext';
import type { ColumnDef } from '@/domain/columns';
import { fmtDisplayDatetime, fmtMoney } from '@/domain/format';
import { ddayLabel, ddayTone, institutionPair, regionLabel } from './indexRows';
import { SaveNoticeButton } from './SaveNoticeButton';

const EMPTY = <span className="cell-empty">-</span>;

/** 단계 배지 색. 생애주기 순서대로 파랑 → 보라 → 초록 → 회색. */
const CATEGORY_TONE: Readonly<Record<string, string>> = {
  계획: 'stage-plan',
  사전규격: 'stage-spec',
  입찰: 'stage-open',
  마감: 'stage-closed',
};

export interface IndexCellActions {
  /** 공고명 클릭 — 상세 서랍. */
  openDetail: (item: NoticeIndexItem) => void;
  /** 사전규격 → 그 규격에서 나온 입찰공고로 조건을 좁힌다. */
  crossToSpec: (beforeSpecRgstNo: string) => void;
}

interface IndexCellProps {
  item: NoticeIndexItem;
  column: ColumnDef;
  actions: IndexCellActions;
}

export function IndexCell({ item, column, actions }: IndexCellProps): ReactNode {
  const notReady = useNotReady();
  const raw = (item as unknown as Record<string, unknown>)[column.key];

  switch (column.fmt) {
    /*
     * 수주기회 점수는 팬아웃 파이프라인이 붙이던 값이라 색인 응답에는 아직 없다.
     * 자리를 지워 버리면 다음 웨이브에서 되살릴 곳을 잃으므로 칸은 남기고, 누르면 알린다.
     */
    case 'opportunity-pending':
      return (
        <button
          type="button"
          className="opp-pending not-ready-control"
          onClick={() => notReady.notify('수주기회 점수')}
          title="수주기회 점수는 백엔드에서 작업 중입니다"
        >
          준비 중
        </button>
      );

    case 'category-badge': {
      const value = String(item.category ?? '');
      if (!value) return EMPTY;
      return <span className={`stage-badge ${CATEGORY_TONE[value] ?? 'stage-open'}`}>{value}</span>;
    }

    // 상태는 넷 모두 예외 상태다. 정상 공고는 값이 없으므로 대부분의 칸이 비어 있는 것이 맞다.
    case 'state-badge': {
      const value = String(item.state ?? '');
      if (!value) return EMPTY;
      return (
        <span className={value === '취소' ? 'notice-status status-cancelled' : 'notice-status'}>
          {value}
        </span>
      );
    }

    case 'type-badge': {
      const value = String(item.businessDivision ?? '');
      return value ? <TypeBadge value={value} /> : EMPTY;
    }

    // 빈 지역은 '전국'이다 — 값이 없다고 '-' 로 그리면 필터 오작동으로 읽힌다.
    case 'region': {
      const label = regionLabel(item.region);
      return <span className={label === '전국' ? 'region-any' : undefined}>{label}</span>;
    }

    case 'institutions': {
      const { primary, demand } = institutionPair(item);
      if (!primary) return EMPTY;
      return (
        <div className="instt-pair">
          <span>{primary}</span>
          {demand ? <em title="수요기관">수요 {demand}</em> : null}
        </div>
      );
    }

    case 'notice-name': {
      const name = String(item.noticeName ?? '').trim();
      if (!name) return EMPTY;
      const no = String(item.id ?? '').trim();
      return (
        <div className="notice-name-cell">
          <button type="button" className="bid-link" onClick={() => actions.openDetail(item)}>
            {name}
          </button>
          {/* 공고번호는 표에서 별도 컬럼을 빼는 대신 공고명 아래 옅게 붙인다(SVG 목업). */}
          {no ? <span className="notice-no">{no}</span> : null}
          {item.bodyPreview ? <p className="body-preview">{item.bodyPreview}</p> : null}
        </div>
      );
    }

    // 마감일시 + D-DAY 를 한 칸에. 계획 단계는 마감이 없어 '-' 하나로 깔끔히 떨어진다.
    case 'close-dday': {
      if (!item.closeDate) return EMPTY;
      const label = ddayLabel(item.dday);
      return (
        <div className="close-dday-cell">
          <span>{fmtDisplayDatetime(String(item.closeDate))}</span>
          {label != null ? (
            label === '마감' ? (
              <span className="dday-badge dday-expired">마감</span>
            ) : (
              <span
                className="dday-badge"
                style={{ color: ddayTone(item.dday as number), borderColor: ddayTone(item.dday as number) }}
              >
                {label}
              </span>
            )
          ) : null}
        </div>
      );
    }

    case 'dday-count': {
      const label = ddayLabel(item.dday);
      if (label == null) return EMPTY;
      if (label === '마감') return <span className="dday-expired">마감</span>;
      return (
        <span className="dday" style={{ color: ddayTone(item.dday as number) }}>
          {label}
        </span>
      );
    }

    /*
     * 계획 → 사전규격 → 입찰 을 잇는 칸. 사전규격 행에서는 이 값이 자기 id 와 같으므로
     * 눌러도 제자리다 — 그때는 버튼을 그리지 않는다.
     */
    case 'spec-cross': {
      const specNo = String(item.beforeSpecRgstNo ?? '').trim();
      if (!specNo || specNo === String(item.id)) return EMPTY;
      return (
        <button
          type="button"
          className="cross-tab-btn"
          onClick={() => actions.crossToSpec(specNo)}
          title={`사전규격 ${specNo} 로 이어진 공고 모아보기`}
        >
          규격 →
        </button>
      );
    }

    // ★ 저장 — 구현돼 있으나 부를 UI 가 없던 POST /api/saved-notices 를 행에서 바로 잇는다.
    case 'save-star':
      return <SaveNoticeButton item={item} variant="icon" />;

    case 'money':
      return raw == null || raw === '' ? (
        EMPTY
      ) : (
        <span className="amt">{fmtMoney(raw as number)}</span>
      );

    case 'datetime':
      return raw ? fmtDisplayDatetime(String(raw)) : EMPTY;

    case 'tel': {
      const tel = String(raw ?? '').trim();
      if (!tel) return EMPTY;
      // 담당자 연락처에는 전화번호가 아닌 것(이메일·내선 안내)이 섞여 온다.
      if (tel.includes('@')) return <a href={`mailto:${tel}`}>{tel}</a>;
      return /^[0-9+\-()\s]+$/.test(tel) ? (
        <a href={`tel:${tel}`} style={{ whiteSpace: 'nowrap' }}>
          {tel}
        </a>
      ) : (
        tel
      );
    }

    default: {
      const text = raw == null ? '' : String(raw);
      return text === '' ? EMPTY : text;
    }
  }
}
