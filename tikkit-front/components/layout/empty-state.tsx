import type { ReactNode } from "react";

// 목록이 비었을 때 쓰는 공통 블록. 다음에 할 수 있는 행동이 있으면 action으로 넘긴다.
export function EmptyState({ message, action }: { message: string; action?: ReactNode }) {
  return (
    <div className="flex flex-col items-center gap-4 py-16 text-center">
      <p className="text-muted-foreground">{message}</p>
      {action}
    </div>
  );
}
