# LJSZY 路线数据知识

本文记录新路线优化项目已确认的数据来源和业务口径。实现新接口或算法前，应先核实本文未确认的业务假设。

## 数据关系

```text
cloud_management.cloud_department
    id
      |
      +-- ljszy_route_info.department_id
      |       |
      |       +-- ljszy_route_fac_banding.route_id -> fac_id
      |
      +-- ljszy_route_record.unit_id
              |
              +-- ljszy_route_facility_record_yyyyMM.route_record_id
```

## 核心表

| 表 | 用途 | 关键字段 |
| --- | --- | --- |
| `cloud_management.cloud_department` | 公司/部门来源 | `id`, `depName`, `depCode`, `beenDeleted` |
| `ljszy_route_info` | 规划路线 | `id`, `name`, `department_id`, `data_type`, `been_deleted` |
| `ljszy_route_fac_banding` | 规划路线点位及顺序 | `route_id`, `fac_id`, `order_num`, `been_deleted` |
| `ljszy_facility_info` | 点位详情 | `id`, `name`, `facility_type_name`, 坐标 |
| `ljszy_route_record` | 路线流水 | `id`, `unit_id`, `route_id`, `car_start_time`, `car_end_time` |
| `ljszy_route_facility_record_yyyyMM` | 流水实际点位，按月分表 | `route_record_id`, `facility_id`, 点位时间及匹配状态 |

## 已确认查询口径

公司列表当前来自所有未删除部门：

```sql
SELECT id, depName, depCode
FROM cloud_management.cloud_department
WHERE beenDeleted = 0
```

该结果不一定全部是实际项目公司，因此某些单位路线数为零是正常情况。后续需要确认项目公司的准确筛选条件。

公司规划路线当前按以下条件获取：

```sql
SELECT id, name
FROM ljszy_route_info
WHERE been_deleted = 0
  AND department_id = ?
  AND (data_type = 0 OR data_type IS NULL)
```

规划点位顺序：

```sql
SELECT route_id, fac_id
FROM ljszy_route_fac_banding
WHERE been_deleted = 0
ORDER BY route_id, IFNULL(order_num, 999999), id
```

实际点位数据按流水开始时间定位月份分表，通常按以下顺序排列：

```sql
ORDER BY COALESCE(entry_point_time, create_time), id
```

## PathAlgorithmData 结论

原项目中的 `PathAlgorithmData` 主要负责为清华路径算法统计和补充数据，不是路线顺序优化算法本身。

- `routeRecordDataTask`：统计已完成流水的终端频次、发车速度、作业速度、回场速度，并补充点位进出时间。
- `routeFacilityRecordDataTask`：按点位分组，根据近 7 天流水计算收运频次。
- 实际启用的是 scheduler 模块中的 XXL-JOB 任务。
- service 模块中的同名定时类已整体注释。
- 当前链路未确认存在 `addTask` 或自动推荐终点操作。

## 后续算法接入要求

优化算法实现前必须明确：

- 起点和终点是否固定；
- 是否允许添加、删除或重复点位；
- 点位时间窗、载重和频次约束；
- 优化目标是距离、时间、成本还是历史执行吻合度；
- 优化结果是否仅预览，以及确认后的保存位置。

在规则未确认前，不应直接修改原有规划路线或业务流水数据。
