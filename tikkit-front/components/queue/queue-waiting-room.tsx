"use client";

import { useEffect, useRef, useState } from "react";
import { QueueLeaveButton } from "@/components/queue/queue-leave-button";
import { QueueProgressBar } from "@/components/queue/queue-progress-bar";
import { Button } from "@/components/ui/button";
import { formatDateTime } from "@/lib/format-date";
import {
  formatWaitSeconds,
  POLL_FAILURE_NOTICE_THRESHOLD,
  toProgressRatio,
  toRetrySeconds,
} from "@/lib/queue-rules";
import type { QueueStatusResponse } from "@/types/api";

interface QueueWaitingRoomProps {
  // 서버 컴포넌트가 진입(POST .../entries) 응답을 그대로 넘겨준다. 마운트 직후 추가 왕복이 없다.
  initialStatus: QueueStatusResponse;
  scheduleId: number;
  performanceId: number;
  // 입장하면 갈 곳. 서버가 고정 템플릿으로 만든 값만 쓴다 — 클라이언트가 조립한 문자열을
  // 이동 함수에 넘기지 않는다.
  seatsPath: string;
  loginPath: string;
  performanceTitle: string | null;
  showAt: string | null;
}

interface Progress {
  // 진행바의 분모. 처음 받은 내 순번으로 고정한다 (근거는 lib/queue-rules.ts의 toProgressRatio).
  anchor: number;
  // 지금까지 본 최대 비율. 바가 되감기지 않게 한다.
  peak: number;
}

export function QueueWaitingRoom({
  initialStatus,
  scheduleId,
  performanceId,
  seatsPath,
  loginPath,
  performanceTitle,
  showAt,
}: QueueWaitingRoomProps) {
  const [status, setStatus] = useState(initialStatus);
  const [progress, setProgress] = useState<Progress>(() => {
    const anchor = initialStatus.position ?? 1;
    return { anchor, peak: toProgressRatio(anchor, anchor) };
  });
  const [connectionLost, setConnectionLost] = useState(false);

  // 폴링을 멈춰야 하는 상태(입장·만료·이동 중)를 리렌더와 무관하게 들고 있는다.
  const stoppedRef = useRef(false);
  const timerRef = useRef<number | null>(null);
  const failuresRef = useRef(0);

  const statusPath = `/queue/${scheduleId}/status`;

  useEffect(() => {
    let cancelled = false;

    function clearTimer() {
      if (timerRef.current !== null) {
        window.clearTimeout(timerRef.current);
        timerRef.current = null;
      }
    }

    // setInterval이 아니라 setTimeout 체인이다 — 간격을 매 응답이 지시하기 때문이다.
    function schedule(seconds: number) {
      clearTimer();
      timerRef.current = window.setTimeout(poll, Math.max(1, seconds) * 1000);
    }

    function apply(next: QueueStatusResponse) {
      setStatus(next);

      if (next.state === "ADMITTED" || next.state === "DISABLED") {
        stoppedRef.current = true;
        clearTimer();
        // 클라이언트 라우터 이동이 아니라 하드 내비게이션이다. 좌석 페이지는 조금 전 403으로
        // 우리를 여기로 보낸 그 경로라서 라우터 캐시에 옛 결과가 남아 있을 수 있고, 그걸
        // 재사용하면 입장했는데도 대기열로 다시 튕기는 루프가 된다. 서버에서 입장권을 다시
        // 검증받아야 한다.
        window.location.assign(seatsPath);
        return;
      }

      if (next.state === "EXPIRED") {
        stoppedRef.current = true;
        clearTimer();
        return;
      }

      if (next.position !== null) {
        const position = next.position;
        setProgress((prev) => {
          // 큐 키가 축출되거나 재진입해서 꼬리로 갔다면 분모를 다시 잡는다.
          if (position > prev.anchor) {
            return { anchor: position, peak: toProgressRatio(position, position) };
          }
          return {
            anchor: prev.anchor,
            peak: Math.max(prev.peak, toProgressRatio(prev.anchor, position)),
          };
        });
      }

      // 여기가 "간격을 하드코딩하지 않는다"의 유일한 지점이다.
      schedule(next.pollAfterSeconds);
    }

    async function poll() {
      if (cancelled || stoppedRef.current) {
        return;
      }
      try {
        const response = await fetch(statusPath, { cache: "no-store" });
        if (response.status === 401) {
          stoppedRef.current = true;
          clearTimer();
          window.location.assign(loginPath);
          return;
        }
        if (!response.ok) {
          throw new Error(`queue status ${response.status}`);
        }
        const next = (await response.json()) as QueueStatusResponse;
        if (cancelled) {
          return;
        }
        failuresRef.current = 0;
        setConnectionLost(false);
        apply(next);
      } catch {
        if (cancelled) {
          return;
        }
        // 통신이 실패해도 큐에서 빠지는 건 아니므로 화면을 버리지 않고 백오프로 계속 시도한다.
        failuresRef.current += 1;
        if (failuresRef.current >= POLL_FAILURE_NOTICE_THRESHOLD) {
          setConnectionLost(true);
        }
        schedule(toRetrySeconds(failuresRef.current));
      }
    }

    if (initialStatus.pollAfterSeconds > 0) {
      schedule(initialStatus.pollAfterSeconds);
    }

    // 브라우저가 백그라운드 탭의 타이머를 1분에 1회로 스로틀한다. 돌아왔을 때 순번이 한참
    // 낡아 있으므로 즉시 한 번 확인한다.
    function handleVisibilityChange() {
      if (document.visibilityState === "visible" && !stoppedRef.current) {
        void poll();
      }
    }
    document.addEventListener("visibilitychange", handleVisibilityChange);

    // cleanup에서 이탈(DELETE .../entries)을 보내지 않는다. StrictMode가 마운트 시 effect를
    // 한 번 접었다 펴므로, 여기에 이탈을 두면 개발 환경에서 사용자가 조용히 줄에서 빠진다 —
    // 진입은 서버 렌더에서 이미 끝났으니 다시 줄 서지도 않고, 증상은 "dev에서만 순번이 안 보임"
    // 하나로만 나타난다. 타이머와 리스너만 정리한다.
    return () => {
      cancelled = true;
      clearTimer();
      document.removeEventListener("visibilitychange", handleVisibilityChange);
    };
  }, [statusPath, loginPath, seatsPath, initialStatus.pollAfterSeconds]);

  const expired = status.state === "EXPIRED";
  const position = status.position ?? progress.anchor;

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-col gap-1">
        <h1 className="text-xl font-bold md:text-2xl">
          {expired ? "대기 순번이 만료되었습니다" : "예매 대기 중입니다"}
        </h1>
        {performanceTitle !== null && (
          <p className="min-w-0 break-words text-sm text-muted-foreground">
            {performanceTitle}
            {showAt !== null && ` · ${formatDateTime(showAt)}`}
          </p>
        )}
      </div>

      {expired ? (
        <div className="flex flex-col gap-4 rounded-xl border p-5">
          <p className="text-sm text-muted-foreground">
            대기 정보가 사라졌거나 입장 유효 시간이 지났습니다. 다시 참여하면 맨 뒤에서
            시작합니다.
          </p>
          {/* 같은 URL이라 Link로는 아무 일도 일어나지 않는다. 서버 컴포넌트를 다시 돌려야
              진입 API가 호출되므로 새로고침으로 재진입한다. */}
          <Button type="button" size="lg" onClick={() => window.location.reload()}>
            다시 대기열에 참여하기
          </Button>
        </div>
      ) : (
        <div className="flex flex-col gap-5 rounded-xl border p-5">
          <div className="flex items-end justify-between gap-4">
            <div className="flex flex-col gap-1">
              <span className="text-sm text-muted-foreground">내 순번</span>
              <span className="text-4xl font-bold tabular-nums">
                {position.toLocaleString("ko-KR")}
                <span className="ml-1 text-base font-normal text-muted-foreground">번째</span>
              </span>
            </div>
            <div className="flex flex-col items-end gap-1">
              <span className="text-sm text-muted-foreground">예상 대기시간</span>
              <span className="text-lg font-semibold tabular-nums">
                {formatWaitSeconds(status.estimatedWaitSeconds)}
              </span>
            </div>
          </div>

          <QueueProgressBar
            ratio={progress.peak}
            position={position}
            totalWaiting={status.totalWaiting}
          />

          {/* 숫자는 2~10초마다 바뀌므로 aria-live를 달지 않는다. 상태 문구에만 달아서
              바뀔 때만 읽게 한다 (components/booking/countdown-timer.tsx의 판단과 같다). */}
          <p role="status" className="text-sm text-muted-foreground">
            {position === 1
              ? "곧 입장합니다. 이 화면을 닫지 마세요."
              : `앞에 ${(position - 1).toLocaleString("ko-KR")}명이 기다리고 있습니다.`}
          </p>

          {connectionLost && (
            <p className="rounded-md border border-destructive/40 bg-destructive/5 p-3 text-sm">
              연결이 불안정합니다. 순번은 유지되고 있으니 이 화면을 닫지 말고 기다려 주세요.
            </p>
          )}
        </div>
      )}

      <div className="flex flex-col gap-3 text-sm text-muted-foreground">
        <p>
          새로고침해도 순번은 그대로입니다. 다만 이 탭을 닫으면 순번이 사라지고, 다시 참여하면
          맨 뒤에서 시작합니다.
        </p>
        {!expired && <QueueLeaveButton scheduleId={scheduleId} performanceId={performanceId} />}
      </div>
    </div>
  );
}
