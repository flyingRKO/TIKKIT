package com.tikkit.api.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * @Scheduled 배치 활성화. test 프로필에서는 꺼서 통합 테스트에 배치가 끼어들지 않게 한다.
 */
@Configuration
@EnableScheduling
@Profile("!test")
public class SchedulingConfig {
}
