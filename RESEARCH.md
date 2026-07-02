# 探究方向：设备注册机制集成

## 当前问题

- 设备 `933935730452521` 已触发 `ILLEGAL_ACCESS` 风控，无法继续使用
- `log5-applog.fqnovel.com/service/2/device_register/` 返回 `device_id:0` —— 这是内部日志端点，不是设备注册 API
- 需要找到正确的设备注册方法以获取新设备参数

## 目标项目

### `autobcb/fqnovel-unidbg`

仓库：https://github.com/autobcb/fqnovel-unidbg

- 使用 unidbg 模拟 SO 库执行，调用 fqnovel 安卓端接口
- 基于 `anjia0532/unidbg-boot-server` + `rudo-rs/fqnovel-api`（加解密参考）
- 需要 Redis 支持

### 关键文件

| 文件 | 说明 |
|------|------|
| `tools/batch_device_register_xml.py` | 批量生成设备注册信息的脚本 |
| `src/main/java/com/anjia/unidbg/IdleFQ.java` | unidbg 模拟执行核心代码 |
| `src/test/java/com/anjia/unidbgserver/service/FQEncryptServiceTest.java` | 签名加密测试代码 |
| `src/main/resources/com/dragon/read/oversea/gp` | unidbg 模拟执行的 SO 库文件 |
| `application.yml` | 设备配置（device-id, install-id, cdid 等） |

### API 端点

```
GET  /api/fqnovel/fqsearch/book         搜索
GET  /api/fqnovel/book/{bookId}         书籍详情
GET  /api/fqnovel/directory/{bookId}    目录
GET  /api/fqnovel/chapter/{bookId}/{chapterId}  单章（警告：频繁调用触发风控）
POST /api/fqnovel/chapter/batch         批量获取章节内容（推荐）
```

### 设备注册流程（待探究）

1. `batch_device_register_xml.py` 如何生成设备信息
2. 生成的设备信息如何与 fqnovel API 交互（设备注册 + 获取 key）
3. 如何集成到当前 Java 项目的 `reinitDevice()` 中

## 集成计划

1. **克隆** `autobcb/fqnovel-unidbg` 到本地
2. **研究** `batch_device_register_xml.py` 的设备注册机制
3. **理解** IdleFQ / FQEncryptService 的签名流程
4. **提取** 设备注册 + key 获取的核心逻辑
5. **集成** 到 `Main.java` 的 `reinitDevice()` 方法中，替代原 `deviceRegister()`

## 注意事项

- 不要频繁调用单章接口，建议使用批量接口
- SO 库文件可能需要在 Linux x86_64 下运行 unidbg 模拟
- Spring Boot 项目需 JDK 8+
- Redis 是必须的依赖
