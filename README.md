# JMX

> 请勿在任何公开平台宣传、搬运、引流或二次包装推广本软件。  
> JMX 面向成人用户；未满 18 岁请勿下载、编译、安装或使用。

JMX 是一个使用 Kotlin 与 Jetpack Compose 构建的 Android 第三方客户端，面向 JMComic / 禁漫天堂的浏览、搜索、阅读、账号同步与下载管理场景。项目的目标不是复制官方体验，而是在移动端重新整理信息层级、阅读路径和视觉语言，让一个内容型客户端更轻、更直接、更容易维护。

JMX 不是 JMComic / 禁漫天堂官方应用，也没有任何官方网站。当前仓库、GitHub Actions 构建产物与 GitHub Release 是唯一推荐获取渠道。


## 项目状态

| 项目 | 状态 |
| --- | --- |
| 平台 | Android 12+ |
| 架构 | Single Activity + Jetpack Compose |
| 构建产物 | Release APK / GitHub Actions artifact |
| 许可证 | GPL-3.0 |

## 主要能力

- 首页发现：今日精选、分类切换、每周推荐、列表刷新与搜索入口。
- 搜索浏览：关键词搜索、排序筛选、分页结果、二次搜索与历史记录。
- 漫画详情：封面、作者、标签、喜欢数、浏览数、简介、章节、评论、相关作品与下载入口。
- 阅读体验：滚动阅读、分页阅读、章节切换、进度定位、图片预加载与本地缓存。
- 账号同步：JM 账号登录、自动登录、用户资料、收藏、历史、评论与签到数据。
- 下载管理：后台下载、状态管理、本地缓存、压缩包导出与缓存清理。


## 构建

推荐环境：

- Android Studio 新版本
- JDK 25
- Android SDK Platform 36
- Gradle Wrapper 使用仓库内置版本

本地构建：

```powershell
git clone https://github.com/Sakura-TWT/JMX.git
cd JMX
.\gradlew.bat :app:assembleRelease --no-daemon
```

Linux / macOS：

```bash
./gradlew :app:assembleRelease --no-daemon
```

生成的 APK 位于：

```text
app/build/outputs/apk/release/
```

## GitHub Actions

仓库内置 Android CI：

- push 到 `main` 会编译 release APK。
- pull request 会进行同样的构建校验。
- 手动触发 workflow 可生成一次构建产物。
- 推送 `v*` 标签时会自动创建 GitHub Release 并上传 APK。

## 目录结构

```text
JMX/
├── app/                                      主 Android 应用
│   ├── src/main/java/dev/jmx/client/data     API、DTO、转换器与领域模型
│   ├── src/main/java/dev/jmx/client/database Room 数据库、DAO 与本地实体
│   ├── src/main/java/dev/jmx/client/repository 数据访问边界
│   ├── src/main/java/dev/jmx/client/storage  本地存储与安全存储
│   ├── src/main/java/dev/jmx/client/store    运行时状态管理
│   ├── src/main/java/dev/jmx/client/ui       Compose 页面、组件、导航与视觉系统
│   ├── src/main/java/dev/jmx/client/worker   WorkManager 后台任务
│   └── src/main/res                          图标、主题、字符串与资源文件
├── third_party/AndroidLiquidGlass/backdrop   本地 Liquid Glass / Backdrop 模块
├── docs/screenshots/                         README 预览图
├── gradle/libs.versions.toml                 依赖版本目录
├── version.properties                        应用版本号
├── CHANGELOG                                 版本记录
└── LICENSE                                   GPL-3.0 许可证
```

核心数据流：

```text
Compose Screen -> ViewModel -> Repository -> Retrofit / Room -> StateFlow -> Compose Screen
```

阅读链路：

```text
AlbumDetail -> AlbumReadViewModel -> AlbumRepository -> Image State -> Reader UI
```

下载链路：

```text
DownloadScreen -> DownloadViewModel -> DownloadManager -> WorkManager Worker -> Room -> UI
```

## 技术栈

| 类型 | 选型 |
| --- | --- |
| 语言 | Kotlin |
| UI | Jetpack Compose |
| 导航 | Navigation Compose |
| 状态 | ViewModel / StateFlow |
| 网络 | Retrofit / OkHttp |
| 图片 | Coil |
| 数据库 | Room / KSP |
| 分页 | Paging 3 |
| 后台任务 | WorkManager |
| 注入 | Koin |
| 玻璃效果 | AndroidLiquidGlass / Backdrop |

## 来源与引用

JMX 在第三方 JM 移动客户端方向上继续整理，并引用或参考了以下项目与资料：

- [Dedicatus546/jm-mobile](https://github.com/Dedicatus546/jm-mobile)：原始客户端基础与业务方向参考。
- [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)：Liquid Glass / Backdrop 视觉系统参考与本地模块。
- [jmcomic-api-java documentation](https://jmcomic-api-java.readthedocs.io/zh-cn/latest/)：API 行为、模型与功能文档参考。
- AndroidX、Coil、Retrofit、OkHttp、Room、Koin、Paging、WorkManager 等开源 Android 生态组件。

第三方代码、文档与资源仍遵循其原始许可证。AndroidLiquidGlass 相关代码保留在 `third_party/AndroidLiquidGlass/`，并附带其原始 Apache-2.0 许可文件。

## 免责声明

JMX 与 JMComic / 禁漫天堂及其关联方无任何隶属、合作、授权或官方认可关系。

本应用仅通过用户主动访问的第三方服务获取公开返回的数据，不访问第三方服务数据库，不进行注入攻击、越权请求、绕过认证或获取非公开用户隐私数据。项目不保证第三方服务的可用性、完整性、准确性或实时性。

漫画、图片、文本、评论及相关内容版权归原站、原作者、制作方或发行方所有。JMX 不存储、不修改、不售卖、不主张拥有任何第三方版权内容。

本项目仅用于技术研究、学习交流、移动端体验优化和界面设计实验，不提供商业化服务。用户应自行确认所在地法律法规、平台规则和账号服务条款，并对账号登录、内容访问、下载缓存、数据同步等行为承担全部责任。

若权利方认为本项目存在不当引用、侵权风险或其他问题，请通过 GitHub 仓库功能联系维护者处理。

最后更新日期：2026-05-26

## 许可证

本项目采用 GPL-3.0 许可证发布，完整条款请参阅 [LICENSE](LICENSE)。
