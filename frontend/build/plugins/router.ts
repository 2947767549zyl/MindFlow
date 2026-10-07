import type { RouteMeta } from 'vue-router';
import ElegantVueRouter from '@elegant-router/vue/vite';
import type { RouteKey } from '@elegant-router/types';

const routeMetaOverrides: Partial<Record<RouteKey, Record<string, unknown>>> = {
  chat: { icon: 'solar:chat-round-call-line-duotone', order: 1 },
  'chat-history': { icon: 'solar:hashtag-chat-broken', order: 2, roles: ['ADMIN'] },
  'knowledge-base': { icon: 'solar:folder-line-duotone', order: 3 },
  'org-tag': { icon: 'solar:tag-line-duotone', order: 4, roles: ['ADMIN'] },
  'model-provider': { icon: 'solar:tuning-square-line-duotone', order: 5, roles: ['ADMIN'] },
  'usage-monitor': { icon: 'solar:chart-2-line-duotone', order: 6, roles: ['ADMIN'] },
  'personal-center': { icon: 'solar:people-nearby-line-duotone', order: 7 },
  skill: { icon: 'solar:book-bookmark-line-duotone', order: 8 },
  'recharge-manage': { icon: 'solar:shop-minimalistic-broken', order: 9, roles: ['ADMIN'] },
  'mcp-config': { icon: 'solar:server-square-line-duotone', order: 10, roles: ['ADMIN'] },
  'wiki-graph': { icon: 'solar:graph-new-line-duotone', order: 11 },
  registration: { icon: 'solar:key-minimalistic-square-line-duotone', order: 12, roles: ['ADMIN'] },
  experience: { icon: 'solar:notebook-line-duotone', order: 15 },
  'agent-tool': { icon: 'solar:widget-5-line-duotone', order: 13 },
  user: { icon: 'solar:users-group-two-rounded-line-duotone', order: 14, roles: ['ADMIN'] }
};

export function setupElegantRouter() {
  return ElegantVueRouter({
    layouts: {
      base: 'src/layouts/base-layout/index.vue',
      blank: 'src/layouts/blank-layout/index.vue'
    },
    routePathTransformer(routeName, routePath) {
      const key = routeName as RouteKey;

      if (key === 'login') {
        const modules: UnionKey.LoginModule[] = ['pwd-login', 'code-login', 'register', 'reset-pwd', 'bind-wechat'];

        const moduleReg = modules.join('|');

        return `/login/:module(${moduleReg})?`;
      }

      return routePath;
    },
    onRouteMetaGen(routeName) {
      const key = routeName as RouteKey;

      const constantRoutes: RouteKey[] = ['login', '403', '404', '500'];

      const meta = {
        title: key,
        i18nKey: `route.${key}` as App.I18n.I18nKey,
        ...(routeMetaOverrides[key] ?? {})
      } as Partial<RouteMeta>;

      if (constantRoutes.includes(key)) {
        meta.constant = true;
      }

      return meta;
    }
  });
}
