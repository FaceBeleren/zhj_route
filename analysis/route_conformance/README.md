# 路线吻合度分析脚本

本目录迁移自 `ljszy/scripts/route_conformance`，用于验证路线数据口径、生成统计结果，并为后续 Java 优化算法提供参考实现。

## 脚本说明

- `company_route_stats.py`：统计单个项目公司的公司、路线和流水三级吻合度。
- `multi_company_scores.py`：批量统计所有未删除部门/公司的路线吻合度得分。
- `route_conformance.py`：按公司或路线批量计算流水与规划路线的吻合度。
- `route_flow_monthly.py`：分析单月路线流水、指纹及累计唯一指纹。

脚本支持直接在 Spyder 中运行。无命令行参数时读取文件顶部的 `RUN_CONFIG`。

## 评分口径

- 规划序列：`ljszy_route_fac_banding` 按 `order_num, id` 排序后的 `fac_id`。
- 实际序列：路线流水对应月份的 `ljszy_route_facility_record_yyyyMM`，按点位时间排序后的 `facility_id`。
- 默认实际点位过滤：`facility_match_type = 0` 且 `is_route_facility = 1`。
- F1：基于规划点位集合与实际点位集合计算准确率和召回率。
- LCS 比例：`2 * 最长公共子序列长度 / (规划序列长度 + 实际序列长度)`。
- 综合得分：默认 `0.6 * F1 + 0.4 * LCS比例`。

这些脚本当前是分析与验证工具。生产接口应逐步将确认后的查询和评分逻辑迁移到 Spring Boot 后端。

## 使用

```powershell
cd D:\projects\zhj_route\analysis\route_conformance
python -m pip install -r requirements.txt
python company_route_stats.py
```

运行生成的 CSV 和输出目录已由项目 `.gitignore` 排除。
