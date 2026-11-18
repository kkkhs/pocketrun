module.exports = {
  '{app/**/*,.github/workflows/app.yml,.nvmrc,lint-staged.config.cjs,scripts/**/*}': () => [
    'npm --prefix app run lint',
    'npm --prefix app run typecheck',
    'npm --prefix app test -- --runInBand',
  ],
  '{website/**/*,README.md,.github/workflows/pages.yml,.nvmrc,lint-staged.config.cjs,scripts/**/*}': () => [
    'npm --prefix website run test:toolchain',
    'npm --prefix website run typecheck',
    'npm --prefix website run build',
  ],
};
