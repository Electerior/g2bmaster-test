/*
 * 첨부문서 전수조사 — 원본 scanAttachments(app.js:3791).
 *
 * 파일 내 키워드 검색이나 '입찰 불가 조항 자동 제외' 가 켜져 있으면, 서버는 페이지 하나가
 * 아니라 **후보 전체**(pageNo=0)를 주고 화면이 그것을 50건씩 잘라 스캔 API 로 보낸다.
 * 한 번에 다 보내면 서버가 수백 개의 PDF·HWPX 를 동시에 열게 되고, 그 요청은 타임아웃 안에
 * 못 끝난다 — 잘라 보내는 것이 요점이다.
 *
 * 스캔 결과는 두 가지다.
 *  - matches   : 키워드가 걸린 행 → 표 아래에 발췌 행이 붙는다
 *  - exclusions: 경쟁 제한 조항이 있는 행 → 체크가 켜져 있으면 목록에서 빼고,
 *                꺼져 있으면 '?' 표식으로 사유를 보여 준다(제외와 표시는 짝이다)
 */
import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { isAiEnabled, scanAttachments, type DecoratedRow, type NoticeSearchQuery } from '@/api';
import type { NoticeTableKind } from '@/domain/columns';
import { collectFileEntries, rowKeyForItem, type ScannedRow } from './rows';

const CHUNK_SIZE = 50;

export interface ScanProgress {
  done: number;
  total: number;
  /** '입찰 불가 조항' · '파일 키워드' 중 지금 돌고 있는 것. */
  tasks: string[];
}

export interface ScanOutcome {
  /** 화면에 보일 행(제외가 켜져 있으면 걸러진 뒤). */
  rows: ScannedRow[];
  matchCount: number;
  exclusionCount: number;
  /** '제조사 확약서 3건 · 타 업체 참여 금지 1건' 형태의 사유 집계. */
  reasonNote: string;
  scanned: number;
  total: number;
  cacheHits: number;
}

interface UseAttachmentScanArgs {
  kind: NoticeTableKind;
  /** 목록 조회에 쓴 질의 — 같은 질의면 같은 후보 집합이므로 캐시 키로 쓴다. */
  query: NoticeSearchQuery;
  items: DecoratedRow[] | undefined;
  fileKeywords: string[];
  excludeBlockingClauses: boolean;
  /** 블로킹 판정을 서버에 요청할지. 체크가 꺼져 있어도 '?' 표시를 위해 받아 둔다. */
  scanBlocking: boolean;
  enabled: boolean;
}

export function useAttachmentScan({
  kind,
  query,
  items,
  fileKeywords,
  excludeBlockingClauses,
  scanBlocking,
  enabled,
}: UseAttachmentScanArgs) {
  // 진행률은 쿼리 결과가 아니라 "지금 몇 번째 묶음인지"라 state 로 따로 든다.
  const [progress, setProgress] = useState<ScanProgress | null>(null);

  const result = useQuery<ScanOutcome>({
    queryKey: [
      'attachment-scan',
      kind,
      query,
      fileKeywords.join(','),
      excludeBlockingClauses,
      scanBlocking,
    ],
    // 스캔(POST /api/scan-attachments)은 첨부 파싱에 의존한다 — 백엔드 이식 전이라 호출하면
    // 500 이다. AI 플래그가 꺼져 있으면 아예 돌리지 않는다(그 컨트롤 자체도 '준비 중'이다).
    enabled: enabled && isAiEnabled() && Array.isArray(items),
    // 스캔은 서버에서 파일을 여는 작업이라 값이 비싸다. 조건이 그대로면 다시 돌리지 않는다.
    staleTime: Infinity,
    queryFn: async (): Promise<ScanOutcome> => {
      const source = items ?? [];
      const total = source.length;
      const tasks = [
        excludeBlockingClauses ? '입찰 불가 조항' : '',
        fileKeywords.length ? `파일 키워드 ${fileKeywords.join(', ')}` : '',
      ].filter(Boolean);

      // 행마다 스캔 요청과 응답을 짝지을 id 를 붙인다.
      const withIds: ScannedRow[] = source.map((item, index) => ({
        ...item,
        __rowId: rowKeyForItem(item, index, kind),
      }));

      const scans = withIds.map((item) => ({
        id: String(item.__rowId ?? ''),
        fileEntries: collectFileEntries(item),
        bidNtceNo: String(item.bidNtceNo ?? item.bfSpecRgstNo ?? item.prdctClfcNo ?? ''),
        bidNtceSqNo: String(item.bidNtceSqNo ?? item.bidNtceOrd ?? '000'),
        _type: String(item._type ?? ''),
        _tab: kind,
        _version: String(item.chgDt ?? item.rgstDt ?? ''),
      }));

      const matches = new Map<string, { matchedKeywords: string[]; excerpt: string }>();
      const exclusions = new Map<string, { reasons: string[]; excerpt: string }>();
      let scanned = 0;
      let cacheHits = 0;

      for (let offset = 0; offset < scans.length; offset += CHUNK_SIZE) {
        const chunk = scans.slice(offset, offset + CHUNK_SIZE);
        setProgress({ done: Math.min(offset + chunk.length, total), total, tasks });
        const data = await scanAttachments({
          scans: chunk,
          fileKeywords,
          excludeBlockingClauses,
          scanBlocking,
          candidateCount: total,
          immediateLimit: chunk.length,
          warmLimit: chunk.length,
        });
        for (const match of data.matches ?? []) {
          matches.set(match.id, {
            matchedKeywords: match.matchedKeywords ?? [],
            excerpt: match.excerpt ?? '',
          });
        }
        for (const exclusion of data.exclusions ?? []) {
          exclusions.set(exclusion.id, {
            reasons: Array.isArray(exclusion.reasons) ? exclusion.reasons : [],
            excerpt: exclusion.excerpt ?? '',
          });
        }
        scanned += Number(data.scanned ?? 0);
        cacheHits += Number(data.cacheHits ?? 0);
      }
      setProgress(null);

      const decorated: ScannedRow[] = withIds.map((item) => {
        const id = String(item.__rowId ?? '');
        const match = matches.get(id);
        const exclusion = exclusions.get(id);
        const next: ScannedRow = match
          ? { ...item, _fileExcerpt: match.excerpt, _matchedKeywords: match.matchedKeywords }
          : item;
        // 체크 해제 상태에서만 '?' 표식용 사유·발췌를 붙인다(체크 시엔 어차피 제외된다).
        if (exclusion && !excludeBlockingClauses) {
          return { ...next, _blocking: exclusion };
        }
        return next;
      });

      const rows = excludeBlockingClauses
        ? decorated.filter((item) => !exclusions.has(String(item.__rowId ?? '')))
        : decorated;

      const reasonCounts = new Map<string, number>();
      for (const exclusion of exclusions.values()) {
        for (const reason of exclusion.reasons) {
          reasonCounts.set(reason, (reasonCounts.get(reason) ?? 0) + 1);
        }
      }

      return {
        rows,
        matchCount: matches.size,
        exclusionCount: excludeBlockingClauses ? exclusions.size : 0,
        reasonNote: [...reasonCounts]
          .map(([reason, count]) => `${reason} ${count}건`)
          .join(' · '),
        scanned,
        total,
        cacheHits,
      };
    },
  });

  return { ...result, progress };
}
