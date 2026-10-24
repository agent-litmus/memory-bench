# AgentLitmus 运行镜像：jar 只在构建期由 CI 放进 target/，
# 镜像本身不含源码与构建工具，运行层只需要 JRE。
#
# 本地构建：  ./mvnw -q clean package -DskipTests && docker build -t agentlitmus .
# 运行：      docker run --rm -v "$PWD/out":/out ghcr.io/agent-litmus/memory-bench run --out /out
#
# 以 eclipse-temurin JRE 为底、显式固定工作目录与非 root 用户：
# 评测工具自己就该遵守它对被测对象的要求——最小权限、可预测的环境。
FROM eclipse-temurin:21-jre-jammy

RUN useradd --create-home --uid 1000 litmus
USER litmus
WORKDIR /work
COPY target/memory-bench-*.jar /opt/agentlitmus/memory-bench.jar

# 输出统一落 /out（挂载卷），证据与报告留在容器外的宿主目录
VOLUME ["/out"]
ENV LITMUS_OUT=/out

ENTRYPOINT ["java", "-jar", "/opt/agentlitmus/memory-bench.jar"]
CMD ["run", "--out", "/out"]
