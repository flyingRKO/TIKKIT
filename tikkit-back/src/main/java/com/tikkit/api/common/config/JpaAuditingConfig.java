package com.tikkit.api.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * BaseTimeEntity의 @CreatedDate/@LastModifiedDate를 동작시키기 위한 JPA Auditing 활성화.
 */
@Configuration
@EnableJpaAuditing
public class JpaAuditingConfig {
}