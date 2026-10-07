import { defineConfig } from '@unocss/vite';
import transformerDirectives from '@unocss/transformer-directives';
import transformerVariantGroup from '@unocss/transformer-variant-group';
import presetWind3 from '@unocss/preset-wind3';
import type { Theme } from '@unocss/preset-uno';
import { presetSoybeanAdmin } from '@sa/uno-preset';
import { themeVars } from './src/theme/vars';

export default defineConfig<Theme>({
  content: {
    pipeline: {
      exclude: ['node_modules', 'dist']
    }
  },
  theme: {
    ...themeVars,
    fontSize: {
      'icon-xs': '0.875rem',
      'icon-small': '1rem',
      icon: '1.125rem',
      'icon-large': '1.5rem',
      'icon-xl': '2rem',
      'body-xs': ['11px', '1.45'],
      'body-sm': ['12px', '1.5'],
      body: ['13px', '1.6'],
      'body-lg': ['14px', '1.6'],
      'title-xs': ['15px', '1.5'],
      'title-sm': ['16px', '1.45'],
      title: ['18px', '1.4'],
      'display-sm': ['22px', '1.25'],
      display: ['28px', '1.2']
    }
  },
  shortcuts: {
    'card-wrapper': 'rd-4 shadow-2xl dark:shadow-[0_25px_50px_-12px_rgba(27,27,27,0.1)]',
    'flex-cc': 'flex items-center justify-center'
  },
  transformers: [transformerDirectives(), transformerVariantGroup()],
  presets: [presetWind3({ dark: 'class' }), presetSoybeanAdmin()]
});
