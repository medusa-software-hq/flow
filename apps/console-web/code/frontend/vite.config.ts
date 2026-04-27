import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

interface ModeConfig {
  readonly mainTsxAlias: string;
}

const developmentConfig: ModeConfig = {
  mainTsxAlias: '/src/main.dev.tsx',
};

const productionConfig: ModeConfig = {
  mainTsxAlias: '/src/main.prod.tsx',
};

function chooseConfig(modeName: string): ModeConfig {
  switch (modeName) {
    case 'development':
      return developmentConfig;
    case 'production':
      return productionConfig;
    default:
      throw new Error(`Unsupported mode: ${modeName}`);
  }
}

export default defineConfig(({ mode }) => {
  const config = chooseConfig(mode);

  return {
    plugins: [react()],
    resolve: {
      alias: {
        '/src/main.tsx': config.mainTsxAlias,
      },
    },
    build: {
      rolldownOptions: {
        output: {
          entryFileNames: 'js/[name]-[hash].js',
          chunkFileNames: 'js/[name]-[hash].js',
          assetFileNames: 'assets/[name]-[hash][extname]',
        },
      },
    },
  };
});
