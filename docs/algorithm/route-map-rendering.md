# 路线地图展示方案

## 当前落地状态

路线详情页的优化预览和多路线生成结果已经接入 `RouteMapPanel`：

- 配置 `VITE_BAIDU_MAP_AK` 时，前端加载百度地图，在真实地图上展示原路线和优化后路线。
- 未配置 AK 或百度脚本加载失败时，自动回退到本地 SVG 坐标预览。
- 路线详情页中，原路线使用红色虚线，优化后路线使用蓝色实线。
- 多路线生成页中，点击某一趟路线后展示该趟生成路线。
- 当前地图点位坐标直接使用业务表里的经纬度字段，尚未做坐标系转换。

后端 `RouteOptimizeService` 返回的 `segments` 已经支持 `path`：

- 优先从 `ljszy_odpair_pool` 读取缓存 OD。
- 解析 `msg_full` 中百度 `directionlite/v1/driving` 返回的 `result.routes[].steps[].path`。
- 若缓存缺失，并且 `app.baidu-route.enabled=true` 且配置了 `BAIDU_ROUTE_AK`，则在线调用百度驾车路线接口。
- 百度返回成功后写回 `ljszy_odpair_pool`，本次响应直接使用道路折线。
- 读取不到缓存、未启用在线补算、表不存在、JSON 异常或百度请求失败时回退为起终点直线。
- 每段返回 `pathSource`，目前可能是 `OD_CACHE`、`BAIDU_ONLINE` 或 `DIRECT`。

## 配置项

前端地图底图：

```env
VITE_BAIDU_MAP_AK=
```

后端在线算路补缓存：

```yaml
app:
  baidu-route:
    enabled: false
    ak: ${BAIDU_ROUTE_AK:}
    sk: ${BAIDU_ROUTE_SK:}
    connect-timeout-ms: 2000
    read-timeout-ms: 5000
```

默认 `enabled=false`，避免页面请求默认产生外部调用。`sk` 为空时不计算 `sn`，适合未开启服务端校验的 AK；如果百度应用开启了 SN 校验，需要同时配置 `BAIDU_ROUTE_SK`。
在线请求设置了连接超时和读取超时，避免百度接口慢时长时间阻塞路线预览。

## 为什么不直接迁移 ljszy 全套

ljszy 的旧实现包含算法任务、批量 OD 补算、百度签名、数据库 mapper、调度与结果展示多条链路。当前项目只需要路线预览最小闭环，所以只迁移了三件事：

- 旧 OD 缓存表的读取口径。
- 百度返回道路折线的解析方式。
- 缓存缺失时可选在线补算并写回缓存。

批量缺失 OD 预热、异步调度、路线结果长期落库后续再做，避免把旧项目复杂度提前带进来。

## 后续建议

1. 增加请求限流与批量预热，避免一次多路线生成触发过多百度请求。
2. 将缺失 OD 补算异步化，页面先展示直线，后台补完后可刷新为道路折线。
3. 校验当前经纬度坐标系。如果业务表是 GCJ-02 或 WGS-84，而百度地图需要 BD-09，应在展示层或入库层统一转换。
4. 多路线地图后续可支持多条路线分色叠加，而不是只展示选中的单趟路线。
