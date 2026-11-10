# Mobile Model Hub · 手机上的模型聚合

> 应用显示名仍是 **统一API网关**；项目名用 Mobile Model Hub，强调「在手机上把多个模型聚合成一个端点」这件事。

在手机上**直接登录** WorkBuddy / Qoder / ZCode / Raccoon / 华为 CodeArts 等平台账号，或用 API Key 接任意
OpenAI 兼容上游，聚合成 **1 个端点**，供同一部手机上的其他 AI 应用调用。

```
手机内登录 WorkBuddy 国际版 ─┐
手机内登录 WorkBuddy 国内版 ─┤
手机内登录 Qoder / Qoder CN ─┼─→ 本 APK（127.0.0.1:8787/v1）─→ Cherry Studio / ChatBox 等
手机内登录 ZCode / Raccoon  ─┤        1 个 BaseURL + 1 个 Key
手机内登录 华为 CodeArts    ─┤
自己的 API Key（DeepSeek 等）─┘
```

## 安装

把 [`MobileModelHub.apk`](MobileModelHub.apk) 传到手机安装（约 129 KB，已签名）。
支持 Android 7.0（API 24）及以上。用 Android Debug 签名，安装时允许「未知来源」即可。

> **升级不会丢数据**：签名密钥固定在项目目录（`debug.keystore`），
> 后续版本可以直接覆盖安装。早期版本每次构建都会换签名，覆盖安装会失败、
> 只能卸载重装 —— 那样 App 内已登录的账号会一起没掉。


## 后台保活（必做，否则会被系统杀掉）

装好后**先做这一步**。不做的典型症状：息屏几分钟后其他 App 调用就连不上，
或者划掉最近任务后网关消失，看起来像「它坏了」。

### 为什么光有前台服务不够

前台服务只挡住「普通后台回收」，挡不住这两层：

| 层级 | 表现 | 谁能解决 |
| --- | --- | --- |
| 电池优化 / Doze | 息屏后网络被冻结，进程还在但请求超时 | App 可申请白名单 |
| 厂商「自启动 / 后台管理」 | 息屏或划掉任务后被清理 | **只能用户手动开**（系统策略） |

实测证据（2026-11-10，小米）：未加白名单时息屏 60 秒后 `WakeLock` 被系统标成
`DISABLED`、`curl` 直接超时；加白名单后同样息屏 90 秒仍是 `ACQ ... LONG`，
强制深度 Doze 下也能正常响应。

### 要做两件事

到 App 的 **设置 → 后台保活**：

1. **点「忽略电池优化」**，在系统弹窗里选「允许」
2. **点「自启动 / 后台运行」**，在跳过去的系统页面里打开本应用

第二项跳转按品牌定向（小米 / 华为 / 荣耀 / OPPO / 一加 / realme / vivo / iQOO /
三星 / 魅族 / 联想 / 中兴 / 努比亚 / 传音 / 华硕 / 诺基亚 等 12 类），
未知品牌会扫描设备上名字像「安全中心 / 手机管家」的应用去找自启页；
全都找不到时回落到应用详情页 —— 不会点了没反应。

界面底部会直接显示**当前机型的具体操作路径**（各家菜单名不一样，照着点即可）。

### 已内置的保活措施

- 前台服务 + 常驻通知（`foregroundServiceType=dataSync`）
- `PARTIAL_WAKE_LOCK`：只阻止 CPU 休眠，**不亮屏**；启停成对释放
- 开机自启（`BOOT_COMPLETED` / `LOCKED_BOOT_COMPLETED` / 应用更新后）：
  只在**用户上次是开着的状态**才自启 —— 你主动点过「停止」，重启手机不会又把它拉起来
- 划掉最近任务后自动重新拉起（`onTaskRemoved`）
- 端口健康探测：服务显示「运行中」但 accept 线程已死时（端口 LISTEN 但连接全超时），
  点「启动网关」会检测到并自动重启，而不是像以前那样什么都不发生

## 其他设备访问（局域网）

默认只监听 `127.0.0.1`，**只有本机能用**。要让平板 / 电脑 / 另一部手机也连：

1. 设置里打开「允许局域网访问」，然后**重启网关**（改监听地址要重启才生效）
2. 首页会多出一行「局域网地址」，形如 `http://10.101.214.80:8787/v1`，点「复制」
3. 在另一台设备上把这一行填成 Base URL，API Key 用同一个

⚠ `127.0.0.1` 是**本机回环**，别的设备填了必然连不上 —— 所以首页只会给出真实网卡地址。

界面会把所有可用网卡列出来并标注来源，因为同一台手机可能同时有多个地址：

- Wi-Fi / 以太网 / USB 网络共享 → 通常可被其他设备访问
- 蜂窝（`rmnet` / `ccmni`）→ 运营商 NAT，别的设备连不上，界面会用黄字提示
- VPN / CGNAT / Docker 虚拟网段（`26.x`、`100.64-127.x`、`172.17.x`）→ **直接过滤不显示**，
  免得你拿一个根本连不通的地址去试

> 如果只显示「当前没有可用地址」，说明手机没连 Wi-Fi 也没开 USB 共享。

## 界面：底部四分类

底部固定导航把界面拆成四页，每页只放一件事：

| 分类 | 内容 |
|---|---|
| **首页** | 网关状态、启动/停止、Base URL 与 API Key（可复制）、运行日志 |
| **账号** | 七个平台的登录入口、积分、签到（含「一键签到」）、**打开官网登录页** |
| **渠道** | 已配置渠道列表、每个渠道的模型开关、添加 API Key 渠道 |
| **设置** | 监听端口、是否允许局域网访问 |

> 之前是五张卡片竖着排，手机上要滑两三屏才能摸到「设置」，日志几乎没人翻到底。
> 分类之后每个页面都能一眼看完。

两个实现细节：

- **切页会重建该页视图，并立刻填一次数据**（只建骨架不填数据的话，
  切过去只看到标题和说明文字，列表是空的 —— 这个坑踩过一次）；
- **日志存在内存缓冲里**，不在「首页」时写入的日志不会丢，切回去会回填。

## 登录平台账号

App「账号」页，**七个渠道全部支持在 App 内完成登录**：

| 平台 | 登录方式 | 说明 |
|---|---|---|
| **WorkBuddy AI（国际版）** | 打开登录页 → 自动识别 | 扫码或账号登录，App 每 2 秒轮询 |
| **WorkBuddy（国内版）** | 同上 | `copilot.tencent.com` / codebuddy.cn |
| **Qoder** | 打开登录页 → 自动完成 | 设备码式 PKCE，选完账号自动回 App |
| **Qoder（中国版）** | 同上 | `qoder.cn` |
| **ZCode（智谱）** | **App 内页面登录（自动识别）** | 内置 WebView 截获 `zcode://` 回调，无须任何操作 |
| **Raccoon（商汤）** | **App 内页面登录（手机号 + 短信验证码）** | 见下文，为什么不能做成原生表单 |
| **华为 CodeArts** | **App 内页面登录（华为账号）** | 内置 WebView 截获 `127.0.0.1:…/oauth/callback`，换到华为云临时凭据 |

> **七个渠道全部是「点一下 → 登录 → 自动回 App」**，不需要复制粘贴任何东西。

### 每个渠道都有「打开登录页」

「账号」页每个渠道下面都有一行「官网：xxx」，右边是 **打开登录页** ——
用系统浏览器打开该渠道的官方登录页。两个真实用途：

1. App 内登录偶发失败时，去官网确认「账号本身能不能登」；
2. Qoder / ZCode 这类要 token 的场景，官网登录后可以从浏览器拿到凭据再粘回 App。

地址都是实测过的（不是按域名猜），且**刻意不用** `Spec.loginUrlTemplate` ——
那个字段带 `{state}` 占位符，是给设备码流程用的，直接打开会拼出一个打不开的地址：

| 渠道 | 登录页 |
|---|---|
| WorkBuddy AI（国际版） | `https://www.workbuddy.ai/login` |
| WorkBuddy（国内版） | `https://www.codebuddy.cn/login`（**不是** `copilot.tencent.com`，那是 API 域名，打开是一段 JSON） |
| Qoder | `https://qoder.com/users/sign-in` |
| Qoder（中国版） | `https://qoder.cn/users/sign-in` |
| ZCode | `https://zcode.z.ai/` |
| Raccoon | `https://xiaohuanxiong.com/login` |
| 华为 CodeArts | `https://codearts.huaweicloud.com/portal/login`（华为云统一认证） |

没有可用浏览器时不会只弹一句「失败」，而是**把地址复制到剪贴板**，
还能拿到别的设备上打开。

### 4a. Raccoon：为什么是「App 内嵌页面」而不是原生表单

Raccoon 的手机号登录带**阿里云人机验证**（`initAliyunCaptcha`，SceneId `1pkmy0x3`）。
服务端对 `captcha_token` 做了**来源绑定**。实测结论（2026-11-03）：

```text
无 token                                  → captcha_token required
随手编的 token                            → captcha_token invalid
阿里云组件在本机拿到的真 token（跨上下文发） → captcha_token invalid
网页自己发出去的真 token（抓包后重放）      → captcha_token invalid
网页自己在同一次页面会话里发               → 200（正常下发短信）
```

也就是说 **只有「同一次页面会话里由页面自己发」才被认**。
原生界面重放一定失败 —— 这不是实现偷懒，是服务端校验决定的。

所以 Raccoon 走 `RaccoonLoginActivity`：WebView 加载 `xiaohuanxiong.com/login`，
用户在页面里填手机号、完成人机验证、收短信、填验证码；登录成功后 App 读取 token
并自动回到原生界面、落库给网关。**用户全程不需要离开 App，也不需要粘贴任何东西。**

顺带把这条链路上实测到的数据点记下来（都验证过，不是猜的）：

| 项 | 实测值 |
|---|---|
| 手机号加密 | AES-128-CFB，key = `publicKey`（网页 config 里就是 `senseraccoon2023`），`base64(IV‖密文)`，IV 16 字节随机 |
| 交叉验证 | 用该 key 解密网页真实发出的密文，能得到原手机号 ✅ |
| `nation_code` | 网页发的是 **`"+86"`**（带加号），不是 `"86"` |
| 发短信端点 | `POST /api/iam/v1/sms/send` |
| 短信登录端点 | `POST /api/iam/v1/auth/login/sms` |
| 聊天端点 | `POST /api/web/llm/v2/chat/completions`（无 token → `401 authorization_empty_error`，说明存在且要鉴权） |
| 模型目录端点 | **不存在**（`/llm/v2/models`、`/llm/v1/models`、`/desktop/v1/models` 全 404）→ 只能用内置表 |

### 4b. Raccoon 登录态怎么取

网页的 token 存在 **cookie** 里（bundle 原文：`getToken: () => readCookie(document.cookie, config.cookieName)`），
不是 localStorage。`cookieName` 来自运行时配置、并非固定字符串，站点自己的派生规则是：

```js
cookieName 形如 `*xhx_token`
访问令牌 = cookieName.slice(0, -9) + "raccoon_token"
刷新令牌 = 同上前缀 + "raccoon_refresh_token"
```

所以 App **不硬编码键名**，按规则识别，并同时走两条路：

1. `CookieManager.getCookie()` —— 原生侧读 cookie 罐（即使 token 被标 HttpOnly 也能读到）；
2. 注入 JS 读 `document.cookie` —— 覆盖站点改成别的存储方式的情况。

> ⚠ 一个曾经写错、值得记住的点：未登录时页面上本来就有 `_l_KPLiPs` 这类
> **设备指纹 cookie**，随手取「第一个非空 cookie」当 token，会得到
> 「登录成功但一用就 401」。所以识别规则必须严格，取不到就宁可报未登录。

**但规则本身也不够**：`cookieName` 由运行时配置下发，离线无法确证它一定是
`*xhx_token` 形态；而实测该站点确实会用 HttpOnly cookie（`INGRESSCOOKIE` 就是
`httpOnly=true`），说明「哪些 cookie 可见」也会变。

所以最终实现是 **「收集候选 → 用真实请求验证」**：

```text
候选来源：① 按规则推导  ② CookieManager 里名字带 token 的  ③ 页面 JS 自报的
验证方式：逐个拿去调 GET /api/web/auth/v1/user_info
判定：能拿到 data 的那个才是真令牌；全不行就继续等（不误判、不崩）
```

这比任何命名推断都可靠，也不怕站点改版 —— 代价是登录成功瞬间多一次 HTTP 请求。

> ⚠ 这条链路必须**在后台线程**执行。第一版把 `user_info` 请求写在了
> `pollOnce()` 里，而那是 UI 线程的 Handler 驱动的 —— Android 会直接抛
> `NetworkOnMainThreadException`，症状是「登录成功的瞬间 App 崩掉」。
> **桌面验证抓不到这个问题**（桌面没有这条限制），只能靠真机跑出来。

### 4c. ZCode 为什么必须粘一次 code

它的回调是**自定义 scheme**（`zcode://oauth/callback`）。浏览器认得这个 scheme，
但只有装了 ZCode 桌面端的机器才有人接它 —— 手机上没有任何应用响应，
页面会停在那个地址上。

所以最短的可走通路径是：登录完成后从地址栏把 `code=…` 那一段复制回 App。
App 会从三种形态里自动抠出 code（完整 URL / `code=xxx` / 纯 code），
粘错东西（比如把 `state=…` 粘进来）会明确提示「没读到有效的 code」，
不会拿无效串去换 token。

### 各渠道的登录链路（均从生产包逆向确认，并做过真实请求验证）

#### WorkBuddy 两区域：设备码

```text
POST {endpoint}/v2/plugin/auth/state?platform=workbuddy-ai
  头部 X-Domain / X-No-Authorization: true / X-No-User-Id: true
       / X-No-Enterprise-Id: true / X-No-Department-Info: true
  → {"code":0,"data":{"state":"…","authUrl":"https://www.workbuddy.ai/login?platform=workbuddy-ai&state=…"}}

GET  {endpoint}/v2/plugin/auth/token?state={state}
  → 未完成：{"code":11217,"msg":"login ing..."}       ← App 每 2 秒轮询
  → 已完成：{"code":0,"data":{"access_token":…, "refresh_token":…}}
```

实测输出（本机真实请求）：

```text
state: bd1fd16c-f619-46dc-aca1-2e0b10b5d89d
authUrl: https://www.workbuddy.ai/login?platform=workbuddy-ai&state=bd1fd16c-…
poll code: 11217 | msg: login ing...
✓ 等待扫码（11217）— 与官方协商一致
```

#### Qoder / Qoder 中国版：设备码式 PKCE

**不需要 WASM 签名器** —— 签名器只在「聊天」那一跳用，登录这一跳不用。

```text
1. 本地生成 PKCE（verifier 64 位，challenge = base64url(sha256(verifier))）
   与 nonce / machine_id（都是随机 UUID）
2. 打开 {authBase}/device/selectAccounts
      ?challenge=…&challenge_method=S256&nonce=…&machine_id=…&client_id=…
   → 未登录时 302 到 /users/sign-in（实测确认）
3. 轮询 {openApiBase}/api/v1/deviceToken/poll
      ?nonce=…&verifier=…&challenge_method=S256
   → 未完成：HTTP 404        ← 这是**正常等待**，不是错误
   → 完成：{token, refresh_token, expires_at, user_name}
```

两个要点：

- **`authBase` 与 `openApiBase` 不是同一个域名**：授权页在 `qoder.com`，
  换 token 在 `openapi.qoder.sh`。混用只会得到 404，看起来像「登录接口不存在」；
- **`machine_id` 必须存下来**：Qoder 的令牌与它绑定，续期时要原样带回。
  换个 id 续期会被拒（表现为「刷新一直失败，只能重新登录」）。

#### ZCode（智谱）：内置 WebView + 自动截获回调

```text
1. 生成 state
2. 在 App 内置 WebView 里打开
   https://bigmodel.cn/login?redirect=…&appId=zcode&state=…
   其中 redirect = https://zcode.z.ai/app/oauth/login
                     ?redirect=zcode://oauth/callback&app_version=3.14.3
3. 登录完成后门户把浏览器送到
   https://zcode.z.ai/app/oauth/login?…&code=…&state=…
   （这是一个 Next.js 页面，**服务端不跳转**，靠前端 JS 再跳 zcode://）
4. WebView 在 onPageFinished / shouldOverrideUrlLoading 里读 URL 上的 code
5. POST https://zcode.z.ai/api/v1/oauth/token
   {provider:"bigmodel", code, redirect_uri, state}
   → {data:{token:<zcode JWT>, bigmodel:{access_token, refresh_token}}}
```

**为什么这样就能「无须其他操作」**：之前以为「手机上没有应用接 `zcode://`，
只能让用户把 code 粘回来」—— 那只对了一半。**我们自己的 App 可以接**：
把登录放进内置 WebView 后，回调在 WebView 里表现为一次导航，用
`shouldOverrideUrlLoading` 直接截住即可。

两个实现细节：

- **不注册系统 scheme**：注册会污染系统意图，而且用户从外部浏览器登录时
  未必回到本 App；WebView 内截获才是闭环；
- **`onPageFinished` 里也要查一次**：那个 https 中转页服务端不跳转
  （实测 200 + SPA HTML），code 已经挂在地址上，页面加载完就能读到。
  只挂 `shouldOverrideUrlLoading` 有概率错过。

> ⚠ `code` 一次性且短时效，必须在几十秒内换 token —— 所以拿到就立刻后台发起，
> 不能等用户再点一次。

#### Raccoon（商汤）：WebView 内网页登录

见上文 4a / 4b（为什么不走原生表单、token 怎么取）。

> 曾尝试过它的**扫码登录**（`POST /api/iam/v1/auth/mp/qrcode` 建码 → 画二维码 → 轮询），
> 建码与轮询接口都是通的，但二维码里的落地页 `/login/mp` **对所有 UA 都返回 404**，
> 手机扫完只得到一张错误页 —— 因此该方案已放弃。它的建码接口本身仍然可用
> （抓包确认网页端就是这么做的），只是那个落地页当前不可达。

#### 华为 CodeArts：DPoP + STS 换临时凭据

它和上面几个都不一样：**登录换到的不是 Bearer token，而是华为云临时 AK/SK**，
之后每个请求都要用 SK 现算签名（见下文「签名」）。

CodeArts 有**两代登录流程**，回调形态不同 —— 这一点踩过坑，先讲清楚：

| 流程 | 授权入口 | 回调 | 换凭据 |
|---|---|---|---|
| 旧 ticket | `auth.huaweicloud.com?service=doer/redirect…`（`snap_jetbrains/26.3.3`） | `…?secret=…` | 轮询 `/snap-manager/v1/login/ticket` |
| **新 OAuth**（当前客户端在用） | `codearts.huaweicloud.com/portal/authorize`（`snap_AIIDE/5.2.0`） | `…?code=…` | `POST sts…/v1/oauth2/tokens`（DPoP） |

⚠ **App 早期只做了「旧入口 + 只认 code」**：旧入口回调给的是 `secret`，
解析器却去找 `code` —— 于是登录完成后什么都不发生，用户只看到「登录不上」。
现在两条都走通：

```text
1. 生成 PKCE（verifier / challenge）与 ES256 密钥对（DPoP），存下私钥 JWK
2. 内置 WebView 打开（新流程）
   https://codearts.huaweicloud.com/portal/authorize
     ?theme=2&locale=zh-cn
     &uri_scheme=codearts-agent&client_id=codearts-agent
     &port=18080
     &code_challenge=<base64url(sha256(verifier))>&code_challenge_method=SHA-256
     &ticket_id=<64hex>&plugin-name=snap_AIIDE&plugin-version=5.2.0
3. 登录完成后门户把浏览器送到 http://127.0.0.1:18080/oauth/callback?code=…
   WebView 在 shouldOverrideUrlLoading / onPageFinished 里截住并取 code
   （若回调是旧形态 ?secret=…，改走 ticket 轮询，最长 60 秒）
4. POST https://sts.cn-north-4.myhuaweicloud.com/v1/oauth2/tokens
   头：DPoP: <ES256 JWS>、Content-Type: application/x-www-form-urlencoded
   body：client_id=codearts-agent & code & code_verifier
         & grant_type=authorization_code & redirect_uri=http://127.0.0.1:18080/oauth/callback
   → {credentials:{access_key_id, secret_access_key, security_token, expiration},
      refresh_token}
```

要点：

- **`code_challenge_method` 必须是 `SHA-256`**（带连字符），不是 OAuth 惯用的
  `S256`；写错时门户会忽略 challenge，换令牌因 verifier 不匹配失败；
- **不需要真的起 HTTP 服务**：`127.0.0.1:18080` 在 WebView 里只是一次导航，
  截住 URL 就够了（连不上也不影响）；
- **`redirect_uri` 必须与回调完全一致**（含端口），否则 STS 会报 redirect 不匹配；
- **临时凭据约 2 小时**。过期后所有签名请求都回 `APIG.0301`，表现为
  「刚才还能用，过一会儿全 401」；
- **续期要三样**：`refresh_token` + 登录时的 `code_verifier` + **同一把** DPoP 私钥
  （华为校验 JWK 指纹与首次一致）。三样都存进渠道配置，否则用户每天都要重新登录；
- 续期走同一 STS 端点（`grant_type=refresh_token`），触发点是**真正的请求路径**
  —— 手机上的后台定时任务会被系统压制，靠它续期不可靠。

> 签到与积分也会在请求前先续期（`CodeArtsLogin.refreshIfExpired`，成功后写回配置）。
> 否则 2 小时的凭据一过期，每次打开 App 的自动签到都会报「凭据过期」——
> 而用户什么都没做错。

实测（2026-11-04，模拟器内置 WebView）：

```text
门户授权页             → 200（返回一段 JS 跳转页，WebView 内继续走登录）
WebView 导航到假回调    → 截获 code，页面不跳走
用假 code 换令牌        → HTTP 400 {"error_code":"STS.1805","error_msg":"invalid authorization code"}
```

第三条尤其重要：它说明 **STS 端点、表单字段与 DPoP 签名都被上游接受**，
只是 code 是假的 —— 若签名不对，回的会是签名类错误，而不是「授权码无效」。

登录成功后 App 会：
1. 保存 `access_token` / `refresh_token` 到本地（CodeArts 存的是 AK/SK/security_token
   + `refresh_token` + `code_verifier` + DPoP 私钥）；
2. 自动拉一次模型清单（拉不到则用内置表，并在界面标注来源）；
3. token 快过期时**在请求路径上自动续期**（手机上后台任务会被系统压制，
   所以用「真要发请求」作为触发点最可靠），续期结果写回配置。
   Qoder / Raccoon / CodeArts 有续期端点（CodeArts 走 STS `refresh_token`）；
   ZCode 上游没有公开续期接口，会如实提示重新登录。

## 模型清单只显示真实可用的

模型清单**只列该账号真正能调的模型**，不再拿静态表凑数：

```text
WorkBuddy（国内版） · Falcons的Niko本人          [收起] [管理]
模型 16 个已启用
来源：上游实时返回（该账号真实可用）        ← 绿色 = 已与上游核对
  [全选] [全不选]                    16 / 16 已启用
  ☑ auto
    上下文 200K · 输出 32K · 支持图片
  ☑ Hy4 preview（Pro）
    hy4-preview · 上下文 1M · 输出 64K · 推理 high · 支持图片
```

**来源分两种，界面上用颜色区分**：

| 标注 | 含义 |
|---|---|
| 🟢 来源：上游实时返回（该账号真实可用） | WorkBuddy 两区域：`/console/enterprises/personal/models`；华为 CodeArts：`/v1/model/builtin`（签名 GET） |
| 🟡 来源：内置清单，未与账号实际权限核对 | Qoder / ZCode / Raccoon：上游没有可靠的模型目录接口；CodeArts 临时凭据过期时也退回内置表（提示里会写明） |

拉不到清单时**不会**用内置表顶替，而是明确提示原因 + 给「刷新模型」按钮：

```text
未获取到可用模型清单。
可能是账号确实没有可用模型，或接口暂时失败 —— 点「刷新模型」重试。
                                          [刷新模型]
```

> 实测对比（WorkBuddy 国内版真实账号）：上游返回 **16 个**模型，
> 而静态表里列了 22 个 —— 多出来的 `glm-5.1`、`hy3-x`、`kimi-k2.6`、
> `minimax-m2.7` 等 10 个账号**根本没有权限**，现在不会再显示。
## 积分 / 余额显示

登录成功的渠道会显示积分，点一下即查（再点 `[明细]` 展开资源包清单）：

```text
WorkBuddy AI（国际版）
ccg1gcc · 模型 1 个
积分：130 credits · 6 个资源包 · 10-31 到期  [明细]

华为 CodeArts
已登录 · 模型 9 个
积分：剩余 4,300 积分 · 基础积分包 · 11-30 重置
```

> CodeArts 的积分是**按套餐周期重置**的，所以这里标「重置」而不是「到期」；
> 总量 / 已用 / 即将过期直接跟在下一行（它只有一个额度桶，不需要 `[明细]` 折叠）。

各渠道的数据来源（端点为抓包确认，非猜测）：

| 渠道 | 端点 | 说明 |
|---|---|---|
| WorkBuddy 国际版 / 国内版 | `POST /v2/billing/meter/get-user-resource` | 双层信封 `data.Response.Data.Accounts[]` |
| Qoder / Qoder 中国版 | `GET /sash/api/v2/me/usage` | 头需 `Cosy-ClientType: 10`；企业账号不显示 |
| Raccoon（商汤） | `GET /api/web/points/v1/balance` | 分奖励 / 每日 / 会员 / 充值四项 |
| 华为 CodeArts | `GET /snap-manager/v1/statistics/plugin` | AK/SK 签名；`usageTotalPackageCredit` 是总积分，另带套餐名与重置日 |
| ZCode（智谱） | `GET /api/v1/zcode-plan/billing/current` | 额度（token）口径，不是积分；要 `zcodeJwt` 而不是 `accessToken` |
| API Key 渠道 | — | 没有积分概念 |

**两个实现细节**：

1. **月包与一次性包分开算**：一次性包已用完或已过期的不计入总额，否则余额会虚高。
   实测国内版账号 34 个资源包里只有 33 个有效（1 个剩余 0 已排除）；
2. **默认只显示摘要**：国内版账号有 30+ 个资源包，全展开会把界面撑成一堵墙，
   所以默认一行摘要，点 `[明细]` 才展开。

积分是**按需查询**（点一下才发请求，15 秒超时），不做后台轮询 —— 它是辅助信息，
不值得为它耗电。
## 每个渠道的模型开关

「已配置渠道」里每个渠道右侧有「**模型**」按钮，点开就是该渠道的模型清单。
**每行显示「看得懂的名字 + 真实规格 + 上游 id」**，不用再对着一串 id 猜：

`	ext
WorkBuddy·国际版 · ccg1gcc                          [收起] [管理]
  [全选] [全不选]                              21 / 21 已启用

  ☑ GLM-5.3  x0.69
    glm-5.3 · 1M 上下文 · 输出 48K · 推理 low/high/max · 图片

  ☑ GPT-6 Astra  x6.67
    gpt-6-astra · 1M 上下文 · 输出 125K · 推理 low/medium/high/xhigh/max · 图片

  ☑ Hy4  x0.00→x0
    hy4free · 1M 上下文 · 输出 64K · 推理 high · 图片
```

- **第一行**是友好名（`gpt-6-astra` → `GPT-6 Astra`，`cmodel` → `Cantus`）
  **+ 积分倍率**（`x0.69`，有促销时 `x0.2→x0.1`）；名字与 id 实质相同时不重复显示；
- **第二行**是「上游 id · 上下文 · 最大输出 · 推理档位 · 是否支持图片」，
  数据来自上游真实下发（刷新模型时落库），不是猜的；
- 用户自己加的代理渠道（DeepSeek 官方、硅基流动…）不会有元数据，
  显示「**上游自定义模型（无内置规格信息）**」——**不编造**，但也不隐藏该行；
- 勾上 = 对外可用；取消 = **从 `/v1/models` 移除**，且直接指定该 id 调用会被 **403 拒绝**
  （不是只藏起来 —— 客户端绕过列表直接发请求同样拦得住）；
- 点整行任意位置都能切换勾选（勾选框本身较小）；
- 顶部「全选 / 全不选」一次改整组，右侧实时显示「x / y 已启用」；
- 关掉的模型会记住（存 `disabledModels`），重新登录/刷新模型清单不会把它变回开启。

**默认全开**：存的是「关掉的」而不是「打开的」，所以上游新增的模型会自动可用，
不用每次刷新后重新勾一遍。

## 模型名字后面的「x0.11」

有积分体系的渠道，**模型名后面直接跟倍率** —— 它决定这一次调用扣多少积分，
是选模型时最实用的一条信息（`x6.67` 的模型比 `x0.07` 的贵近百倍）：

```text
☑ DeepSeek-V4.1-Flash  x0.25
   sn-deepseek-v4-1-flash · 1M 上下文 · 输出 100K

☑ GLM-5-3-Flash  x0.2→x0.1
   sn-glm-5-3-flash · 1M 上下文 · 输出 100K

☑ Kimi-K3  x1
   sn-kimi-k3 · 1M 上下文 · 输出 100K
```

> 倍率**放在名字这一行**、不带「积分」二字，是为了让列表能一眼扫完 ——
> 放第二行时用户得两行对着看，而他要的就是「哪个便宜」。
> 分档计费（CodeArts）会退回长文案，因为「32K 以内」这半句不能丢。

| 渠道 | 上游字段 | 原形态 | 名字后面 |
|---|---|---|---|
| WorkBuddy 两区域 | `models[].credits` | `"x0.69"` / `"x1.33 credits"` | `GLM-5.3  x0.69` |
| 华为 CodeArts | `builtinModels[].credit[].ratio_display` | `"0.7x"` | `GLM-5.2  积分 x0.7（分档，32K 以内）` |
| Qoder / Qoder 中国版 | `priceFactor` | 数字 `2` | `Ultimate  x2` |
| **Raccoon（商汤）** | `model_catalog` 的 `billing_multiplier` / `billing_effective_multiplier` | 数字 `0.25` / `0.1` | `DeepSeek-V4.1-Flash  x0.25` |
| 代理渠道 / ZCode | — | 没有这个概念 | **不显示**（不写 x1 糊弄） |

三个上游四种写法，统一归一化成 `xN` —— 否则迟早出现「一个渠道显示 0.7x、
另一个显示 x0.7」的不一致。归一化在 `ModelSpec.normalizeRate()` 一处收口。

三个必须处理的细节：

1. **促销时间窗要判**。`/v3/config` 的 `modelPromotions[]` 带
   `validFrom` / `validUntil`，过期促销若照显示，用户会以为某个贵模型现在免费，
   一调就扣积分。现在只在窗口内才显示成 `x0.2→x0.1`：
   ```json
   {"modelIds":["hy4free"],"enabled":true,
    "discount":{"discountedCredits":"0x","factor":0},
    "schedule":{"validFrom":"2026-11-04T00:00:00+08:00","validUntil":"2026-11-16T00:00:00+08:00"}}
   ```
2. **CodeArts 是分档计费**。`credit[]` 可能有多条（如 `≤32K 用 0.7x、以上 1.2x`），
   界面只留第一档做摘要，并用括号注明「分档，32K 以内」——
   硬塞两行到小字里没人看，但完全不提又会误导。
3. **1 倍也要显示**。用户明确报障过「为什么 Kimi-K3 没有倍率，x1 也要显示」——
   省略 1 倍等于「这个模型没有倍率信息」，反而让人以为它没算清楚。

### Raccoon 的倍率从哪来

它的目录端点是 `GET /api/web/llm/v2/model_catalog`（实测无 token 时 401 = 存在且要鉴权；
`/llm/v2/models` 才是 404，两个路径只差一个词）。响应里每个模型带：

```json
{"name":"sn-deepseek-v4-1-flash","description":"DeepSeek-V4.1-Flash",
 "visible":true,"billing_multiplier":0.25,"billing_effective_multiplier":0.25,
 "billing_status":"normal","params":{"context_window":1000000,"max_tokens":100000}}
```

- `name` 是**调用用的 id**，`description` 才是展示名（与 Qoder 一样是反的）；
- `billing_multiplier` 是原价、`billing_effective_multiplier` 是实收；
  两者不同就显示 `x0.2→x0.1`，`billing_status` 为 `discount` / `limited_free` 时再加备注；
- 只看 `categories[type=="chat"]`：绘图 / 视频分类走 chat 接口必然失败。

拉不到目录（账号没开通）时退回**内置表**，那 6 条同样带倍率
（`x0` / `x0.75` / `x1` / `x0.2→x0.1` / `x0.25`），界面不会因为降级就只剩 id。

实测（2026-11-04，真实账号刷新后）：国际版 21 个模型**全部**带倍率，
`gpt-6-astra` 是 `x6.67`、`gpt-6-luna` 是 `x0.07`、`hy4free` / `hy3` 促销期内是 `x0`
（原价 `x0.00`，箭头显示促销）。
## 加 API Key 渠道

点「+ 添加 API Key 渠道」，填**前缀 id / Base URL / API Key**：

| 平台 | Base URL |
|---|---|
| DeepSeek | `https://api.deepseek.com/v1` |
| 硅基流动 | `https://api.siliconflow.cn/v1` |
| OpenRouter | `https://openrouter.ai/api/v1` |
| 智谱 | `https://open.bigmodel.cn/api/paas/v4` |
| 月之暗面 | `https://api.moonshot.cn/v1` |
| 自建 vLLM | `http://<主机IP>:8000/v1` |

## 在其他 AI 应用里配置

```text
供应商类型：OpenAI 兼容
Base URL  ：http://127.0.0.1:8787/v1
API Key   ：（App 首页显示的 Key，首次启动生成后固定）
模型 id   ：cn:glm-5.3     ← 渠道前缀（登录渠道的 id 就是平台标识）
```

模型 id 规则：`<渠道id>:<模型>` 明确指定；裸 `<模型>` 在恰好一个渠道提供它时
自动路由，多个渠道都有则报错要求加前缀。`GET /v1/models` 列出全部。

## 能力

- **流式透传**：上游 SSE 按块边收边发，首字延迟与直连一致；
- **Key 隔离**：客户端只用网关自己的 Key；登录凭据与上游 Key 只存在手机本地；
- **多 Key 轮换**：每个代理渠道可配多个 Key；
- **后台常驻**：前台服务 + 常驻通知（Android 8+ 保活要求）；
- **自动续期**：登录型渠道的 token 过期时自动 refresh（CodeArts 续的是华为云临时凭据）。
- **协议归一**：客户端永远只说 OpenAI；ZCode 那种「上游只讲 Anthropic」的渠道，
  由网关双向翻译（见下）。

### 每个渠道的聊天协议（都不一样）

| 渠道 | 路径（基址 + 后缀） | 关键要求 |
|---|---|---|
| WorkBuddy 国际版 | `https://www.workbuddy.ai/v2/chat/completions` | **必须**带 `X-Conversation-{ID,Message-ID,Request-ID}`，否则 `11128 request illegal` |
| WorkBuddy 国内版 | `https://www.codebuddy.cn/v2/chat/completions` | 同上（不带也能过，但带上吃会话缓存） |
| Qoder / 中国版 | `…/model/v1/chat/completions` | 普通 `Bearer dt-` 即可（**不需要** COSY 签名）；目录声明 17 个，实测只有 6 个 id 能用 |
| ZCode | `https://zcode.z.ai/api/v1/zcode-plan/anthropic/v1/messages` | **Anthropic 协议**，要转换；鉴权用 `zcodeJwt` 不是 `accessToken` |
| Raccoon | `https://xiaohuanxiong.com/api/web/llm/v2/chat/completions` | `X-Org-Code`（个人账号空串也要发）+ `X-Raccoon-Language: zh` |
| 华为 CodeArts | `…/api/v2/chat/completions` | 华为云 SDK-HMAC-SHA256 签名 + `x-auth-token` |
| 代理渠道 | 用户填的 `/v1` | 只需 `Authorization: Bearer <key>` |

### ZCode：OpenAI ⇄ Anthropic 双向转换

ZCode 的上游只支持 Anthropic Messages（`/api/v1/chat/completions` 等路径全 404）。
网关对外仍承诺 OpenAI，所以中间做一层翻译（`ZcodeBridge`）：

```text
请求  {model, messages[{role,content}], max_tokens, stream}
   →  {model, max_tokens, system:"…", messages[{role,content:[{type:"text",text}]}], stream}

响应  content_block_delta → choices[].delta.content
      message_delta       → finish_reason
      usage{input/output_tokens} → usage{prompt/completion/total_tokens}
      message_stop        → data: [DONE]
```

三个容易错的点：`system` 是**顶层**字段（不是 messages 里一条）、`content` 必须是
**块数组**、`max_tokens` **必填**（缺了补 8192）。流式里 usage 必须排在 `[DONE]`
**之前** —— 否则客户端一看到 `[DONE]` 就停止读取，用量恒为 0。

### 华为云签名（CodeArts 专用）

CodeArts 是唯一**没有 Bearer token** 的渠道：调 InferHub 要用华为云临时凭据
（AK + SK + security_token）现算 `SDK-HMAC-SHA256` 签名，签名里绑定时间戳与 body 的
SHA256，所以不能缓存、不能复用。

两个容易踩的点：

1. **`x-auth-token: <security_token>` 必须真的发出去**。只带签名不带它，
   APIG 会回 `APIG.0301 decrypt token fail`（看起来像签名错，其实是缺这个头）；
2. **`maas_type: benefit` 必须参与签名**（不是事后补头）。免费额度模型
   （`glm-5.3-flash` / `deepseek-v4.1-flash`）靠它走免费额度；上游回
   `InferHub.4004.200` 表示「这个模型用不上免费额度」，此时要**去掉该头重新签名**
   再试一次 —— 复用上一次签名是无效的。

> 实测（假凭据）：签名 + `x-auth-token` 一起发 → `401 APIG.0301 decrypt token fail`；
> 不发 `x-auth-token` → `401 ... the access key ... is not found`。
> 两种错误不同，说明签名格式、AK、header 都正确，只是凭据本身不可用。

### 渠道缺 Base URL：从「no protocol」到明确报配置错误

现象（真实用户反馈，两次）：

```text
SERVER 502 {"message":"无法连接上游 ：no protocol: /chat/completions","type":"network_error"}
[proxy:workbuddy-cn] 渠道未配置 Base URL
```

看着像网络断了，其实是渠道**没存 `baseUrl`**：拼接得到裸的 `/chat/completions`，
`new URL` 直接抛 `no protocol`。根因是 **WorkBuddy 登录流程从来没写过这个字段**
（只有 Qoder / ZCode / Raccoon 通过 `ChannelLogin.newChannel` 写过）。现在三层处理：

1. **登录时写**：`LoginProviders.pollToken` 落库前调 `chatBaseFor(loginType)`，
   七个渠道各有自己的聊天基址；
2. **读配置时迁移**：老渠道（JSON 里没有 `baseUrl`）读出来自动补上，**不用重新登录**；
3. **发请求前兜底**：`Gateway.chatBase()` 再兜一次（覆盖「已加载进内存」的老对象），
   仍然为空才报 `502 config_error`，消息里写清哪个渠道、该填什么。

| 渠道 | 聊天基址（后面拼 `/chat/completions`） |
|---|---|
| WorkBuddy AI（国际版） | `https://www.workbuddy.ai/v2` |
| WorkBuddy（国内版） | `https://www.codebuddy.cn/v2` |
| Qoder | `https://api2-v2.qoder.sh/model/v1` |
| Qoder（中国版） | `https://gateway.qoder.com.cn/model/v1` |
| ZCode | `https://zcode.z.ai/api/v1/zcode-plan/anthropic/v1` |
| Raccoon | `https://xiaohuanxiong.com/api/web/llm/v2` |
| 华为 CodeArts | `https://snap-access.cn-north-4.myhuaweicloud.com/api/v2` |

> 这一轮已确认**国内版与国际版都通了**：同一份发头逻辑真实调用，
> 两边都是 200 + SSE 出字（国际版的关键是补三个 `X-Conversation-*`，见上文）。

### Qoder：不需要 WASM，但只有 6 个模型能用

**先纠正一个错误结论。** 之前的版本判定「Qoder 必须 WASM 生成 COSY 签名，
否则 401」，于是聊天整条被放弃。实测（2026-11-04，真实 `dt-` token）证明
**普通 `Bearer <access_token>` 就能调通**：

```text
POST https://api2-v2.qoder.sh/model/v1/chat/completions
Authorization: Bearer dt-…
→ HTTP 200 + 标准 OpenAI SSE
```

之前 401 的真因是**拿错了凭据**：`.qoder-accounts.*.json` 里存的是 `pt-`（个人
访问令牌，未激活时回 `token is not active`），而 chat 要的是登录后拿到的
`dt-` 令牌。`Cosy-ClientType` 也不是必需的 —— 带不带都 200。

**第二个坑：模型 id 大量不可用。** 目录里声明过 17 个，逐个打这个端点只有 6 个 200：

```text
200 : ultimate, qmodel, kmodel, gmodel, dmodel, mmodel
400 : cmodel, smodel, qmodel_38max, qfmodel, qmodel_latest,
      kmodel_latest, gfmodel, dfmodel      ← Unsupported model
429 : auto, efficient                      ← All backends failed（后端不可用）
```

所以内置表从 17 条缩到 6 条（`qoderAllSpecs()` 里保留全量备查）。界面若列 17 个，
用户点一个失败一个 —— 这才是「Qoder 用不了」的真实体感。

**第三个坑：错误藏在 SSE 里。** 不支持的模型回的是 **HTTP 200**，响应体却是：

```text
event: error
data: {"code":"invalid_model_error","message":"Unsupported model \"cmodel\""}
```

直接透传的话，客户端看到 200 却没有输出，会以为「成功了但没回答」。
网关现在窥探首块（读到一个完整事件为止），识别到 `event: error` 就改写成
`502 upstream_error`，并把上游原文带出来：

```text
上游拒绝该请求：Unsupported model "cmodel"（invalid_model_error）
```

> 窥探必须**读到一个完整事件**（`\n\n`）再判断：SSE 是分块到达的，
> 只读一次很可能只拿到 `event: error` 那一行、data 还没到 ——
> 现象是「识别到错误却抽不出原因」。这个坑在真实响应上踩到过。

> 顺带修了同一类「配置正确但用不了」的隐患：网关的 worker 线程池改成
> **daemon 且每次 start 重建**。原来停止后再启动会复用已 shutdown 的池 ——
> 端口在监听、却没有任何线程处理请求（症状是「重启后全超时」）。

## 刷新模型

「刷新模型」走 `ModelCatalog.refresh()` 这一个入口，按渠道分两种行为：

| 渠道 | 行为 | 界面提示 |
|---|---|---|
| WorkBuddy 两区域 | 先拉 scoped 端点，失败退 `/v3/config`，**含上游规格** | `已从上游刷新 21 个模型（含 23 条规格）` |
| 华为 CodeArts | 签名 GET `/v1/model/builtin`；拉不到（凭据过期）退内置 9 条 | `已从华为云刷新 N 个模型` / `没有取到华为云清单（临时凭据可能已过期），已用内置清单（9 个）` |
| Qoder / Qoder 中国版 | 不发请求，用**真实模型表**（17 条，带规格） | `已用内置清单（17 个，含真实规格，未与账号权限核对）` |
| ZCode / Raccoon | 不发请求，用内置表 | `已用内置清单（N 个）` |

### 上游规格：从 `data.models[]` 直接取

`data.models[]` 每条都带完整规格（实测国内版 34 条）：

```text
{id, name, maxInputTokens, maxOutputTokens, supportsImages,
 reasoning:{defaultEffort, supportedEfforts}, vendor, tags}
```

所以规格**优先用上游值**，静态表 `ModelMeta` 只在缺字段时兜底。
清单仍以 `data.agents[].models[]` 为准（那是账号**权限内**的 17 个），
规格从 `data.models[]` 里查 —— 有个别 id（如 `lite`）不在规格表里，那就不带规格。

### Qoder 的真实模型名

Qoder **没有模型目录端点**（实测：同一 token 调 `/sash/api/v1/me/campaigns`
与 `/sash/api/v2/me/usage` 都 200，而 `/sash/api/v1/me/models`、
`/sash/api/v2/models` 等全 404 —— 说明接口不存在，不是权限问题）。

所以内置了 17 条真实规格，数据来自 `$DSH_HOME/.qoder-catalog.qoder.json`：

| id（调用用） | 真实名 | 上下文 | 推理档位 |
|---|---|---|---|
| `auto` | Auto | 200K | — |
| `ultimate` | Ultimate | 1M | xhigh/high/low/max/medium |
| `performance` | Performance | 1M | xhigh/high/low/max/medium |
| `efficient` | Efficient | 200K | — |
| `smodel` | Sonus | 180K | xhigh/high/low/max/medium |
| `cmodel` | Cantus | 180K | xhigh/high/low/max/medium |
| `qmodel_38max` | Qwen3.8-Max | 180K | xhigh/low/medium |
| `qfmodel` | Qwen3.8-Flash | 180K | xhigh/low/medium（免费） |
| `qmodel_latest` | Qwen3.7-Max | 1M | — |
| `qmodel` | Qwen3.7-Plus | 1M | — |
| `kmodel_latest` | Kimi-K3 | 180K | high/low/max |
| `kmodel` | Kimi-K2.8-Preview | 200K | high/low/max |
| `gmodel` | GLM-5.3 | 180K | high/low/max |
| `gfmodel` | GLM-5.3-Flash | 1M | high/max |
| `dmodel` | DeepSeek-V4-Pro | 1M | high/max |
| `dfmodel` | DeepSeek-Flash | 1M | high/max/low |
| `mmodel` | MiniMax-M3 | 180K | — |

> ⚠ 注意 Qoder 的目录里 `key` 才是调用用的 id，`id` 是**展示名**，两者是反的。

### WorkBuddy 国际版：scoped 目录接口 500，改走 `/v3/config`

`www.workbuddy.ai/console/enterprises/personal/models` **稳定返回 500**
（openresty）；而国内版 `www.codebuddy.cn` 同路径 200。同一账号的
`/v2/billing/meter/*` 在国际版也正常 200 —— 所以是这个**目录接口**在国际版挂了，
不是账号或鉴权问题。

**但「接口挂了」不等于「拿不到清单」**：`GET /v3/config` 是好的
（国际版实测 200 / 18.8 KB，国内版 200 / 38 KB），而那正是客户端真正用来出
模型列表的端点。现在的取清单顺序是：

```text
1. GET /console/enterprises/personal/models   ← 有就用（带权限范围，最准）
2. GET /v3/config                             ← 上面失败/为空时退回
   合并 trialModelIds（ModelTrialBanner.banners[].targetModelId）里的试用模型
3. 两条都空 → 如实报错，让用户知道不是网络问题
```

实测（同一账号、真实 token）：

| 渠道 | scoped 端点 | `/v3/config` | APK 现在拿到 |
|---|---|---|---|
| 国际版 `www.workbuddy.ai` | 500 | **200** | **21 个模型 / 23 条规格** |
| 国内版 `www.codebuddy.cn` | 200 | 200 | 18 个模型 / 39 条规格 |

> 之前「刷新不了」就是卡在第 1 步：一失败就整条报错，没试第 2 步。
> 现在国际版能正常刷新，且**仍然是上游真实清单**（界面显示绿色），
> 不是拿内置表糊过去。

两个解析细节（都会直接导致「刷出来少一半」）：

- **`auto` / `default` 是别名不是模型**，要过滤掉 —— 真去调会 400；
- `nes-` / `completion-` / `maxOutputTokens ≤ 256` 是出图与补全类，也不是聊天模型。

### 修过的两个真 bug

1. **刷新打的是别人的接口**：原来直接调 WorkBuddy 的私有目录路径，
   对别的渠道等于把请求拼到错误地址。实测：
   `qoder.com/console/...` → 404、`zcode.z.ai/console/...` → 307、
   `xiaohuanxiong.com/console/...` → 200 但返 HTML。
   用户看到的就是「点了刷新没反应」。
2. **Qoder 内置表只有 `auto` 一个**，刷完还是 1 个，更像没生效。现在 17 个真实模型。

### 两个「替身与真实实现」的差异（都曾伪装成产品 bug）

桌面验证用的 `org.json` 替身与 Android 真实实现有行为差异，两次都让断言失败：

- **嵌套对象类型**：替身把嵌套对象解析成 `LinkedHashMap`，Android 是 `JSONObject`。
  所以代码里用 `optJSONObject()` 而**不能**用 `opt(...) instanceof JSONObject`
  —— 后者在桌面上永远 false，「推理档位」会全部丢掉；
- **序列化递归**：替身的 `JSONWriter` 原来不认 `JSONObject`/`JSONArray`，
  会把它当字符串写出（双重编码），读回来是 `null`。
  表现为「规格存不进去」，其实产品代码没问题。

这两个坑值得记住：**验证替身的行为要和目标平台一致**，否则会在错的地方找 bug。
## 每日签到（自动）

登录成功的渠道会自动领每日免费额度，**不需要手动点**：每次打开 App（或新登录一个渠道）
把「今天还没签的」补签一遍。

| 渠道 | 签到是什么 | 判定条件 |
|---|---|---|
| WorkBuddy 国际版 / 国内版 | 领每日积分 | `today_checked_in == false` 才领 |
| Qoder / Qoder 中国版 | 领每日 100 Credits 活动 | 有 `CLAIM_BENEFIT` + `CLAIMABLE` 的活动才领 |
| ZCode（智谱） | 领可领取的套餐额度 | preview 里有 `plan` 就领（领完自然变空，天然幂等） |
| 华为 CodeArts | 领每日登录奖励（积分） | `USER_LOGIN` 活动的 `claimable == true` 才领 |
| Raccoon | **没有**每日签到 | 如实说明「只有一次性登录奖励」 |

界面上「一键签到」也可以手动全扫一遍（不受「每天一次」限制）。

### 协议（均从生产包逆向确认）

```text
WorkBuddy
  POST {endpoint}/v2/billing/meter/checkin-activity-status   {}
    → data:{active, today_checked_in, streak_days, daily_credit, today_credit, …}
  POST {endpoint}/v2/billing/meter/daily-checkin             {}
    → code 0        : data:{credit, streak_days, is_streak_day}
    → 10001 / 1001  : 今天已签过   ← **不是失败**
    → 1002 / 1003   : 无资格 / 活动已结束

Qoder / Qoder 中国版
  GET  {openApiBase}/sash/api/v1/me/campaigns            头 Cosy-ClientType: 10
    → campaigns:[{campaignId, actionType, claimStatus, benefit:{amount}}]
  POST {openApiBase}/sash/api/v1/me/campaigns/{id}/claim   body ""
    → {status:"CLAIMED", replayed:bool, benefit:{amount}}
       replayed=true 表示今天已经领过（同样不是失败）

ZCode
  GET  {endpoint}/api/v1/zcode-plan/billing/preview?app_version=…&platform=android-arm64
    → data:{server_time, plans:[{plan_id, name, entitlements:[…]}]}
  POST {endpoint}/api/v1/zcode-plan/billing/claim   {plan_id}
    头 X-ZCode-App-Version / X-Platform / Content-Type
    → {code:0, data:{plan:{user_plan_id, plan_id, status, starts_at, ends_at}}}

华为 CodeArts（全部 AK/SK 签名，DPoP 不参与）
  GET  {snap}/snap-manager/v1/statistics/plugin
    → {package:{is_credit_package, is_token_package, package_name_cn}, metrics:[…]}
  GET  {snap}/v1/ops/delivery?channel=IDE
    → {items:[{campaignId, type, title, claimable, status, benefitAmount}]}
       每日签到就是 type == "USER_LOGIN" 那条（campaignId 实测是**数字** 1）
  POST {snap}/v1/ops/claim     {campaignId, channel:"IDE"} → {id, benefitAmount}
  POST {snap}/v1/ops/confirm   {campaignId}
    已领状态：CLAIMED / CONFIRMED / CONSUMED
    非积分计费账号（is_credit_package=false）→ 如实说「不在积分活动范围」
```

### 四个实现要点

1. **「已签过」不是失败**。`10001`/`1001` 与 `replayed=true` 都是正常状态，
   判错会提示「签到失败」而额度其实已到账 —— 这条已用 8 条断言锁住；
2. **失败不记账**。只有「真的领到」或「本来已签」才记「今天做过了」；
   网络失败不记，下次打开还会重试。否则一次抖动就整天不再自动签到；
3. **CodeArts 的「已领」要看状态位**。`claimable=false` 不等于已领：
   只有 status 属于 `CLAIMED / CONFIRMED / CONSUMED` 才算；其它不可领
   （活动未开始等）按「当前不可领取」处理，不能当成今天已签；
4. **不做后台定时任务**。手机上定时任务会被系统压制（Doze / 厂商省电），
   设了也不会准时响，反而给人「已经自动签过了」的错觉。「打开 App」是最可靠的触发点。

## 从源码构建

不需要 Gradle：

```powershell
# 依赖：JDK 17+、Android SDK（platform 34 + build-tools 34.0.0）
powershell -ExecutionPolicy Bypass -File build.ps1
```

流程：`aapt2 compile` → `aapt2 link` → `javac` → `d8` → 打包 dex →
`zipalign` → `apksigner`。

四个必须知道的构建约束（脚本与源码里已处理）：

1. **`aapt2` 处理不了中文路径** → 脚本先把源码复制到 `%TEMP%\wbapk`（纯 ASCII）再构建；
2. **JDK 21 拒绝 `-source 8`，但 `d8` 只吃 Java 8 字节码** → 用 `--release 8`；
3. **`d8` 在合成类/泛型签名上直接崩**（`NullPointerException: String.length()`）→
   所有监听器与后台任务都是**具名静态类 + WeakReference**，源码里没有一处匿名类。
   ⚠ 这一条牵连四处不那么直观的地方，全是实际踩出来的：
   - **对 `int` 做 `switch` 会生成合成类**（`QrCode$1`）→ 掩码计算改用 `if-else` 链；
   - **`enum` 也会生成空合成类** → 纠错等级 `Ecc` 写成静态常量类，不用 `enum`；
   - **实现泛型接口会写泛型 `Signature`**，`d8` 解析它时同样崩 ——
     `implements android.webkit.ValueCallback<String>` 就是这个形状。
     → 改成实现**原始类型** `ValueCallback`，参数按 `Object` 收再转 String；
   - **`javac` 不会删除上次残留的 `.class`**，所以脚本每次构建前清空 `classes/`，
     否则改掉 `switch` 之后旧合成类仍会被 `d8` 收走、继续崩。
4. **`d8` 的命令行有 8191 字符上限** → class 清单写进 `@argfile`，不摊在命令行上；
   也不要把 class 根目录直接传给 `d8`（它报 `Unsupported source file type`）。

> `build.ps1` 与 `verify.ps1` 都带 **UTF-8 BOM**：这是给 `powershell.exe`（5.1）看的 ——
> 它读无 BOM 的 UTF-8 会按 ANSI 解码，脚本里的中文注释会被撕成乱码并导致语法错误。
> 用编辑器改完这两个文件后如果构建突然报「Missing closing '}'」，先检查 BOM 还在不在。

## 验证记录

九组验证跑的都是 **APK 里同一份源码**（`app/src` 原样编译），把上游换成假服务。
一条命令跑完：

```powershell
powershell -ExecutionPolicy Bypass -File verify.ps1
```

```text
=== 1/9 网关本体 ===
=== 结果：PASS 30 / FAIL 0 ===

=== 2/9 登录 ===
=== 结果：PASS 83 / FAIL 0 ===

=== 3/9 签到 ===
=== 结果：PASS 88 / FAIL 0 ===

=== 4/9 模型目录刷新 ===
=== 结果：PASS 31 / FAIL 0 ===

=== 5/9 模型规格 ===
=== 结果：PASS 97 / FAIL 0 ===

=== 6/9 华为 CodeArts ===
=== 结果：PASS 91 / FAIL 0 ===

=== 7/9 ZCode 协议转换（OpenAI ⇄ Anthropic）===
=== 结果：PASS 25 / FAIL 0 ===

=== 8/9 Qoder ===
=== 结果：PASS 37 / FAIL 0 ===

=== 9/9 二维码编码器 ===
二维码编码器：32 PASS / 0 FAIL

=== 全部验证通过 ===
```

### 1. `verify/Verify.java` —— 网关本体（30 项）

鉴权、模型聚合、非流式/流式透传、Key 替换不泄漏、错误分支、状态页，
外加四条回归：

- **缺 Base URL 返回 `config_error`**（而不是 `no protocol`）；
- **停止后再启动仍能服务**（线程池重建）；
- **WorkBuddy 会话三元组真的发出去了**（见下节，缺了上游回 `11128`）；
- **仓库路径正确**（`/v2/chat/completions`，不是 `/chat/completions`）；
- **上游「HTTP 200 + `event: error`」要改写成 502**（Qoder 实测形态，
  透传会让客户端「成功但没输出」）。

`verify/VerifyCatalog.java`（31 项）另外钉住模型目录的降级链：
scoped 端点失败要能退 `/v3/config`，且 `auto`/`default` 别名与
出图、补全类模型必须被过滤 —— 这几条错了表现都是「刷新出来少一半或点了没反应」。

### 2. `verify/VerifyCheckin.java` —— 签到（88 项）与 `verify/VerifyLogin.java` —— 登录（83 项）

对着假上游跑 Qoder 的真实状态机，逐字段核对落位：

```text
PASS  begin 产出授权 URL
PASS  授权 URL 带齐五个参数
PASS  首轮 404 → 等待（不是失败）
PASS  第二轮拿到 token -- 登录成功
PASS  refresh_token 落位
PASS  machine_id 被写回渠道（续期要用）
PASS  续期请求带了 machine_id
PASS  续期保留原 refresh_token（上游没给新的）
PASS  续期后过期时间更晚
```

外加：ZCode 的 code 提取（URL / JSON / 纯 code / 粘错东西）、PKCE 形状（RFC 7636）、
时间戳解析（秒 / 毫秒 / ISO 字符串）、渠道支持矩阵、内置模型表兜底。

**Raccoon 登录态识别**（11 项）—— 这段是「登录成功了但一用就 401」的高发区：

```text
PASS  按规则推导出访问令牌 -- ACCESS-1
PASS  不会误取 refresh_token
PASS  带前缀时也能推导 -- ACCESS-PREFIXED
PASS  没有 xhx_token 时走兜底 -- ACCESS-ONLY
PASS  未登录时返回空（不误判设备指纹）
PASS  解析 cookie 串 / cookie 值做了 URL 解码
PASS  Raccoon 拉模型不发请求（直接返回空）
```

### 3. `verify/VerifyCodeArts.java` —— 华为 CodeArts（91 项）

这个渠道每一环都「错一个字节就全 401」，所以逐层钉住：

```text
=== 1. 华为云签名：与官方算法对齐 ===
PASS  Authorization 前缀是 SDK-HMAC-SHA256
PASS  带 SignedHeaders / 64 位 hex Signature
PASS  带 x-sdk-date（华为格式）/ x-sdk-content-sha256 / x-security-token
PASS  GET 不签 content-type，POST 会签
PASS  body 不同 → 签名不同

=== 2. 回调解析（认错就永远拿不到 code）===
PASS  标准回调能取到 code
PASS  登录页里的 code 参数不算回调
PASS  code 在 # 片段里也能取到
PASS  旧流程 ?secret= 回调也能识别（且不误取）

=== 3. 协议常量与端点 ===
PASS  授权 URL 指向 portal/authorize，带 port 与 code_challenge_method=SHA-256
PASS  client_id / uri_scheme / 新插件标识 snap_AIIDE 5.2.0 齐全
PASS  ticket_id / code_challenge 都在 URL 上

=== 4. PKCE 与 DPoP（续期必需）===
PASS  DPoP 私钥能存能还原
PASS  还原后 x 一致

=== 5. 凭据解析与模型表 ===
PASS  AK/SK/ST 都落位 / refresh_token 落位 / 过期时间解析成毫秒
PASS  内置 9 个模型
PASS  ticket 的 result / credential 两种形状都能解析
PASS  老渠道缺 baseUrl → 补默认基址 / Base URL 去末尾斜杠
PASS  转发基址有兜底；其它登录渠道缺基址返回空（报配置错误）

=== 6. 支持矩阵 ===
PASS  CodeArts 有 Spec / 有内置模型表

=== 7. 凭据识别（没有 accessToken 也要算已登录）===
PASS  AK/SK/ST 齐全 → 算已登录
PASS  缺 security_token → 不算已登录
PASS  Checkin / Credits 支持 CodeArts
PASS  未过期 / 缺 refresh_token / 丢 DPoP 私钥 → 都不硬发续期请求

=== 8. 签到判定（纯函数）===
PASS  campaignId 数字也能读成 id
PASS  CLAIMED / CONFIRMED 算已领；空活动不崩

=== 9. 完整签到链路（本地假上游）===
PASS  能查到可领状态 / 可领 1000 积分
PASS  领取成功 / 领取后调了 confirm
PASS  重复领取 → already-claimed（不是失败）
PASS  Token 计费账号 / 没有活动时如实说明
```

> 第 7 组是这次修掉的一个**真 bug**：CodeArts 没有 `accessToken`，
> 旧的 `hasCredential()` 只看它，于是登录成功后界面仍显示「登录」、
> 网关回「未登录」、积分和签到都点不动。离线断言与模拟器上都确认了修复。

### 4. `verify/VerifyZcode.java` —— ZCode 协议转换（25 项）

ZCode 的上游是 **Anthropic Messages 协议**，网关对外是 OpenAI 兼容 —— 中间那层转换
错一处，症状都是「连得上但返回空 / 400」，看不出是哪一步：

```text
=== 1. 请求：OpenAI → Anthropic ===
PASS  system 提到顶层
PASS  messages 里不再有 system 角色
PASS  content 是块数组（不是纯字符串）
PASS  缺 max_tokens 时补默认值 -- 8192
PASS  未知角色降级为 user

=== 2. 响应：Anthropic → OpenAI（非流式）===
PASS  正文被拼出来 / finish_reason 映射成 stop
PASS  usage 字段名转成 OpenAI 口径 -- {"prompt_tokens":12,"completion_tokens":5,"total_tokens":17}

=== 3. 响应：Anthropic SSE → OpenAI SSE（流式）===
PASS  正文分两帧传出 / 首帧带 role / 有 finish_reason=stop
PASS  以 [DONE] 结尾
PASS  不再出现 Anthropic 事件类型
PASS  usage 带出来
```

> 第三组抓到过一个真 bug：`[DONE]` 原来写在 usage 帧**之前**，
> 客户端一看到 `[DONE]` 就停止读取 —— 表现为「用量恒为 0」。
> 现在 usage 排在前面，`[DONE]` 固定收尾。

### 5. `verify/VerifyQr.java` —— 二维码编码器（32 项）

拿 Node 的 `qrcode` 包生成参考矩阵，**逐模块**比对 8 组文本 × 4 个纠错等级：

```text
PASS L v1 21×21 「A」
PASS H v30 137×137 「zzz…」
二维码编码器：32 PASS / 0 FAIL
```

> 这一步不是形式主义。二维码**没有中间反馈**：编错了仍然是一张看着像二维码的图，
> 只有真机扫才发现扫不出来 —— 而那时用户已经卡在登录这一步了。
> 实现过程中它抓出了 4 个 bug（容量用总码字数、纠错表按总量而非每块、
> 交织错位、`enum` 格式位与表行号搞混），全部是「矩阵看起来正常」的类型。
>
> 编码器本身仍保留（它已被验证过，将来任何需要扫码的渠道都能直接复用）；
> 但 Raccoon 不再走扫码 —— 原因是它的落地页 404，不是编解码问题。

### 6. 真实接口验证

协议里的关键端点都用**真实请求**确认过，不是照文档猜：

```text
Qoder 授权页        → 302 /users/sign-in（未登录时的正确行为）
Qoder 轮询          → 404 NotFound（未完成时的正确行为）
Qoder chat          → Bearer `dt-` → 200 + SSE（gmodel/dmodel/ultimate 实测出字）
Qoder PAT (`pt-`)   → 401 `token is not active`（未激活，**不能**当 access_token 用）
Qoder 不支持的模型   → HTTP **200** 但响应体 `event: error` + `invalid_model_error`
ZCode 授权页        → 200（bigmodel）/ 307 → /auth（chat.z.ai）
Raccoon 发短信      → 400 captcha_token required / invalid（顺序：先校验 captcha 再看手机号）
Raccoon 手机号加密   → 用 publicKey 解密网页真实密文，得回原号码 ✅
Raccoon 聊天端点     → 401 authorization_verify_error（存在且要鉴权）
Raccoon 模型目录     → `/llm/v2/model_catalog` 401（存在）/ `/llm/v2/models` 404
Raccoon 扫码落地页   → /login/mp 对所有 UA 均 404 → 扫码方案放弃
CodeArts 授权页      → 200（华为云统一认证，SMS / 密码登录均可用）
CodeArts STS 换令牌  → 假 code → HTTP 400 STS.1805 invalid authorization code
                       （说明表单字段与 DPoP 签名都被接受，只是 code 无效）
CodeArts 签名 + x-auth-token → 401 APIG.0301 decrypt token fail
                       （说明签名与业务头都到了 IAM，只是假 token 解不开）
CodeArts 统计/活动   → 假 AK/SK → 503 APIG.0613（IAM 无法校验这对凭据）
ZCode 路径族         → /api/v1/zcode-plan/anthropic/v1/messages 401（存在）
                       /api/v1/chat/completions、/api/v1/anthropic/v1/messages 全 404
WorkBuddy 国际版 chat → 缺会话三元组 → 400 code 11128「request illegal」
                       带 X-Conversation-* → **200 + SSE**（实测出字）
WorkBuddy 国内版 chat → 200 + SSE（对三元组不敏感，但带上无害）
```

**WorkBuddy 两区域用真实账号跑通聊天**（2026-11-04，走的是 APK 里同一份发头逻辑）：

```text
intl  status=200  data: {"delta":{"role":"assistant","content":"OK"} …}
cn    status=200  data: {"delta":{"role":"assistant","content":"OK"} …}
```

> 国际版那个 `11128 request illegal` 很能说明问题：错误文案写「没通过渠道校验，
> 可能不是官方客户端发出的」，看起来像要伪造客户端指纹。实际排查是靠**逐项对照**：
> 先补齐官方 UA / X-IDE-* 仍然 400，再补 `X-Conversation-ID` /
> `X-Conversation-Message-ID` / `X-Conversation-Request-ID` 立刻 200。
> **只看文案会往「伪造指纹」的方向查很久**，而真正缺的是三个会话头。

**Qoder 用真实 `dt-` 令牌跑通聊天**（2026-11-04）：

```text
gmodel  → 200  data: {"choices":[{"delta":{"reasoning_content":"1",…}}]}
dmodel  → 200  data: …（6.7 KB 流式正文）
cmodel  → 200  但响应体是 event: error / invalid_model_error  ← 伪成功
```

> Qoder 这一条最值得记：**错误结论会直接砍掉一整个渠道**。之前判定「必须 WASM
> 生成 COSY 签名」，于是聊天被放弃；实际只是「拿错了凭据（`pt-` 未激活）」
> 加「模型 id 不在这个端点接受的集合里」。判据是**用真实凭据真打一次** ——
> 拿到 `invalid_model_error` 就说明鉴权已过，剩下的只是参数问题。

**CodeArts 用真实账号跑过一遍**（2026-11-04，凭据在有效期内）：

```text
积分     → ok=true，剩余 14,340.09（总量 14,600 · 已用 259.9 · 即将过期 5,340.1），重置日 11-11
签到状态 → active=true，today_checked_in=true（"今天已领取"）
签到执行 → already-claimed「今天已经签过了」   ← 与界面判据一致，不是失败
```

> 三条都走的是 **APK 里同一份 `Credits` / `Checkin` 代码**（桌面直接调用，只是把
> 上游换回生产）。它同时验证了：真实响应确实没有 `{code,data}` 信封、
> `campaignId` 是数字、以及「已领」被正确当成正常状态。

> 顺带一条方法论：`captcha_token invalid` 这个报错**看起来**像「token 不对」，
> 实际排查下来是「校验顺序问题 + 来源绑定」。判据要靠**对照实验**：
> 用同一个 token 分别提交明文手机号与密文手机号，前者回 `phone invalid`、
> 后者回 `captcha_token invalid` —— 说明 captcha 已经通过、卡在别处。
> 只看单次报错很容易把方向带偏（我第一轮就带偏过）。

### 7. 模拟器实测（不是只跑离线验证）

离线验证跑的是同一份源码，但**跑不到 Android 运行时**。所以另外在
Android 14（API 34，x86_64）模拟器上做了真机等价测试：

```text
安装 + 启动                     → 进程存活，无 FATAL
启动网关                        → 8787 监听 127.0.0.1（/proc/net/tcp 确认）
GET /v1/models 无 Key           → 拒绝
GET /v1/models 带 Key           → 200 {"object":"list","data":[]}
点 Raccoon「登录」              → 打开 RaccoonLoginActivity，WebView 渲染出登录页
勾协议 + 填手机号 + Send code    → 服务端接受，按钮变「Resend in 40s」（短信真发出去了）
注入无效 token 后等轮询         → 仍停在登录页（没被骗过去），无崩溃
```

**WebView 内取 token 的四个场景**（通过 CDP 在真实 WebView 里执行 App 的 JS）：

| 场景 | 结果 |
|---|---|
| 未登录（只有设备指纹 cookie） | `""` ✅ 不会误用 |
| 注入 `xhx_token` + `raccoon_token` | 取到访问令牌 ✅ |
| 只有 `raccoon_refresh_token` | `""` ✅ 不误取 refresh |
| 带前缀 `web_prod_*` 形态 | 按规则推导成功 ✅ |

> APK 是**纯 Java、无 `lib/` 原生库**，所以 x86 模拟器的结果对 ARM 真机同样成立。
>
> 另外确认了一件事：**桌面验证抓不到主线程网络问题**。第一版把 `user_info`
> 请求写在了 UI 线程，59 项离线断言全绿，但真机上是「登录成功瞬间崩溃」。
> 这类问题只有跑起来才暴露 —— 所以这一节的实测不是可选项。

**华为 CodeArts 的模拟器实测**（2026-11-04，Android 14 x86_64）：

```text
点 CodeArts「登录」       → 打开 CodeArtsLoginActivity，WebView 渲染出华为云登录页
（CDP 把 WebView 导航到假回调）→ 页面不跳走，App 拿到 code 并开始换令牌
换令牌结果                → 提示「换取凭据失败：HTTP 400 STS.1805 invalid authorization code」
启动网关 + GET /v1/models → 200，列出 codearts:GLM-5.2 / codearts:deepseek-v4.1-flash
POST /v1/chat/completions → 上游 401 APIG.0301 decrypt token fail（假凭据，链路已通）
```

**凭证识别的界面回归**（注入一个只有 AK/SK/ST 的渠道，模拟登录成功后）：

| 修复前 | 修复后 |
|---|---|
| 账号页仍显示「登录」按钮，积分/签到行不出现 | 「已登录 · 模型 2 个」+「管理」按钮，积分与签到行都在 |

> 这一条是**真 bug**，且离线断言原先没覆盖：`hasCredential()` 只看
> `accessToken`，而 CodeArts 根本没有它。症状是「登录成功但处处未登录」，
> 网关也回 401 —— 现在由 `VerifyCodeArts` 第 7 组 + 这条界面回归共同锁住。

### 文件

| 文件 | 职责 |
|---|---|
| `MobileModelHub.apk` | **交付产物**（已签名，可直接安装） |
| `build.ps1` | 构建脚本（UTF-8 BOM，见上面构建约束第 4 条） |
| `verify.ps1` | 一键跑全部离线验证 |
| `app/src/com/wbgateway/Gateway.java` | HTTP 服务器 + 转发 + SSE（零 Android 依赖） |
| `app/src/com/wbgateway/ChannelLogin.java` | **直接登录**：Qoder 设备码 PKCE / ZCode OAuth + 续期 |
| `app/src/com/wbgateway/CodeArtsLogin.java` | **华为 CodeArts 登录/续期**：PKCE + DPoP + STS 换临时 AK/SK（纯逻辑，可离线验证） |
| `app/src/com/wbgateway/CodeArtsLoginActivity.java` | **CodeArts 登录页**：内置 WebView 截获 `127.0.0.1/oauth/callback` |
| `app/src/com/wbgateway/HuaweiSign.java` | **华为云 SDK-HMAC-SHA256 签名**（聊天、模型目录、积分、签到都走它） |
| `app/src/com/wbgateway/RaccoonLoginActivity.java` | **Raccoon 登录页**：WebView 走官方网页登录，登录后取 token 回传 |
| `app/src/com/wbgateway/ZcodeLoginActivity.java` | **ZCode 登录页**：内置 WebView 自动截获 `zcode://` 回调 |
| `app/src/com/wbgateway/OAuthCallback.java` | **OAuth 回调解析**（纯逻辑，零 Android 依赖，可离线验证） |
| `app/src/com/wbgateway/RaccoonToken.java` | **Raccoon 登录态解析**（纯逻辑，零 Android 依赖，可离线验证） |
| `app/src/com/wbgateway/LoginPage.java` | **各渠道官方登录页地址**（「打开登录页」入口） |
| `app/src/com/wbgateway/LoginProviders.java` | WorkBuddy 两区域登录 + 各渠道 Spec + 模型目录/内置表 + **聊天基址表 `chatBaseFor`** |
| `app/src/com/wbgateway/ZcodeBridge.java` | **OpenAI ⇄ Anthropic 协议转换**（ZCode 上游只讲 Anthropic） |
| `app/src/com/wbgateway/UpstreamError.java` | **SSE「伪成功」识别**（HTTP 200 但 `event: error`，Qoder 实测形态） |
| `app/src/com/wbgateway/QrCode.java` | **二维码编码器**（字节模式，纯 Java，与参考实现逐位一致；当前未用于登录，保留备用） |
| `app/src/com/wbgateway/QrRenderer.java` | 二维码 → Bitmap（4 模块静区，整数倍缩放） |
| `app/src/com/wbgateway/Config.java` | 配置存储（SharedPreferences） |
| `app/src/com/wbgateway/Channel.java` | 渠道模型（登录型 / 代理型）+ 模型开关状态 + CodeArts 临时凭据 |
| `app/src/com/wbgateway/ModelSpec.java` | **上游下发的模型规格**（名/上下文/输出/图片/推理档位/**积分倍率**） |
| `app/src/com/wbgateway/ModelCatalog.java` | **刷新模型的统一入口**（有目录接口就拉，没有就用内置表） |
| `app/src/com/wbgateway/Checkin.java` | **每日签到 / 额度领取**（WorkBuddy / Qoder / ZCode / CodeArts） |
| `app/src/com/wbgateway/AutoCheckin.java` | **自动签到**（打开 App 触发，每天每渠道一次，失败不记账） |
| `app/src/com/wbgateway/Credits.java` | **积分查询**（WorkBuddy / Qoder / Raccoon / ZCode / CodeArts） |
| `app/src/com/wbgateway/ModelMeta.java` | **模型元数据表（54 条）**：id → 友好名/上下文/输出/推理档位/图片 |
| `app/src/com/wbgateway/Ui.java` | 视觉样式（卡片、圆角、配色） |
| `app/src/com/wbgateway/GatewayService.java` | 前台服务（保活） |
| `app/src/com/wbgateway/MainActivity.java` | UI |
| `verify/Verify.java` | 网关本体验证（30 项断言，含 SSE 伪成功改写） |
| `verify/VerifyLogin.java` | 登录验证（83 项断言） |
| `verify/VerifyCheckin.java` | 签到 + 自动签到验证（88 项） |
| `verify/VerifyCatalog.java` | 模型目录刷新验证（31 项） |
| `verify/VerifySpec.java` | 模型规格解析验证（97 项） |
| `verify/VerifyCodeArts.java` | 华为 CodeArts 验证（91 项：签名 / DPoP / 凭据识别 / 签到链路） |
| `verify/VerifyZcode.java` | ZCode 协议转换验证（25 项：请求/响应/流式/错误） |
| `verify/VerifyQoder.java` | Qoder 验证（37 项：基址 / 可用模型集合 / 规格 / SSE 伪成功） |
| `verify/VerifyQr.java` + `run-qr-verify.mjs` | 二维码编码器逐模块对账（32 项） |
| `verify/org/json/*` | **仅验证用**的最小 `org.json` 替身（Android 自带，桌面没有；不进 APK） |
| `verify/com/wbgateway/ChannelLoginProbe.java` | 验证桥接（暴露内部函数 + 可把端点指到假上游） |
| `verify/com/wbgateway/RaccoonLoginProbe.java` | 验证桥接（Raccoon 登录态识别） |
| `verify/com/wbgateway/CodeArtsCallbackProbe.java` | 验证桥接（CodeArts 回调取 code） |
| `verify/com/wbgateway/CatalogProbe.java` | 验证桥接（模型目录解析：scoped / `/v3/config`） |
| `verify/com/wbgateway/RateProbe.java` | 验证桥接（积分倍率归一化 / CodeArts 分档 / 促销时间窗） |
