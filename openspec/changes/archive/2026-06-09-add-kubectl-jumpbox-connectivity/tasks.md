## 1. 后端模型 + 服务

- [x] 1.1 `DetectionTask` 新增 `connectionType`, `targetPod`, `targetNamespace` 字段
- [x] 1.2 新建 `KubectlService` — SSH 跳板机连接、kubectl get pods 解析、kubectl exec 命令包装
- [x] 1.3 新建 `KubectlController` — `POST /api/kubectl/pods` 端点
- [x] 1.4 `TaskController` 适配 kubectl 字段透传、`TaskManagementService.persistTask()` 设为 public

## 2. 检测流程适配

- [x] 2.1 `DetectionOrchestrator` 注入 `KubectlService`，新增 `wrapCommand()` 方法
- [x] 2.2 所有 SSH 调用（3 处）和 `collectEnvironmentFingerprint`/`collectRawEnvOutput` 传递 task 并包装命令
- [x] 2.3 测试 `DetectionOrchestratorIntegrationTest` 更新构造函数参数

## 3. 前端

- [x] 3.1 `api/index.js` 新增 `listKubectlPods()`
- [x] 3.2 `CreateTask.vue` 重写：连接方式选择器（直连 SSH / Kubectl 跳板机）、Pod 列表浏览器、表单适配
- [x] 3.3 `StatusBadge.vue` 新增 K8s Pod 状态样式（Pending, Succeeded, Unknown）

## 4. 验证

- [ ] 4.1 使用跳板机 + kubectl 模式：填跳板机信息 → 连接 → 显示 Pod 列表 → 选择 Pod → 启动检测
- [ ] 4.2 直连 SSH 模式不受影响
- [ ] 4.3 kubectl exec 命令包装正确性：`kubectl exec -n <ns> <pod> -- <originalCmd>`
