---
name: compile-server
description: 编译 Spring Boot 后端（server/）验证代码能否通过编译（compile 验错）。
---

# 后端编译验错（compile-server）

编译 `server/` 后端模块，验证代码能否通过编译。

## 执行命令

```bash
cd server && mvn clean compile -DskipTests
```

说明：
- `clean` - 清理旧的编译文件
- `compile` - 编译源代码
- `-DskipTests` - 跳过测试（只验证编译）

## 结果判断

- **看到 `BUILD SUCCESS`** = 编译成功
- **看到 `BUILD FAILURE`** = 编译失败，按报错定位到具体文件与行号并修复

## 前置条件

- 已安装 JDK 21（通过 PATH 环境变量自动识别）
- 已安装 Maven 3.9+（通过 PATH 环境变量自动识别）
- 如果终端未识别 `mvn` 命令，运行 `source ~/.bashrc` 刷新环境变量
