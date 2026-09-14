package com.miuxuer.linkforge.config;

import com.miuxuer.linkforge.interceptor.JwtTokenInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spring MVC 配置。
 *
 * <p>继承 {@code WebMvcConfigurer} 而不是 {@code WebMvcConfigurationSupport}：
 * 前者是在 Spring Boot 的默认配置上做加法，后者会<b>完全接管</b> MVC 配置、
 * 把 Boot 的自动配置（消息转换器、静态资源处理等）全部顶掉，是一个很常见的坑。
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfiguration implements WebMvcConfigurer {

    private final JwtTokenInterceptor jwtTokenInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(jwtTokenInterceptor)
                // 只拦 /api/**。短码跳转是 /{shortCode}，必须保持公开 ——
                // 短链要是还得先登录才能跳，那就不是短链了。
                .addPathPatterns("/api/**");
    }
}
