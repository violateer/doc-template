<script setup lang="ts">
/**
 * Add this component to a host route/page. Change the tokenStorageKey to the
 * exact key used by the host's auth store if it differs.
 *
 * `layout=bare` hides the built-in left sidebar — without it the host menu and
 * the embedded menu stack into two navigation bars.
 */
const src = '/doc-template/?tokenStorageKey=token&tokenHeader=Authorization&tokenPrefix=Bearer&layout=bare';
</script>
<template>
  <div class="h-full min-h-0 w-full overflow-hidden">
    <iframe :src="src" title="文档模板管理" class="h-full min-h-[70vh] w-full border-0" />
  </div>
</template>
