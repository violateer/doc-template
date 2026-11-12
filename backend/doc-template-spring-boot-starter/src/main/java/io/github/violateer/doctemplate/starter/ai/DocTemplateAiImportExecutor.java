package io.github.violateer.doctemplate.starter.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * AI 导入任务的执行队列。
 *
 * <p>不引 Spring Batch、不引消息队列：宿主要多一套中间件，而这里的需求只是「后台跑、串行、别把内存堆爆」。
 * 也不用 {@code @Async} —— 那要求宿主自己开 {@code @EnableAsync}，等于给模块加一个隐形前提。</p>
 *
 * <p>单独一个类型（而不是直接暴露 {@code ThreadPoolTaskExecutor}）是为了让
 * {@code @ConditionalOnMissingBean} 有意义：Boot 自己就有一个 {@code applicationTaskExecutor}，
 * 按那个类型判断会永远命中「宿主已有」，我们自己的池根本不会被创建。宿主想接管时注册一个本类型的 Bean 即可。</p>
 */
public class DocTemplateAiImportExecutor implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(DocTemplateAiImportExecutor.class);

    private final ThreadPoolTaskExecutor delegate;

    public DocTemplateAiImportExecutor(int threads, int queueCapacity) {
        int poolSize = Math.max(1, threads);
        this.delegate = new ThreadPoolTaskExecutor();
        this.delegate.setCorePoolSize(poolSize);
        this.delegate.setMaxPoolSize(poolSize);
        this.delegate.setQueueCapacity(Math.max(1, queueCapacity));
        this.delegate.setThreadNamePrefix("doc-template-ai-import-");
        // 队列满了直接拒绝：任务表里还是 PENDING，用户再点一次就行，不要静默排队把内存吃掉
        this.delegate.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        // 停机时等正在跑的这一次走完（一次导入要几分钟，硬中断只会留下一个 RUNNING 的孤儿任务）
        this.delegate.setWaitForTasksToCompleteOnShutdown(true);
        this.delegate.setAwaitTerminationSeconds(30);
        this.delegate.initialize();
        log.debug("AI 导入队列：{} 线程，队列容量 {}", poolSize, Math.max(1, queueCapacity));
    }

    /** 提交一个任务。队列满时抛 {@code TaskRejectedException}，由调用方转成可读提示。 */
    public void submit(Runnable task) {
        delegate.execute(task);
    }

    @Override
    public void close() {
        delegate.shutdown();
    }
}
