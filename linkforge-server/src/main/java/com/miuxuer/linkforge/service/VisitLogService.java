package com.miuxuer.linkforge.service;

import com.miuxuer.linkforge.event.VisitEvent;

/**
 * 访问明细服务。
 */
public interface VisitLogService {

    /**
     * 记录一次访问明细。
     *
     * <p>由异步监听器调用，<b>不在跳转的响应链路上</b>。
     *
     * @param event 访问事件
     */
    void record(VisitEvent event);
}
