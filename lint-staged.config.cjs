// ponytail: check the whole tutorial site; split staged scopes when app code is added.
module.exports = {
  '*': () => [
    'npm --prefix website run test:toolchain',
    'npm --prefix website run typecheck',
    'npm --prefix website run build',
  ],
};
