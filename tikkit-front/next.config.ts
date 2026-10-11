import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // Dockerfile(Task 024)이 .next/standalone만 복사해 최소 이미지를 만들기 위한 설정.
  // node_modules 전체 대신 실제로 쓰이는 파일만 추적해서 담는다.
  output: "standalone",

  // 모노레포 루트에 package-lock.json이 있어서(의존성 없는 빈 파일) Next가 그쪽을
  // 워크스페이스 루트로 추정한다. 그러면 standalone 산출물이 .next/standalone/tikkit-front/
  // 아래로 한 겹 중첩되고 Dockerfile의 COPY 경로가 어긋난다.
  // 이 폴더를 추적 루트로 고정해서 레이아웃이 밖의 lockfile에 흔들리지 않게 한다.
  outputFileTracingRoot: __dirname,
};

export default nextConfig;
