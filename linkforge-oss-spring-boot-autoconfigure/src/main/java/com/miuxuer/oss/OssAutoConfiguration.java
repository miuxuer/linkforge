package com.miuxuer.oss;

import com.aliyun.sdk.service.oss2.OSSClient;
import com.aliyun.sdk.service.oss2.credentials.EnvironmentVariableCredentialsProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OSS 自动配置。
 *
 * <p><b>这个类是怎么被 Spring 发现的</b>：靠
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * 这个文件里写的一行全限定类名。Spring Boot 启动时会扫描 classpath 上所有 jar 的
 * 这个文件，把里面列的类当成自动配置类加载。
 *
 * <p>注意它<b>不是</b>靠 {@code @ComponentScan} 扫出来的，所以这个类没有
 * （也不该有）{@code @Component}。用 {@code @AutoConfiguration} 而不是
 * {@code @Configuration}：前者是 Boot 2.7 起给自动配置类专用的，
 * 它额外带了"必须在用户自己的 Bean 之后注册"的语义，
 * 配合 {@code @ConditionalOnMissingBean} 才能实现"用户配置优先、starter 兜底"。
 *
 * <p>包名故意放在 {@code com.miuxuer.oss} 而不是 {@code com.miuxuer.linkforge} 下面：
 * 后者是应用的组件扫描范围，放进去的话这个类会被扫描到，
 * 于是"自动配置到底有没有生效"就验证不出来了 —— 你分不清 Bean 是扫描注册的还是
 * imports 文件注册的。放在扫描范围之外，能跑起来就说明注册机制真的通了。
 */
@AutoConfiguration
@EnableConfigurationProperties(OssProperties.class)
public class OssAutoConfiguration {

    /**
     * OSS 客户端与上传工具。
     *
     * <p><b>整块包在 {@code @ConditionalOnProperty} 里</b>：只有配了
     * {@code aliyun.oss.endpoint} 才会生效。这解决一个很实际的问题 ——
     * 引了这个 starter 但还没配 OSS 的模块（比如本地跑单元测试时），
     * 如果无条件创建 OSSClient，它会拿 null 的 endpoint 去初始化然后启动失败。
     * 用条件注解让它"没配就整个不生效"，starter 才是真正可插拔的。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "aliyun.oss", name = "endpoint")
    static class OssClientConfiguration {

        /**
         * OSS 客户端，<b>单例</b>。
         *
         * <p>相对参考实现的关键改动：原实现每次上传都新建一个 client。
         * 建 client 要初始化 HTTP 连接池、准备凭证链，这些开销在一次上传里纯属浪费；
         * 并发上传时还会把连接数打满。OSSClient 本身线程安全，交给容器管生命周期就好。
         *
         * <p>{@code destroyMethod = "close"}：容器关闭时释放连接池。
         * 不写的话进程退出时连接不会优雅关闭，压测时能看到一堆 TIME_WAIT。
         */
        @Bean(destroyMethod = "close")
        @ConditionalOnMissingBean
        public OSSClient ossClient(OssProperties properties) {
            return OSSClient.newBuilder()
                    .endpoint(properties.getEndpoint())
                    .region(properties.getRegion())
                    // 从环境变量 OSS_ACCESS_KEY_ID / OSS_ACCESS_KEY_SECRET 读凭证。
                    // 不落到配置文件里，也就不会被提交进仓库
                    .credentialsProvider(new EnvironmentVariableCredentialsProvider())
                    .build();
        }

        @Bean
        @ConditionalOnMissingBean
        public AliOssOperator aliOssOperator(OSSClient ossClient, OssProperties properties) {
            return new AliOssOperator(ossClient, properties);
        }
    }
}
