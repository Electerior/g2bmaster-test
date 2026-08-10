/*
 * 업로드 분석 — 규격서 랩과 확약서 랩. 두 영역은 상태를 공유하지 않는 독립 화면이다.
 * 공고를 검색하는 게 아니라 올린 파일 하나를 분석하는 모드라 탭 스트립이 함께 사라진다.
 *
 * TODO(다음 웨이브): Dropzone · 본문 추출 · 딜 분석 · 위법 검토 · 콜드메일 초안.
 */
import { ScreenPlaceholder } from './ScreenPlaceholder';

export function AnalysisLabScreen() {
  return (
    <ScreenPlaceholder
      title="업로드 분석"
      description="규격서 업로드 · AI 분석 / 확약서 업로드 · 위법 검토"
    />
  );
}
