# 通用路线优化模块设计

本文定义 `zhj_route` 后续路线优化模块的目标、边界、数据模型和实施顺序。

目标不是给某个项目写一次性脚本，而是形成可复用的路线优化能力。不同业务系统只需要把自己的数据转换成标准输入，即可调用算法。

## 目标

第一阶段目标：

```text
输入一批待收运点位、一批车辆、起终点和 OD 矩阵，
输出一条或多条可解释的收运路线。
```

长期目标：

- 支持单路线优化；
- 支持多路线自动生成；
- 支持多车辆、多车型、多趟次；
- 支持车辆容量、作业时长、时间窗、通行条件；
- 支持多个处理厂或中转站选择；
- 支持未分配点位及原因解释；
- 支持真实道路折线返回；
- 支持规划路线与实际流水对比。

## 分层设计

路线优化能力分为三层：

```text
业务适配层
  把 ljszy、广东项目或其他系统数据转换成标准输入

算法核心层
  只处理标准模型，不依赖数据库、不依赖具体业务表

结果展示层
  把算法结果转换成前端可展示的路线详情、指标、polyline
```

不要让算法核心直接依赖 `ljszy_route_info`、`ljszy_facility_info` 等具体表结构。

## 标准输入模型

### RouteOptimizeRequest

```json
{
  "points": [],
  "vehicles": [],
  "facilities": [],
  "odPairs": [],
  "constraints": {},
  "objective": {}
}
```

### RoutePoint

```json
{
  "id": "point-1",
  "name": "收运点A",
  "longitude": 116.0,
  "latitude": 39.0,
  "amount": 120.0,
  "serviceDuration": 5.0,
  "timeWindows": [],
  "trafficTags": [],
  "frequency": 1
}
```

字段说明：

| 字段 | 含义 |
| --- | --- |
| `id` | 点位唯一编码 |
| `amount` | 本次待收运量，可用 kg 或 L，但必须在请求中统一单位 |
| `serviceDuration` | 作业停留时间，分钟 |
| `timeWindows` | 可服务时间窗 |
| `trafficTags` | 通行限制标签 |
| `frequency` | 收运频次要求 |

### Vehicle

```json
{
  "id": "vehicle-1",
  "plateNo": "车牌",
  "capacity": 5000.0,
  "ratedCapacity": 4500.0,
  "maxDuration": 240.0,
  "startFacilityId": "park-1",
  "endFacilityIds": ["station-1"],
  "tripCount": 1,
  "trafficTags": []
}
```

### Facility

```json
{
  "id": "station-1",
  "name": "处理厂",
  "type": "TRANSFER_STATION",
  "longitude": 116.0,
  "latitude": 39.0,
  "serviceDuration": 10.0,
  "capacityPerHour": 10
}
```

设施类型建议：

- `PARKING`：停车场；
- `TRANSFER_STATION`：中转站；
- `DISPOSAL_PLANT`：处理厂；
- `TEMP_POINT`：临时点。

### OdPair

```json
{
  "fromId": "point-1",
  "toId": "point-2",
  "distance": 1234.0,
  "duration": 180.0,
  "polyline": ["116.1,39.1", "116.2,39.2"],
  "provider": "BAIDU",
  "rawPayload": "{}"
}
```

`distance` 单位建议为米，`duration` 单位建议为秒或分钟，必须统一。

`polyline` 是前端绘制真实道路用的折线点。如果初期没有地图服务，可以为空，前端退化为点对直线。

### Constraints

```json
{
  "respectCapacity": true,
  "respectMaxDuration": true,
  "respectTimeWindow": false,
  "respectTrafficTags": false,
  "allowUnassigned": true
}
```

### Objective

```json
{
  "primary": "MIN_DISTANCE",
  "secondary": ["HIGH_LOAD_RATE", "LOW_UNASSIGNED"],
  "weights": {
    "distance": 1.0,
    "duration": 0.5,
    "loadRate": 0.2
  }
}
```

第一阶段先支持 `MIN_DISTANCE` 即可。

## 标准输出模型

### RouteOptimizeResult

```json
{
  "routes": [],
  "unassignedPoints": [],
  "summary": {},
  "warnings": []
}
```

### OptimizedRoute

```json
{
  "routeId": "route-1",
  "vehicleId": "vehicle-1",
  "tripIndex": 1,
  "pointIds": ["park-1", "point-1", "point-2", "station-1"],
  "points": [],
  "distance": 5000.0,
  "duration": 60.0,
  "load": 3200.0,
  "loadRate": 0.71,
  "polyline": [],
  "metrics": {}
}
```

### UnassignedPoint

```json
{
  "pointId": "point-9",
  "reason": "CAPACITY_EXCEEDED",
  "message": "所有车辆插入该点后均超过容量或时间限制"
}
```

常见未分配原因：

- `NO_OD_PAIR`：缺少点对距离；
- `CAPACITY_EXCEEDED`：容量不足；
- `DURATION_EXCEEDED`：时长超限；
- `TIME_WINDOW_VIOLATION`：时间窗冲突；
- `TRAFFIC_NOT_ALLOWED`：车辆通行条件不满足；
- `NO_VEHICLE_AVAILABLE`：没有可用车辆；
- `INVALID_POINT`：点位数据不合法。

## OD 矩阵服务

OD 服务是算法落地的基础。建议抽象为：

```text
OdMatrixService
  getRequiredPairs(nodeIds)
  findCachedPairs(requiredPairs)
  fetchMissingPairs(missingPairs)
  savePairs(fetchedPairs)
  buildMatrix(allPairs)
```

第一阶段可以先使用数据库已有 OD 或 mock OD，不立即接地图 API。

第二阶段接入地图服务：

- 支持百度或高德；
- 地图 AK/SK 使用配置；
- 支持限流；
- 支持失败重试；
- 支持缓存；
- 保存原始返回，便于后续生成 polyline。

## 单路线算法

第一版单路线算法采用全路径插入。

输入：

- 起点；
- 终点；
- 候选点；
- OD 矩阵；
- 车辆约束。

输出：

- 本趟路线；
- 已分配点；
- 未分配点；
- 路线指标。

伪代码：

```text
route = [start, end]
remaining = candidate points

while remaining not empty:
    candidates = []
    for point in remaining:
        for each segment [route[i], route[i+1]]:
            delta = d(route[i], point) + d(point, route[i+1]) - d(route[i], route[i+1])
            newRoute = insert point into segment
            if constraints satisfied:
                candidates.add(point, i, delta)

    if candidates empty:
        break

    choose candidate with minimum delta
    insert point
    remove point from remaining

return route
```

第一阶段只校验：

- OD 是否存在；
- 是否超过车辆容量；
- 是否超过最大路线时长。

时间窗和通行条件后续再加。

## 多路线算法

多路线算法是在单路线算法外层增加车辆和剩余点池。

伪代码：

```text
remaining = all points
routes = []

for each vehicle trip in dispatch order:
    result = singleRoute(vehicle, remaining)
    if result has collected points:
        routes.add(result.route)
        remove collected points from remaining
    if remaining empty:
        break

if remaining not empty:
    mark remaining as unassigned

return routes + unassigned
```

关键问题：

- 车辆顺序如何确定；
- 大车优先还是小车优先；
- 同一车是否连续跑多趟；
- 第二趟起点是否是上一趟终点；
- 处理厂是否固定；
- 是否允许未分配点。

第一版可以使用明确输入的车辆顺序，不自动优化派车。

## 开发顺序

### 阶段 1：模型和单路线算法

- 定义标准领域模型。
- 实现内存 OD 矩阵。
- 实现单路线全路径插入。
- 使用 mock 数据测试。

### 阶段 2：接入现有 preview 接口

- 将 `/api/optimize/preview` 从占位返回改为调用算法。
- 先用当前路线规划点位做单路线优化。
- 返回优化前后点位顺序、距离、时长。

### 阶段 3：路线详情和 polyline

- 引入 OD 缓存结构。
- 返回每段距离和时长。
- 支持真实道路 polyline。
- 前端展示路线指标和折线。

当前实现状态：

- 已返回优化后路线的分段距离、估算时长和直线 path；
- 已返回可供前端绘制预览的 polyline 点列；
- 前端已展示优化后点位和分段距离；
- 已接入点位静态预计垃圾量，口径为桶数乘桶容量，再按公司 `liters_per_ton` 换算为 kg；
- 历史流水和过磅净重暂只作为后续校准依据，不在当前版本直接拆分到点位；
- 尚未接入 `ljszy_odpair_pool` 和百度真实道路 polyline。

### 阶段 4：多路线 MVP

- 加车辆输入。
- 加剩余点池。
- 输出多条路线。
- 输出未分配点及原因。

### 阶段 5：约束增强

- 时间窗；
- 通行条件；
- 点位频次；
- 多处理厂；
- 车辆排班策略；
- 历史路线学习指标。

## 与老 ljszy 的差异

老系统：

```text
数据库 DTO -> Service 大方法 -> OD 补算 -> 单路线算法 -> 字符串结果
```

新系统应改为：

```text
业务适配 -> 标准请求 -> OD 服务 -> 算法核心 -> 标准结果 -> 展示适配
```

核心差异：

- 算法不依赖数据库表；
- 结果不只是一串点位 ID；
- 未分配和不可行原因必须显式返回；
- 地图服务与算法服务分离；
- 单路线和多路线共用同一套模型。

## 暂不做的事

第一版不做：

- 全局最优数学规划；
- 复杂车辆排班优化；
- 自动学习司机历史路线；
- 自动修改正式业务路线；
- 司机端执行程序。

这些可以在基础路线优化能力稳定后再做。
