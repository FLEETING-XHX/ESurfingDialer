#!/usr/bin/env python3
"""从本地 Noto Sans SC 可变字体生成待审阅的 WPF 静态字体。"""

import argparse
import copy
import hashlib
import json
import shutil
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "app" / "ESurfingDialerLite"
LICENSE = APP / "Fonts" / "NotoSansSC-OFL.txt"
VARIATION_TABLES = ("fvar", "gvar", "avar", "HVAR", "MVAR", "VVAR")
STYLES = ((400, "Regular"), (500, "Medium"), (600, "SemiBold"), (700, "Bold"))


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def require(condition, message):
    if not condition:
        raise ValueError(message)


def cmap_snapshot(font):
    # 同时核对 Unicode、其他编码和异体字映射，不能只比较 getBestCmap()。
    return [
        {
            "format": table.format,
            "platform": table.platformID,
            "encoding": table.platEncID,
            "cmap": sorted(table.cmap.items()),
            "uvs": sorted((key, sorted(value)) for key, value in getattr(table, "uvsDict", {}).items()),
        }
        for table in font["cmap"].tables
    ]


def names_snapshot(font):
    return sorted(
        (record.nameID, record.platformID, record.platEncID, record.langID, record.toUnicode())
        for record in font["name"].names
    )


def check_static_font(font, weight):
    require(font["OS/2"].fsType == 0, "字体嵌入许可 fsType 必须为 0")
    require(font["OS/2"].usWeightClass == weight, f"静态字体字重不是 {weight}")
    require(not any(tag in font for tag in VARIATION_TABLES), "静态字体仍含可变字体表")
    require("SIL Open Font License" in (font["name"].getDebugName(13) or ""), "字体缺少 SIL OFL 许可元数据")


def scan_ui(full_cmap):
    files = sorted(
        path for path in APP.rglob("*")
        if path.suffix.lower() in (".cs", ".xaml")
        and not {"bin", "obj"}.intersection(part.lower() for part in path.relative_to(APP).parts)
    )
    require(bool(files), "未找到客户端 .xaml / .cs 源文件")
    source_text = "".join(path.read_text(encoding="utf-8-sig") for path in files)
    chinese = {
        ord(character) for character in source_text
        if 0x3400 <= ord(character) <= 0x9FFF or 0x20000 <= ord(character) <= 0x2FA1F
    }
    ascii_characters = set(range(0x20, 0x7F))
    requested = ascii_characters | {ord(character) for character in source_text if ord(character) >= 0x80}
    requested.update(range(0x3000, 0x3040))
    requested.update(range(0xFF01, 0xFF66))
    codepoints = requested.intersection(full_cmap)
    require(chinese.issubset(codepoints), "源字体未覆盖界面全部中文，拒绝生成子集")
    require(ascii_characters.issubset(codepoints), "源字体未覆盖全部可打印 ASCII，拒绝生成子集")
    return codepoints, chinese, {
        "sourceFiles": [{"file": path.relative_to(ROOT).as_posix(), "sha256": sha256(path)} for path in files],
        "sourceChineseCount": len(chinese),
        "sourceChineseCodepoints": sorted(chinese),
        "codepointCount": len(codepoints),
        "codepoints": sorted(codepoints),
        "notPresentInOriginalFont": sorted(requested.difference(full_cmap)),
    }


def repair_semibold(font):
    # 原可变字体没有对应 STAT 命名值；空 STAT 会让 WPF 错配为字重 1。
    replacements = {
        1: "Noto Sans SC SemiBold", 2: "Regular", 3: "NotoSansSC-SemiBold;static-wght-600",
        4: "Noto Sans SC SemiBold", 6: "NotoSansSC-SemiBold", 16: "Noto Sans SC", 17: "SemiBold",
    }
    locales = {
        (record.platformID, record.platEncID, record.langID)
        for record in font["name"].names if record.nameID == 1
    }
    for name_id, value in replacements.items():
        for platform, encoding, language in locales:
            font["name"].setName(value, name_id, platform, encoding, language)
    if "STAT" in font and font["STAT"].table.AxisValueCount == 0:
        del font["STAT"]


def main():
    parser = argparse.ArgumentParser(description="离线生成四款客户端字体；只写入全新目录，正式 Fonts 需人工核对后更新。", add_help=False)
    parser.add_argument("-h", "--help", action="help", help="显示帮助并退出")
    parser.add_argument("--source", required=True, type=Path, metavar="TTF", help="本地 NotoSansSC-VF.ttf 路径")
    parser.add_argument("--output", type=Path, default=ROOT / "artifacts" / "fonts-rebuild", metavar="目录", help="全新输出目录，默认源码根下 artifacts/fonts-rebuild")
    parser.add_argument("--fonttools-dir", type=Path, metavar="目录", help="可选：本机 fontTools 依赖目录（包含 fontTools 包）")
    args = parser.parse_args()
    source = args.source.expanduser().resolve()
    output = args.output.expanduser().resolve()
    try:
        require(source.is_file(), f"源字体不存在：{source}")
        require(not output.exists(), f"输出目录已存在，请选择全新目录：{output}")
        require(LICENSE.is_file(), f"项目 OFL 文件不存在：{LICENSE}")
        if args.fonttools_dir:
            dependency = args.fonttools_dir.expanduser().resolve()
            require((dependency / "fontTools" / "__init__.py").is_file(), "--fonttools-dir 必须包含 fontTools 包")
            sys.path.insert(0, str(dependency))
        try:
            import fontTools
            from fontTools import subset
            from fontTools.ttLib import TTFont
            from fontTools.varLib.instancer import instantiateVariableFont
        except ImportError as error:
            raise ValueError("未找到 fontTools；请使用已装有该包的 Python，或指定 --fonttools-dir（脚本不会安装或联网）") from error

        original = TTFont(source, recalcTimestamp=False)
        try:
            require(original["OS/2"].fsType == 0, "源字体嵌入许可 fsType 必须为 0")
            require("SIL Open Font License" in (original["name"].getDebugName(13) or ""), "源字体必须使用 SIL OFL")
            require("Noto Sans SC" in (original["name"].getDebugName(16) or original["name"].getDebugName(1) or ""), "源字体必须为 Noto Sans SC")
            require("fvar" in original and [axis.axisTag for axis in original["fvar"].axes] == ["wght"], "源字体必须仅含 wght 可变轴")
            axis = original["fvar"].axes[0]
            require(axis.minValue <= 400 and axis.maxValue >= 700, "源字体 wght 范围必须包含 400 至 700")
            original_order = original.getGlyphOrder()
            original_cmap = cmap_snapshot(original)
            full_cmap = original.getBestCmap()
            codepoints, chinese, ui_evidence = scan_ui(full_cmap)
        finally:
            original.close()

        evidence = {
            "complete": False,
            "source": str(source), "sourceSha256": sha256(source), "sourceBytes": source.stat().st_size,
            "fontToolsVersion": fontTools.__version__, "pythonVersion": sys.version,
            "sourceGlyphs": len(original_order), "sourceUnicodeCharacters": len(full_cmap),
            "sourceCmapSha256": hashlib.sha256(json.dumps(original_cmap, sort_keys=True).encode("utf-8")).hexdigest(),
            "license": "SIL Open Font License 1.1; Reserved Font Name Source",
            "licenseSha256": sha256(LICENSE), "outputDirectory": str(output), "ui": ui_evidence, "fonts": [],
        }
        output.mkdir(parents=True, exist_ok=False)
        evidence_path = output / "font-rebuild-evidence.json"

        def save_evidence():
            evidence_path.write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

        save_evidence()
        shutil.copyfile(LICENSE, output / LICENSE.name)
        for weight, style in STYLES:
            is_subset = weight in (500, 600)
            target = output / f"NotoSansSC-{style}{'-UI' if is_subset else ''}.ttf"
            print(f"生成 {style}（{weight}，{'界面子集' if is_subset else '完整字形'}）", flush=True)
            font = TTFont(source, recalcTimestamp=False)
            try:
                font = instantiateVariableFont(font, {"wght": weight}, inplace=True, updateFontNames=weight != 600)
                if weight == 600:
                    repair_semibold(font)
                check_static_font(font, weight)
                require(font.getGlyphOrder() == original_order, "静态实例丢失源字形")
                require(cmap_snapshot(font) == original_cmap, "静态实例改变源 cmap")
                expected_names = names_snapshot(font)
                if is_subset:
                    name_table = copy.deepcopy(font["name"])
                    options = subset.Options()
                    options.name_IDs = ["*"]
                    options.name_languages = ["*"]
                    options.name_legacy = True
                    options.layout_features = ["*"]
                    options.notdef_outline = True
                    options.recalc_timestamp = False
                    subsetter = subset.Subsetter(options=options)
                    subsetter.populate(unicodes=codepoints)
                    subsetter.subset(font)
                    font["name"] = name_table
                font.save(target)
            finally:
                font.close()
            saved = TTFont(target, recalcTimestamp=False)
            try:
                check_static_font(saved, weight)
                require(names_snapshot(saved) == expected_names, "输出字体 name 元数据发生变化")
                require(codepoints.issubset(saved.getBestCmap()), "输出字体丢失界面字符")
                require(chinese.issubset(saved.getBestCmap()), "输出字体丢失界面中文")
                if not is_subset:
                    require(saved.getGlyphOrder() == original_order, "完整字体丢失源字形")
                    require(cmap_snapshot(saved) == original_cmap, "完整字体改变源 cmap")
                record = {
                    "file": target.name, "weight": weight, "subsetApplied": is_subset,
                    "bytes": target.stat().st_size, "sha256": sha256(target),
                    "glyphs": len(saved.getGlyphOrder()), "unicodeCharacters": len(saved.getBestCmap()),
                    "allRequestedUiCharactersPreserved": True, "allApplicationChinesePreserved": True,
                    "allOriginalGlyphsPreserved": not is_subset, "allOriginalCmapTablesPreserved": not is_subset,
                    "namesPreserved": True, "variationTablesRemaining": [],
                    "names": {str(key): saved["name"].getDebugName(key) for key in (1, 2, 6, 13, 14, 16, 17)},
                }
            finally:
                saved.close()
            evidence["fonts"].append(record)
            save_evidence()
            print(f"已核对：{record['glyphs']} 字形 / {record['unicodeCharacters']} Unicode 字符 / {record['bytes']} 字节", flush=True)
        evidence["complete"] = True
        evidence["totalBytes"] = sum(record["bytes"] for record in evidence["fonts"])
        save_evidence()
        print(f"完成：{output}；四字体合计 {evidence['totalBytes']} 字节", flush=True)
    except Exception as error:
        parser.exit(1, f"生成失败：{error}\n")


if __name__ == "__main__":
    main()
