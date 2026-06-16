# AGENTS.md

本项目使用中文协作。

## 项目定位

`repo_zhj_route` 是垃圾收运路线优化的重构与产品化项目。它不是从零开始的孤立项目，而是来自三类历史资产：

- 2021 年前后 OneNote 笔记中的清华算法研究脉络；
- 历史 Python 脚本，包括 `saving_main`、`fixcode`、`oldmethodwithdifferentrate`、`randommethod`、`newmethod`、`newmethodwithrate`、`simplemethod`、`completemethod`、`easymethod`、`py4java` 等；
- 旧 Java 业务系统 `ljszy`，路径曾为 `D:\projects\groupByMonths\202604\ljszy`。

本项目目标是把历史路线算法、旧系统落地经验、路线流水评分和新路线生成能力逐步整理成可演示、可迭代、可接入飞书生态的系统。

## 重要业务理解

历史算法主线大致是：

- 最初尝试基于距离、垃圾量、时间等因子的启发式路线选择；
- 后来形成“全路径插入”思想：对当前路线的每一段尝试插入候选点，选择增加距离最小的点；
- 当前实现中的单路线优化和多路线生成仍以该 cheapest-insertion 类贪心算法为基础；
- 该算法适合作为 MVP 和初解，但不是全局最优算法，后续应考虑 2-opt、relocate、swap、跨路线 relocate/swap，最后再评估 OR-Tools 等成熟 VRP 求解器。

旧 `ljszy` 里的关键表和字段：

- `ljszy_route_info`：路线/岗位主表，`data_type=0/null` 表示路线，`data_type=1` 表示岗位。
- `ljszy_route_fac_banding`：路线或岗位绑定的规划点位。
- `ljszy_route_record`：路线执行流水。
- `ljszy_route_facility_record_yyyyMM`：流水中的实际点位记录。
- `ljszy_facility_info`：设施点位主表。
- `ljszy_facility_container_info`：点位桶信息，可用于估算垃圾量。
- `ljszy_base_config.liters_per_ton`：体积到重量换算系数。
- `ljszy_transfer_station_configuration`：公司绑定中转站和处置场关系。

设施类型枚举来自旧系统 `EquipmentTypeEnum`：

- `2`：中转站；
- `4`：停车场；
- `5`：处置场；
- `6`：收集点。

## 当前功能状态

当前项目已实现三个主要页面：

- `路线详情`：查看公司路线/岗位、规划点位、流水点位、单路线优化预览。
- `多路线生成`：按公司点位池、预计垃圾量、目标装载率和最大趟数生成多条路线。
- `路线评分`：按历史流水对路线进行评分，并解释某条流水为什么得分高或低。

已实现的后端能力：

- `GET /api/companies`
- `GET /api/companies/{unitId}/routes`
- `GET /api/companies/{unitId}/facilities`
- `GET /api/companies/{unitId}/route-anchors`
- `GET /api/routes/{routeId}/plan-points`
- `GET /api/routes/{routeId}/records`
- `GET /api/records/{recordId}/points`
- `POST /api/optimize/preview`
- `POST /api/optimize/multi-preview`
- `GET /api/conformance/companies/score`
- `GET /api/conformance/companies/{unitId}/routes`
- `GET /api/conformance/routes/{routeId}/trips`
- `GET /api/conformance/routes/{routeId}/trips/{recordId}/explain`

路线评分当前口径：

- F1 用于衡量计划点位和实际流水点位的集合命中；
- LCS 用于衡量计划顺序和实际顺序的最长公共子序列；
- 当前综合分为 `F1 * 0.6 + LCS * 0.4`；
- 解释页中绿色表示最长顺序重合，橙色表示点位命中但顺序偏离，灰色表示未命中。

多路线生成当前口径：

- 公司点位池只取 `facility_type=6` 的收集点；
- 垃圾量通过桶数量和桶容量估算，再用 `liters_per_ton` 换算为 kg；
- 支持手动筛选点位，前端传 `facilityIds`，后端按所选点位生成路线；
- 起点优先使用停车场，终点优先使用处置场；缺失时可手填坐标或回退点位中心；
- 当前距离仍为经纬度 Haversine 直线距离，尚未接入真实道路 OD。

## 后续大方向

1. 飞书生态化
   - 飞书知识库沉淀路线算法、旧代码、OneNote 笔记和当前重构过程；
   - 飞书文档生成路线评分/优化报告；
   - 飞书多维表格承载点位、车辆、参数和人工确认结果；
   - 飞书机器人推送低分路线、异常点和优化结果；
   - 飞书审批/任务承接算法建议的人工确认。

2. 路线优化算法升级
   - 当前算法作为初解；
   - 后续加入 2-opt、relocate、swap；
   - 多路线阶段考虑跨路线 relocate/swap；
   - 最后再评估 OR-Tools。

3. 多路线生成与业务约束
   - 车辆载重量、车辆数量、车辆趟次；
   - 起终点、停车场、中转站、处置场选择；
   - 时间窗、通行限制、点位可见性、收运频次；
   - 未分配点原因解释；
   - 车辆排班顺序。

4. 路线工作台数据可视化
   - 地图展示规划路线、岗位、真实流水、优化后路线；
   - 多路线结果用不同颜色图层展示；
   - 接入真实道路 polyline 和 OD 矩阵。

5. 司机端路线指示
   - 手机端查看今日路线；
   - 一键导航到下一个点；
   - 完成、跳过、异常上报；
   - 执行结果回流评分系统。

补充基础能力：

- 数据质量体检：缺坐标、缺垃圾量、场站缺失、路线绑定异常、历史流水异常；
- OD 矩阵基础设施：地图 API、坐标系、缓存、限流、降级；
- 算法评估体系：覆盖率、计划外点比例、顺序一致性、里程节省、时间节省、装载率、未分配率；
- 方案版本管理：算法建议版、人工确认版、正式执行版、回滚和对比。

## 开发与运行

前端目录：

```powershell
cd "D:\桌面\工作事宜\代码\垃圾收运线路优化清华0720\repo_zhj_route\frontend"
npm run dev
```

前端常用访问地址可能是 `http://localhost:5173/`，也可能因端口占用变为 `5174/5175`。

后端目录：

```powershell
cd "D:\桌面\工作事宜\代码\垃圾收运线路优化清华0720\repo_zhj_route\backend"
mvn -q -DskipTests compile
```

Windows 上验证后端时，如 Maven 需要访问内网 Nexus，通常要临时清空代理变量：

```powershell
$env:HTTP_PROXY=''; $env:HTTPS_PROXY=''; $env:ALL_PROXY='';
$env:http_proxy=''; $env:https_proxy=''; $env:all_proxy='';
mvn -q -DskipTests compile
```

如果后端新增接口，必须重启后端服务；前端 Vite 通常可以热更新，但浏览器页面仍建议刷新。

## Git 与协作规则

- 只提交 `repo_zhj_route` 子项目内容，不把外层历史资料、报告、OneNote 导出等一起提交。
- 用户要求每个阶段保存一次中文 commit。
- 内网 Git 推送时临时放弃代理/清空代理变量，不要永久删除系统代理，因为用户连接 Codex 仍需要代理。
- 修改代码前先看当前文件和 `git status`，不要覆盖用户未提交改动。
- 不要使用破坏性命令，例如 `git reset --hard` 或 `git checkout --`，除非用户明确要求。

## 工作树建议

后续可按功能线切多个 worktree：

- `feature/route-score-visualization`：路线评分解释、评分可视化、评分报告；
- `feature/map-od-routing`：地图展示、OD 矩阵、真实道路 polyline；
- `feature/route-local-search`：2-opt、relocate、swap 等局部优化；
- `feature/multi-route-constraints`：载重、时间窗、点位可见性、车辆排班；
- `feature/lark-integration`：飞书知识库、文档、机器人、多维表格、审批；
- `feature/driver-route-guide`：司机端路线指示。

不要同时开太多。优先考虑评分可视化、地图 OD、局部优化三条线。
