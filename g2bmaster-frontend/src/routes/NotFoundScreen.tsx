/*
 * 404. 주소가 곧 화면 상태이므로 오타 난 링크나 옛 북마크가 들어올 수 있다.
 * 빈 화면 대신 돌아갈 길을 준다.
 */
import { Link } from 'react-router-dom';
import { DEFAULT_ROUTE } from './routePaths';

export function NotFoundScreen() {
  return (
    <section className="panel" aria-label="페이지를 찾을 수 없음">
      <h2 className="panel-title">페이지를 찾을 수 없습니다</h2>
      <div className="empty-msg">
        요청하신 주소에 해당하는 화면이 없습니다.
        <div style={{ marginTop: 16 }}>
          <Link className="btn-home" to={DEFAULT_ROUTE}>
            처음 화면으로
          </Link>
        </div>
      </div>
    </section>
  );
}
