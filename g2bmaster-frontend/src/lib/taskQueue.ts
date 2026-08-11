/**
 * 동시 실행 제한기.
 *
 * 브라우저는 오리진당 연결을 6개쯤만 연다. 화면이 20건을 한꺼번에 요청하면 나머지는
 * 브라우저 큐에 쌓이는데, **axios 의 타임아웃은 큐에 있는 동안에도 흐른다.** 그래서
 * 실제로 서버가 일을 시작하기도 전에 마감이 지나 "분석 실패"가 된다 —
 * 20건 동시 재현에서 7건이 60초를 넘겼고, 그중 셋은 서버가 300초에도 못 끝냈다.
 *
 * 여기서 **요청을 내보내기 전에** 붙잡으면 대기 시간이 마감에 포함되지 않는다.
 * 순서를 바꾸는 것뿐인데 마감의 의미가 "서버가 답하는 데 걸린 시간"으로 되돌아온다.
 */
export function createLimiter(max: number) {
  if (max < 1) throw new Error('동시 실행 수는 1 이상이어야 합니다');

  let active = 0;
  const waiting: Array<() => void> = [];

  const release = () => {
    active -= 1;
    waiting.shift()?.();
  };

  return function run<T>(task: () => Promise<T>): Promise<T> {
    return new Promise<T>((resolve, reject) => {
      const start = () => {
        active += 1;
        // task() 자체가 동기적으로 던질 수 있다. 그러면 release 가 안 돌아
        // 슬롯이 영영 잠긴다 — 큐 전체가 멈춘다.
        let settled: Promise<T>;
        try {
          settled = task();
        } catch (error) {
          release();
          reject(error);
          return;
        }
        settled.then(resolve, reject).finally(release);
      };

      if (active < max) start();
      else waiting.push(start);
    });
  };
}
