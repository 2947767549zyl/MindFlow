package com.mindflow.module.admin.repository;

import com.mindflow.module.admin.entity.RateLimitConfig;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RateLimitConfigRepository extends JpaRepository<RateLimitConfig, String> {
}
