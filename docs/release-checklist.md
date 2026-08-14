# Release Checklist

## 1. v0.13.0 Documentation & Local Demo Release

发布前确认：

- [ ] README 已更新为最终展示版
- [ ] README 未包含线上 Demo 地址
- [ ] README 未包含个人简历、面试、求职信息
- [ ] Mermaid 架构图可以在 GitHub 渲染
- [ ] 核心链路图可以在 GitHub 渲染
- [ ] docs/demo-guide.md 存在
- [ ] docs/screenshots.md 存在
- [ ] docs/env-guide.md 存在
- [ ] docs/local-dev.md 可用于本地启动
- [ ] 如果 README 引用了截图，截图文件真实存在
- [ ] 不存在伪造截图引用
- [ ] 不存在真实密钥

## 2. 本地功能验证

- [ ] Docker Compose 基础设施可启动
- [ ] Java Backend 测试通过
- [ ] AI Service pytest 通过
- [ ] BM25 regression gate 通过
- [ ] Frontend npm build 通过
- [ ] 本地页面可访问
- [ ] 推荐 Demo 路径可以跑通至少 1-2 个

## 3. v1.0.0 Learning Platform Release

如果 v0.13.0 稳定，可以发 v1.0.0。

v1.0.0 不要求新增功能，重点确认：

- [ ] README 准确
- [ ] 本地启动流程可复现
- [ ] 文档链接有效
- [ ] 截图与当前页面一致
- [ ] Release 描述准确
- [ ] tag 指向 main 最新 commit
- [ ] 项目边界说明清楚

## 4. 不应包含

- [ ] 不包含线上 Demo 地址，除非未来真实部署
- [ ] 不包含真实密钥
- [ ] 不包含个人隐私信息
- [ ] 不包含面试 / 简历 / 求职包装内容
- [ ] 不声明生产级能力
