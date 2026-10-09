import type { ReactNode } from "react";

/**
 * 모바일에서 화면 하단에 고정되고, md 이상에서는 흐름 안에 그대로 남는 액션 영역.
 *
 * 공연 상세·결제·좌석 선택 세 화면이 같은 모양을 쓴다. 쓰는 쪽 페이지는 본문이 가려지지 않게
 * `pb-56 md:pb-8` 같은 아래 여백을 잡아줘야 한다.
 */
export function StickyCta({ children }: { children: ReactNode }) {
  return (
    <div className="fixed inset-x-0 bottom-0 z-20 flex flex-col gap-3 border-t bg-background p-4 md:static md:z-auto md:border-t md:bg-transparent md:px-0 md:pb-0">
      {children}
    </div>
  );
}
