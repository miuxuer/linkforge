package com.miuxuer.linkforge.utils;

/**
 * Base62 编解码工具：把自增 id（数字）转成短码字符串，反之亦然。
 *
 * <p>为什么用 Base62：0-9 a-z A-Z 共 62 个字符，比十进制紧凑得多。
 * 十进制的 10 亿是 10 位，Base62 只要 6 位，短码就短。
 *
 * <p>这是"发号器"方案生成短码的核心 —— 号段模式发出来的 id 全局唯一，
 * 转成 Base62 后短码自然唯一，从根本上避免了 Hash 取模方案的碰撞问题
 * （Hash 方案要额外存碰撞记录并重试，还要处理"同一个长链接要不要复用短码"的歧义）。
 *
 * <p>字符表选 {@code 0-9a-zA-Z} 而不是 {@code a-zA-Z0-9}，是为了让纯数字 id
 * 编码出来仍是纯数字，肉眼看上去更有规律。
 */
public final class Base62 {

    private static final String ALPHABET =
            "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final int BASE = ALPHABET.length();

    /** 工具类不允许实例化。 */
    private Base62() {
    }

    /**
     * 数字 id -> 短码。
     *
     * @param num 必须是非负数（号段模式发出来的 id 从 1 开始）
     */
    public static String encode(long num) {
        if (num == 0) {
            return String.valueOf(ALPHABET.charAt(0));
        }
        StringBuilder sb = new StringBuilder();
        // 从低位往高位取，所以是"除基取余"，最后得反过来
        while (num > 0) {
            sb.append(ALPHABET.charAt((int) (num % BASE)));
            num /= BASE;
        }
        return sb.reverse().toString();
    }

    /**
     * 短码 -> 数字 id。
     *
     * @throws IllegalArgumentException 短码为空、含非法字符或长到解码溢出
     */
    public static long decode(String code) {
        if (code == null || code.isEmpty()) {
            throw new IllegalArgumentException("短码不能为空");
        }
        long num = 0;
        for (int i = 0; i < code.length(); i++) {
            int idx = ALPHABET.indexOf(code.charAt(i));
            if (idx < 0) {
                throw new IllegalArgumentException("短码包含非法字符: " + code.charAt(i));
            }
            // 溢出检查：短码是用户可控的输入，不检查的话传个 20 位字符串就能让 long 静默回绕，
            // 解出一个负数 id，然后拿着它去查库。宁可直接拒绝。
            if (num > (Long.MAX_VALUE - idx) / BASE) {
                throw new IllegalArgumentException("短码过长，解码溢出: " + code);
            }
            num = num * BASE + idx;
        }
        return num;
    }
}
