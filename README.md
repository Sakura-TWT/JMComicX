# JMComicX

> 请勿在公开平台宣传、搬运、引流或二次包装推广本软件。<br>
> 本项目面向成年用户；未满 18 周岁请勿下载、编译、安装或使用。

JMComicX 是使用 Kotlin、Jetpack Compose 与 Compose Miuix 构建的 Android 第三方客户端。工程重点是将易变的第三方协议、网络线路、会话和图片处理能力从界面中隔离，并提供适合移动端连续浏览与阅读的交互实现。

JMComicX 与 JMComic / 禁漫天堂及其关联方没有隶属、合作、授权或官方认可关系。仓库不提供、不存储漫画内容，应用显示的数据来自用户主动访问的第三方服务。

## 工程目标

- **传输可恢复**：网络故障、线路失效、会话过期和图片失败都有明确状态与恢复路径。
- **协议集中管理**：Token、API 版本、响应解密、动态域名和 Cookie 不散落在页面代码中。
- **界面状态稳定**：分类、分页、搜索、详情与阅读状态在导航往返时保持一致。
- **阅读优先**：图片解密、预加载、失败重试、连续滚动和缩放围绕真实阅读路径设计。
- **可验证**：纯 Kotlin 底层支持单元测试、MockWebServer 测试和真实环境验收。

## 技术选型

| 类型      | 实现                            |
| ------- | ----------------------------- |
| 语言      | Kotlin 2.4                    |
| Android | minSdk 33 / targetSdk 37      |
| UI      | Jetpack Compose、Compose Miuix |
| 导航      | Navigation 3、MIUIX            |
| 网络      | OkHttp 5                      |
| 图片      | Coil、Android Bitmap           |
| 序列化     | Gson                          |
| 中文转换    | OpenCC4J                      |
| 构建      | AGP 9.2、Gradle 9.4、JDK 21     |

## 架构

工程由 `app` 和 `core` 两个模块组成，依赖方向固定为 `app -> core`。

```text
Compose Screen
    -> UI State / Repository
        -> JmxCore API Facade
            -> Request / Token / Endpoint / Session
                -> OkHttp
                    -> Decode / Map / Result
                        -> UI State
```

### app

`app` 只负责 Android 和界面相关能力：

- Compose 页面、MIUIX 组件、导航和转场。
- 首页、搜索、详情、账号、签到、设置与阅读器状态。
- Android Keystore 凭据加密、SharedPreferences 状态和 Coil 缓存。
- 将 `core` 返回的结构化结果转换为可操作的 UI 状态。

页面不直接拼接 API Token，不自行处理响应解密，也不持有动态线路规则。

### core

`core` 是不依赖 Android UI 的 Kotlin 模块：

- API 路由、请求模型、响应模型和数据映射。
- Token、TokenParam、远端 API 版本和协议常量。
- 动态域名、自动/手动线路、测速、健康评分和故障降级。
- Cookie 持久化、AVS 同步与登录会话管理。
- AES 响应解密、错误分类和诊断报告。
- 章节模板解析、图片地址规划、分段还原和预下载基础能力。

底层保留下载任务、图片还原和持久化能力，但功能是否对用户开放由 `app` 的产品界面决定。

## 网络与会话

### 请求链路

```text
ApiRoute
  -> ApiRequest
  -> ApiEndpointManager.current()
  -> ApiTokenProvider
  -> JmxHttpClient
  -> ApiResponseDecoder
  -> ApiMapper
  -> JmxResult<T>
```

- 域名服务返回的 API 列表会被规范化并持久化。
- 自动模式根据成功率、连续失败和延迟选择线路；手动模式固定用户选择的线路。
- 可重试网络错误会在有限次数内重试并按策略切换线路。
- HTTP、API、网络、域名、解密与结构错误使用不同类型表示，避免仅返回模糊异常文本。

### 登录会话

- 登录请求使用干净的临时 Cookie 容器，成功后再提交新会话。
- AVS 会同步到可用 API 主机，其他线路私有 Cookie 不跨域复制。
- Cookie 按域名、路径和名称去重并持久化。
- 账号凭据通过 Android Keystore 加密保存。
- 收藏、历史等接口返回 401 时会串行恢复会话，避免并发重复登录。
- 无可恢复凭据时由界面请求用户重新登录，并在成功后刷新原页面。

## 图片与阅读

```text
Chapter API
  -> ChapterTemplateParser
  -> ImagePlan
  -> Coil ImageRequest
  -> JmxUnscrambleTransformation
  -> Reader UI
```

- 章节模板解析与页面 URL 生成位于 `core`。
- 图片请求统一补充 Referer、User-Agent 等必要请求头。
- 分段图片在 Bitmap 阶段按还原计划重组，避免接缝和横线。
- 阅读器仅预取当前位置之后的有限页面，不一次性解码整章。
- 单页失败不会清空已经加载成功的页面，可独立重试。
- 缩放作用于连续阅读画布：双击按触点放大，双指调整倍率，横向平移，并保持倍率继续纵向跨页滚动。

## 界面实现

- 首页分类分别保存内容、页码、加载状态和滚动位置。
- 下拉刷新替换当前分类数据；触底加载只追加新页面并按漫画 ID 去重。
- 搜索仅在提交后请求，支持简繁体匹配、标签、JM 车号和本地历史。
- 首页、搜索结果与详情共享封面转场状态，返回时恢复来源页面。
- 详情评论独立分页，不阻塞漫画主体和章节目录。
- 主导航与二级页面使用 MIUIX Navigation 3 转场，系统返回统一回退到上一级。

## 功能范围

- 动态分类首页、下拉刷新和分页加载。
- 标题、标签、作者、简繁体和 JM 车号搜索。
- 漫画详情、章节目录、分页评论、喜欢与收藏。
- 账号登录、用户资料、漫画收藏、观看历史和每日签到。
- 连续阅读、章节切换、进度定位、音量键翻页和沉浸状态。
- 双击/双指缩放、图片预加载、失败重试和缓存清理。
- API 线路与图源测速、自动选择和手动切换。
- GitHub Release 更新检测

## 测试

测试分为三层：

1. 纯逻辑单元测试：协议、Token、解密、映射、分页、版本和阅读计算。
2. MockWebServer 测试：请求路径、参数、Cookie、重试、登录和错误响应。
3. 真实环境测试：动态域名、远端设置、登录、收藏、历史、签到、章节和图片还原。

普通测试不会读取真实账号。真实测试只通过环境变量或本地忽略文件获取凭据，报告不会输出密码。

```powershell
.\gradlew.bat :core:test :app:testDebugUnitTest :app:lintDebug --no-daemon
```

## 本地构建

Debug APK：

```powershell
.\gradlew.bat :app:assembleDebug --no-daemon
```

签名 Release APK：

```powershell
$env:JMX_RELEASE_KEYSTORE_FILE="D:\path\to\release.keystore"
$env:JMX_RELEASE_STORE_PASSWORD="<store password>"
$env:JMX_RELEASE_KEY_ALIAS="<key alias>"
$env:JMX_RELEASE_KEY_PASSWORD="<key password>"
.\gradlew.bat :app:lintRelease :app:assembleRelease --no-daemon
```

Release 构建启用 R8、资源压缩和签名配置校验。签名变量缺失时构建会在 `preReleaseBuild` 明确失败，不会静默生成未签名正式包。

## GitHub Actions

仓库提供两个相互独立、仅手动触发的工作流：

- `Android Debug APK`：测试、Lint 并生成 Debug APK artifact。
- `Android Release APK`：测试、Lint、R8、签名验证并生成 Release APK artifact。

Release 工作流通过以下 GitHub Actions Secrets 使用发布证书：

- `JMX_RELEASE_KEYSTORE_BASE64`
- `JMX_RELEASE_STORE_PASSWORD`
- `JMX_RELEASE_KEY_ALIAS`
- `JMX_RELEASE_KEY_PASSWORD`

## 目录结构

```text
JMComicX/
├── app/                         Android APP 与 Compose 界面
│   └── src/main/kotlin/dev/jmx/client/
├── core/                        协议、网络、加解密、线路、图片与诊断
│   └── src/main/kotlin/dev/jmx/client/core/
├── gradle/libs.versions.toml    依赖版本目录
├── version.properties           统一版本号
├── README.md                    工程说明
```

## 开源引用

- [compose-miuix-ui/miuix](https://github.com/compose-miuix-ui/miuix)：UI实现。
- [hect0x7/JMComic-Crawler-Python](https://github.com/hect0x7/JMComic-Crawler-Python)：协议行为与图片处理参考。
- [Dedicatus546/jm-mobile](https://github.com/Dedicatus546/jm-mobile)：移动客户端业务参考。
- AndroidX、Kotlin、Coil、OkHttp、Gson、OpenCC4J、WebP ImageIO 等开源组件。

第三方项目和代码继续遵循各自原始许可证。应用内“关于 JMComicX -> 第三方开源库”可查看主要依赖及项目地址。

## 免责声明

本项目仅用于技术研究、学习交流和移动端体验优化，不提供商业化服务。用户应自行确认所在地法律法规、平台规则和账号服务条款，并对账号登录、内容访问、下载缓存及数据同步行为承担责任。

漫画、图片、文本、评论及相关内容版权归原站、原作者、制作方或发行方所有。项目不保证第三方服务的可用性、完整性、准确性或实时性。

若权利方认为项目存在不当引用或侵权风险，请通过 GitHub 仓库功能联系维护者处理。

## 许可证

JMComicX 使用 GPL-3.0 许可证发布，完整条款见 [LICENSE](LICENSE)。
