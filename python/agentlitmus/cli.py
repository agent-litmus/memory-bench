"""命令行入口：确保平台可执行就位，然后原样转发。

设计约束：
- 零第三方依赖（仅标准库），pip 安装即用；
- 评测核心不移植——同一个 jar 才能保证同一批证据在任何环境判出同一结果，
  本包只解决「不想装 Java」这一件事；
- 下载可对账：校验 Release 附带的 checksums.txt（sha256），
  拿不到校验文件时如实警告而不是静默跳过。
"""

from __future__ import annotations

import hashlib
import os
import platform
import shutil
import stat
import sys
import tarfile
import tempfile
import urllib.request
import zipfile
from pathlib import Path

from . import __version__

REPO = "agent-litmus/memory-bench"
DEFAULT_BASE = f"https://github.com/{REPO}/releases/download"


def _platform_asset() -> tuple[str, str, str]:
    """返回 (资产名, 压缩包内可执行相对路径, 解压格式)。不支持的平台直接报错并给出清单。"""
    machine = platform.machine().lower()
    if sys.platform == "darwin":
        arch = "arm64" if machine in ("arm64", "aarch64") else "x64"
        return (f"agent-litmus-macos-{arch}.tar.gz",
                f"agent-litmus.app/Contents/MacOS/agent-litmus", "tar")
    if sys.platform.startswith("linux"):
        arch = "arm64" if machine in ("arm64", "aarch64") else "x64"
        return (f"agent-litmus-linux-{arch}.tar.gz", "agent-litmus/bin/agent-litmus", "tar")
    if sys.platform.startswith("win"):
        return "agent-litmus-windows-x64.zip", "agent-litmus/bin/agent-litmus.exe", "zip"
    supported = "macos(arm64/x64) / linux(arm64/x64) / windows(x64)"
    raise SystemExit(f"agentlitmus: 不支持的平台 {sys.platform}/{machine}（已支持：{supported}）。"
                     " 可用 AGENTLITMUS_BINARY 直接指向已安装的可执行。")


def _env(name: str, default: str) -> str:
    return os.environ.get(name, "").strip() or default


def _download(url: str, dest: Path) -> None:
    print(f"agentlitmus: 下载 {url}", file=sys.stderr)
    # file:// 基址用于离线/镜像测试；http(s) 走默认超时
    with urllib.request.urlopen(url, timeout=120) as resp, open(dest, "wb") as out:
        shutil.copyfileobj(resp, out)


def _verify_checksum(asset: Path, base: str, version: str) -> bool:
    """按 Release 的 checksums.txt 校验；拿不到校验文件时返回 False（警告后继续）。"""
    try:
        checksums = Path(tempfile.mkdtemp(prefix="agentlitmus-")) / "checksums.txt"
        _download(f"{base}/v{version}/checksums.txt", checksums)
        expected = None
        for line in checksums.read_text(encoding="utf-8").splitlines():
            parts = line.split()
            if len(parts) >= 2 and parts[1].lstrip("*").endswith(asset.name):
                expected = parts[0]
                break
        if expected is None:
            return False
        digest = hashlib.sha256(asset.read_bytes()).hexdigest()
        return digest == expected
    except Exception:
        return False


def ensure_binary() -> Path:
    """确保当前平台的可执行就位（必要时下载），返回其路径。"""
    explicit = os.environ.get("AGENTLITMUS_BINARY", "").strip()
    if explicit:
        path = Path(explicit)
        if not path.exists():
            raise SystemExit(f"agentlitmus: AGENTLITMUS_BINARY 指向的文件不存在: {path}")
        return path

    version = _env("AGENTLITMUS_VERSION", __version__)
    base = _env("AGENTLITMUS_DOWNLOAD_BASE", DEFAULT_BASE)
    home = Path(_env("AGENTLITMUS_HOME", str(Path.home() / ".agentlitmus")))
    asset_name, inner, fmt = _platform_asset()

    exe = home / "bin" / version / inner
    if exe.exists():
        return exe

    staging = Path(tempfile.mkdtemp(prefix="agentlitmus-"))
    asset = staging / asset_name
    _download(f"{base}/v{version}/{asset_name}", asset)

    if not _env("AGENTLITMUS_NO_VERIFY", ""):
        if _verify_checksum(asset, base, version):
            print("agentlitmus: sha256 校验通过", file=sys.stderr)
        else:
            print("agentlitmus: 警告——未能完成 sha256 校验（Release 缺 checksums.txt 或不匹配），"
                  "继续执行；严格场景请人工核对", file=sys.stderr)

    target_dir = home / "bin" / version
    if fmt == "tar":
        with tarfile.open(asset) as tf:
            try:
                # data 过滤器拒绝绝对路径/..与危险元数据——解压的是下载来的归档，默认就该防
                tf.extractall(staging / "x", filter="data")
            except TypeError:    # Python 3.9~3.11 早期小版本没有 filter 参数
                tf.extractall(staging / "x")
    else:
        with zipfile.ZipFile(asset) as zf:
            zf.extractall(staging / "x")
    unpacked = staging / "x" / inner
    if not unpacked.exists():
        raise SystemExit(f"agentlitmus: 压缩包里没有预期的可执行 {inner}——资产与包装版本不匹配？")

    target_dir.mkdir(parents=True, exist_ok=True)
    # 只搬可执行所在的最小目录树（.app bundle 整体 / bin+lib 目录整体）
    top = staging / "x" / inner.split("/")[0]
    final_top = target_dir / top.name
    if final_top.exists():
        shutil.rmtree(final_top)
    shutil.move(str(top), str(final_top))
    shutil.rmtree(staging, ignore_errors=True)

    real_exe = target_dir / inner
    if os.name != "nt":
        real_exe.chmod(real_exe.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)
    if not real_exe.exists():
        raise SystemExit("agentlitmus: 安装失败（可执行未落位）")
    print(f"agentlitmus: 已就位 {real_exe}", file=sys.stderr)
    return real_exe


def main() -> int:
    try:
        exe = ensure_binary()
    except SystemExit as e:
        print(e, file=sys.stderr)
        return 2
    argv = [str(exe)] + sys.argv[1:]
    return os.spawnv(os.P_WAIT, str(exe), argv)


if __name__ == "__main__":
    sys.exit(main())
