/**
 * 数据块配置的「表单定义」。
 *
 * 数据仍然以 JSON 存在 `doc_template_data_block.data_source_config` 里，管理界面不再让人手写整段 JSON，
 * 而是按这里的字段定义渲染表单项：每个字段有标签、说明、示例值，可以单独「填入示例」。
 *
 * 新增/调整配置项时只需要改这个文件，界面会自动跟着变。
 */
export type DataSourceType = 'JAVA' | 'SQL' | 'API';

export type ConfigFieldType = 'text' | 'textarea' | 'number' | 'switch' | 'select' | 'json' | 'kv';

export interface ConfigOption {
  label: string;
  value: string;
}

export interface ConfigField {
  /** 对应 data_source_config 里的键名 */
  key: string;
  label: string;
  /** 字段说明，直接显示在输入框下面 */
  desc: string;
  type: ConfigFieldType;
  placeholder?: string;
  options?: ConfigOption[];
  /** 选项来自后端接口：providers（Java Provider）/ dataSources（SQL 数据源） */
  dynamic?: 'providers' | 'dataSources';
  /** 单字段「填入示例」用的值 */
  sample?: unknown;
  required?: boolean;
  /** textarea 行数 */
  rows?: number;
  /** 单位后缀，如 ms */
  suffix?: string;
  /**
   * 在表单两列网格里占几列。
   * 不填时按类型推断：textarea / json / kv 占满整行，其余占半行。
   */
  span?: 1 | 2;
  /** 分组标题：同一组的字段在表单里排在一起，上方显示小标题 */
  group?: string;
}

export const dataSourceMeta: Record<
  DataSourceType,
  { label: string; summary: string; warning?: string }
> = {
  JAVA: {
    label: 'Java Provider',
    summary: '调用宿主应用里注册的 DocumentDataProvider，由业务代码自己取数'
  },
  SQL: {
    label: 'SQL 数据源',
    summary: '执行一条只读查询，把结果集作为数据块内容',
    warning:
      '不推荐使用：配置一条 SQL 等于把该数据源账号可见的所有表交给配置者（auth.mode 为 none 时就是任何能打开本页的人）。' +
      '模块会做语句白名单校验、只读连接和错误脱敏，但那只是纵深防御，不是安全边界。' +
      '优先用 Java Provider，查什么表、带什么条件由宿主业务代码决定。'
  },
  API: {
    label: 'API 数据源',
    summary: '请求一个 HTTP 接口，把响应 JSON 里指定的节点作为数据块内容'
  }
};

const METHOD_OPTIONS: ConfigOption[] = [
  { label: 'GET', value: 'GET' },
  { label: 'POST', value: 'POST' }
];

export const configFields: Record<DataSourceType, ConfigField[]> = {
  JAVA: [
    {
      key: 'provider',
      label: 'Provider 编码',
      type: 'select',
      dynamic: 'providers',
      required: true,
      span: 2,
      desc: 'DocumentDataProvider#getCode() 的返回值；参数会原样传给 execute(params)，由 Provider 自己决定怎么用。',
      placeholder: '选择宿主应用注册的 Provider',
      sample: 'demoReport'
    }
  ],
  SQL: [
    {
      key: 'dataSource',
      label: '数据源',
      type: 'select',
      dynamic: 'dataSources',
      group: '查询',
      span: 2,
      desc: '留空用宿主主库；写 docTemplate 表示模板元数据所在的库；其他值按 Spring Bean 名解析。'
    },
    {
      key: 'sql',
      label: 'SQL 语句',
      type: 'textarea',
      rows: 6,
      required: true,
      group: '查询',
      desc:
        '只允许单条只读查询：语句必须能被解析为 SELECT（WITH 后面也只能接 SELECT），写操作、' +
        '文件函数（load_file / file_read / into outfile 等）和 for update 都会被拒绝。' +
        '参数用 :name 命名占位（值来自「参数映射」），不要拼字符串，避免注入。',
      placeholder: 'select report_no, product_name from qc_report where report_no = :reportNo',
      sample: 'select report_no, product_name, inspect_result from qc_report where report_no = :reportNo'
    },
    {
      key: 'single',
      label: '返回单条对象',
      type: 'switch',
      group: '结果处理',
      desc: '开启：结果按单条对象放进数据块（主信息）；关闭：结果按数组返回，配合模板里的 {{#each 块编码}} 做明细行。',
      sample: true
    },
    {
      key: 'maxRows',
      label: '最大行数',
      type: 'number',
      placeholder: '500',
      group: '结果处理',
      desc: '最多返回多少行，防止误查全表；留空用默认 500。',
      sample: 1
    }
  ],
  API: [
    {
      key: 'url',
      label: '请求地址',
      type: 'text',
      span: 2,
      required: true,
      group: '请求',
      desc:
        '接口地址，路径和查询串里的占位符都会替换（值做 URL 编码），{name} 和 ${name} 两种写法都认。' +
        '例如 https://api.example.com/orders/{id}?name={name}，id、name 来自「参数映射」。',
      placeholder: 'https://api.example.com/orders/{id}?name={name}',
      sample: 'https://api.example.com/qc/reports/{reportNo}'
    },
    {
      key: 'method',
      label: '请求方法',
      type: 'select',
      options: METHOD_OPTIONS,
      group: '请求',
      desc: '只支持 GET / POST，都用于查询：GET 把参数拼在 URL 上、不发送请求体，POST 把参数放在 JSON 请求体里。',
      sample: 'GET'
    },
    {
      key: 'timeoutMs',
      label: '请求超时',
      type: 'number',
      suffix: 'ms',
      placeholder: '5000',
      group: '请求',
      desc: '超时毫秒数，默认 5000，上限 15000；超时会判定生成失败。',
      sample: 5000
    },
    {
      key: 'resultPath',
      label: '结果路径',
      type: 'text',
      span: 2,
      placeholder: 'data',
      group: '响应',
      desc: '从响应 JSON 里取哪个节点作为数据块，如 data、data.rows、data.rows.0.name；留空表示用整个响应。',
      sample: 'data'
    },
    {
      key: 'headers',
      label: '请求头',
      type: 'kv',
      group: '请求头与查询参数',
      desc: '键值对；值里可用 ${name} 引用参数。密钥建议走宿主配置再映射进来，不要直接写死。',
      sample: { Authorization: 'Bearer ${token}', 'Content-Type': 'application/json' }
    },
    {
      key: 'query',
      label: '查询参数',
      type: 'kv',
      group: '请求头与查询参数',
      desc: '拼到 URL 上的查询参数，值里可用 ${name}。',
      sample: { lang: 'zh-CN' }
    },
    {
      key: 'body',
      label: '请求体（JSON）',
      type: 'json',
      rows: 5,
      placeholder: '{ "reportNo": "${reportNo}" }',
      group: '请求体',
      desc:
        'POST 的 JSON 请求体，字符串值里的 ${name} 会被替换；某个值整串就是一个 ${name} 时按原始类型塞进去' +
        '（对象、数组、数字、布尔都能带）。GET 不发送请求体。',
      sample: { reportNo: '${reportNo}' }
    }
  ]
};

/** 参数映射里的两种写法说明 */
export const paramFieldNotes = [
  { name: '$request.xxx', desc: '取生成时传入的参数（requestParams）里的同名值，支持 a.b 嵌套' },
  { name: '字面量', desc: '不是 $request. 开头的值按常量使用，例如 zh-CN、1、true' }
];

/** 参数映射的示例（「填入示例」用） */
export const paramSample: Record<string, string> = {
  reportNo: '$request.reportNo'
};
