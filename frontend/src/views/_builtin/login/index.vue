<script setup lang="ts">
import { computed } from 'vue';
import type { Component } from 'vue';
import IconSolarFolder from '~icons/solar/folder-with-files-line-duotone';
import IconSolarRouting from '~icons/solar/routing-2-line-duotone';
import IconSolarShield from '~icons/solar/shield-check-line-duotone';
import { loginModuleRecord } from '@/constants/app';
import { useAppStore } from '@/store/modules/app';
import { useThemeStore } from '@/store/modules/theme';
import { $t } from '@/locales';
import PwdLogin from './modules/pwd-login.vue';
import CodeLogin from './modules/code-login.vue';
import Register from './modules/register.vue';
import ResetPwd from './modules/reset-pwd.vue';
import BindWechat from './modules/bind-wechat.vue';

interface Props {
  /** The login module */
  module?: UnionKey.LoginModule;
}

const props = defineProps<Props>();

const appStore = useAppStore();
const themeStore = useThemeStore();

interface LoginModule {
  label: string;
  component: Component;
}

const moduleMap: Record<UnionKey.LoginModule, LoginModule> = {
  'pwd-login': { label: loginModuleRecord['pwd-login'], component: PwdLogin },
  'code-login': { label: loginModuleRecord['code-login'], component: CodeLogin },
  register: { label: loginModuleRecord.register, component: Register },
  'reset-pwd': { label: loginModuleRecord['reset-pwd'], component: ResetPwd },
  'bind-wechat': { label: loginModuleRecord['bind-wechat'], component: BindWechat }
};

const activeModule = computed(() => moduleMap[props.module || 'pwd-login']);
const isRegisterModule = computed(() => (props.module || 'pwd-login') === 'register');

interface BrandFeature {
  icon: string;
  title: string;
  desc: string;
}

const brandFeatures: BrandFeature[] = [
  {
    icon: IconSolarFolder,
    title: '知识库引擎',
    desc: '文档上传、切分、向量化一站式管理，知识资产随手可取'
  },
  {
    icon: IconSolarRouting,
    title: '多跳检索推理',
    desc: '跨库多跳检索与 Agent 协同编排，复杂问题一步到位'
  },
  {
    icon: IconSolarShield,
    title: '安全可控',
    desc: '私有化部署，数据不出域，权限与用量清晰可控'
  }
];
</script>

<template>
  <div class="relative size-full flex overflow-hidden bg-layout">
    <!-- brand panel -->
    <aside class="brand-panel relative z-0 hidden flex-1 flex-col overflow-hidden px-48px py-48px lg:flex xl:px-64px">
      <div class="brand-grid absolute-lt size-full" />
      <div class="absolute -left-140px -top-140px size-400px rounded-full bg-white/10 blur-3xl" />
      <div class="absolute -bottom-160px -right-120px size-460px rounded-full bg-white/5 blur-3xl" />

      <div class="relative z-1 flex-y-center gap-14px">
        <div class="i-flex-center size-52px shrink-0 rounded-14px bg-white/12 text-32px text-white backdrop-blur-4px">
          <SystemLogo />
        </div>
        <div class="flex-col gap-2px">
          <span class="text-26px text-white font-800 leading-[1.2] tracking-wide">MindFlow</span>
          <span class="text-12px text-white opacity-60 tracking-0.18em">KNOWLEDGE-DRIVEN AGENT</span>
        </div>
      </div>

      <div class="relative z-1 my-auto py-48px">
        <h1 class="text-40px text-white font-800 leading-[1.25] xl:text-44px">
          知识库驱动的
          <br />
          高校 Agent 平台
        </h1>
        <p class="mt-18px max-w-500px text-15px text-white leading-[1.9] opacity-80">
          依托 RAG 检索增强与多 Agent 协同编排，让知识可检索、可推理、可行动，构建高校实验室专属的智能问答与科研工作入口。
        </p>
      </div>

      <div class="relative z-1 flex-col gap-12px">
        <div
          v-for="feature in brandFeatures"
          :key="feature.title"
          class="flex-y-center gap-14px border border-white/15 rounded-16px bg-white/8 px-18px py-15px backdrop-blur-6px"
        >
          <div class="i-flex-center size-40px shrink-0 rounded-12px bg-white/14 text-22px text-white">
            <component :is="feature.icon" />
          </div>
          <div class="min-w-0 flex-col gap-3px">
            <div class="text-15px text-white font-600">{{ feature.title }}</div>
            <div class="text-13px text-white leading-[1.6] opacity-70">{{ feature.desc }}</div>
          </div>
        </div>
      </div>
    </aside>

    <!-- form panel -->
    <section class="login-form-side relative z-1 h-full flex-1 overflow-y-auto">
      <div class="min-h-full flex-center px-16px py-32px sm:px-32px lg:px-40px">
        <div class="login-card w-full" :class="{ 'login-card--register': isRegisterModule }">
          <div :class="isRegisterModule ? 'login-panel login-panel--register' : 'login-panel'">
            <header class="flex-y-center justify-between gap-16px">
              <div class="min-w-0 flex-y-center gap-12px">
                <SystemLogo class="shrink-0 text-44px text-primary lt-sm:text-38px" />
                <div class="min-w-0 flex-col gap-2px">
                  <span class="ellipsis-text text-24px text-primary font-800 leading-[1.2] lt-sm:text-20px">
                    {{ $t('system.title') }}
                  </span>
                  <span class="ellipsis-text text-12px text-base-text tracking-0.08em opacity-55">
                    {{ isRegisterModule ? $t(activeModule.label) : 'KNOWLEDGE-DRIVEN AGENT PLATFORM' }}
                  </span>
                </div>
              </div>
              <div class="shrink-0 flex-y-center">
                <ThemeSchemaSwitch
                  :theme-schema="themeStore.themeScheme"
                  :show-tooltip="false"
                  class="text-20px lt-sm:text-18px"
                  @switch="themeStore.toggleThemeScheme"
                />
                <LangSwitch
                  v-if="themeStore.header.multilingual.visible"
                  :lang="appStore.locale"
                  :lang-options="appStore.localeOptions"
                  :show-tooltip="false"
                  @change-lang="appStore.changeLocale"
                />
              </div>
            </header>
            <main class="pt-26px">
              <h3 v-if="!isRegisterModule" class="text-20px text-base-text font-700 lt-sm:text-18px">
                {{ $t(activeModule.label) }}
              </h3>
              <div class="pt-22px">
                <Transition :name="themeStore.page.animateMode" mode="out-in" appear>
                  <component :is="activeModule.component" />
                </Transition>
              </div>
            </main>
          </div>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.login-panel {
  width: 400px;
  max-width: 100%;
}

.login-panel--register {
  width: min(860px, 100%);
}

.login-card {
  max-width: 468px;
  border: 1px solid rgb(var(--base-text-color) / 0.06);
  border-radius: 22px;
  padding: 32px 36px;
  background: rgb(var(--container-bg-color) / 0.94);
  box-shadow: 0 24px 64px -20px rgb(var(--primary-900-color) / 0.16);
  backdrop-filter: blur(10px);
}

.login-card--register {
  max-width: 920px;
}

.brand-panel {
  background:
    radial-gradient(120% 90% at 0% 0%, rgb(var(--primary-500-color) / 0.45) 0%, transparent 55%),
    radial-gradient(90% 80% at 100% 100%, rgb(var(--primary-400-color) / 0.4) 0%, transparent 60%),
    linear-gradient(155deg, rgb(var(--primary-950-color)) 0%, rgb(var(--primary-800-color)) 52%, rgb(var(--primary-600-color)) 100%);
}

.brand-grid {
  background-image: radial-gradient(rgb(255 255 255 / 0.14) 1px, transparent 1.5px);
  background-size: 22px 22px;
  mask-image: linear-gradient(to bottom, rgb(0 0 0 / 0.65), transparent 78%);
}

.login-form-side {
  background:
    radial-gradient(900px 460px at 85% -8%, rgb(var(--primary-100-color) / 0.6), transparent 62%),
    radial-gradient(760px 420px at 8% 108%, rgb(var(--primary-100-color) / 0.5), transparent 58%),
    rgb(var(--layout-bg-color));
}

html.dark .login-card {
  border-color: rgb(255 255 255 / 0.08);
  box-shadow: 0 24px 64px -20px rgb(0 0 0 / 0.55);
}

html.dark .login-form-side {
  background:
    radial-gradient(900px 460px at 85% -8%, rgb(var(--primary-900-color) / 0.4), transparent 62%),
    radial-gradient(760px 420px at 8% 108%, rgb(var(--primary-900-color) / 0.32), transparent 58%),
    rgb(var(--layout-bg-color));
}

@media (max-width: 640px) {
  .login-panel,
  .login-panel--register {
    width: 100%;
  }

  .login-card {
    border-radius: 18px;
    padding: 26px 22px;
  }
}
</style>
