# ljszy 路线优化落地链路

本文整理老 `ljszy` 项目中路线优化的实际落地方式，供 `zhj_route` 后续迁移和重构参考。

老项目路径参考：

```text
D:\projects\groupByMonths\202604\ljszy
```

## 结论

老系统真正落地的是“单条路线点位顺序优化”，不是多路线规划。

它的能力是：

```text
用户给定一条路线中的起点、终点和收运点
系统优化这些点的经过顺序
返回优化前后路线、总距离、总时间和地图折线
```

它没有实现：

- 多车辆多路线自动生成；
- 车辆载重约束；
- 车辆排班；
- 点位频次；
- 点位时间窗可见性；
- 多处理厂动态选择。

## 核心代码位置

| 功能 | 文件 |
| --- | --- |
| 路线优化接口 | `hw-cloud-ljszy-controller/.../RouteOptimiseController.java` |
| 算法任务表模型 | `hw-cloud-ljszy-model/.../AlgTask.java` |
| 任务状态枚举 | `hw-cloud-ljszy-support/.../AlgTaskEnum.java` |
| 定时扫描任务 | `hw-cloud-ljszy-scheduler/.../Scheduler.java` |
| 优化主服务 | `hw-cloud-ljszy-service/.../RouteOptimiseServiceImpl.java` |
| 单路线算法 | `hw-cloud-ljszy-support/.../ZhjAlgUpdateUtils.java` |
| OD 矩阵模型 | `hw-cloud-ljszy-model/.../ODPairPool.java` |
| OD 矩阵 Mapper | `OdpairPoolMapper.xml` |

## 任务链路

整体链路：

```text
addtask
-> setOptimiseTask
-> 写入 ljszy_alg_task
-> syncPathAlgTask 定时扫描
-> getRoute
-> getSingleOdPairsList 查询或补齐 OD 矩阵
-> singleRouteAlg 计算点位顺序
-> 更新 route_names 和 alg_status
-> viewTask 查询优化前后路线
-> getRouteCount 生成前端展示数据
```

## AlgTask 数据结构

`ljszy_alg_task` 是任务表，主要字段：

| 字段 | 含义 |
| --- | --- |
| `alg_status` | 任务状态：待运算、运算中、完成、失败 |
| `mission_code` | 任务编号 |
| `pre_route_names` | 优化前路线，点位 ID 逗号分隔 |
| `route_names` | 优化后路线，点位 ID 逗号分隔 |
| `contain_points` | 中间收运点 |
| `start_point` | 起点 |
| `start_type` | 起点类别，如停车场或收运点 |
| `end_point` | 终点，可多个 |
| `end_choose` | 是否由算法推荐终点，历史中基本未完成 |
| `param_1` | 路线或岗位类型 |
| `param_2` | 用户 ID |

状态枚举：

| 值 | 含义 |
| --- | --- |
| `0` | 待运算 |
| `1` | 运算中 |
| `2` | 运算完毕 |
| `-1` | 运算错误 |

## 定时任务

`Scheduler.syncPathAlgTask` 通过 XXL-JOB 每 10 分钟扫描一次待运算任务：

1. 查询 `alg_status = 0` 的任务。
2. 将状态改成 `1`。
3. 加入 `ConcurrentLinkedQueue`。
4. 同步调用 `routeOptimiseService.getRoute(...)`。

这说明老系统不是请求内立即计算，而是通过任务表异步运算。

## OD 矩阵查询与补算

路线算法不能只依赖点位坐标。老系统使用 `ljszy_odpair_pool` 缓存点对距离、耗时和百度完整返回。

`getSingleOdPairsList(allPoints, firstPointType)` 的逻辑：

1. 构造本次路线所需全量点对：

```text
allPoints x allPoints
```

2. 查询 `ljszy_odpair_pool` 已存在的点对。
3. 计算缺失点对。
4. 查询缺失点对两端点坐标。
5. 调百度驾车路线接口。
6. 把返回的距离、耗时和完整 JSON 写入 `ljszy_odpair_pool`。
7. 把新查出的 OD 也加入本次算法输入。

OD 池关键字段：

| 字段 | 含义 |
| --- | --- |
| `start_code` | 起点编码 |
| `end_code` | 终点编码 |
| `longitude_start` / `latitude_start` | 起点坐标 |
| `longitude_end` / `latitude_end` | 终点坐标 |
| `distance` | 百度返回距离 |
| `time_duration` | 百度返回耗时 |
| `msg_full` | 百度接口完整返回 JSON |

`msg_full` 对算法排序不是必须，但对前端画真实道路折线很关键。

## 百度接口

老系统调用百度 `directionlite/v1/driving`。

主要参数：

- `origin`
- `destination`
- `tactics = 0`
- `ak`
- `sn`
- `timestamp`

返回中使用：

- `routes[0].distance`
- `routes[0].duration`
- `routes[].steps[].path`

历史代码中 `ak/sk` 写在 Java 代码里。新项目应改成配置项，不能硬编码。

## 单路线算法

`ZhjAlgUpdateUtils.singleRouteAlg(...)` 是真正排序函数。

输入：

- 点位列表；
- OD 矩阵；
- 起点；
- 终点。

输出：

- 优化后的点位编码序列。

算法逻辑：

```text
routes = [startPoint, endPoint]
while 还有未加入点:
    遍历所有候选点
    遍历当前路线所有相邻段
    delta = d(A, P) + d(P, B) - d(A, B)
    选择 delta 最小的点 P 和插入位置
    插入路线
    从候选点删除 P
return routes
```

该函数只看距离增量，不看：

- 垃圾量；
- 车辆载重；
- 作业时间；
- 时间窗；
- 通行条件；
- 车辆数量；
- 多路线拆分。

因此迁移时应把它视为“单路线排序基础算法”，不是完整路线规划系统。

## 多终点处理

如果用户传入多个终点，老系统会：

1. 对每个终点分别调用 `singleRouteAlg`。
2. 计算每条结果路线的总距离。
3. 选择总距离最短的路线。

这不是完整的处理厂选择模型，只是多个终点候选中的最短路线选择。

## 查看接口与前端渲染

`viewTask` 返回优化前后两条路线：

```text
TwoRouteCountDto
  beforeRoutes
  afterRoutes
```

每条路线是 `RouteCountDto`：

| 字段 | 含义 |
| --- | --- |
| `allLength` | 总距离 |
| `allTime` | 总时间 |
| `routeMessage` | 路线上的点位信息 |
| `allPointPositions` | 真实道路折线坐标 |

`allPointPositions` 的生成方式：

1. 遍历路线相邻点对。
2. 从 `ljszy_odpair_pool` 查询该点对最新记录。
3. 解析 `msg_full`。
4. 读取 `result.routes[].steps[].path`。
5. 按 `;` 拆分 path。
6. 将所有路径点扁平化返回给前端。

因此前端画的不是简单点到点直线，而是百度返回的真实道路折线。

## 对 zhj_route 的迁移启示

不要只迁 `singleRouteAlg`。可运行产品至少需要四层：

1. 标准输入输出模型。
2. OD 矩阵服务。
3. 路线优化算法。
4. 路线展示数据生成。

推荐迁移顺序：

1. 定义标准模型：点位、车辆、设施、OD、路线、结果。
2. 实现 OD 服务：先查缓存，缺失则地图补算并回写。
3. 改写单路线算法：去掉老 DTO 依赖。
4. 生成路线详情：距离、时长、点位信息、真实道路 polyline。
5. 再扩展多路线、多车辆、多约束。

## 需要避免的历史问题

- 不要硬编码地图 AK/SK。
- 不要把算法和数据库 DTO 绑死。
- 不要把路线 ID 字符串逗号拼接作为核心数据结构。
- 不要只返回点位序列，应同时返回指标和不可行原因。
- 不要把 OD 查询、地图调用、算法排序混在一个大 service 里。
- 不要在没有约束解释的情况下直接修改正式规划路线。
