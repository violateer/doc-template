package io.github.violateer.doctemplate.core.datasource;

import java.util.Map;

/**
 * 宿主实现这个接口，把业务数据交给文档模板。
 *
 * <p>接一个 {@code JAVA} 数据块只需要这一个类型，而且它刻意做成零依赖：只实现 Provider 的模块可以
 * 只依赖 {@code doc-template-core}，不必引入 Spring、POI 等任何第三方库。</p>
 *
 * <p>{@code params} 是唯一入参，由数据块的 {@code param_config} 映射（固定值 + {@code $request.*} 引用）
 * 与生成请求参数合并而成。所以「只查当前调用方自己的订单」这类行级过滤，完全靠传进来的
 * {@code params} 实现；Provider 拿不到单独的调用方身份。</p>
 */
public interface DocumentDataProvider {
    /** 数据块配置里选中的稳定标识。 */
    String getCode();

    /** 用解析好的数据块参数执行取数。 */
    Object execute(Map<String, Object> params);
}
