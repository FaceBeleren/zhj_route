/** Return one deterministic longest common subsequence of point IDs. */
export function flowLcsSequence(plan, actual) {
  const table = Array.from({ length: plan.length + 1 }, () => Array(actual.length + 1).fill(0))
  for (let i = 1; i <= plan.length; i += 1) {
    for (let j = 1; j <= actual.length; j += 1) {
      table[i][j] = plan[i - 1] === actual[j - 1]
        ? table[i - 1][j - 1] + 1
        : Math.max(table[i - 1][j], table[i][j - 1])
    }
  }
  const result = []
  let i = plan.length
  let j = actual.length
  while (i > 0 && j > 0) {
    if (plan[i - 1] === actual[j - 1]) {
      result.push(plan[i - 1])
      i -= 1
      j -= 1
    } else if (table[i - 1][j] >= table[i][j - 1]) i -= 1
    else j -= 1
  }
  return result.reverse()
}
