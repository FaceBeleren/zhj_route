<template>
  <div>
    <span>规划道路里程</span>
    <strong>{{ formatKm(mileage.totalMeters) }}</strong>
    <small v-for="row in mileage.rows" :key="row.label">{{ row.label }}：{{ formatKm(row.meters) }}</small>
    <small>按来源方案的卸料和返场开关统计。</small>
  </div>
</template>

<script setup>
defineProps({ mileage: { type: Object, required: true } })

function formatKm(meters) {
  return (meters / 1000).toFixed(2) + ' km'
}
</script>
