/*
 * 하드웨어 스펙 검색.
 *
 * 원본에서 이 화면은 렌더러(renderSpecSearchPanel, app.js:1384)가 완전히 구현돼 있었는데도
 * TABS 에 항목이 없어 탭을 누르면 TypeError 로 죽었다. 이식하면서 정식 라우트로 살린다.
 *
 * TODO(다음 웨이브): 제목 의미 검색(Module A) + LLM 스펙 추출(Module B).
 */
import { ScreenPlaceholder } from './ScreenPlaceholder';

export function SpecSearchScreen() {
  return (
    <ScreenPlaceholder
      title="하드웨어 스펙 검색"
      description="CPU·GPU 제목 의미 검색과 규격서 본문 스펙 추출."
    />
  );
}
