/**
 * AES-256-GCM 加密脚本
 *
 * 输入:scripts/config.plain.json(明文)
 * 输出:../public/config.json.enc(密文,base64 字符串)
 *
 * 密文格式:base64(iv(12) || ciphertext || tag(16))
 *
 * 解密端(Android)按相同格式拆分:
 *   1. base64 decode
 *   2. 前 12 字节 = iv
 *   3. 最后 16 字节 = auth tag
 *   4. 中间 = ciphertext
 *
 * 环境变量:CONFIG_AES_KEY(32 字节 hex,64 个字符)
 * 与 App 端 BuildConfig.CONFIG_AES_KEY 必须一致
 */

const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const PLAIN_PATH = path.join(__dirname, 'config.plain.json');
const OUTPUT_DIR = path.join(__dirname, '..', 'public');
const OUTPUT_PATH = path.join(OUTPUT_DIR, 'config.json.enc');

// 加载 AES key(优先环境变量,其次 .env 文件)
let KEY = process.env.CONFIG_AES_KEY;
if (!KEY) {
  const envPath = path.join(__dirname, '..', '.env');
  if (fs.existsSync(envPath)) {
    const envContent = fs.readFileSync(envPath, 'utf8');
    const match = envContent.match(/^CONFIG_AES_KEY=(.+)$/m);
    if (match) KEY = match[1].trim();
  }
}

if (!KEY) {
  console.error('错误:未设置 CONFIG_AES_KEY');
  console.error('请在 app-config/.env 中设置 CONFIG_AES_KEY=<32字节hex>');
  console.error('生成方法:node -e "console.log(require(\'crypto\').randomBytes(32).toString(\'hex\'))"');
  process.exit(1);
}

if (KEY.length !== 64 || !/^[0-9a-fA-F]+$/.test(KEY)) {
  console.error(`错误:CONFIG_AES_KEY 必须是 32 字节 hex(64 个字符),当前长度 ${KEY.length}`);
  process.exit(1);
}

const keyBuffer = Buffer.from(KEY, 'hex');
if (keyBuffer.length !== 32) {
  console.error(`错误:key 解码后应为 32 字节,实际 ${keyBuffer.length}`);
  process.exit(1);
}

if (!fs.existsSync(PLAIN_PATH)) {
  console.error(`错误:明文配置不存在 ${PLAIN_PATH}`);
  process.exit(1);
}

const plainJson = fs.readFileSync(PLAIN_PATH, 'utf8');

// 校验 JSON 格式
try {
  JSON.parse(plainJson);
} catch (e) {
  console.error(`错误:config.plain.json 不是合法 JSON: ${e.message}`);
  process.exit(1);
}

// AES-256-GCM 加密
const iv = crypto.randomBytes(12);  // GCM 推荐 12 字节 iv
const cipher = crypto.createCipheriv('aes-256-gcm', keyBuffer, iv);
const encrypted = Buffer.concat([
  cipher.update(plainJson, 'utf8'),
  cipher.final()
]);
const tag = cipher.getAuthTag();  // 16 字节

// 拼接 iv(12) + ciphertext + tag(16),整体 base64 编码
const combined = Buffer.concat([iv, encrypted, tag]);
const base64 = combined.toString('base64');

// 确保输出目录存在
if (!fs.existsSync(OUTPUT_DIR)) {
  fs.mkdirSync(OUTPUT_DIR, { recursive: true });
}

fs.writeFileSync(OUTPUT_PATH, base64, 'utf8');
console.log(`加密成功 → ${OUTPUT_PATH}`);
console.log(`明文长度: ${plainJson.length} 字节`);
console.log(`密文长度: ${base64.length} 字符(base64)`);
console.log(`iv: ${iv.toString('hex')}`);
console.log(`tag: ${tag.toString('hex')}`);
