// Merge these entries into the host frontend's existing server.proxy object.
// The backend process serves both /doc-template/** static resources and /api/doc-template/** APIs.
export const docTemplateProxy = {
  '/doc-template': {
    target: 'http://127.0.0.1:8080',
    changeOrigin: true
  },
  '/api/doc-template': {
    target: 'http://127.0.0.1:8080',
    changeOrigin: true
  }
};
