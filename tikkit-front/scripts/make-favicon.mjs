/**
 * `app/icon.svg`에서 `app/favicon.ico`를 만든다 (Task 041).
 *
 * ★ 왜 스크립트로 두는가 ★
 *   아이콘이 두 파일로 나뉘는 순간 **둘이 어긋나는 함정**이 생긴다 — `icon.svg`의 색이나
 *   모양을 고치고 `.ico`를 안 고치면, 현대 브라우저와 구형 브라우저가 서로 다른 로고를
 *   보여주는데 **그걸 알아챌 방법이 없다**(구형 경로를 평소에 아무도 안 본다).
 *   생성 절차를 코드로 남겨 "svg를 고쳤으면 이걸 다시 돌린다"가 되게 한다.
 *
 * ★ 왜 `.ico`를 두는가 ★
 *   009_1이 "폴백을 두면 양쪽 요청이 다 나가서 절감이 사라지므로 의도적으로 뺐다"로
 *   기각했던 항목이고, Task 041이 HTTP/2 위에서 재판정해 **채택**했다. 양쪽이 다 나가는 것은
 *   사실이지만(실측: `Other` 1개 663B → 2개 5,139B) **절감이 사라지지는 않고**(009_1의
 *   before 26,269B 대비 80% 유지) **FCP 중앙값이 483 → 483ms로 변하지 않는다.** h2에서는
 *   커넥션 경쟁이 없어 요청 1개 추가의 지연 비용이 0에 가깝다. 근거는 018 문서 5절.
 *
 * ★ PNG-in-ICO를 쓴다 ★
 *   ICO 컨테이너는 BMP 대신 PNG를 그대로 품을 수 있다. 그래서 **PNG를 디코드할 필요가 없고**
 *   의존성도 늘지 않는다(Playwright는 이미 e2e용으로 있다). 목적이 구형 Safari 폴백인데
 *   PNG-in-ICO는 macOS·Windows 양쪽에서 오래 지원돼 왔다.
 *
 * 실행:
 *   cd tikkit-front && node scripts/make-favicon.mjs
 */
import { readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { chromium } from "playwright";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const svgPath = join(root, "app", "icon.svg");
const icoPath = join(root, "app", "favicon.ico");

/** 32x32. 16x16까지 넣으면 바이트가 두 배인데 구형 폴백 용도로는 32가 있으면 축소돼 쓰인다. */
const SIZE = 32;

const svg = readFileSync(svgPath, "utf8");

const browser = await chromium.launch();
try {
  const page = await browser.newPage({
    viewport: { width: SIZE, height: SIZE },
    deviceScaleFactor: 1,
  });
  // svg를 그대로 넣고 여백만 없앤다. icon.svg의 viewBox가 0 0 32 32라 1:1로 떨어진다.
  await page.setContent(
    `<!doctype html><style>html,body{margin:0;padding:0}svg{display:block;width:${SIZE}px;height:${SIZE}px}</style>${svg}`
  );
  const png = await page.screenshot({ omitBackground: true });

  // ICONDIR(6B) + ICONDIRENTRY(16B) + PNG
  const dir = Buffer.alloc(6);
  dir.writeUInt16LE(0, 0); // reserved
  dir.writeUInt16LE(1, 2); // type: 1 = icon
  dir.writeUInt16LE(1, 4); // count

  const entry = Buffer.alloc(16);
  entry[0] = SIZE; // width
  entry[1] = SIZE; // height
  entry[2] = 0; // 팔레트 색 수 (0 = 256 이상)
  entry[3] = 0; // reserved
  entry.writeUInt16LE(1, 4); // color planes
  entry.writeUInt16LE(32, 6); // bits per pixel
  entry.writeUInt32LE(png.length, 8); // bytesInRes
  entry.writeUInt32LE(dir.length + entry.length, 12); // imageOffset

  const ico = Buffer.concat([dir, entry, png]);
  writeFileSync(icoPath, ico);
  console.log(
    `favicon.ico 생성: ${ico.length.toLocaleString()}B (PNG ${png.length.toLocaleString()}B + 헤더 22B)`
  );
} finally {
  await browser.close();
}
