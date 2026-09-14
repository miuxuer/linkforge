import request from './request'

/** 看板总览：短链总数、累计访问、今日访问。 */
export function getOverview() {
  return request.get('/link/stat/overview')
}

/** 访问趋势，返回 [{ visitDate, visitCount }]，没有访问的日期也有记录（补 0）。 */
export function getTrend(days = 7) {
  return request.get('/link/stat/trend', { params: { days } })
}

/** 访问量 Top N。 */
export function getTop(limit = 10) {
  return request.get('/link/stat/top', { params: { limit } })
}
