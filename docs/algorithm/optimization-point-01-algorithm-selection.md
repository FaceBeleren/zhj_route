# 优化点 1：算法选择与小规模全局最优校验

## 背景

当前项目已经实现了以“全路径插入”为核心的自研路线优化算法。该算法来自历史 Python 脚本中 `newmethod`、`simplemethod`、`completemethod` 等阶段的思想：从起点和终点构成初始路线，遍历候选点和当前路线中的每一段，选择插入后距离增量最小的点。

不过历史脚本中不只有这一种算法思想。早期还有清华算法路线合并类脚本，如 `saving_main`、`fixcode`、`oldmethodwithdifferentrate`、`randommethod` 等；后续也提出过把垃圾量、距离增量、单位收运收益等指标加入选择函数的想法。

因此，后续不应只保留一个固定算法，而应把算法能力做成可选择、可比较、可解释的配置项。

## 目标

在单路线优化和后续多路线生成中增加“算法设置”，允许用户选择不同算法和策略，并在小点量场景下启用全局穷举作为最优解校验。

目标不是一开始做复杂算法平台，而是先把历史算法思想产品化为几个清晰选项：

- 清华算法：用于历史复现和对照；
- 自研算法：当前主线，全路径插入；
- 自研策略：距离增量最小、单位增量多收垃圾量等；
- 全局穷举：小点量下计算理论最短路线，用于校验启发式算法效果。

## 前端设计

在路线优化入口增加算法设置区域。

建议字段：

| 字段 | 说明 |
| --- | --- |
| 算法类型 | 清华算法 / 自研算法 / 全局穷举 |
| 自研策略 | 距离增量最小 / 单位增量多收垃圾量 / 距离垃圾量加权 |
| 全局最优校验 | 仅当点位数量较少时可启用 |

单路线优化中可以完整开放这些选项。多路线生成中第一阶段先只开放自研算法策略，因为全局穷举不适合多路线大点量场景。

## 算法类型

### 1. 清华算法

用于复现早期清华路线合并类算法思想。

大致思路：

```text
每个点位先作为独立路线
按点对距离或节省值排序
尝试合并路线
合并后检查容量、时间等约束
输出若干可行路线或最优路线
```

第一版可以先不完整实现全部清华逻辑，而是作为后续历史算法复现入口保留。

### 2. 自研算法

当前主算法，全路径插入。

基础策略：

```text
route = [start, end]
remaining = candidate points

while remaining not empty:
    遍历 remaining 中每个候选点 P
    遍历当前路线中每一段 [A, B]
    计算插入增量 delta = d(A,P) + d(P,B) - d(A,B)
    根据策略计算 score
    选择 score 最优的插入动作
    插入路线
```

当前已实现的是“距离增量最小”：

```text
score = deltaDistance
选择 score 最小
```

后续要扩展为可切换策略。

### 3. 全局穷举

用于小点量最优解校验。

固定起点和终点时，只排列中间点：

```text
start + permutations(middlePoints) + end
```

计算每一种排列的总距离，选择最短路线。

建议启用规则：

| 中间点数量 | 策略 |
| --- | --- |
| <= 8 | 可直接启用 |
| 9-10 | 可启用，但提示可能较慢 |
| > 10 | 禁用 |

原因是阶乘增长很快：

```text
8! = 40,320
9! = 362,880
10! = 3,628,800
```

全局穷举不是生产主算法，而是小样本基准，用来判断启发式算法离最优解差多少。

## 自研策略

### 1. 距离增量最小

当前策略。

```text
score = deltaDistance
选择 score 最小
```

优点：简单、稳定、容易解释。

缺点：只关心多走多少路，不关心这个点能收多少垃圾。

### 2. 单位增量多收垃圾量

把垃圾量加入选择函数。

一种写法：

```text
score = deltaDistance / amount
选择 score 最小
```

含义：每多走一米，能收多少垃圾。这个值越小，表示单位垃圾的路线成本越低。

也可以写成收益最大化：

```text
score = amount / max(deltaDistance, epsilon)
选择 score 最大
```

需要注意：当 deltaDistance 很小或为 0 时，要加 `epsilon` 防止除零。

### 3. 距离 + 垃圾量加权

后续可扩展为：

```text
score = alpha * normalizedDeltaDistance - beta * normalizedAmount
```

其中：

- `alpha` 控制距离成本权重；
- `beta` 控制垃圾量收益权重；
- 需要做归一化，否则不同单位量纲会互相干扰。

第一版不建议直接做太复杂，先实现“距离增量最小”和“单位增量多收垃圾量”即可。

## 后端请求参数

建议在优化请求中增加：

```json
{
  "algorithm": "SELF_INSERTION",
  "strategy": "MIN_DISTANCE_DELTA",
  "enableExactSearch": false
}
```

枚举建议：

```text
algorithm:
- TSINGHUA_SAVINGS
- SELF_INSERTION
- EXACT_SEARCH

strategy:
- MIN_DISTANCE_DELTA
- MAX_AMOUNT_PER_DISTANCE_DELTA
- WEIGHTED_DISTANCE_AMOUNT
```

其中：

- `TSINGHUA_SAVINGS` 后续实现；
- `SELF_INSERTION` 对应当前全路径插入；
- `EXACT_SEARCH` 对应小点量全局穷举；
- `enableExactSearch` 可以作为“在当前算法之外额外做最优校验”的开关。

## 后端返回结果

建议返回：

```json
{
  "algorithm": "SELF_INSERTION",
  "strategy": "MIN_DISTANCE_DELTA",
  "exactSearchEnabled": true,
  "exactDistance": 12345.0,
  "exactGapDistance": 300.0,
  "exactGapRate": 0.024
}
```

含义：

| 字段 | 说明 |
| --- | --- |
| algorithm | 实际使用算法 |
| strategy | 实际使用策略 |
| exactDistance | 小点量穷举得到的最短距离 |
| exactGapDistance | 当前算法距离与最优距离差值 |
| exactGapRate | 当前算法相对最优解的偏差比例 |

这样前端可以展示：

```text
当前算法距离：12.65 km
小样本最优距离：12.35 km
偏差：2.4%
```

## 实施顺序

建议分阶段做：

1. 后端抽象算法参数，不改变当前默认行为。
2. 自研算法支持策略枚举，默认仍为 `MIN_DISTANCE_DELTA`。
3. 增加 `MAX_AMOUNT_PER_DISTANCE_DELTA` 策略。
4. 单路线优化增加 `EXACT_SEARCH` 穷举算法。
5. 单路线优化结果展示最优偏差。
6. 后续再复现清华 `TSINGHUA_SAVINGS` 算法。
7. 多路线生成暂时只接自研策略，不开放全局穷举。

## 价值

这个优化点的价值不是单纯多几个按钮，而是把历史算法资产变成可运行、可验证的产品能力：

- 保留清华算法和自研算法的历史脉络；
- 让用户能选择不同优化目标；
- 用小规模最优解检验启发式算法是否离谱；
- 为后续 2-opt、relocate、swap、OR-Tools 等算法扩展留下接口；
- 汇报时可以清楚说明“当前算法不是拍脑袋，而是可以和最优解、历史算法做对照”。

## 注意事项

- 全局穷举只适合小点量，不可用于公司级大点池。
- 单位增量垃圾量策略依赖预计垃圾量质量；如果垃圾量估算不准，策略可能误导路线。
- 清华算法复现应作为对照，不应立即替代当前主算法。
- 算法选择结果必须写入返回值和导出结果，避免后续不知道某次结果是按什么策略生成的。
