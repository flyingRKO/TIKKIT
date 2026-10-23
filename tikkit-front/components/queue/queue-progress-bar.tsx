import { MAX_PROGRESS } from "@/lib/queue-rules";

interface QueueProgressBarProps {
  // 0~1. 계산은 lib/queue-rules.ts의 toProgressRatio가 하고 여기서는 그리기만 한다.
  ratio: number;
  position: number;
  totalWaiting: number;
}

/**
 * 대기열 진행바 (Task 032). 상태를 갖지 않는 프레젠테이션 컴포넌트다.
 *
 * components/ui에 progress를 설치하지 않았다. 의존성을 아끼려는 게 아니라, Task 030_1이
 * @base-ui/react/button을 네이티브 button으로 되돌린 것과 같은 판단이다 — div 두 개로
 * 끝나는 것에 컴포넌트 계층을 올릴 이유가 없다(seat-map.tsx가 raw SVG를 쓰는 것도 같다).
 */
export function QueueProgressBar({ ratio, position, totalWaiting }: QueueProgressBarProps) {
  const percent = Math.round(ratio * 100);

  return (
    <div
      role="progressbar"
      aria-valuenow={percent}
      aria-valuemin={0}
      aria-valuemax={100}
      // 비율보다 "몇 명 중 몇 번째"가 사용자에게 의미 있는 정보다
      aria-valuetext={`대기 ${totalWaiting.toLocaleString("ko-KR")}명 중 ${position.toLocaleString("ko-KR")}번째`}
      className="h-3 w-full overflow-hidden rounded-full bg-muted"
    >
      <div
        className="h-full rounded-full bg-linear-to-r from-primary to-highlight transition-[width] duration-500 ease-out"
        style={{ width: `${percent}%` }}
      />
      {/* 상한(95%)에 닿았다는 건 순번이 1번이라는 뜻이다 — 숫자 쪽에서 따로 말한다 */}
      {ratio >= MAX_PROGRESS && <span className="sr-only">입장 직전입니다</span>}
    </div>
  );
}
