# app-config

TrackToSearch App 的云端配置服务,部署在 Cloudflare Pages。

## 部署流程

1. 编辑 `scripts/config.plain.json`,填入真实 API key

   GitHub Actions 的 `APP_CONFIG_PLAIN` Secret 可保存原文 JSON，也可保存
   `base64 -w 0 scripts/config.plain.json` 的结果；workflow 会自动识别并解码。
2. 生成 AES key(32 字节 hex,64 个字符):

   ```bash
   node -e "console.log(require('crypto').randomBytes(32).toString('hex'))"
   ```

3. 写入 `.env`:

   ```
   CONFIG_AES_KEY=生成的key
   ```

4. 运行加密脚本生成密文:

   ```bash
   cd scripts && node encrypt-config.js
   ```

   产物:`public/config.json.enc`

5. 部署到 CF Pages:

   ```bash
   npx wrangler pages deploy public --project-name=app-config
   ```

6. 验证:`curl https://app-config.pages.dev/config.json.enc` 应返回 base64 密文

## 与 App 端的关系

- App 拉取 `GET https://app-config.pages.dev/config.json.enc`
- App 用 `BuildConfig.CONFIG_AES_KEY`(与 `.env` 中的 `CONFIG_AES_KEY` 相同)解密
- 两端 key 不一致 → 解密失败 → App 回退 `BuildConfig` 兜底

## 更新配置

修改 `scripts/config.plain.json` → 重新运行加密脚本 → 重新部署。App 会在 24h 内自动拉取新配置。

## 故障转移

- TMDB 配置多个 key(`tmdb.apiKeys` 数组),App 收到 429 自动冷却当前 key 5 分钟并切下一个,401 标记失效直到下次配置刷新
- 单个请求最多轮换 3 次,避免死循环
- 全部 key 失效时返回第一个 key 重试(避免无 key 可用)

## 安全说明

配置文件经 AES-256-GCM 加密,解密 key 嵌入 APK 的 `BuildConfig.CONFIG_AES_KEY`。

- 防中间人篡改:HTTPS + GCM 认证标签
- 防爬虫直连明文:密文部署,非明文 JSON
- 不对抗逆向工程:解密 key 嵌入 APK 理论上可被反编译提取(YAGNI 范围内可接受)
