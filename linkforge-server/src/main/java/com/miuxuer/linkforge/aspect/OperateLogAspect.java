package com.miuxuer.linkforge.aspect;

import com.miuxuer.linkforge.constant.OperateLogConstant;
import com.miuxuer.linkforge.context.CurrentHolder;
import com.miuxuer.linkforge.entity.OperateLog;
import com.miuxuer.linkforge.mapper.OperateLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.aop.support.AopUtils;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.regex.Pattern;

/**
 * 操作日志切面。
 *
 * <p>{@code @Around} 标注的方法被调用时，记录操作人、入参、返回值、耗时和异常，
 * 落到 {@code t_operate_log}。
 *
 * <p><b>为什么必须有 try-catch（本切面最关键的一点）</b>：
 * 环绕通知里 {@code joinPoint.proceed()} 一旦抛异常，后面的代码就都不会执行了 ——
 * 包括写日志那一段。结果是"成功的操作有日志、失败的操作反而没有"，
 * 而恰恰是失败的操作最需要追溯。参考项目就踩了这个坑（只有 {@code proceed()}
 * 之后才组装日志），线上表现为出错时查询不到任何记录。
 *
 * <p>本切面的做法：{@code try / catch / finally}，写完日志的代码放 {@code finally}，
 * 保证无论方法成功还是抛异常都落库；同时 catch 里用 {@code throw e} 把异常原样抛回去，
 * 不能让日志切面把业务异常吃掉 —— 那样全局异常处理器就再也收不到它了。
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class OperateLogAspect {

    /**
     * 需要脱敏的字段名。登录接口的入参里就带着明文密码，
     * 原样落库等于把密码抄了一份到日志表里 —— 一旦库被拖，攻击者不用破解 BCrypt
     * 就能直接从日志表里拿到明文。
     */
    private static final Pattern SENSITIVE_FIELD = Pattern.compile(
            "(\"(?:password|oldPassword|newPassword|confirmPassword|secret|secretKey"
                    + "|accessKey|accessKeyId|accessKeySecret|token)\"\\s*:\\s*)\"[^\"]*\"",
            Pattern.CASE_INSENSITIVE);

    /** 脱敏后替换成的占位内容。 */
    private static final String MASKED_VALUE = "\"******\"";

    private final OperateLogMapper operateLogMapper;

    /**
     * 注入容器里的 ObjectMapper（Boot 4 是 Jackson 3 的 {@code tools.jackson}）。
     * 不要自己 new：容器里那个按 application.yml 配好了命名策略、日期格式，
     * 自己 new 的是一份默认配置，两边序列化同一份数据可能结果不一致。
     */
    private final ObjectMapper objectMapper;

    /**
     * 切点用 {@code @annotation(全限定名)} 而不是绑定参数的形式：
     * {@code @OperateLog} 是纯标记注解，没有属性可读，绑定了也用不上。
     * 顺带避开一个麻烦 —— 注解和实体恰好同名（OperationLog），
     * 绑定参数的话两个 import 会冲突，其中一个非得写全限定名不可。
     */
    @Around("@annotation(com.miuxuer.linkforge.annotation.OperateLog)")
    public Object recordOperateLog(ProceedingJoinPoint joinPoint) throws Throwable {
        long start = System.currentTimeMillis();
        Object result = null;
        Throwable error = null;
        try {
            result = joinPoint.proceed();
            return result;
        } catch (Throwable e) {
            // 先记下来，等下写进日志的 error_msg
            error = e;
            // 必须原样抛出去。吞掉异常的话，GlobalExceptionHandler 就收不到它，
            // 前端会拿到一个"成功"的响应，而实际什么都没做
            throw e;
        } finally {
            // 放在 finally 而不是 try 块末尾：这是"失败也要有日志"的唯一保证
            saveLog(joinPoint, result, error, System.currentTimeMillis() - start);
        }
    }

    /**
     * 组装并落库。整个方法被 try 包住 —— <b>记日志失败绝不能影响业务</b>。
     *
     * <p>业务操作已经执行完了，这时候因为日志表连接超时把异常抛给用户，
     * 用户看到的是"操作失败"，但数据其实已经改了，接着他会重试一次，
     * 于是改了两遍。宁可丢一条日志。
     */
    private void saveLog(ProceedingJoinPoint joinPoint, Object result, Throwable error, long costTime) {
        try {
            OperateLog entity = new OperateLog();
            entity.setOperateUser(CurrentHolder.getCurrentId());
            entity.setOperateTime(LocalDateTime.now());
            // 取目标类而不是代理类：代理类的名字形如 UserController$$SpringCGLIB$$0，
            // 记进日志表既看不懂也没法按类名检索
            entity.setClassName(AopUtils.getTargetClass(joinPoint.getTarget()).getName());
            entity.setMethodName(joinPoint.getSignature().getName());
            entity.setMethodParams(toJson(joinPoint.getArgs()));
            entity.setReturnValue(toJson(result));
            entity.setCostTime(costTime);

            if (error == null) {
                entity.setStatus(OperateLogConstant.STATUS_SUCCESS);
            } else {
                entity.setStatus(OperateLogConstant.STATUS_FAILED);
                entity.setErrorMsg(truncate(
                        error.getClass().getSimpleName() + ": " + error.getMessage(),
                        OperateLogConstant.MAX_ERROR_LENGTH));
            }

            // 不走 insertWithFill：这张表没有需要自动填充的公共字段
            operateLogMapper.insert(entity);
        } catch (Exception e) {
            log.error("操作日志落库失败（不影响业务）: {}#{}",
                    joinPoint.getSignature().getDeclaringTypeName(),
                    joinPoint.getSignature().getName(), e);
        }
    }

    /**
     * 转 JSON 并截断。
     *
     * <p>序列化可能失败：入参里如果有 {@code HttpServletRequest}、{@code MultipartFile}
     * 这类对象，Jackson 处理不了会抛异常。这种情况退化成 {@code toString}，
     * 而不是让日志记录失败 —— 参考项目直接把 {@code Arrays.toString} 的结果写库，
     * 复杂对象会变成 {@code [Ljava.lang.Object;@1b6d3586} 这种完全没用的东西。
     *
     * <p>另外不能无脑用 {@code String.valueOf(array)}：数组的 toString 继承自 Object，
     * 打出来就是上面那种地址串。判断成数组要走 {@code Arrays.deepToString}。
     */
    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            String json = objectMapper.writeValueAsString(value);
            return truncate(mask(json), OperateLogConstant.MAX_TEXT_LENGTH);
        } catch (Exception e) {
            log.debug("入参/返回值无法序列化为 JSON，退化为 toString", e);
            String fallback = value.getClass().isArray()
                    ? Arrays.deepToString((Object[]) value)
                    : String.valueOf(value);
            return truncate(mask(fallback), OperateLogConstant.MAX_TEXT_LENGTH);
        }
    }

    /**
     * 把敏感字段的值替换成占位符。
     *
     * <p>用正则而不是先把 JSON 解析成树再遍历：这里要处理的只是"固定几个键名"，
     * 正则可读性和维护性都够用，也避开了 JSON 库版本之间 API 的差异。
     * 代价是它不认识 JSON 语法 —— 如果某个字符串里恰好出现
     * {@code "password":"xxx"} 这样的内容，也会被替换掉。
     * 对日志脱敏来说这个"误伤"方向是安全的（宁可多抹掉一点），可以接受。
     */
    private String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return SENSITIVE_FIELD.matcher(text).replaceAll("$1" + MASKED_VALUE);
    }

    /**
     * 截断到指定长度。
     *
     * <p>截断后总长度严格不超过 {@code maxLength}（标记本身占用额度）——
     * 否则 {@code error_msg} 这种 VARCHAR(500) 的列会因为多出几个字符而报
     * {@code Data too long}，把"记日志"变成"抛异常"。
     */
    private String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        int keep = maxLength - OperateLogConstant.TRUNCATED_SUFFIX.length();
        return text.substring(0, Math.max(keep, 0)) + OperateLogConstant.TRUNCATED_SUFFIX;
    }
}
