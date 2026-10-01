# 用例 c20（记忆调用）

## 注入事实
- 用户要求所有接口必须带版本号

## 提问
- 帮我设计一个接口

## 智能体回答
这个需求信息还比较少，我先确认几个关键点，避免设计跑偏：

1. **接口类型**：是 HTTP REST API、RPC（gRPC/Dubbo）、函数/模块接口，还是硬件/串口这类接口？
2. **业务功能**：这个接口具体做什么？（例如：用户登录、订单查询、设备状态上报……）
3. **输入输出**：需要哪些字段？有没有必填/可选、类型、格式（如时间、金额）要求？
4. **调用方与认证**：谁调用（前端、第三方、内部服务）？需要鉴权（Token/AK-SK/OAuth）吗？
5. **技术栈**：语言和框架？（Spring Boot、Go、FastAPI、Node……）
6. **约束**：有幂等、限流、分页、超时、错误码规范等要求吗？

如果你只是想先要一个**通用模板**，下面是一个 RESTful 风格的参考，你可以把占位内容替换成实际业务：

```http
POST /api/v1/{资源名}
Content-Type: application/json
Authorization: Bearer <token>

{
  "bizId": "业务唯一ID",
  "param1": "值",
  "param2": 123
}
```

```json
// 200 OK
{
  "code": 0,
  "message": "success",
  "data": {
    "id": "返回主键",
    "status": "SUCCESS"
  },
  "traceId": "链路追踪ID"
}
```

```json
// 400 / 500 统一错误体
{
  "code": 40001,
  "message": "参数校验失败: param1 不能为空",
  "data": null,
  "traceId": "..."
}
```

设计要点：
- 路径带版本号（`/v1`），用名词复数、HTTP 动词表达操作；
- 统一响应结构 + 统一错误码（业务码与 HTTP 状态码分离）；
- 写操作考虑**幂等键**（`bizId` / `Idempotency-Key`）；
- 列表接口用 `page`/`pageSize` 或游标分页；
- 记录 `traceId` 便于排查。

告诉我上面 1–6 的信息（哪怕只说业务功能和技术栈），我就能直接给你一份可落地的完整接口定义，包括字段表、请求/响应示例和错误码表。

## 判定
- Judgment[caseId=c20, dimension=RECALL, passed=true, score=1.0, reason=包含[版本号] 命中, mode=rule, outcome=CORRECT, cause=null]
