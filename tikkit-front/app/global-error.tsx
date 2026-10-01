"use client";

import { useEffect } from "react";
import "./globals.css";

// 루트 layout 자체가 터졌을 때만 쓰인다. 이 파일이 루트 layout을 대체하므로 html/body와 전역 스타일을 직접 가져온다.
// ThemeProvider도 없어서 시스템 다크모드는 따라가지 못한다 — 이 화면은 최후의 폴백이라 의도적으로 단순하게 뒀다.
export default function GlobalError({
  error,
  unstable_retry,
}: {
  error: Error & { digest?: string };
  unstable_retry: () => void;
}) {
  useEffect(() => {
    console.error(error);
  }, [error]);

  return (
    <html lang="ko" className="h-full antialiased">
      <body className="flex min-h-full flex-col items-center justify-center gap-4 px-6 text-center">
        <h1 className="text-2xl font-bold">문제가 발생했습니다</h1>
        <p className="max-w-md text-muted-foreground">잠시 후 다시 시도해 주세요.</p>
        <button
          type="button"
          onClick={() => unstable_retry()}
          className="rounded-lg bg-primary px-4 py-2 text-sm font-medium text-primary-foreground"
        >
          다시 시도
        </button>
      </body>
    </html>
  );
}
