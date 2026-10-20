import type { Metadata } from "next";
import localFont from "next/font/local";
import { ThemeProvider } from "@/components/layout/theme-provider";
import "./globals.css";

/**
 * 한글 폰트를 단일 서브셋 파일로 받는다 (Task 030_2 항목 5).
 *
 * 030_1은 CSS @import를 Pretendard의 dynamic subset으로 바꿔 전송량 76.8%를 줄였다. 그 뒤에도
 * 전송량 1순위는 여전히 폰트이고(홈 536KB 중 289KB), 요청이 1개 -> 8~11개로 늘어서
 * nginx가 HTTP/1.1이라 커넥션 6개 제한에 걸려 2라운드가 된다.
 *
 * 그래서 요청을 1개로 되돌려 본다. 파일은 scripts/build-font-subset.mjs가 만든다 —
 * dynamic subset 조각 중 번호가 큰(= 사용 빈도가 높은) 24개의 unicode-range를 합집합으로
 * 모아 2,620자를 남긴 것이고, 374,336B다.
 *
 * 거래 구도가 양방향이다. 요청은 11 -> 1로 줄지만 바이트는 홈 기준 289KB -> 365KB로 늘어난다.
 * 방문자가 어느 페이지를 보든 안 쓰는 글자까지 전부 받기 때문이다. 어느 쪽이 FCP에 더 센지는
 * 측정이 정한다 — 결과는 docs/improvements/009_2에 적는다.
 *
 * weight를 "45 920"으로 주는 것이 중요하다. 원본이 가변 폰트이고 dynamic subset CSS도 같은
 * 범위를 선언했다. 안 주면 next/font가 단일 굵기로 보고 굵기 변화가 합성으로 처리된다.
 */
const pretendard = localFont({
  src: "./fonts/PretendardVariable.subset.woff2",
  weight: "45 920",
  style: "normal",
  display: "swap",
  variable: "--font-pretendard",
  // 서브셋에 없는 글자는 아래 대체 글꼴로 떨어진다. 한국어 공연 제목·회원 이름에 빈도가
  // 낮은 글자가 들어오면 그 글자만 시스템 글꼴로 그려진다 — 정적 서브셋의 구조적 한계다.
  fallback: [
    "-apple-system",
    "BlinkMacSystemFont",
    "system-ui",
    "Apple SD Gothic Neo",
    "Noto Sans KR",
    "Malgun Gothic",
    "sans-serif",
  ],
});

export const metadata: Metadata = {
  title: "TIKKIT",
  description: "티켓 예매/관리 서비스",
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="ko" className={`${pretendard.variable} h-full antialiased`} suppressHydrationWarning>
      <body className="min-h-full flex flex-col">
        <ThemeProvider>{children}</ThemeProvider>
      </body>
    </html>
  );
}
