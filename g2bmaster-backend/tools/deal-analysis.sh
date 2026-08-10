#!/usr/bin/env bash
# 저장 공고 일괄 딜 분석 + 저장된 결과 조회.
#
#   tools/deal-analysis.sh backfill            # 저장 공고 전부 deep 분석 → DB 저장
#   tools/deal-analysis.sh backfill 100        # 최근 100건만
#   tools/deal-analysis.sh list                # 저장된 딜 분석 요약 (DB에서)
#   tools/deal-analysis.sh show <공고번호>      # 한 건의 저장된 딜 분석 JSON 전체
#   tools/deal-analysis.sh count               # 저장 건수
#
# 환경변수(기본값):
#   BACKEND=http://localhost:8080     백엔드 주소
#   APP_API_KEY=                      backfill 은 앱 키가 필요할 수 있다(운영). 개발 모드면 불필요
#   MYSQL: docker compose 의 g2b-mysql 컨테이너를 통해 조회한다(3307 로컬 포트가 아니라 exec)

set -euo pipefail

BACKEND="${BACKEND:-http://localhost:8080}"
MYSQL_CONTAINER="${MYSQL_CONTAINER:-g2b-mysql}"
MYSQL_DB="${MYSQL_DATABASE:-g2b}"
MYSQL_USER="${MYSQL_USER:-g2b}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-g2b}"

cmd="${1:-help}"

# DB 조회는 compose MySQL 컨테이너 안에서 실행한다 — 호스트에 mysql 클라이언트가 없어도 된다.
db() {
	docker exec -i "${MYSQL_CONTAINER}" mysql -u"${MYSQL_USER}" -p"${MYSQL_PASSWORD}" \
		--default-character-set=utf8mb4 -N -e "$1" "${MYSQL_DB}"
}

auth_header() {
	[[ -n "${APP_API_KEY:-}" ]] && printf 'X-API-Key: %s' "${APP_API_KEY}" || printf ''
}

case "${cmd}" in
	backfill)
		limit="${2:-500}"
		echo "저장 공고 일괄 딜 분석 시작 (최근 ${limit}건, deep) — 오래 걸립니다…"
		# deep 분석은 공고당 규격서 다운로드+LLM+다나와라 느리다. 타임아웃을 넉넉히.
		curl -sS --max-time 3600 -X POST \
			"${BACKEND}/api/deal-analysis/backfill?limit=${limit}&deep=true" \
			${APP_API_KEY:+-H "$(auth_header)"} \
			| python3 -m json.tool 2>/dev/null || echo "(백엔드 응답 확인 실패 — 백엔드가 떠 있는지 보세요)"
		;;

	list)
		echo "저장된 딜 분석 (bid_ntce_no · deep · 분석시각):"
		db "SELECT bid_ntce_no, deep, created_at,
		           JSON_EXTRACT(result_json, '\$.deal.cost') AS cost,
		           JSON_EXTRACT(result_json, '\$.estimatedUnitCost.mid') AS est_mid
		    FROM deal_analysis_result ORDER BY created_at DESC LIMIT 200;" \
			| column -t -s $'\t' 2>/dev/null || true
		;;

	show)
		no="${2:?사용법: deal-analysis.sh show <공고번호>}"
		# 같은 공고의 여러 입력 해시 중 가장 최근 것.
		db "SELECT result_json FROM deal_analysis_result
		    WHERE bid_ntce_no = '${no}' ORDER BY created_at DESC LIMIT 1;" \
			| python3 -m json.tool 2>/dev/null || echo "(그 공고의 저장된 딜 분석이 없습니다)"
		;;

	count)
		echo -n "저장된 딜 분석 건수: "
		db "SELECT COUNT(*) FROM deal_analysis_result;"
		;;

	*)
		sed -n '2,20p' "$0" | sed 's/^# \?//'
		;;
esac
