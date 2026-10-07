package com.mindflow.config;

import com.mindflow.config.properties.UsageQuotaProperties;
import com.mindflow.module.auth.repository.UserRepository;
import com.mindflow.module.usage.service.UsageBalanceDashboardService;
import com.mindflow.module.usage.service.UsageBalanceQuotaService;
import com.mindflow.module.usage.service.UsageDashboardService;
import com.mindflow.module.usage.service.UsageQuotaService;
import com.mindflow.module.usage.service.UserTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 *
 * @author YiHui
 * @date 2026/3/19
 */
@Configuration
@RequiredArgsConstructor
public class QuotaConfiguration {

    private final UsageQuotaProperties usageQuotaProperties;

    /**
     * 根据实际的配置，启用对应的服务
     * @param stringRedisTemplate
     * @param userTokenService
     * @return
     */
    @Bean
    public UsageQuotaService usageQuotaService(StringRedisTemplate stringRedisTemplate,
                                               UserTokenService userTokenService) {
        if (usageQuotaProperties.isUseUserTokenBalance()) {
            return new UsageBalanceQuotaService(stringRedisTemplate, usageQuotaProperties, userTokenService);
        } else {
            return new UsageQuotaService(stringRedisTemplate, usageQuotaProperties);
        }
    }

    /**
     * 根据实际的配置，启用对应服务
     * @param userRepository
     * @param usageQuotaService
     * @param userTokenService
     * @return
     */
    @Bean
    public UsageDashboardService usageBalanceQuotaService(UserRepository userRepository,
                                                          UsageQuotaService usageQuotaService,
                                                          UserTokenService userTokenService) {
        if (usageQuotaProperties.isUseUserTokenBalance()) {
            return new UsageBalanceDashboardService(userRepository, usageQuotaService, userTokenService);
        } else {
            return new UsageDashboardService(userRepository, usageQuotaService);
        }
    }

}
