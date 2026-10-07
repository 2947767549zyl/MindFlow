<script setup lang="ts">
import { h } from 'vue';
import { NButton } from 'naive-ui';
import { useEcharts } from '@/hooks/common/echarts';
import type { ECOption } from '@/hooks/common/echarts';
import { VueMarkdownIt, VueMarkdownItProvider } from '@/vendor/vue-markdown-shiki';

defineOptions({ name: 'WikiGraph' });

interface GraphNode {
  id: string;
  title: string;
  degree: number;
  isolated: boolean;
}

interface GraphLink {
  source: string;
  target: string;
}

interface WikiGraphData {
  nodes: GraphNode[];
  links: GraphLink[];
  nodeCount: number;
  linkCount: number;
}

interface WikiPageDetail {
  title: string;
  markdown: string;
  backlinks: string[];
  related: { title: string; markdown: string }[];
}

const loading = ref(false);
const nodes = ref<GraphNode[]>([]);
const links = ref<GraphLink[]>([]);
const keyword = ref('');
const viewMode = ref<'graph' | 'list'>('graph');

const detailVisible = ref(false);
const detailLoading = ref(false);
const detail = ref<WikiPageDetail | null>(null);

const COLOR_PALETTE = ['#5B8FF9', '#5AD8A6', '#F6BD16', '#E86452', '#6DC8EC', '#945FB9'];

function colorByDegree(degree: number): string {
  if (degree === 0) return '#A8ABB2';
  if (degree <= 2) return COLOR_PALETTE[0];
  if (degree <= 5) return COLOR_PALETTE[1];
  if (degree <= 9) return COLOR_PALETTE[2];
  if (degree <= 15) return COLOR_PALETTE[3];
  return COLOR_PALETTE[5];
}

function sizeByDegree(degree: number): number {
  return Math.min(44, 14 + degree * 3);
}

let chartInstance: any = null;

const option = {
  tooltip: {
    confine: true,
    formatter: (params: any) => {
      if (params.dataType === 'edge') {
        return `${params.data.source} → ${params.data.target}`;
      }
      return `${params.data.name}<br/>关联概念数：${params.data.degree}`;
    }
  },
  series: [
    {
      type: 'graph',
      layout: 'force',
      roam: true,
      draggable: true,
      label: { show: true, position: 'right', fontSize: 11, formatter: '{b}' },
      emphasis: { focus: 'adjacency', label: { show: true } },
      force: { repulsion: 320, edgeLength: [70, 150], gravity: 0.06 },
      edgeSymbol: ['none', 'arrow'],
      edgeSymbolSize: 6,
      lineStyle: { curveness: 0.08, opacity: 0.45, width: 1.2 },
      data: [] as any[],
      links: [] as any[]
    }
  ]
};

const { domRef, updateOptions } = useEcharts(() => option as unknown as ECOption, {
  onRender: (chart: any) => {
    chartInstance = chart;
    chart.on('click', (params: any) => {
      if (params.dataType === 'node') {
        openDetail(String(params.data.name));
      }
    });
  }
});

async function loadGraph() {
  loading.value = true;
  const { error, data } = await request<WikiGraphData>({ url: 'wiki/graph' });
  loading.value = false;
  if (error || !data) return;

  nodes.value = data.nodes || [];
  links.value = data.links || [];
  applyGraphData();
}

function applyGraphData() {
  option.series[0].data = nodes.value.map(node => ({
    id: node.id,
    name: node.title,
    degree: node.degree,
    symbolSize: sizeByDegree(node.degree),
    itemStyle: { color: colorByDegree(node.degree) }
  }));
  option.series[0].links = links.value.map(link => ({ source: link.source, target: link.target }));
  updateOptions(() => option as unknown as ECOption);
}

async function openDetail(title: string) {
  if (!title) return;
  detailVisible.value = true;
  detailLoading.value = true;
  const { error, data } = await request<WikiPageDetail>({
    url: 'wiki/page',
    params: { title }
  });
  detailLoading.value = false;
  if (error || !data || !data.title) {
    detail.value = null;
    window.$message?.warning(`未找到 Wiki 页面：${title}`);
    return;
  }
  detail.value = data;
  highlightNode(title);
}

function highlightNode(title: string) {
  if (!chartInstance) return;
  chartInstance.dispatchAction({ type: 'downplay', seriesIndex: 0 });
  chartInstance.dispatchAction({ type: 'highlight', seriesIndex: 0, name: title });
}

function locateByKeyword() {
  const kw = keyword.value.trim().toLowerCase();
  if (!kw) return;
  const matched = nodes.value.find(node => node.title.toLowerCase().includes(kw));
  if (!matched) {
    window.$message?.warning('未找到匹配的概念');
    return;
  }
  openDetail(matched.title);
}

const isolatedCount = computed(() => nodes.value.filter(node => node.isolated).length);

const tableColumns = [
  { title: '概念', key: 'title', ellipsis: { tooltip: true } },
  { title: '关联概念数', key: 'degree', width: 120 },
  {
    title: '状态',
    key: 'isolated',
    width: 110,
    render: (row: GraphNode) => (row.isolated ? '孤立节点' : '已连接')
  },
  {
    title: '操作',
    key: 'actions',
    width: 100,
    render: (row: GraphNode) => h(NButton, { size: 'tiny', onClick: () => openDetail(row.title) }, { default: () => '查看' })
  }
];

onMounted(() => {
  loadGraph();
});
</script>

<template>
  <div class="h-full flex-col gap-12px p-16px">
    <div class="flex-y-center justify-between gap-12px">
      <div class="flex-y-center gap-10px">
        <div class="text-16px font-medium">Wiki 知识图谱</div>
        <NTag size="small" :bordered="false">概念 {{ nodes.length }}</NTag>
        <NTag size="small" :bordered="false">关联 {{ links.length }}</NTag>
        <NTag v-if="isolatedCount > 0" size="small" :bordered="false" type="warning">孤立 {{ isolatedCount }}</NTag>
      </div>
      <div class="flex-y-center gap-8px">
        <NInput
          v-model:value="keyword"
          size="small"
          placeholder="搜索概念并定位"
          class="w-220px"
          clearable
          @keyup.enter="locateByKeyword"
        />
        <NButton size="small" @click="locateByKeyword">定位</NButton>
        <NButtonGroup size="small">
          <NButton :type="viewMode === 'graph' ? 'primary' : 'default'" @click="viewMode = 'graph'">图谱</NButton>
          <NButton :type="viewMode === 'list' ? 'primary' : 'default'" @click="viewMode = 'list'">列表</NButton>
        </NButtonGroup>
        <NButton size="small" @click="loadGraph">刷新</NButton>
      </div>
    </div>

    <div class="min-h-0 flex-1">
      <div v-show="viewMode === 'graph'" class="wiki-chart-wrap">
        <div ref="domRef" class="wiki-chart" />
        <div v-if="loading" class="wiki-overlay">加载中…</div>
        <NEmpty v-else-if="nodes.length === 0" description="暂无 Wiki 概念页" class="absolute-center" />
      </div>
      <NDataTable
        v-show="viewMode === 'list'"
        :columns="tableColumns"
        :data="nodes"
        :bordered="false"
        size="small"
        flex-height
        class="wiki-table"
      />
    </div>

    <NDrawer v-model:show="detailVisible" :width="620" placement="right">
      <NDrawerContent :title="detail?.title || '概念详情'" closable>
        <NSpin :show="detailLoading">
          <div v-if="detail" class="flex-col gap-14px">
            <VueMarkdownItProvider>
              <VueMarkdownIt :content="detail.markdown" />
            </VueMarkdownItProvider>

            <div v-if="detail.related.length > 0">
              <div class="mb-6px text-13px text-base-text opacity-70">关联概念（点击继续跳转，支持多跳探索）</div>
              <NSpace>
                <NButton
                  v-for="item in detail.related"
                  :key="item.title"
                  size="tiny"
                  secondary
                  type="primary"
                  @click="openDetail(item.title)"
                >
                  {{ item.title }}
                </NButton>
              </NSpace>
            </div>

            <div v-if="detail.backlinks.length > 0">
              <div class="mb-6px text-13px text-base-text opacity-70">被引用</div>
              <NSpace>
                <NButton v-for="title in detail.backlinks" :key="title" size="tiny" quaternary @click="openDetail(title)">
                  {{ title }}
                </NButton>
              </NSpace>
            </div>
          </div>
          <NEmpty v-else-if="!detailLoading" description="无内容" />
        </NSpin>
      </NDrawerContent>
    </NDrawer>
  </div>
</template>

<style scoped>
.wiki-chart-wrap {
  position: relative;
  width: 100%;
  height: 100%;
  min-height: 420px;
}

.wiki-chart {
  width: 100%;
  height: 100%;
}

.wiki-table {
  width: 100%;
  height: 100%;
  min-height: 420px;
}

.wiki-overlay {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  color: rgb(var(--text-color-3));
}
</style>
