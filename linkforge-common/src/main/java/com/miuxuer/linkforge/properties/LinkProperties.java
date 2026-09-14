package com.miuxuer.linkforge.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 短链相关配置。
 *
 * <p>目前只有 {@code linkforge.domain} 一项：拼"完整短链接"给前端展示用。
 * 换域名（上线、多环境）时只改 yaml，不用翻代码。
 */
@Data
@Component
@ConfigurationProperties(prefix = "linkforge")
public class LinkProperties {

    /**
     * 短链的域名前缀，不带结尾斜杠。
     *
     * <p>例：{@code http://localhost:8080}，拼出来是 {@code http://localhost:8080/1aB}。
     */
    private String domain;
}
