package com.miuxuer.linkforge.service.impl;

import com.miuxuer.linkforge.entity.Link;
import com.miuxuer.linkforge.entity.VisitLog;
import com.miuxuer.linkforge.event.VisitEvent;
import com.miuxuer.linkforge.mapper.LinkMapper;
import com.miuxuer.linkforge.mapper.VisitLogMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("访问明细落库")
class VisitLogServiceImplTest {

    private static final VisitEvent EVENT =
            new VisitEvent("abc123", "1.2.3.4", "Mozilla/5.0", "https://ref.example.com");

    @Mock
    private VisitLogMapper visitLogMapper;

    @Mock
    private LinkMapper linkMapper;

    private VisitLogServiceImpl visitLogService;

    @BeforeEach
    void setUp() {
        visitLogService = new VisitLogServiceImpl(visitLogMapper, linkMapper);
    }

    private static Link linkWithId(Long id) {
        Link link = new Link();
        link.setId(id);
        link.setShortCode("abc123");
        return link;
    }

    @Test
    @DisplayName("正常访问 → 补齐 linkId 后落库，字段都带上")
    void record_shouldFillLinkIdAndPersist() {
        when(linkMapper.selectOne(any())).thenReturn(linkWithId(1001L));

        visitLogService.record(EVENT);

        ArgumentCaptor<VisitLog> captor = ArgumentCaptor.forClass(VisitLog.class);
        verify(visitLogMapper).insert(captor.capture());
        VisitLog saved = captor.getValue();

        // link_id 在库里是 NOT NULL。不填的话 MyBatis-Plus 会把它从 INSERT 语句里
        // 漏掉，MySQL 直接报 "Field 'link_id' doesn't have a default value"
        assertThat(saved.getLinkId()).isEqualTo(1001L);
        assertThat(saved.getShortCode()).isEqualTo("abc123");
        assertThat(saved.getIp()).isEqualTo("1.2.3.4");
        assertThat(saved.getUserAgent()).isEqualTo("Mozilla/5.0");
        assertThat(saved.getReferer()).isEqualTo("https://ref.example.com");
    }

    @Test
    @DisplayName("访问时间用服务端时间，不是客户端时间")
    void record_shouldUseServerTime() {
        when(linkMapper.selectOne(any())).thenReturn(linkWithId(1L));
        LocalDateTime before = LocalDateTime.now();

        visitLogService.record(EVENT);

        ArgumentCaptor<VisitLog> captor = ArgumentCaptor.forClass(VisitLog.class);
        verify(visitLogMapper).insert(captor.capture());

        // 客户端时钟可以随便改；用它做统计的话趋势图上会出现"未来"的数据点
        assertThat(captor.getValue().getVisitTime()).isAfterOrEqualTo(before);
        assertThat(captor.getValue().getVisitTime()).isBeforeOrEqualTo(LocalDateTime.now());
    }

    @Test
    @DisplayName("★ 短链已被删除 → 跳过落库，而不是让 NOT NULL 约束报错")
    void record_linkGone_shouldSkipInsteadOfFailing() {
        // 访客点开短链之后、异步写明细之前，用户正好把短链删了。
        // @TableLogic 会让查询过滤掉它，于是拿不到 linkId
        when(linkMapper.selectOne(any())).thenReturn(null);

        visitLogService.record(EVENT);

        // 硬插的话会抛 DataIntegrityViolationException，然后被监听器吞掉 ——
        // 结果一样是"没记上"，但每次都要走一遍异常栈，白白浪费开销
        verify(visitLogMapper, never()).insert(any(VisitLog.class));
    }
}
