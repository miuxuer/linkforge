package com.miuxuer.oss;

import com.aliyun.sdk.service.oss2.OSSClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

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
    @DisplayName("没配 aliyun.oss.* → 上下文仍能起来，但 OSS 相关的 Bean 都不注册")
    void missingConfigShouldNotBreakStartup() {
        // 这一条很重要：模块里只要引了这个 starter，自动配置就会被加载。
        // 如果无条件创建 OSSClient，它会拿 null 的 endpoint 去初始化然后启动失败 ——
        // 于是"引了但没配"就变成一个启动即崩的状态，本地跑单测都过不去。
        // 用 @ConditionalOnProperty 让整块在没配时直接不生效，starter 才是可插拔的。
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(OssProperties.class);
            assertThat(context.getBean(OssProperties.class).getBucketName()).isNull();

            assertThat(context).doesNotHaveBean(OSSClient.class);
            assertThat(context).doesNotHaveBean(AliOssOperator.class);
        });
    }

    @Test
    @DisplayName("配了 endpoint → OSSClient 和 AliOssOperator 都被注册，且是单例")
    void withEndpointShouldRegisterOssBeans() {
        contextRunner
                .withPropertyValues(
                        "aliyun.oss.endpoint=https://oss-cn-beijing.aliyuncs.com",
                        "aliyun.oss.bucket-name=miuxuer",
                        "aliyun.oss.region=cn-beijing")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(OSSClient.class);
                    assertThat(context).hasSingleBean(AliOssOperator.class);

                    // 单例是关键：参考实现每次上传都新建一个 client，
                    // 连接池和凭证链反复初始化，并发上传时连接数会被打满
                    assertThat(context.getBean(OSSClient.class))
                            .isSameAs(context.getBean(OSSClient.class));
                });
    }

    @Test
    @DisplayName("用户自己定义了 OSSClient → starter 让路，不覆盖")
    void userDefinedBeanShouldWin() {
        // @ConditionalOnMissingBean 的意义：使用者永远有最终决定权。
        // 少了它，starter 会和应用自己的配置打架，而且是先注册的赢 —— 结果不可预期。
        OSSClient custom = mock(OSSClient.class);

        contextRunner
                .withPropertyValues("aliyun.oss.endpoint=https://oss-cn-beijing.aliyuncs.com")
                .withBean("ossClient", OSSClient.class, () -> custom)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(OSSClient.class);
                    assertThat(context.getBean(OSSClient.class)).isSameAs(custom);
                });
    }
}
