<script setup lang="ts">
import { $t } from '@/locales';

defineOptions({
  name: 'GlobalLogo'
});

interface Props {
  /** Whether to show the title */
  showTitle?: boolean;
}

withDefaults(defineProps<Props>(), {
  showTitle: true
});
</script>

<template>
  <RouterLink to="/" class="w-full flex items-center gap-10px overflow-hidden">
    <span class="brand-mark">
      <SystemLogo class="text-20px" />
    </span>
    <h2 v-show="showTitle" class="brand-word">{{ $t('system.title') }}</h2>
  </RouterLink>
</template>

<style scoped>
.brand-mark {
  display: inline-flex;
  height: 34px;
  width: 34px;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  border-radius: 11px;
  background: var(--brand-grad);
  color: #fff;
  box-shadow: 0 8px 18px -10px rgb(6 182 212 / 0.9);
  transition: transform 0.22s ease;
}

.brand-mark:hover {
  transform: translateY(-1px) rotate(-4deg);
}

.brand-word {
  margin: 0;
  font-size: 17px;
  font-weight: 700;
  letter-spacing: -0.01em;
  color: #fff;
}
</style>
