#!/usr/bin/env node
/**
 * 把 docs/public/demo 下的示例模板刷进正在运行的系统。
 *
 * <p>做的事和手工「执行 SQL + 后台上传文件」完全等价，只是全部走 HTTP 接口，不需要 MySQL 客户端：</p>
 *
 *   1. 解析每个 *.sql：取出 doc_template 元数据、doc_template_data_block 数据块、
 *      doc_template_dict / doc_template_dict_item 数据字典（有的话）；
 *   2. 按 template_code 找模板，没有就建，有就复用（元数据不覆盖，与 SQL 的
 *      `ON DUPLICATE KEY UPDATE template_code = VALUES(template_code)` 语义一致）；
 *   3. 把同名 .docx / .xlsx 传成模板文件；当前版本的 sha256 与本地一致时跳过，重复跑不会堆版本；
 *   4. 数据块按 block_code 增改，字典按 dict_code 增改、字典项「先清后插」重建。
 *
 * <p>只依赖 Node 18+ 内置能力（fetch / FormData / Blob），不需要 npm install。
 * 两个入口脚本 seed-demo.bat（Windows）、seed-demo.sh（Linux / macOS）都只是转发到这里。</p>
 */

import { readFileSync, readdirSync, existsSync, statSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { basename, extname, join, resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const SCRIPT_DIR = dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = resolve(SCRIPT_DIR, '..');
const DEFAULT_DEMO_DIR = join(REPO_ROOT, 'docs', 'public', 'demo');

// --------------------------------------------------------------------------- 输出

const useColor = Boolean(process.stdout.isTTY) && !process.env.NO_COLOR;
const paint = (code) => (text) => (useColor ? `\u001b[${code}m${text}\u001b[0m` : String(text));
const green = paint('32');
const red = paint('31');
const yellow = paint('33');
const dim = paint('2');
const bold = paint('1');

const OK = green('✓');
const FAIL = red('✗');
const WARN = yellow('!');

function fail(message) {
  console.error(`${FAIL} ${message}`);
  process.exit(1);
}

// --------------------------------------------------------------------------- 参数

const USAGE = `
把 docs/public/demo 下的示例模板刷进系统（模板 + 数据块 + 字典 + 模板文件）。

用法：
  node scripts/seed-demo.mjs [选项]
  scripts\\seed-demo.bat        [选项]      Windows
  scripts/seed-demo.sh         [选项]      Linux / macOS

选项：
  -b, --base-url <url>   服务地址，默认 http://localhost:9150
  -t, --token <token>    直接用已有 token，不调登录接口
      --username <name>  登录账号，默认 admin
      --password <pwd>   登录密码，默认 Admin@123
  -d, --dir <path>       示例目录，默认 <仓库>/docs/public/demo
      --only <codes>     只刷指定模板，逗号分隔，例如 WordShowcase,ExcelShowcase
  -n, --dry-run          只解析并打印将要写入的内容，不调接口
      --skip-file        不上传模板文件
      --skip-blocks      不写数据块
      --skip-dicts       不写数据字典
      --force            文件 sha256 相同也重新上传（会多一个版本）
  -h, --help             显示本帮助

环境变量：DOC_TEMPLATE_BASE_URL / DOC_TEMPLATE_TOKEN / DOC_TEMPLATE_USERNAME / DOC_TEMPLATE_PASSWORD
`.trim();

function parseArgs(argv) {
  const opts = {
    baseUrl: process.env.DOC_TEMPLATE_BASE_URL || 'http://localhost:9150',
    token: process.env.DOC_TEMPLATE_TOKEN || '',
    username: process.env.DOC_TEMPLATE_USERNAME || 'admin',
    password: process.env.DOC_TEMPLATE_PASSWORD || 'Admin@123',
    dir: '',
    only: [],
    dryRun: false,
    skipFile: false,
    skipBlocks: false,
    skipDicts: false,
    force: false,
    help: false,
  };
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    const value = () => {
      const next = argv[++i];
      if (next === undefined) fail(`参数 ${arg} 缺少值`);
      return next;
    };
    switch (arg) {
      case '-b': case '--base-url': opts.baseUrl = value(); break;
      case '-t': case '--token': opts.token = value(); break;
      case '--username': opts.username = value(); break;
      case '--password': opts.password = value(); break;
      case '-d': case '--dir': opts.dir = value(); break;
      case '--only': opts.only = value().split(',').map((s) => s.trim()).filter(Boolean); break;
      case '-n': case '--dry-run': opts.dryRun = true; break;
      case '--skip-file': opts.skipFile = true; break;
      case '--skip-blocks': opts.skipBlocks = true; break;
      case '--skip-dicts': opts.skipDicts = true; break;
      case '--force': opts.force = true; break;
      case '-h': case '--help': opts.help = true; break;
      default: fail(`未知参数：${arg}（--help 查看用法）`);
    }
  }
  opts.baseUrl = opts.baseUrl.replace(/\/+$/, '');
  return opts;
}

// --------------------------------------------------------------------------- SQL 解析
//
// 只认这四张表，而且只处理 INSERT ... VALUES 这一种写法 —— demo 里的 SQL 都是这个形状，
// 换别的写法（多表 INSERT、INSERT ... SELECT）宁可报错，也不要静默漏数据。

/** 去掉 -- 行注释和 /* *\/ 块注释，字符串里的内容原样保留。 */
function stripComments(sql) {
  let out = '';
  let i = 0;
  while (i < sql.length) {
    const c = sql[i];
    if (c === "'") {
      out += c;
      i++;
      while (i < sql.length) {
        out += sql[i];
        if (sql[i] === "'") {
          if (sql[i + 1] === "'") { out += sql[i + 1]; i += 2; continue; }
          i++;
          break;
        }
        i++;
      }
      continue;
    }
    if (c === '-' && sql[i + 1] === '-') {
      while (i < sql.length && sql[i] !== '\n') i++;
      continue;
    }
    if (c === '/' && sql[i + 1] === '*') {
      i += 2;
      while (i < sql.length && !(sql[i] === '*' && sql[i + 1] === '/')) i++;
      i += 2;
      continue;
    }
    out += c;
    i++;
  }
  return out;
}

/** 按分号切语句，字符串里的分号不算。 */
function splitStatements(sql) {
  const statements = [];
  let current = '';
  let inString = false;
  for (let i = 0; i < sql.length; i++) {
    const c = sql[i];
    if (inString) {
      current += c;
      if (c === "'") {
        if (sql[i + 1] === "'") { current += sql[++i]; } else { inString = false; }
      }
      continue;
    }
    if (c === "'") { inString = true; current += c; continue; }
    if (c === ';') { statements.push(current); current = ''; continue; }
    current += c;
  }
  if (current.trim()) statements.push(current);
  return statements;
}

/** 按顶层分隔符切分（括号深度和字符串之外的才算）。 */
function splitTopLevel(text, separator = ',') {
  const parts = [];
  let current = '';
  let depth = 0;
  let inString = false;
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (inString) {
      current += c;
      if (c === "'") {
        if (text[i + 1] === "'") { current += text[++i]; } else { inString = false; }
      }
      continue;
    }
    if (c === "'") { inString = true; current += c; continue; }
    if (c === '(') { depth++; current += c; continue; }
    if (c === ')') { depth--; current += c; continue; }
    if (c === separator && depth === 0) { parts.push(current); current = ''; continue; }
    current += c;
  }
  parts.push(current);
  return parts;
}

/** 把 VALUES 后面的 `(..), (..)` 拆成每个元组的内部文本。 */
function readTuples(body) {
  const tuples = [];
  let i = 0;
  while (i < body.length) {
    while (i < body.length && /\s/.test(body[i])) i++;
    if (body[i] !== '(') break;
    let depth = 0;
    let inString = false;
    let buffer = '';
    let j = i;
    for (; j < body.length; j++) {
      const c = body[j];
      if (inString) {
        if (c === "'") {
          if (body[j + 1] === "'") { buffer += "''"; j++; continue; }
          inString = false;
        }
        buffer += c;
        continue;
      }
      if (c === "'") { inString = true; buffer += c; continue; }
      if (c === '(') { depth++; if (depth === 1) continue; buffer += c; continue; }
      if (c === ')') { depth--; if (depth === 0) break; buffer += c; continue; }
      buffer += c;
    }
    tuples.push(buffer);
    i = j + 1;
    while (i < body.length && /\s/.test(body[i])) i++;
    if (body[i] === ',') { i++; continue; }
    break;
  }
  return tuples;
}

/** 单个字面量：字符串 / @变量 / NULL / 数字。 */
function parseLiteral(token) {
  const text = token.trim();
  if (text === '') return '';
  if (text[0] === "'" && text[text.length - 1] === "'") {
    return text.slice(1, -1).replace(/''/g, "'");
  }
  if (/^@[A-Za-z0-9_]+$/.test(text)) return { __var: text.slice(1) };
  if (/^null$/i.test(text)) return null;
  if (/^-?\d+(\.\d+)?$/.test(text)) return Number(text);
  return text;
}

function parseInsert(statement) {
  const match = /^\s*INSERT\s+INTO\s+`?([A-Za-z0-9_]+)`?\s*\(([\s\S]*?)\)\s*VALUES\s*([\s\S]*)$/i.exec(statement);
  if (!match) return null;
  const table = match[1].toLowerCase();
  const columns = splitTopLevel(match[2]).map((c) => c.trim().replace(/`/g, '').toLowerCase());
  const rows = readTuples(match[3]).map((tuple) => {
    const values = splitTopLevel(tuple).map(parseLiteral);
    const row = {};
    columns.forEach((column, index) => { row[column] = values[index]; });
    return row;
  });
  return { table, rows };
}

/** `SET @dict_x = (SELECT id FROM doc_template_dict WHERE dict_code = 'xxx')` → 变量名到字典编码。 */
function parseDictVars(sql) {
  const map = new Map();
  const pattern = /SET\s+@([A-Za-z0-9_]+)\s*=\s*\(\s*SELECT\s+id\s+FROM\s+doc_template_dict\s+WHERE\s+dict_code\s*=\s*'([^']*)'\s*\)/gi;
  let match;
  while ((match = pattern.exec(sql)) !== null) map.set(match[1], match[2]);
  return map;
}

function asJsonObject(text, where) {
  const raw = typeof text === 'string' ? text.trim() : '';
  if (!raw) return {};
  try {
    const value = JSON.parse(raw);
    return value && typeof value === 'object' ? value : {};
  } catch (error) {
    throw new Error(`${where} 的 JSON 解析失败：${error.message}`);
  }
}

/** 解析一个 sql 文件，得到「模板 + 数据块 + 字典」这份种子数据。 */
function parseDemoSql(filePath) {
  const raw = readFileSync(filePath, 'utf8');
  const name = basename(filePath);
  const statements = splitStatements(stripComments(raw));
  const dictVars = parseDictVars(stripComments(raw));

  let template = null;
  const blocks = [];
  const dicts = new Map();
  const dictItems = [];

  for (const statement of statements) {
    const insert = parseInsert(statement);
    if (!insert) continue;

    if (insert.table === 'doc_template') {
      const row = insert.rows[0];
      if (!row || !row.template_code) continue;
      template = {
        code: row.template_code,
        name: row.template_name,
        fileType: String(row.file_type || '').toUpperCase() || null,
        exportType: String(row.export_type || 'ORIGINAL').toUpperCase(),
        status: row.status || 'ENABLED',
        remark: row.remark || '',
      };
    } else if (insert.table === 'doc_template_data_block') {
      for (const row of insert.rows) {
        if (!row.block_code) continue;
        blocks.push({
          blockCode: row.block_code,
          blockName: row.block_name || row.block_code,
          dataSourceType: String(row.data_source_type || 'JAVA').toUpperCase(),
          dataSourceConfig: asJsonObject(row.data_source_config, `${name} / 数据块 ${row.block_code}`),
          paramConfig: asJsonObject(row.param_config, `${name} / 数据块 ${row.block_code} 参数映射`),
          sortOrder: Number.isFinite(row.sort_order) ? row.sort_order : blocks.length + 1,
          status: row.status || 'ENABLED',
        });
      }
    } else if (insert.table === 'doc_template_dict') {
      for (const row of insert.rows) {
        if (!row.dict_code) continue;
        if (!dicts.has(row.dict_code)) {
          dicts.set(row.dict_code, {
            dictCode: row.dict_code,
            dictName: row.dict_name || row.dict_code,
            remark: row.remark || '',
            items: [],
          });
        }
      }
    } else if (insert.table === 'doc_template_dict_item') {
      for (const row of insert.rows) {
        const ref = row.dict_id;
        const code = ref && typeof ref === 'object' && ref.__var ? dictVars.get(ref.__var) : null;
        if (!code) {
          throw new Error(`${name} 里有一条字典项找不到所属字典（dict_id=${JSON.stringify(ref)}），请检查 SET @dict_* 语句`);
        }
        dictItems.push({
          dictCode: code,
          itemValue: row.item_value,
          itemLabel: row.item_label,
          sortOrder: Number.isFinite(row.sort_order) ? row.sort_order : null,
        });
      }
    }
  }

  for (const item of dictItems) {
    if (!dicts.has(item.dictCode)) {
      dicts.set(item.dictCode, { dictCode: item.dictCode, dictName: item.dictCode, remark: '', items: [] });
    }
    dicts.get(item.dictCode).items.push(item);
  }

  if (!template) return null;

  // 模板文件就是同名的 docx / xlsx
  const base = basename(filePath, extname(filePath));
  const candidates = [`${base}.docx`, `${base}.xlsx`];
  const file = candidates.map((f) => join(dirname(filePath), f)).find((f) => existsSync(f)) || null;

  return { sqlFile: name, template, blocks, dicts: [...dicts.values()], file };
}

// --------------------------------------------------------------------------- HTTP 请求

/** fetch 失败时原始错误只有一句「fetch failed」，把底层原因（拒绝连接 / DNS）带出来。 */
function describeFetchError(error, target) {
  const cause = error && error.cause;
  const code = cause && (cause.code || cause.errno);
  if (code === 'ECONNREFUSED') return `连不上 ${target}，服务是不是没启动？（ECONNREFUSED）`;
  if (code === 'ENOTFOUND' || code === 'EAI_AGAIN') return `域名解析失败：${target}（${code}）`;
  if (code === 'ETIMEDOUT') return `连接 ${target} 超时（ETIMEDOUT）`;
  const detail = code ? `${code}${cause.message ? ' ' + cause.message : ''}` : (cause && cause.message) || (error && error.message);
  return `${target} 请求失败：${detail}`;
}

/** 带分类的错误：main 里据此决定是「直接退出」还是「无认证继续」。 */
function tagged(message, kind) {
  const error = new Error(message);
  error.kind = kind;
  return error;
}

class Client {
  constructor(baseUrl) {
    this.baseUrl = baseUrl;
    this.apiBase = `${baseUrl}/api/doc-template`;
    this.headers = { Accept: 'application/json' };
  }

  async login(username, password) {
    const target = `${this.baseUrl}/demo/login`;
    let response;
    try {
      response = await fetch(target, {
        method: 'POST',
        headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
        body: JSON.stringify({ username, password }),
      });
    } catch (error) {
      throw tagged(describeFetchError(error, target), 'network');
    }
    if (!response.ok) {
      const text = await response.text().catch(() => '');
      if (response.status === 404 || response.status === 405) {
        throw tagged(`没有登录接口（HTTP ${response.status}）`, 'unsupported');
      }
      let detail = text;
      try {
        const parsed = JSON.parse(text);
        detail = parsed.msg || parsed.message || text;
      } catch { /* 保持原文 */ }
      const kind = response.status === 401 ? 'auth' : 'unsupported';
      throw tagged(`登录失败（HTTP ${response.status}）：${String(detail).slice(0, 200)}`, kind);
    }
    const body = await response.json();
    const header = body.tokenName || 'Authorization';
    const prefix = body.tokenPrefix === undefined ? 'Bearer' : body.tokenPrefix;
    const value = body.tokenValue;
    if (!value) throw tagged('登录成功但响应里没有 tokenValue', 'unsupported');
    this.headers[header] = prefix ? `${prefix} ${value}` : value;
    return body.username || username;
  }

  async call(method, path, body) {
    const init = { method, headers: { ...this.headers } };
    if (body !== undefined) {
      init.headers['Content-Type'] = 'application/json';
      init.body = JSON.stringify(body);
    }
    const target = this.apiBase + path;
    let response;
    try {
      response = await fetch(target, init);
    } catch (error) {
      throw new Error(describeFetchError(error, target));
    }
    return this.unwrap(response, `${method} ${path}`);
  }

  async upload(path, filePath, bytes) {
    const form = new FormData();
    form.append('file', new Blob([bytes]), basename(filePath));
    const target = this.apiBase + path;
    let response;
    try {
      response = await fetch(target, {
        method: 'POST',
        headers: { ...this.headers },
        body: form,
      });
    } catch (error) {
      throw new Error(describeFetchError(error, target));
    }
    return this.unwrap(response, `POST ${path}`);
  }

  async unwrap(response, label) {
    const text = await response.text();
    if (!response.ok) {
      let detail = text;
      try {
        const parsed = JSON.parse(text);
        detail = parsed.msg || parsed.message || parsed.error || text;
      } catch { /* 保持原文 */ }
      if (response.status === 401) {
        detail = `${detail}（401：当前是 custom 认证模式，需要带 token，用 --token 或 --username/--password）`;
      }
      throw new Error(`${label} → HTTP ${response.status}：${String(detail).slice(0, 300)}`);
    }
    if (!text) return null;
    try { return JSON.parse(text); } catch { return text; }
  }
}

// --------------------------------------------------------------------------- 刷数据

function sha256(bytes) {
  return createHash('sha256').update(bytes).digest('hex');
}

async function seedOne(client, demo, opts, stats, ctx) {
  const title = `${demo.template.code} ${dim(demo.template.name)}`;
  console.log(`\n${bold(title)}  ${dim(demo.sqlFile)}`);

  // 1. 数据字典（数据块的 converters 可能引用，先建）
  if (!opts.skipDicts && demo.dicts.length) {
    const existingDicts = await client.call('GET', '/dicts');
    for (const dict of demo.dicts) {
      // 两份 demo 可能共用同一本字典（Word / Excel 各一份），同一次运行里只重建一次
      if (ctx.seenDicts.has(dict.dictCode)) {
        console.log(`  ${dim('·')} 字典 ${dict.dictCode} 已由前面的模板建过，跳过`);
        continue;
      }
      ctx.seenDicts.add(dict.dictCode);
      let target = existingDicts.find((d) => d.dict_code === dict.dictCode);
      if (!target) {
        target = await client.call('POST', '/dicts', {
          dictCode: dict.dictCode,
          dictName: dict.dictName,
          remark: dict.remark,
        });
        stats.dictCreated++;
        console.log(`  ${OK} 字典 ${dict.dictCode} 新增`);
      } else {
        if (target.dict_name !== dict.dictName || (target.remark || '') !== (dict.remark || '')) {
          await client.call('PUT', `/dicts/${target.id}`, {
            dictCode: dict.dictCode,
            dictName: dict.dictName,
            remark: dict.remark,
          });
          stats.dictUpdated++;
          console.log(`  ${OK} 字典 ${dict.dictCode} 更新`);
        } else {
          console.log(`  ${dim('·')} 字典 ${dict.dictCode} 无变化`);
        }
      }

      // 字典项「先清后插」重建，和 SQL 里的 DELETE + INSERT 一致
      const current = await client.call('GET', `/dicts/${target.id}/items`);
      if (current.length) {
        await client.call('POST', '/dicts/items/batch-delete', { ids: current.map((i) => i.id) });
      }
      for (const item of dict.items) {
        await client.call('POST', `/dicts/${target.id}/items`, {
          itemValue: item.itemValue,
          itemLabel: item.itemLabel,
          ...(item.sortOrder === null ? {} : { sortOrder: item.sortOrder }),
        });
      }
      stats.dictItems += dict.items.length;
      console.log(`     ${dim(`字典项 ${dict.items.length} 条已重建`)}`);
    }
  }

  // 2. 模板元数据：已存在就不动（与 SQL 的 ON DUPLICATE 只保底一致）
  const templates = await client.call('GET', '/templates');
  let template = templates.find((t) => t.template_code === demo.template.code);
  if (!template) {
    template = await client.call('POST', '/templates', {
      templateCode: demo.template.code,
      templateName: demo.template.name,
      fileType: demo.template.fileType || 'DOCX',
      exportType: demo.template.exportType,
      status: demo.template.status,
      remark: demo.template.remark,
    });
    stats.templateCreated++;
    console.log(`  ${OK} 模板新增 id=${template.id}`);
  } else {
    stats.templateExisting++;
    console.log(`  ${dim('·')} 模板已存在 id=${template.id}（元数据不覆盖）`);
  }
  const templateId = template.id;

  // 3. 模板文件
  if (!opts.skipFile) {
    if (!demo.file) {
      console.log(`  ${WARN} 找不到同名 docx / xlsx，跳过文件上传`);
    } else {
      const bytes = readFileSync(demo.file);
      const digest = sha256(bytes);
      const detail = await client.call('GET', `/templates/${templateId}`);
      const versions = await client.call('GET', `/templates/${templateId}/versions`);
      const current = versions.find((v) => v.id === detail.current_version_id);
      if (!opts.force && current && current.file_sha256 === digest) {
        stats.fileSkipped++;
        console.log(`  ${dim('·')} 文件 ${basename(demo.file)} 已是最新（v${current.version_no}），跳过`);
      } else {
        const uploaded = await client.upload(`/templates/${templateId}/file`, demo.file, bytes);
        stats.fileUploaded++;
        console.log(`  ${OK} 文件 ${basename(demo.file)} 上传为 v${uploaded.version_no}`);
      }
    }
  }

  // 4. 数据块：按 block_code 增改
  if (!opts.skipBlocks && demo.blocks.length) {
    const existing = await client.call('GET', `/data-blocks?templateId=${templateId}`);
    const created = [];
    const updated = [];
    for (const block of demo.blocks) {
      const payload = {
        blockName: block.blockName,
        dataSourceType: block.dataSourceType,
        dataSourceConfig: block.dataSourceConfig,
        paramConfig: block.paramConfig,
        sortOrder: block.sortOrder,
        status: block.status,
      };
      const target = existing.find((b) => b.block_code === block.blockCode);
      if (target) {
        await client.call('PUT', `/data-blocks/${target.id}`, payload);
        updated.push(block.blockCode);
      } else {
        await client.call('POST', `/data-blocks?templateId=${templateId}`, { ...payload, blockCode: block.blockCode });
        created.push(block.blockCode);
      }
    }
    stats.blockCreated += created.length;
    stats.blockUpdated += updated.length;
    console.log(`  ${OK} 数据块 ${demo.blocks.length} 个` +
      `${created.length ? `，新增 ${created.join(',')}` : ''}` +
      `${updated.length ? `，更新 ${updated.join(',')}` : ''}`);
  }

  return templateId;
}

// --------------------------------------------------------------------------- 主流程

async function main() {
  const opts = parseArgs(process.argv.slice(2));
  if (opts.help) {
    console.log(USAGE);
    return;
  }

  const demoDir = opts.dir ? resolve(opts.dir) : DEFAULT_DEMO_DIR;
  if (!existsSync(demoDir) || !statSync(demoDir).isDirectory()) {
    fail(`示例目录不存在：${demoDir}`);
  }

  const sqlFiles = readdirSync(demoDir).filter((f) => f.toLowerCase().endsWith('.sql')).sort();
  if (!sqlFiles.length) fail(`示例目录里没有 .sql 文件：${demoDir}`);

  const wanted = new Set(opts.only.map((c) => c.toLowerCase()));
  const demos = [];
  const parseErrors = [];
  for (const file of sqlFiles) {
    try {
      const demo = parseDemoSql(join(demoDir, file));
      if (!demo) continue;
      if (wanted.size && !wanted.has(demo.template.code.toLowerCase())) continue;
      demos.push(demo);
    } catch (error) {
      parseErrors.push(`${file}：${error.message}`);
    }
  }

  console.log(`${bold('示例目录')} ${demoDir}`);
  console.log(`${bold('服务地址')} ${opts.baseUrl}${opts.dryRun ? dim('（dry-run，不写任何数据）') : ''}`);
  console.log(`${bold('待刷模板')} ${demos.length} 套`);
  for (const error of parseErrors) console.log(`${FAIL} 解析失败 ${error}`);
  if (!demos.length) {
    fail(wanted.size ? `没有匹配 ${opts.only.join(',')} 的模板` : '没有解析出任何模板');
  }

  if (opts.dryRun) {
    for (const demo of demos) {
      console.log(`\n${bold(demo.template.code)} ${dim(demo.template.name)}`);
      console.log(`  文件 ${demo.file ? basename(demo.file) : dim('（无）')}`);
      console.log(`  字典 ${demo.dicts.length ? demo.dicts.map((d) => `${d.dictCode}(${d.items.length})`).join(', ') : dim('（无）')}`);
      for (const block of demo.blocks) {
        console.log(`  块 ${block.blockCode.padEnd(14)} ${block.dataSourceType.padEnd(5)} sort=${block.sortOrder} ${dim(block.blockName)}`);
      }
    }
    return;
  }

  const client = new Client(opts.baseUrl);
  if (opts.token) {
    client.headers.Authorization = opts.token.startsWith('Bearer ') ? opts.token : `Bearer ${opts.token}`;
    console.log(`${bold('认证')} 使用传入的 token`);
  } else {
    try {
      const user = await client.login(opts.username, opts.password);
      console.log(`${bold('认证')} ${user} 登录成功`);
    } catch (error) {
      // 账号密码错 / 连不上，都没有继续试下去的意义；只有「这个宿主没有 /demo/login」才按无认证继续
      if (error.kind === 'auth' || error.kind === 'network') fail(error.message);
      console.log(`${WARN} ${error.message}，按无认证模式继续`);
      console.log(`${dim('  接口若返回 401，请用 --token 指定 token')}`);
    }
  }

  const stats = {
    templateCreated: 0, templateExisting: 0,
    fileUploaded: 0, fileSkipped: 0,
    blockCreated: 0, blockUpdated: 0,
    dictCreated: 0, dictUpdated: 0, dictItems: 0,
    failed: 0,
  };

  const ctx = { seenDicts: new Set() };

  for (const demo of demos) {
    try {
      await seedOne(client, demo, opts, stats, ctx);
    } catch (error) {
      stats.failed++;
      console.log(`  ${FAIL} ${error.message}`);
    }
  }

  console.log(`\n${bold('汇总')}`);
  console.log(`  模板   新增 ${stats.templateCreated} / 已存在 ${stats.templateExisting}`);
  console.log(`  文件   上传 ${stats.fileUploaded} / 跳过 ${stats.fileSkipped}`);
  console.log(`  数据块 新增 ${stats.blockCreated} / 更新 ${stats.blockUpdated}`);
  console.log(`  字典   新增 ${stats.dictCreated} / 更新 ${stats.dictUpdated} / 字典项 ${stats.dictItems}`);
  if (stats.failed || parseErrors.length) {
    console.log(`  ${FAIL} 失败 ${stats.failed + parseErrors.length} 套`);
    process.exit(1);
  }
  console.log(`  ${OK} 全部完成`);
}

// 只有直接 node 这个文件时才跑；被 import（比如单独验证解析结果）时只导出函数。
const entry = process.argv[1] ? resolve(process.argv[1]).toLowerCase() : '';
if (entry === fileURLToPath(import.meta.url).toLowerCase()) {
  main().catch((error) => {
    fail(error && error.stack ? error.stack : String(error));
  });
}

export { parseDemoSql, splitStatements, parseInsert };
