/*
 * 공고 저장 버튼 — 공고 통합 검색의 행(★ 아이콘)과 상세 드로어([★ 저장])가 공유한다.
 *
 * 배선의 요점은 "구현돼 있는데 부를 UI 가 없던" 경로를 잇는 것이다:
 *   POST /api/saved-notices (useSaveNotice) 는 완성돼 있었지만 호출부가 없어, 저장 공고 화면이
 *   "AI 수주 데스크에서 [저장]을 누르세요"라는 존재하지 않는 버튼을 안내하고 있었다. 그 순환을
 *   여기서 끊는다 — 검색 결과에서 바로 담는다.
 *
 * 이 경로는 @RequireAppAuth 다. 운영에서 백엔드가 앱 키를 켜면 lib/apiClient 의 request
 * 인터셉터가 헤더를 실어 준다(VITE_APP_API_KEY). 키가 없으면(개발 모드) 그대로 통과한다.
 * 그래서 이 컴포넌트는 인증을 알 필요가 없고, 실패는 여느 뮤테이션 오류와 같게 다룬다.
 */
import { useSaveNotice } from '@/api/saved';
import type { NoticeIndexItem } from '@/api/search';
import { toSaveRequest } from './indexRows';

type Variant = 'icon' | 'button';

interface SaveNoticeButtonProps {
  item: NoticeIndexItem;
  /** 'icon' = 표 행의 ★ 하나, 'button' = 드로어의 [★ 저장] 라벨 버튼. */
  variant?: Variant;
}

export function SaveNoticeButton({ item, variant = 'icon' }: SaveNoticeButtonProps) {
  const save = useSaveNotice();

  // 낙관적 표시는 하지 않는다 — 저장은 드물게 눌리고, 실패(예: 401)를 조용히 성공처럼
  // 보이게 하는 편이 더 나쁘다. 성공하면 별을 채우고, 실패하면 사유를 title 로 남긴다.
  const saved = save.isSuccess;
  const pending = save.isPending;

  const onSave = () => {
    if (saved || pending) return;
    save.mutate(toSaveRequest(item));
  };

  const title = save.isError
    ? `저장 실패: ${save.error.message}`
    : saved
      ? '저장 목록에 담겼습니다'
      : '이 공고를 저장 목록에 담습니다';

  if (variant === 'button') {
    return (
      <button
        type="button"
        className="drawer-save-btn"
        onClick={onSave}
        disabled={pending || saved}
        title={title}
      >
        {pending ? '저장 중…' : saved ? '★ 저장됨' : '★ 저장'}
      </button>
    );
  }

  return (
    <button
      type="button"
      className={saved ? 'row-save-star saved' : 'row-save-star'}
      onClick={onSave}
      disabled={pending}
      aria-pressed={saved}
      aria-label={saved ? '저장됨' : '공고 저장'}
      title={title}
    >
      {pending ? '⋯' : saved ? '★' : '☆'}
    </button>
  );
}
