package com.tikkit.api.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * 쿼리 카운트 테스트가 센 경로별 문장 수를 사람이 읽는 표로 모은다 (Task 042_1).
 * <p>
 * {@link QueryCounter}가 세고 테스트가 {@code isEqualTo(n)}으로 단정하면 <b>통과한 실행에는
 * 숫자가 아무데도 남지 않는다.</b> 그래서 리뷰어는 "선점이 몇 문장이어야 하는가"를 테스트
 * 소스를 열어야만 알고, PR에서는 숫자가 바뀐 것을 볼 수 없다. 007 523줄·008 633줄이 둘 다
 * 미배정으로 남긴 항목이 정확히 이것이다.
 * <p>
 * 여기 모은 표를 {@code build/query-counts.md}로 쓰고, CI의 {@code query-count} 잡이 그 파일을
 * {@code $GITHUB_STEP_SUMMARY}에 붙인다.
 * <p>
 * <b>이 파일은 게이트가 아니다.</b> 회귀를 막는 것은 테스트 안의 단정이고, 기준값을 외부 파일로
 * 빼지 않은 것도 의도다 — 기준값이 파일에 있으면 "베이스라인 갱신"이라는 통과 경로가 생긴다.
 * 테스트 코드 안의 숫자를 고치려면 왜 6이 7이 됐는지를 PR에 써야 한다.
 * <p>
 * 기록할 때마다 파일을 통째로 다시 쓴다. 엔트리가 열 몇 개라 비용이 없고, 셧다운 훅에
 * 의존하지 않으므로 <b>테스트가 중간에 깨져도 거기까지의 표가 남는다</b> — 실패한 CI에서
 * "몇이 몇으로 늘었나"를 보려면 그게 필요하다.
 * <p>
 * 한 JVM에서 돈 것만 모이므로, 세 클래스 중 일부만 실행하면 표도 그만큼만 나온다.
 * 전체 표는 {@code ./gradlew queryCountTest}가 만든다.
 */
public final class QueryCountReport {

    private static final Path OUTPUT = Path.of("build", "query-counts.md");

    /** 라벨의 자연 정렬이 그대로 표 순서다 — 같은 경로의 변형들이 붙어 보인다. */
    private static final Map<String, Long> COUNTS = new ConcurrentSkipListMap<>();

    private QueryCountReport() {
    }

    /**
     * 경로 하나의 문장 수를 기록한다.
     *
     * @param label   007 5.1절 표의 경로 표기를 그대로 쓴다 (예: {@code GET /performances (size 20)})
     * @param queries {@link QueryCounter}가 센 JDBC 문장 수
     */
    public static synchronized void record(String label, long queries) {
        COUNTS.put(label, queries);
        write();
    }

    private static void write() {
        StringBuilder table = new StringBuilder("### 쿼리 카운트 (Task 042_1)\n\n")
                .append("경로별 JDBC 문장 수다. 테스트 안의 단정이 게이트이고 이 표는 읽히기 위한 것이다.\n\n")
                .append("| 경로 | 문장 수 |\n|---|---:|\n");
        COUNTS.forEach((label, queries) -> table
                .append("| `").append(label).append("` | ").append(queries).append(" |\n"));

        try {
            Files.createDirectories(OUTPUT.getParent());
            Files.writeString(OUTPUT, table.toString());
        } catch (IOException e) {
            // 표를 못 쓴 것이 테스트 실패로 번지면 게이트의 신호가 흐려진다. 다만 조용히 넘기면
            // Step Summary가 빈 이유를 알 수 없으므로, 원인을 그대로 들고 올린다.
            throw new UncheckedIOException("쿼리 카운트 표를 " + OUTPUT.toAbsolutePath() + "에 쓰지 못했다", e);
        }
    }
}
