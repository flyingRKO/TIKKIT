import type { ReactNode } from "react";
import { Footer } from "@/components/layout/footer";
import { Header } from "@/components/layout/header";

export default function MainLayout({ children }: { children: ReactNode }) {
  return (
    <>
      {/* 키보드 사용자가 Tab을 여러 번 누르지 않고 헤더 메뉴를 건너뛰게 한다. 포커스가 갔을 때만 보인다 */}
      <a
        href="#main-content"
        className="sr-only focus:not-sr-only focus:fixed focus:top-2 focus:left-2 focus:z-50 focus:rounded-md focus:bg-background focus:px-3 focus:py-2 focus:text-sm focus:font-medium focus:ring-2 focus:ring-ring"
      >
        본문 바로가기
      </a>
      <Header />
      {/* 각 페이지가 자기 <main>을 가지고 있어서, 건너뛰기 대상은 이 래퍼에 id를 준다. tabIndex={-1}은 링크로 이동할 때 포커스를 받기 위한 것 */}
      <div id="main-content" tabIndex={-1} className="flex flex-1 flex-col outline-none">
        {children}
      </div>
      <Footer />
    </>
  );
}
