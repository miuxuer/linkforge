package com.miuxuer.linkforge.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Base62 编解码单元测试。
 *
 * <p>纯逻辑测试，不加载 Spring 上下文，因此不依赖 MySQL / Redis，随时可跑。
 * 需要在 Maven 的 test 阶段之外单独跑时：{@code mvn -pl linkforge-common test -Dtest=Base62Test}。
 */
class Base62Test {

    @Test
    void encodeDecodeRoundTrip() {
        // 边界值 61/62 是重点：62 刚好进位，最容易被"取余/整除"写反的实现搞错
        long[] samples = {0, 1, 61, 62, 63, 12345, 1_000_000_000L, Long.MAX_VALUE};
        for (long n : samples) {
            String code = Base62.encode(n);
            assertEquals(n, Base62.decode(code), "编码再解码应还原原值: " + n);
        }
    }

    @Test
    void encodeZero() {
        assertEquals("0", Base62.encode(0));
    }

    @Test
    void encodeShouldShrinkDigits() {
        // Base62 的意义所在：10 亿十进制 10 位，Base62 只要 6 位
        assertEquals(6, Base62.encode(1_000_000_000L).length());
    }

    @Test
    void decodeNullShouldThrow() {
        assertThrows(IllegalArgumentException.class, () -> Base62.decode(null));
    }

    @Test
    void decodeEmptyShouldThrow() {
        assertThrows(IllegalArgumentException.class, () -> Base62.decode(""));
    }

    @Test
    void decodeInvalidCharShouldThrow() {
        // '-' 不在 Base62 字符表里
        assertThrows(IllegalArgumentException.class, () -> Base62.decode("abc-def"));
    }

    @Test
    void decodeOverflowShouldThrow() {
        // 短码是用户可控输入，超长字符串必须被拒绝而不是静默解出负数
        assertThrows(IllegalArgumentException.class, () -> Base62.decode("ZZZZZZZZZZZZZZ"));
    }
}
