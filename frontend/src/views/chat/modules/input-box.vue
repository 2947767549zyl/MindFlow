<script setup lang="ts">
import { h } from 'vue';
import { NButton, NInput, NSpace } from 'naive-ui';
import { useStorage } from '@vueuse/core';

const chatStore = useChatStore();
const { connectionStatus, input, isRateLimited, list, rateLimitRemainingSeconds, wsData } = storeToRefs(chatStore);

function buildWsErrorMessage(data: Record<string, any>) {
  if (data.code === 429) {
    const retryAfterSeconds = Number(data.retryAfterSeconds || 0);
    const baseMessage = data.message || '聊天请求过于频繁';

    if (retryAfterSeconds > 0) {
      return `${baseMessage}，请在 ${retryAfterSeconds} 秒后重试`;
    }

    return `${baseMessage}，请稍后再试`;
  }

  if (typeof data.error === 'string' && data.error.trim()) {
    return data.error.trim();
  }

  if (typeof data.message === 'string' && data.message.trim()) {
    return data.message.trim();
  }

  return '服务器繁忙，请稍后再试';
}

const latestMessage = computed(() => {
  return list.value[list.value.length - 1] ?? {};
});

let generationStatusTimer: number | null = null;
let lastStreamContentLength = 0;
let lastStreamContentChangedAt = 0;

const isSending = computed(() => {
  return (
    latestMessage.value?.role === 'assistant' && ['loading', 'pending'].includes(latestMessage.value?.status || '')
  );
});

const sendDisabled = computed(() => {
  if (isSending.value) {
    return false;
  }
  if (isRateLimited.value) {
    return true;
  }
  return !input.value.message || ['CLOSED', 'CONNECTING'].includes(connectionStatus.value);
});

const connectionText = computed(() => {
  if (connectionStatus.value === 'OPEN') {
    return '已连接';
  }
  if (connectionStatus.value === 'RECONNECTING') {
    return '重连中';
  }
  if (connectionStatus.value === 'CONNECTING') {
    return '连接中';
  }
  return '未连接';
});

const cooldownText = computed(() => {
  if (!isRateLimited.value) {
    return '';
  }
  return `${rateLimitRemainingSeconds} 秒后可重新发送`;
});

function findAssistantMessage(generationId?: string) {
  if (generationId) {
    for (let i = list.value.length - 1; i >= 0; i -= 1) {
      const item = list.value[i];
      if (item?.role === 'assistant' && item.generationId === generationId) {
        return item;
      }
    }
  }

  const latest = list.value[list.value.length - 1];
  if (latest?.role === 'assistant') {
    return latest;
  }

  return null;
}

function handleStartPayload(assistant: Api.Chat.Message, payload: Record<string, any>) {
  assistant.generationId = payload.generationId || assistant.generationId;
  assistant.conversationId = payload.conversationId || assistant.conversationId;
  if (!assistant.timestamp && payload.timestamp) {
    assistant.timestamp = new Date(payload.timestamp).toISOString();
  }
}

function handleCompletionPayload(assistant: Api.Chat.Message, payload: Record<string, any>) {
  if (payload.status === 'finished' && assistant.status !== 'error') {
    assistant.status = 'finished';
  } else if (payload.status === 'failed') {
    assistant.status = 'error';
  }

  if (payload.referenceMappings) {
    assistant.referenceMappings = payload.referenceMappings;
  }
  markExecutingToolsAsSuccess(assistant);
  stopGenerationStatusMonitor();
}

function handleStopPayload(assistant: Api.Chat.Message) {
  if (assistant.status !== 'error') {
    assistant.status = 'finished';
  }
  markExecutingToolsAsSuccess(assistant);
  stopGenerationStatusMonitor();
}

function handleErrorPayload(assistant: Api.Chat.Message, payload: Record<string, any>) {
  if (Number(payload.code) === 429) {
    chatStore.startRateLimitCountdown(Number(payload.retryAfterSeconds || 0));
  }

  const message = buildWsErrorMessage(payload);
  assistant.status = 'error';
  assistant.content = message;
  markExecutingToolsAsFailed(assistant);
  stopGenerationStatusMonitor();

  if (Number(payload.code) === 429) {
    window.$message?.warning(message);
  } else {
    window.$notification?.error({
      title: '本次回答失败',
      content: message,
      duration: 0,
      keepAliveOnHover: true
    });
  }
}

function showFailureNotice(payload: Record<string, any>) {
  const isWarning = payload.level === 'warning';
  const detail = payload.detail || payload.message || '未知原因';
  const retryAfterSeconds = Number(payload.retryAfterSeconds || 0);
  const retryHint = retryAfterSeconds > 0 ? `（建议 ${retryAfterSeconds} 秒后重试）` : '';
  const scopeLabel = payload.scope === 'plan_task' ? '计划任务' : payload.scope === 'plan' ? '计划' : '本次执行';
  const title = payload.title || `${scopeLabel}失败`;

  if (isWarning) {
    window.$message?.warning(`${title}：${detail}${retryHint}`);
    return;
  }

  window.$notification?.error({
    title,
    content: `${detail}${retryHint}`,
    duration: 0,
    keepAliveOnHover: true,
    meta: payload.retryable ? '可重试' : ''
  });
}

function handleChunkPayload(assistant: Api.Chat.Message, payload: Record<string, any>) {
  assistant.status = 'loading';
  assistant.content += payload.chunk;
  lastStreamContentLength = assistant.content.length;
  lastStreamContentChangedAt = Date.now();
}

function stopGenerationStatusMonitor() {
  if (generationStatusTimer !== null) {
    window.clearInterval(generationStatusTimer);
    generationStatusTimer = null;
  }
}

function startGenerationStatusMonitor() {
  stopGenerationStatusMonitor();
  const startedAt = Date.now();
  lastStreamContentLength = 0;
  lastStreamContentChangedAt = startedAt;
  generationStatusTimer = window.setInterval(async () => {
    const assistant = findAssistantMessage(latestMessage.value?.generationId);
    if (!assistant || assistant.role !== 'assistant') {
      stopGenerationStatusMonitor();
      return;
    }
    if (!['pending', 'loading'].includes(assistant.status || '')) {
      stopGenerationStatusMonitor();
      return;
    }
    if (Date.now() - startedAt > 130_000) {
      stopGenerationStatusMonitor();
      return;
    }
    if (assistant.content.length !== lastStreamContentLength) {
      lastStreamContentLength = assistant.content.length;
      lastStreamContentChangedAt = Date.now();
      return;
    }
    if (Date.now() - lastStreamContentChangedAt < 8000) {
      return;
    }

    const snapshot = await chatStore.fetchGenerationSnapshot(assistant.generationId || '');
    if (!snapshot || snapshot.status === 'STREAMING') {
      return;
    }
    chatStore.upsertGenerationSnapshot(snapshot);
    const refreshedAssistant = findAssistantMessage(snapshot.generationId);
    if (refreshedAssistant?.status === 'finished') {
      markExecutingToolsAsSuccess(refreshedAssistant);
      stopGenerationStatusMonitor();
    } else if (refreshedAssistant?.status === 'error') {
      markExecutingToolsAsFailed(refreshedAssistant);
      stopGenerationStatusMonitor();
    }
  }, 2000);
}

function markExecutingToolsAsSuccess(assistant: Api.Chat.Message) {
  updateExecutingToolStatus(assistant, 'success');
}

function markExecutingToolsAsFailed(assistant: Api.Chat.Message) {
  updateExecutingToolStatus(assistant, 'failed');
}

function updateExecutingToolStatus(assistant: Api.Chat.Message, status: Api.Chat.AgentToolEvent['status']) {
  if (!assistant.toolEvents?.length) {
    return;
  }

  let changed = false;
  const timestamp = Date.now();
  const toolEvents = assistant.toolEvents.map(event => {
    if (event.status !== 'executing') {
      return event;
    }
    changed = true;
    return {
      ...event,
      status,
      timestamp
    };
  });

  if (changed) {
    assistant.toolEvents = toolEvents;
  }
}

function handleToolCallPayload(assistant: Api.Chat.Message, payload: Record<string, any>) {
  const id = typeof payload.toolCallId === 'string' ? payload.toolCallId : '';
  const tool = typeof payload.tool === 'string' ? payload.tool : '';
  const status = typeof payload.status === 'string' ? payload.status : 'executing';
  if (!tool || !['executing', 'success', 'failed'].includes(status)) {
    return;
  }

  assistant.status = 'loading';
  assistant.toolEvents ||= [];

  const matchEvent = (item: Api.Chat.AgentToolEvent) => {
    if (id && item.id) {
      return item.id === id;
    }
    if (!id && !item.id) {
      return item.tool === tool;
    }
    return false;
  };

  const existing = assistant.toolEvents.find(matchEvent);
  const event: Api.Chat.AgentToolEvent = {
    ...existing,
    id,
    tool,
    status: status as Api.Chat.AgentToolEvent['status'],
    timestamp: Number(payload.timestamp || Date.now())
  };

  if (typeof payload.arguments === 'string') {
    event.arguments = payload.arguments;
  }
  if (typeof payload.resultPreview === 'string') {
    event.resultPreview = payload.resultPreview;
  }
  if (typeof payload.elapsedMs === 'number' && payload.elapsedMs > 0) {
    event.elapsedMs = payload.elapsedMs;
  }

  if (existing) {
    assistant.toolEvents = assistant.toolEvents.map(item => (matchEvent(item) ? event : item));
  } else {
    assistant.toolEvents = [...assistant.toolEvents, event];
  }
}

function handleAgentRoundPayload(assistant: Api.Chat.Message, payload: Record<string, any>) {
  const round = Number(payload.round);
  if (!Number.isFinite(round) || round <= 0) {
    return;
  }

  const event: Api.Chat.AgentRound = {
    round,
    phase: payload.phase === 'finished' ? 'finished' : 'started',
    elapsedMs: Number(payload.elapsedMs || 0),
    promptTokens: Number(payload.promptTokens || 0),
    completionTokens: Number(payload.completionTokens || 0),
    timestamp: Number(payload.timestamp || Date.now())
  };

  assistant.status = 'loading';
  assistant.agentRounds ||= [];

  if (assistant.agentRounds.some(item => item.round === round)) {
    assistant.agentRounds = assistant.agentRounds.map(item => (item.round === round ? event : item));
  } else {
    assistant.agentRounds = [...assistant.agentRounds, event];
  }
}

function handleMemoryFindingPayload(assistant: Api.Chat.Message, payload: Record<string, any>) {
  const summary = typeof payload.summary === 'string' ? payload.summary.trim() : '';
  if (!summary) {
    return;
  }

  assistant.findings ||= [];
  assistant.findings = [
    ...assistant.findings,
    {
      round: Number(payload.round || 0),
      tool: typeof payload.tool === 'string' ? payload.tool : '',
      summary,
      timestamp: Number(payload.timestamp || Date.now())
    }
  ];
}

watch(wsData, val => {
  if (!val) return;

  let payload: Record<string, any>;

  try {
    payload = JSON.parse(val);
  } catch {
    return;
  }

  if (payload.type === 'plan_review') {
    handlePlanReviewPayload(payload);
    return;
  }

  if (payload.type === 'approval_request') {
    handleApprovalRequestPayload(payload);
    return;
  }

  if (payload.type === 'notice') {
    showFailureNotice(payload);
    return;
  }

  if (payload.type === 'task_clarification') {
    handleTaskClarificationPayload(payload);
    return;
  }

  if (payload.type === 'failure_decision') {
    handleFailureDecisionPayload(payload);
    return;
  }

  const assistant = findAssistantMessage(payload.generationId);

  if (!assistant) {
    if (payload.error || Number(payload.code) >= 400) {
      showFailureNotice({
        level: Number(payload.code) === 429 ? 'warning' : 'error',
        title: '本次执行失败',
        detail: buildWsErrorMessage(payload),
        retryAfterSeconds: payload.retryAfterSeconds
      });
    }
    return;
  }

  if (payload.type === 'start') {
    handleStartPayload(assistant, payload);
    return;
  }

  if (payload.type === 'completion') {
    handleCompletionPayload(assistant, payload);
    return;
  }

  if (payload.type === 'tool_call') {
    handleToolCallPayload(assistant, payload);
    return;
  }

  if (payload.type === 'agent_round') {
    handleAgentRoundPayload(assistant, payload);
    return;
  }

  if (payload.type === 'memory_finding') {
    handleMemoryFindingPayload(assistant, payload);
    return;
  }

  if (payload.type === 'plan_step' || payload.type === 'plan_status' || payload.type === 'team_event') {
    handlePlanEventPayload(assistant, payload);
    return;
  }

  if (payload.type === 'stop') {
    handleStopPayload(assistant);
    return;
  }

  if (payload.error || Number(payload.code) >= 400) {
    handleErrorPayload(assistant, payload);
    return;
  }

  if (payload.chunk) {
    handleChunkPayload(assistant, payload);
  }
});

const planFeedback = ref('');
const approvalFeedback = ref('');
const planSelections = ref<Record<string, string>>({});
const clarifyChoice = ref('');
const clarifyNote = ref('');

const sendPlanReviewResponse = (planId: string, action: string, feedback?: string) => {
  chatStore.wsSend(
    JSON.stringify({
      type: 'plan_review_response',
      planId,
      action,
      feedback,
      selections: action === 'cancel' ? {} : planSelections.value
    })
  );
};

const sendTaskClarificationResponse = (clarificationId: string, choice: string, note?: string) => {
  chatStore.wsSend(JSON.stringify({ type: 'task_clarification_response', clarificationId, choice, note }));
};

const sendApprovalResponse = (approvalId: string, decision: string) => {
  chatStore.wsSend(JSON.stringify({ type: 'approval_response', approvalId, decision }));
};

const sendFailureDecisionResponse = (decisionId: string, action: string) => {
  chatStore.wsSend(JSON.stringify({ type: 'failure_decision_response', decisionId, action }));
};

const failureDecisionLabels: Record<string, { text: string; type?: 'default' | 'error' | 'primary' }> = {
  retry: { text: '重试这些任务' },
  replan: { text: '重新规划' },
  skip: { text: '跳过并继续' },
  abort: { text: '终止', type: 'error' }
};

const handleFailureDecisionPayload = (payload: Record<string, any>) => {
  const tasks = Array.isArray(payload.tasks) ? payload.tasks : [];
  const options: string[] =
    Array.isArray(payload.options) && payload.options.length ? payload.options : ['retry', 'skip', 'abort'];
  const unit = payload.scope === 'team_step' ? '步骤' : '任务';
  const taskLines = tasks.map(t => `· [${t.id}] ${t.description}\n   失败原因：${t.error || '未知'}`).join('\n');
  const timeoutSeconds = Number(payload.timeoutSeconds || 0);
  const assistant = findAssistantMessage(payload.generationId);
  if (assistant) {
    assistant.content += `\n\n❌ ${tasks.length} 个${unit}失败，等待你的处理决定...\n${taskLines}`;
  }

  const taskNodes = tasks.map((task, index) =>
    h('div', { class: 'mf-dlg__step' }, [
      h('span', { class: 'mf-dlg__step-idx' }, String(index + 1)),
      h('span', { class: 'mf-dlg__step-desc' }, [
        h('div', {}, `${task.id ? `[${task.id}] ` : ''}${task.description || ''}`),
        h('div', { class: 'mf-dlg__err' }, task.error || '未知原因')
      ])
    ])
  );

  const dialog = window.$dialog?.error({
    title: `${tasks.length} 个${unit}失败，请选择处理方式`,
    content: () =>
      h('div', { class: 'mf-dlg' }, [
        h('div', { class: 'mf-dlg__meta' }, [
          h('span', { class: 'mf-dlg__badge mf-dlg__badge--high' }, '需要你的决定'),
          h('span', { class: 'mf-dlg__label', style: 'margin:0' }, `作用域 ${payload.scope || unit}`)
        ]),
        h('div', { class: 'mf-dlg__scroll' }, taskNodes),
        timeoutSeconds > 0
          ? h('div', { class: 'mf-dlg__label', style: 'margin:0' }, `${timeoutSeconds} 秒内未选择将默认跳过`)
          : null
      ]),
    action: () =>
      h(NSpace, null, {
        default: () =>
          options.map(option =>
            h(
              NButton,
              {
                size: 'small',
                type: failureDecisionLabels[option]?.type ?? 'default',
                onClick: () => {
                  dialog?.destroy();
                  sendFailureDecisionResponse(payload.decisionId, option);
                }
              },
              { default: () => failureDecisionLabels[option]?.text ?? option }
            )
          )
      })
  });
};

const handleTaskClarificationPayload = (payload: Record<string, any>) => {
  const options: Record<string, any>[] = Array.isArray(payload.options) ? payload.options : [];
  clarifyChoice.value = options[0]?.label || '';
  clarifyNote.value = '';
  const timeoutSeconds = Number(payload.timeoutSeconds || 0);

  const optionNodes = options.map((option, index) =>
    h(
      'button',
      {
        type: 'button',
        class: ['mf-opt', clarifyChoice.value === option.label ? 'mf-opt--on' : ''],
        onClick: () => {
          clarifyChoice.value = option.label;
        }
      },
      [
        h('span', { class: 'mf-opt__head' }, [
          h('span', { class: 'mf-opt__label' }, option.label),
          index === 0 ? h('span', { class: 'mf-opt__rec' }, '推荐') : null
        ]),
        option.reason ? h('span', { class: 'mf-opt__reason' }, option.reason) : null
      ]
    )
  );

  const dialog = window.$dialog?.info({
    title: '任务澄清：动手前需要你拍板',
    content: () =>
      h('div', { class: 'mf-dlg' }, [
        h('div', { class: 'mf-dlg__meta' }, [
          h('code', { class: 'mf-dlg__tool' }, payload.taskId || ''),
          h('span', { class: 'mf-dlg__badge mf-dlg__badge--medium' }, '待确认')
        ]),
        h('div', { class: 'mf-dlg__reason' }, payload.description || ''),
        options.length
          ? h('div', { class: 'mf-opt-group' }, optionNodes)
          : h('div', { class: 'mf-dlg__reason' }, '这个任务缺少关键输入，请补充说明或直接交给 Agent 决定。'),
        h('div', {}, [
          h('div', { class: 'mf-dlg__label' }, '补充说明（可选）'),
          h(NInput, {
            value: clarifyNote.value,
            size: 'small',
            placeholder: '例如：用 TypeScript + Canvas，单文件，带移动端触屏',
            onUpdateValue: (value: string) => {
              clarifyNote.value = value;
            }
          })
        ]),
        timeoutSeconds > 0
          ? h('div', { class: 'mf-dlg__label', style: 'margin:0' }, `${timeoutSeconds} 秒未响应将交给 Agent 自行决定`)
          : null
      ]),
    action: () =>
      h(NSpace, null, {
        default: () => [
          h(
            NButton,
            {
              size: 'small',
              onClick: () => {
                dialog?.destroy();
                sendTaskClarificationResponse(payload.clarificationId, 'delegate');
              }
            },
            { default: () => '你来定' }
          ),
          h(
            NButton,
            {
              size: 'small',
              type: 'primary',
              onClick: () => {
                dialog?.destroy();
                sendTaskClarificationResponse(payload.clarificationId, clarifyChoice.value, clarifyNote.value);
              }
            },
            { default: () => '按此执行' }
          )
        ]
      })
  });
};

const handlePlanReviewPayload = (payload: Record<string, any>) => {
  const steps = Array.isArray(payload.steps) ? payload.steps : [];
  const stepLines = steps.map(s => `· [${s.id}] ${s.description}`).join('\n');
  const assistant = findAssistantMessage(payload.generationId);
  if (assistant) {
    assistant.content += `\n\n📋 执行计划已生成，等待审阅...\n${stepLines}`;
  }

  planSelections.value = {};
  for (const step of steps) {
    const options = Array.isArray(step.options) ? step.options : [];
    if (options.length > 0) {
      planSelections.value[step.id] = step.chosenOption || options[0].label;
    }
  }

  const optionNodes = (step: Record<string, any>) =>
    (Array.isArray(step.options) ? step.options : []).map((option: Record<string, any>, index: number) =>
      h(
        'button',
        {
          type: 'button',
          class: ['mf-opt', planSelections.value[step.id] === option.label ? 'mf-opt--on' : ''],
          onClick: () => {
            planSelections.value = { ...planSelections.value, [step.id]: option.label };
          }
        },
        [
          h('span', { class: 'mf-opt__head' }, [
            h('span', { class: 'mf-opt__label' }, option.label),
            index === 0 ? h('span', { class: 'mf-opt__rec' }, '推荐') : null
          ]),
          option.reason ? h('span', { class: 'mf-opt__reason' }, option.reason) : null
        ]
      )
    );

  const stepNodes = steps.map((step, index) =>
    h('div', { class: 'mf-dlg__step' }, [
      h('span', { class: 'mf-dlg__step-idx' }, String(index + 1)),
      h('span', { class: 'mf-dlg__step-desc' }, [
        h('div', {}, `${step.id ? `[${step.id}] ` : ''}${step.description || ''}`),
        Array.isArray(step.options) && step.options.length > 0
          ? h('div', { class: 'mf-opt-group' }, [
              h('div', { class: 'mf-dlg__label', style: 'margin:0 0 2px' }, '需要你拍板（已默认选中推荐项）'),
              ...optionNodes(step)
            ])
          : null
      ])
    ])
  );

  const dialog = window.$dialog?.info({
    title: '📋 计划已就绪，等你确认',
    content: () =>
      h('div', { class: 'mf-dlg' }, [
        h('div', {}, [
          h('div', { class: 'mf-dlg__label' }, '目标'),
          h('div', { class: 'mf-dlg__reason' }, payload.goal || '（未提供目标描述）')
        ]),
        h('div', {}, [
          h('div', { class: 'mf-dlg__label' }, `执行步骤（${steps.length}）`),
          h('div', { class: 'mf-dlg__scroll' }, stepNodes)
        ]),
        h('div', {}, [
          h('div', { class: 'mf-dlg__label' }, '补充要求（可选，填写后将按你的要求重新规划）'),
          h(NInput, {
            value: planFeedback.value,
            size: 'small',
            placeholder: '例如：优先给出可落地的方案，并标注风险',
            onUpdateValue: (value: string) => {
              planFeedback.value = value;
            }
          })
        ])
      ]),
    action: () =>
      h(NSpace, null, {
        default: () => [
          h(
            NButton,
            {
              size: 'small',
              onClick: () => {
                dialog?.destroy();
                sendPlanReviewResponse(payload.planId, 'cancel');
              }
            },
            { default: () => '取消' }
          ),
          h(
            NButton,
            {
              size: 'small',
              onClick: () => {
                const feedback = planFeedback.value.trim();
                if (!feedback) {
                  window.$message?.warning('请先填写补充要求，或直接点「开始执行」');
                  return;
                }
                dialog?.destroy();
                sendPlanReviewResponse(payload.planId, 'supplement', feedback);
              }
            },
            { default: () => '补充要求' }
          ),
          h(
            NButton,
            {
              size: 'small',
              type: 'primary',
              onClick: () => {
                dialog?.destroy();
                sendPlanReviewResponse(payload.planId, 'execute');
              }
            },
            { default: () => '开始执行' }
          )
        ]
      })
  });
};

const handleApprovalRequestPayload = (payload: Record<string, any>) => {
  const args = (payload.args ?? {}) as Record<string, unknown>;
  const pathEntries = Object.entries(args).filter(([key]) => /path|file|dir|folder/i.test(key));
  const restEntries = Object.entries(args).filter(([key]) => !/path|file|dir|folder/i.test(key));
  const highRisk = String(payload.riskLevel || '').toUpperCase() === 'HIGH';
  const timeoutSeconds = Number(payload.timeoutSeconds || 0);

  const dialog = window.$dialog?.warning({
    title: '需要你的授权',
    content: () =>
      h('div', { class: 'mf-dlg' }, [
        h('div', { class: 'mf-dlg__meta' }, [
          h(
            'span',
            { class: `mf-dlg__badge ${highRisk ? 'mf-dlg__badge--high' : 'mf-dlg__badge--medium'}` },
            highRisk ? '高风险' : '需确认'
          ),
          h('code', { class: 'mf-dlg__tool' }, payload.tool || '未知工具'),
          payload.serverName
            ? h('span', { class: 'mf-dlg__label', style: 'margin:0' }, `来源 ${payload.serverName}`)
            : null
        ]),
        h('div', { class: 'mf-dlg__reason' }, payload.riskReason || '该操作会修改外部状态或访问受限资源，需要你确认。'),
        ...pathEntries.map(([key, value]) =>
          h('div', {}, [
            h('div', { class: 'mf-dlg__label' }, key),
            h('div', { class: 'mf-dlg__path' }, String(value))
          ])
        ),
        restEntries.length
          ? h('div', {}, [
              h('div', { class: 'mf-dlg__label' }, '参数'),
              h('pre', { class: 'mf-dlg__pre' }, JSON.stringify(Object.fromEntries(restEntries), null, 2))
            ])
          : null,
        timeoutSeconds > 0
          ? h('div', { class: 'mf-dlg__label', style: 'margin:0' }, `${timeoutSeconds} 秒内未响应将自动拒绝`)
          : null
      ]),
    action: () =>
      h(NSpace, null, {
        default: () => [
          h(
            NButton,
            {
              size: 'small',
              type: 'error',
              ghost: true,
              onClick: () => {
                dialog?.destroy();
                sendApprovalResponse(payload.approvalId, 'reject');
              }
            },
            { default: () => '当前拒绝' }
          ),
          h(
            NButton,
            {
              size: 'small',
              onClick: () => {
                dialog?.destroy();
                sendApprovalResponse(payload.approvalId, 'approve_all');
              }
            },
            { default: () => '始终允许（本次会话）' }
          ),
          h(
            NButton,
            {
              size: 'small',
              type: 'primary',
              onClick: () => {
                dialog?.destroy();
                sendApprovalResponse(payload.approvalId, 'approve');
              }
            },
            { default: () => '单次允许' }
          )
        ]
      })
  });
};

const handlePlanEventPayload = (assistant: any, payload: Record<string, any>) => {
  if (payload.type === 'plan_step') {
    const icon = payload.status === 'started' ? '▶️' : payload.status === 'completed' ? '✅' : '❌';
    assistant.content += `\n\n${icon} ${payload.stepId} ${payload.message || ''}`;
    return;
  }
  if (payload.type === 'plan_status') {
    const progress = Math.round(Number(payload.progress || 0) * 100);
    assistant.content += `\n\n📊 计划状态: ${payload.state}（进度 ${progress}%）`;
    return;
  }
  assistant.content += `\n\n${payload.message || ''}`;
};

type ChatMode = 'react' | 'plan' | 'team';

interface ChatModeOption {
  value: ChatMode;
  label: string;
  prefix: string;
  hint: string;
}

const CHAT_MODES: ChatModeOption[] = [
  { value: 'react', label: 'ReAct', prefix: '', hint: '默认：边想边调工具的单 Agent 循环，自动检索知识库' },
  { value: 'plan', label: 'Plan', prefix: '/plan', hint: '先规划 → 弹窗审阅确认后才分步执行，可并行（≤4），失败会重规划' },
  { value: 'team', label: 'Team', prefix: '/team', hint: '多角色协作：规划者拆解 → 执行者分工 → 检查者审查，无审阅环节' }
];

const COMMAND_HINTS = [
  { command: '/plan', example: '总结知识库中关于向量检索的内容' },
  { command: '/team', example: '对比 X 与 Y 两个概念' },
  { command: '/cancel', example: '取消当前正在执行的生成' }
];

const chatMode = useStorage<ChatMode>('mindflow-chat-mode', 'react');
const activeMode = computed(() => CHAT_MODES.find(mode => mode.value === chatMode.value) || CHAT_MODES[0]);

// 仅在“正在敲命令名”时提示；出现空格视为已在写参数，提示面板收起以免遮挡输入
const showCommandHints = computed(() => {
  const text = input.value.message.trimStart();
  return text.startsWith('/') && !text.includes(' ');
});

// 手打 /plan、/team 时同步切换器，避免“选了 Team 却打了 /plan”的歧义
watch(
  () => input.value.message,
  value => {
    const text = (value || '').trimStart();
    if (text.startsWith('/plan')) chatMode.value = 'plan';
    else if (text.startsWith('/team')) chatMode.value = 'team';
  }
);

function composeOutgoingMessage(raw: string): string {
  const text = (raw || '').trim();
  if (chatMode.value === 'react') {
    return text;
  }
  const prefix = activeMode.value.prefix;
  return text.startsWith(prefix) ? text : `${prefix} ${text}`;
}

function applyCommandHint(command: string) {
  input.value.message = `${command} `;
  if (command === '/plan') chatMode.value = 'plan';
  else if (command === '/team') chatMode.value = 'team';
}

const handleSend = async () => {
  if (isRateLimited.value) {
    window.$message?.warning(`当前发送受限，${cooldownText.value}`);
    return;
  }

  if (isSending.value) {
    const { error, data: tokenData } = await request<Api.Chat.Token>({
      url: 'chat/websocket-token'
    });
    if (error) return;

    chatStore.wsSend(
      JSON.stringify({
        type: 'stop',
        generationId: latestMessage.value.generationId,
        _internal_cmd_token: tokenData.cmdToken
      })
    );

    list.value[list.value.length - 1].status = 'finished';
    if (!latestMessage.value.content) list.value.pop();
    return;
  }

  const outgoingMessage = composeOutgoingMessage(input.value.message);

  list.value.push({
    content: outgoingMessage,
    role: 'user'
  });
  list.value.push({
    content: '',
    role: 'assistant',
    status: 'pending',
    toolEvents: []
  });
  chatStore.wsSend(outgoingMessage);
  input.value.message = '';
  startGenerationStatusMonitor();
};

const inputRef = ref();
const insertNewline = () => {
  const textarea = inputRef.value;
  const start = textarea.selectionStart;
  const end = textarea.selectionEnd;

  input.value.message = `${input.value.message.substring(0, start)}\n${input.value.message.substring(end)}`;

  nextTick(() => {
    textarea.selectionStart = start + 1;
    textarea.selectionEnd = start + 1;
    textarea.focus();
  });
};

const handShortcut = (e: KeyboardEvent) => {
  if (e.key === 'Enter') {
    e.preventDefault();

    if (!e.shiftKey && !e.ctrlKey) {
      handleSend();
    } else insertNewline();
  }
};

onUnmounted(() => {
  stopGenerationStatusMonitor();
});
</script>

<template>
  <div class="relative shrink-0 bg-white px-4 pb-3 pt-2 dark:bg-[#1c1c1c]">
    <div
      class="pointer-events-none absolute inset-x-0 h-6 from-white/95 to-transparent bg-gradient-to-t -top-6 dark:from-[#1c1c1c]/95"
    />
    <div v-if="showCommandHints" class="mx-auto mb-1.5 max-w-[960px] w-full px-1">
      <div class="rounded-lg border border-[rgba(15,23,42,0.08)] bg-white p-1.5 dark:border-white/10 dark:bg-[#232323]">
        <div class="mb-1 px-1.5 text-11px color-#999">可用命令（也可直接手打）</div>
        <button
          v-for="hint in COMMAND_HINTS"
          :key="hint.command"
          type="button"
          class="w-full flex items-baseline gap-2 rounded px-1.5 py-1 text-left hover:bg-[rgba(15,23,42,0.04)] dark:hover:bg-white/5"
          @click="applyCommandHint(hint.command)"
        >
          <span class="shrink-0 text-12px font-500 color-[rgb(var(--primary-color))]">{{ hint.command }}</span>
          <span class="min-w-0 flex-1 truncate text-11px color-#999">{{ hint.example }}</span>
        </button>
      </div>
    </div>

    <div
      class="chat-input-shell mx-auto max-w-[960px] w-full flex items-end gap-2 rounded-2xl bg-white px-3.5 py-2.5 dark:bg-[#1f1f1f]"
    >
      <textarea
        ref="inputRef"
        v-model.trim="input.message"
        placeholder="给 MindFlow 发送消息，Enter 发送，Shift+Enter 换行"
        class="max-h-32 min-h-6 w-full flex-1 resize-none border-none bg-transparent py-1 text-14px color-#333 caret-[rgb(var(--primary-color))] outline-none placeholder:text-#bbb dark:color-#e1e1e1 dark:placeholder:text-#555"
        @keydown="handShortcut"
      />
      <button
        type="button"
        :disabled="sendDisabled"
        class="send-btn shrink-0 self-end inline-flex h-9 w-9 items-center justify-center rounded-full text-white"
        :class="isSending ? 'send-btn--stop' : 'gradient-action'"
        @click="handleSend"
      >
        <icon-material-symbols:stop-rounded v-if="isSending" class="text-16px" />
        <icon-material-symbols:arrow-upward-rounded v-else class="text-16px" />
      </button>
    </div>

    <div class="mx-auto mt-2.5 max-w-[960px] w-full flex items-center gap-2 px-1">
      <button
        v-for="mode in CHAT_MODES"
        :key="mode.value"
        type="button"
        :disabled="isSending"
        class="rounded-full px-3.5 py-1.5 text-body transition-all disabled:cursor-not-allowed disabled:opacity-60"
        :class="
          chatMode === mode.value
            ? 'gradient-action font-600 text-white'
            : 'border border-[rgb(var(--primary-color)/0.45)] text-[rgb(var(--primary-color))] hover:bg-[rgb(var(--primary-color)/0.06)]'
        "
        @click="chatMode = mode.value"
      >
        {{ mode.label }}
      </button>
      <span class="min-w-0 flex-1 truncate text-body-sm" style="color: rgb(var(--text-3))">{{ activeMode.hint }}</span>
    </div>
    <div class="mx-auto mt-1.5 max-w-[960px] w-full flex items-center justify-between px-1">
      <div class="flex items-center gap-2">
        <div class="flex items-center gap-1">
          <span
            class="inline-block h-1.5 w-1.5 rounded-full"
            :class="{
              'bg-green-500': connectionStatus === 'OPEN',
              'bg-yellow-500 animate-pulse': connectionStatus === 'CONNECTING' || connectionStatus === 'RECONNECTING',
              'bg-red-400': connectionStatus === 'CLOSED'
            }"
          />
          <span class="text-11px color-#aaa">{{ connectionText }}</span>
        </div>
        <span v-if="isRateLimited" class="text-11px text-[rgb(var(--primary-color))]">
          {{ cooldownText }}
        </span>
      </div>
      <span class="text-11px color-#bbb">Shift+Enter 换行</span>
    </div>
  </div>
</template>

<style scoped>
.chat-input-shell {
  border: 1px solid rgba(15, 23, 42, 0.12);
  box-shadow:
    0 1px 2px rgba(15, 23, 42, 0.04),
    0 6px 18px rgba(15, 23, 42, 0.04);
  transition:
    border-color 0.18s ease,
    box-shadow 0.18s ease,
    background-color 0.18s ease;
}

.chat-input-shell:focus-within {
  border-color: rgb(var(--primary-color) / 0.38);
  box-shadow:
    0 1px 2px rgba(15, 23, 42, 0.04),
    0 8px 24px rgba(15, 23, 42, 0.06);
}

.dark .chat-input-shell {
  border-color: rgba(255, 255, 255, 0.1);
  box-shadow: none;
}
</style>
