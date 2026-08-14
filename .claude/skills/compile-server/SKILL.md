---
name: compile-server
description: 编译 Spring Boot 后端（server/）验证代码能否通过编译（compile 验错）。已记录 JDK 21 与 Maven 3.9.11 的位置。
---

# 后端编译验错（compile-server）

编译 `server/` 后端模块，验证代码能否通过编译。JDK 21 与 Maven 3.9.11 的位置已记录为环境变量，直接运行下面一条命令即可，无需重新查找环境。

## 已记录的环境位置

- **JDK 21**：`C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot`（`JAVA_HOME` 指向此处）
- **Maven 3.9.11**：`D:\IntelliJ\IntelliJ IDEA 2026.1.3\plugins\maven\lib\maven3`（`MAVEN_HOME` 指向此处，其 `bin` 已加入用户 PATH）

## 执行（一条命令，自动兜底）

```bash
cd d:/ClaudeProject/VideoCourseAI-main/server && export JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot" && (command -v mvn >/dev/null 2>&1 && mvn -q -DskipTests compile || "/d/IntelliJ/IntelliJ IDEA 2026.1.3/plugins/maven/lib/maven3/bin/mvn" -q -DskipTests compile)
```

说明：优先用 PATH 里的 `mvn`；若当前终端尚未刷新 PATH（刚改完环境变量、未重启 IDE/终端），自动回退到完整路径，二者等价。

## 结果判断

- **无输出且命令正常结束** = 编译成功（`-q` 静默模式，只有报错才会打印）
- **输出 `[ERROR]` / `BUILD FAILURE`** = 编译失败，按报错定位到具体文件与行号并修复

## 备注

- 显式 `export JAVA_HOME` 指向 JDK 21，避免 PATH 里 Java 8 的 `java8path` 干扰。
- `-q` 静默、`-DskipTests` 只编译不跑测试。若要保留完整构建日志去掉 `-q`；若要全量重新编译改用 `clean compile`。
