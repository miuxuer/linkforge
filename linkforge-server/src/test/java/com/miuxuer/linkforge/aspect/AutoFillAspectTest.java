package com.miuxuer.linkforge.aspect;

import com.miuxuer.linkforge.annotation.AutoFill;
import com.miuxuer.linkforge.context.CurrentHolder;
import com.miuxuer.linkforge.entity.Link;
import com.miuxuer.linkforge.entity.User;
import com.miuxuer.linkforge.enumeration.OperationType;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 公共字段填充切面单元测试。
 *
 * <p>这里直接调 {@code autoFill(joinPoint)}，验证的是<b>切面内部的填充逻辑</b>。
 * "切面到底有没有被织入"是 AOP 代理配置的事，要靠集成测试或实机验证 ——
 * 单元测试里手写的 JoinPoint 永远不会经过代理。
 *
 * <p>FakeMapper 存在的意义只是提供一个带 {@code @AutoFill} 注解的真实 {@link Method}
 * 对象，切面要用它读注解。
 */
@DisplayName("公共字段填充切面")
class AutoFillAspectTest {

    private final AutoFillAspect aspect = new AutoFillAspect();

    /** 只为拿 Method 对象存在的假 Mapper。 */
    private interface FakeMapper {

        @AutoFill(OperationType.INSERT)
        int insert(Object entity);

        @AutoFill(OperationType.UPDATE)
        int update(Object entity);
    }

    @AfterEach
    void tearDown() {
        CurrentHolder.remove();
    }

    private JoinPoint joinPointWith(Object entity, String methodName) throws Exception {
        Method method = FakeMapper.class.getMethod(methodName, Object.class);
        MethodSignature signature = mock(MethodSignature.class);
        when(signature.getMethod()).thenReturn(method);

        JoinPoint joinPoint = mock(JoinPoint.class);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(joinPoint.getArgs()).thenReturn(new Object[]{entity});
        return joinPoint;
    }

    // ==================== INSERT ====================

    @Test
    @DisplayName("插入 → 四个公共字段全部填上")
    void insert_shouldFillAllFourFields() throws Exception {
        CurrentHolder.setCurrentId(1001L);
        User user = new User();

        aspect.autoFill(joinPointWith(user, "insert"));

        assertNotNull(user.getCreateTime());
        assertEquals(1001L, user.getCreateUser());
        assertNotNull(user.getUpdateTime());
        assertEquals(1001L, user.getUpdateUser());
    }

    @Test
    @DisplayName("插入 → 创建时间和修改时间填的是同一个时刻")
    void insert_createAndUpdateTimeShouldMatch() throws Exception {
        CurrentHolder.setCurrentId(1001L);
        User user = new User();

        aspect.autoFill(joinPointWith(user, "insert"));

        // 刚插入的记录，创建时间就是修改时间，这是正常的
        assertEquals(user.getCreateTime(), user.getUpdateTime());
    }

    // ==================== UPDATE ====================

    @Test
    @DisplayName("更新 → 只动 update_*，绝不覆盖 create_*")
    void update_shouldNotTouchCreateFields() throws Exception {
        User user = new User();
        // 模拟一条已经存在的记录：创建人 1001，创建于 2020 年
        LocalDateTimeFixture.setCreated(user);

        // 现在是 2002 这个用户在改它
        CurrentHolder.setCurrentId(2002L);
        aspect.autoFill(joinPointWith(user, "update"));

        // 谁创建的、什么时候创建的，更新时不该被改写
        assertEquals(LocalDateTimeFixture.CREATED_AT, user.getCreateTime());
        assertEquals(1001L, user.getCreateUser());
        // 修改信息被刷新成本次操作的人
        assertEquals(2002L, user.getUpdateUser());
        assertNotNull(user.getUpdateTime());
    }

    // ==================== 容错 ====================

    @Test
    @DisplayName("实体缺少 create_user 字段（Link）→ 不抛异常，能填的照常填")
    void entityMissingCreateUserSetter_shouldNotThrow() throws Exception {
        CurrentHolder.setCurrentId(1001L);
        Link link = new Link();

        // Link 实体目前没有 createUser / updateUser，参考实现在这里会抛
        // NoSuchMethodException 包装的 RuntimeException，把整个写库操作带崩
        assertDoesNotThrow(() -> aspect.autoFill(joinPointWith(link, "insert")));

        // 有的字段照样填上
        assertNotNull(link.getCreateTime());
        assertNotNull(link.getUpdateTime());
    }

    @Test
    @DisplayName("没有登录用户（注册接口）→ 时间照填，create_user 保持 null")
    void noLoggedInUser_shouldStillFillTime() throws Exception {
        // 注册时用户还没登录，CurrentHolder 是空的
        User user = new User();

        aspect.autoFill(joinPointWith(user, "insert"));

        assertNotNull(user.getCreateTime(), "没有登录用户也要填时间");
        assertNull(user.getCreateUser(), "没有登录用户时 create_user 应该是 null，而不是 0");
    }

    @Test
    @DisplayName("没有参数 → 安静返回，不抛异常")
    void noArgs_shouldReturnQuietly() throws Exception {
        JoinPoint joinPoint = joinPointWith(null, "insert");
        when(joinPoint.getArgs()).thenReturn(new Object[0]);

        assertDoesNotThrow(() -> aspect.autoFill(joinPoint));
    }

    @Test
    @DisplayName("第一个参数是 null → 安静返回，不抛 NPE")
    void nullEntity_shouldReturnQuietly() throws Exception {
        assertDoesNotThrow(() -> aspect.autoFill(joinPointWith(null, "insert")));
    }

    /** 给"已存在的记录"造一个初始的创建时间，避免测试里散落魔法值。 */
    private static final class LocalDateTimeFixture {

        static final java.time.LocalDateTime CREATED_AT =
                java.time.LocalDateTime.of(2020, 1, 1, 0, 0);

        static void setCreated(User user) {
            user.setCreateTime(CREATED_AT);
            user.setCreateUser(1001L);
        }
    }
}
