import { useCountDown } from '@/features/beta/useLandingMotion';

interface Props {
  solid: boolean;
  remaining: number;
  total: number;
  onApply: () => void;
}

export default function Nav({ solid, remaining, total, onApply }: Props) {
  // 정원에서 시작해 실제 잔여까지 떨어뜨린다. 방문자가 "이만큼 이미 나갔다"를 숫자가
  // 움직이는 동안 읽게 하는 게 목적이라, 완성된 숫자를 그냥 띄우면 의미가 없다.
  const shown = useCountDown(remaining, total);

  return (
    <nav className={`nav${solid ? ' solid' : ''}`}>
      <div className="logo">
        <i />
        <span>G2B MASTERS</span>
      </div>
      <div className="nav-slots">
        잔여 <b>{shown}개사</b> / {total}개사
      </div>
      <button type="button" className="btn sm" onClick={onApply}>
        베타 신청
      </button>
    </nav>
  );
}
