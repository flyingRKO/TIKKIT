import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // Dockerfile(Task 024)이 .next/standalone만 복사해 최소 이미지를 만들기 위한 설정.
  // node_modules 전체 대신 실제로 쓰이는 파일만 추적해서 담는다.
  output: "standalone",

  // Next는 lockfile을 찾아 워크스페이스 루트를 추정한다. 모노레포 루트에 npm이 만들어 둔
  // 빈 package-lock.json이 있으면 그쪽이 루트로 잡히는데, 그러면 standalone 산출물이
  // .next/standalone/tikkit-front/ 아래로 한 겹 중첩되고 Dockerfile의 COPY 경로가 어긋난다.
  // 이 폴더를 추적 루트로 고정해서 레이아웃이 밖의 lockfile에 흔들리지 않게 한다.
  outputFileTracingRoot: __dirname,

  turbopack: {
    // 위와 같은 추정이 **dev 번들러에도** 따로 적용된다. outputFileTracingRoot는 빌드
    // 산출물 레이아웃만 고정하고 Turbopack의 모듈 해석 루트는 건드리지 않는다.
    //
    // 고정하지 않으면 루트가 모노레포 루트로 잡혀서 거기서 tailwindcss를 찾고, 그 위치에는
    // node_modules가 없으니 **CSS 컴파일이 요청마다 실패한다.** 그런데 Next dev는 CSS
    // 컴파일마다 node PostCSS 워커를 띄우고 실패한 워커를 회수하지 않아서, 요청·HMR 틱마다
    // 워커가 쌓인다. 실제로 워커 11,193개가 생성되고 4,847개가 살아남아 13.8GB를 먹고
    // fork 실패·OOM까지 갔다(2026-10-23). 빌드는 정상이라 증상이 dev에서만 나타난다.
    //
    // 루트의 빈 lockfile은 같은 날 삭제했지만, 누가 모노레포 루트에서 npm 명령을 한 번만
    // 돌리면 다시 생긴다. 그래서 파일 삭제에 의존하지 않고 여기서 못 박는다.
    root: __dirname,
  },
};

export default nextConfig;
