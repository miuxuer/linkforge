package com.miuxuer.oss;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OSS 自动配置测试。
 *
 * <p>用 {@link ApplicationContextRunner} 而不是 {@code @SpringBootTest}：
 * 前者只装配这一个自动配置类，不加载整个应用上下文。测 starter 就该这么测 ——
 * 起整个应用的话，任何一个业务 Bean 出问题都会让这个测试变红，
 * 你就分不清到底是 starter 坏了还是业务坏了。
 */
@DisplayName("OSS 自动配置")
class OssAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OssAutoConfiguration.class));

    @Test
    @DisplayName("配置齐全 → OssProperties 被注册并绑定上值")
    void propertiesShouldBind() {
        contextRunner
                .withPropertyValues(
                        "aliyun.oss.endpoint=https://oss-cn-beijing.aliyuncs.com",
                        "aliyun.oss.bucket-name=miuxuer",
                        "aliyun.oss.region=cn-beijing")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(OssProperties.class);

                    OssProperties properties = context.getBean(OssProperties.class);
                    assertThat(properties.getEndpoint()).isEqualTo("https://oss-cn-beijing.aliyuncs.com");
                    assertThat(properties.getBucketName()).isEqualTo("miuxuer");
                    assertThat(properties.getRegion()).isEqualTo("cn-beijing");
                });
    }

    @Test
    @DisplayName("没配 aliyun.oss.* → 上下文仍能起来，属性为 null")
    void missingConfigShouldNotBreakStartup() {
        // 这一条很重要：模块里只要有 spring-boot-starter-web 之类的依赖，
        // 自动配置就会在"还没配 OSS"的时候被加载。这时候不能抛异常，
        // 否则本地跑单测、或者某个不需要 OSS 的模块引了这个 starter，都会直接起不来。
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(OssProperties.class);
            assertThat(context.getBean(OssProperties.class).getBucketName()).isNull();
        });
    }
}
