package com.miuxuer.oss;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

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
}
