package com.miuxuer.linkforge.service;

import com.miuxuer.linkforge.vo.LinkTopVO;
import com.miuxuer.linkforge.vo.StatOverviewVO;
import com.miuxuer.linkforge.vo.VisitTrendVO;

import java.util.List;

/**
 * 数据看板服务。
 *
 * <p>所有方法都只看当前登录用户的数据 —— 接口签名里没有 userId 参数，
 * 想查别人的都没有入口。
 */
public interface StatService {

    /** 总览：短链总数、累计访问量、今日访问量。 */
    StatOverviewVO overview();

    /**
     * 最近 N 天的访问趋势。
     *
     * @param days 天数，从今天往前数
     * @return 每天一个数据点，<b>没有访问的那天也会有一条 count=0 的记录</b>
     */
    List<VisitTrendVO> trend(int days);

    /**
     * 访问量最高的前 N 条短链。
     *
     * @param limit 条数
     */
    List<LinkTopVO> top(int limit);
}
