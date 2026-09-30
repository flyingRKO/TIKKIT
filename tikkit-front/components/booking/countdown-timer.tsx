"use client";

import { useEffect, useRef, useState } from "react";
import { cn } from "cn";

interface CountdownTimerProps {
  // 서버가 렌더 시점에 계산한 남은 시간(ms). 브라우저 시계와 무관하게 "지금부터 얼마 남았는지"만 받는다.
  initialRemainingMs: number;
  onExpire: () => void;
}

function formatRemaining(ms: number): string {
  const totalSeconds = Math.ceil(ms / 1000);
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${String(minutes).padStart(2, "0")}:${String(seconds).padStart(2, "0")}`;
}

// expiresAt(절대 시각)을 브라우저 시계와 직접 비교하면 사용자 PC 시계가 틀렸을 때 남은 시간이 어긋난다.
// 그래서 서버가 계산한 남은 시간을 받아, 마운트 시점 기준으로 마감 시각을 한 번만 잡고 센다.
// ⚠️ 서버 렌더~브라우저 마운트 사이의 네트워크 지연만큼은 오차가 남는다. 최종 판단은 BE(RESERVATION_EXPIRED)가 한다.
export function CountdownTimer({ initialRemainingMs, onExpire }: CountdownTimerProps) {
  const [deadline] = useState(() => Date.now() + initialRemainingMs);
  const [remainingMs, setRemainingMs] = useState(initialRemainingMs);
  // onExpire가 렌더마다 새로 만들어져도 interval을 다시 걸지 않도록 ref로 들고 있는다.
  const onExpireRef = useRef(onExpire);

  useEffect(() => {
    onExpireRef.current = onExpire;
  }, [onExpire]);

  useEffect(() => {
    const timer = setInterval(() => {
      const next = Math.max(0, deadline - Date.now());
      setRemainingMs(next);
      if (next === 0) {
        clearInterval(timer);
        onExpireRef.current();
      }
    }, 1000);
    return () => clearInterval(timer);
  }, [deadline]);

  const urgent = remainingMs <= 60_000;

  return (
    <div
      className={cn(
        "flex items-center justify-between rounded-xl border px-4 py-3",
        urgent ? "border-destructive/50 bg-destructive/5" : "bg-muted/40"
      )}
    >
      <span className="text-sm text-muted-foreground">좌석 선점 남은 시간</span>
      {/* 초 단위로 계속 바뀌므로 스크린리더가 매초 읽지 않게 aria-live는 달지 않는다 */}
      <span
        className={cn("text-2xl font-bold tabular-nums", urgent && "text-destructive")}
        suppressHydrationWarning
      >
        {formatRemaining(remainingMs)}
      </span>
    </div>
  );
}
