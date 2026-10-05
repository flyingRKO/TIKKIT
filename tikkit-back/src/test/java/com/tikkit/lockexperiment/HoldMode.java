package com.tikkit.lockexperiment;

/**
 * 선점 경로를 어떤 보호 장치로 감쌀지 고르는 값 (Task 020 비교 실험 전용).
 * <p>
 * 어느 모드든 재고 차감과 상태 전이는 Task 019의 조건부 UPDATE를 그대로 쓴다. 바뀌는 건
 * <b>그 위에 분산 락을 어떻게 얹는가</b>뿐이다.
 */
public enum HoldMode {

    /** 락 없음. Task 019가 채택한 현재 코드 그대로 — 두 축의 기준선이다. */
    PLAIN,

    /** 등급 키 분산 락, 트랜잭션 <b>밖</b>. 재고 경쟁(축 A)에 대응하는 교과서적 적용. */
    LOCK_GRADE,

    /** 등급 키 분산 락, 트랜잭션 <b>안</b> (커밋 전 해제). 축 A에서 함정이 드러나는지 보는 arm. */
    LOCK_GRADE_IN_TX,

    /** (회원, 등급) 키 분산 락, 트랜잭션 <b>밖</b>. 중복 선점 가드(축 B)의 해법 후보. */
    LOCK_MEMBER_GRADE,

    /** (회원, 등급) 키 분산 락, 트랜잭션 <b>안</b> (커밋 전 해제). 축 B의 함정 arm. */
    LOCK_MEMBER_GRADE_IN_TX,

    /**
     * 위와 같지만 해제와 커밋 사이에 고정 지연을 넣는다.
     * <p>
     * 함정의 경쟁 창이 "unlock 이후 commit 완료까지"로 수백 µs 수준이라 운에 따라 0건이 나올 수 있다.
     * 창을 결정론적으로 벌려서 "좁아서 안 터진 것"과 "구조적으로 안전한 것"을 구분한다.
     */
    LOCK_MEMBER_GRADE_IN_TX_DELAYED;

    public boolean usesDistributedLock() {
        return this != PLAIN;
    }
}
