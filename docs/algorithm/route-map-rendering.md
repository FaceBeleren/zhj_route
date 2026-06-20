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
- `GET /api/route-map/status` 会检查 `ljszy_odpair_pool` 是否可读、是否有可用缓存行，以及最近缓存时间；前端顶部展示 `OD缓存` 与 `缓存数据` 状态。
- 单路线优化预览保留算法直线距离指标，同时返回 `originalSegments` 和 `segments`，地图对比两条线都优先使用道路分段折线；页面额外展示原道路距离、优化后道路距离和道路耗时。
- 多路线结果卡片的距离和耗时使用 `segments` 汇总口径；命中 OD 或百度在线时就是道路距离，回退时就是直线距离。

## 配置项

前端地图底图：

```env
VITE_BAIDU_MAP_AK=
```

对应样例文件：`frontend/.env.example`。

后端在线算路补缓存：

```yaml
app:
  baidu-route:
    enabled: ${BAIDU_ROUTE_ENABLED:false}
    ak: ${BAIDU_ROUTE_AK:}
    sk: ${BAIDU_ROUTE_SK:}
    connect-timeout-ms: 2000
    read-timeout-ms: 5000
```

默认 `enabled=false`，避免页面请求默认产生外部调用。`sk` 为空时不计算 `sn`，适合未开启服务端校验的 AK；如果百度应用开启了 SN 校验，需要同时配置 `BAIDU_ROUTE_SK`。
在线请求设置了连接超时和读取超时，避免百度接口慢时长时间阻塞路线预览。

对应样例文件：`backend/.env.example`。

本机调试可以使用 Spring 本地 profile 覆盖：

```yaml
spring:
  profiles:
    active: local

app:
  baidu-route:
    enabled: true
```

`backend/src/main/resources/application-local.yml` 已被 `.gitignore` 忽略，可以放本机数据库或百度密钥；不要提交到仓库。

老 `ljszy` 项目里存在两套百度调用方式：

- OD 缓存补算在 `RouteOptimiseServiceImpl.getDistanceByList` 中直接调用 `http://api.map.baidu.com/directionlite/v1/driving`，参数顺序为 `ak`、`origin`、`destination`、`tactics`、`timestamp`，再按 `/directionlite/v1/driving?` 计算 `sn`。
- 路线预览在 `RouteOverallPlanManagerServiceImpl.getDistanceByListDriving` 中调用 `IMessageFeignClient`，最终走 `cloud/webservice/baidu/getDistanceByListDriving`。

当前项目采用第一种“轻量直调百度”方式：不接入旧系统 Feign/message 服务，只复用百度请求参数、SN 计算和 `ljszy_odpair_pool.msg_full` 的道路折线解析口径。

## 运行检查

启动前端和后端后，先看页面顶部状态条：

- `地图底图 已配置`：说明前端存在 `VITE_BAIDU_MAP_AK`，可以尝试加载百度地图底图。
- `百度补算 已开启`：说明后端允许缓存缺失时在线请求百度。
- `后端AK 已配置`：说明后端存在 `BAIDU_ROUTE_AK`。
- `SN 已配置`：说明后端存在 `BAIDU_ROUTE_SK`；如果百度应用未开启 SN 校验，可以不配。
- `OD缓存 可用`：说明 `ljszy_odpair_pool` 可读。
- `缓存数据 最近 ...`：说明 OD 缓存表里存在可用记录。

再生成一次优化预览，看路线分段或路线卡片中的路径来源：

- `OD缓存`：本段路线使用数据库缓存的百度道路折线。
- `百度在线`：本段路线在线请求百度成功，并已尝试写回缓存。
- `直线回退`：本段没有可用道路折线，当前只是两点直连。

也可以直接验证某两个点位的路径解析：

```bash
curl -X POST http://localhost:8088/api/route-map/preview \
  -H "Content-Type: application/json" \
  -d "{\"fromFacilityId\":1,\"fromLongitude\":116.1,\"fromLatitude\":39.9,\"toFacilityId\":2,\"toLongitude\":116.2,\"toLatitude\":39.95}"
```

返回中的 `pathSource`、`distanceMeters`、`durationSeconds`、`pathPointCount` 可用于判断是否命中 OD 缓存或百度在线。

如果地图没有显示真实道路，按顺序检查：

1. 顶部 `地图底图` 是否已配置；否则只能看坐标预览。
2. `OD缓存` 是否可用、`缓存数据` 是否有记录；否则不会命中旧路径。
3. 如果缓存没有命中，确认 `百度补算` 和 `后端AK` 是否已配置。
4. 如果仍是 `直线回退`，优先看百度 AK 权限、SN 配置、网络和后端日志。

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
