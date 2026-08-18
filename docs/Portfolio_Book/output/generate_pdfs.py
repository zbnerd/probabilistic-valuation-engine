#!/usr/bin/env python3
# /// script
# requires-python = ">=3.12,<3.13"
# dependencies = ["reportlab==5.0.0"]
# ///
"""Render the completed Korean Markdown resume and portfolio as A4 PDFs.

The originals are hash-checked and never opened for writing. The renderer
supports the deliberately small Markdown subset used by the two final files.
"""

from __future__ import annotations

import argparse
import hashlib
import html
import pathlib
import re
from dataclasses import dataclass

from reportlab.lib import colors
from reportlab.lib.enums import TA_CENTER, TA_LEFT
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import mm
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import (
    HRFlowable,
    KeepTogether,
    PageBreak,
    Paragraph,
    Preformatted,
    SimpleDocTemplate,
    Spacer,
    Table,
    TableStyle,
)


ROOT = pathlib.Path(__file__).resolve().parents[3]
FINAL_DIR = ROOT / "docs/Portfolio_Book/output/final"
FONT_PATH = pathlib.Path("/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc")
FONT_SHA256 = "79c18ebe7b811951e8311bad7103ebeae8c337ed9988ea69e8a78a66cfe029b9"

ORIGINAL_HASHES = {
    "docs/Portfolio_Book/2026년 이력서 포트폴리오 리뉴얼.pdf": "e67b747879168c5864eef1ea85cde54a658c0ea42f58d08e5ef752b706a59a7b",
    "docs/Portfolio_Book/이력서.pdf": "050ebd6dc8d02e1969d9829d0d93055075fe662d75819d95802a1074b543db2e",
    "docs/Portfolio_Book/포트폴리오.pdf": "fb2104e6e9167c9162b865c25ca9a9afbe238250ec4af252f91659729aadba7b",
}

NAVY = colors.HexColor("#14213D")
BLUE = colors.HexColor("#2563EB")
PALE_BLUE = colors.HexColor("#EAF1FF")
INK = colors.HexColor("#172033")
MUTED = colors.HexColor("#5B6475")
LIGHT = colors.HexColor("#F5F7FA")
LINE = colors.HexColor("#D9DEE8")


@dataclass(frozen=True)
class RenderConfig:
    body_size: float
    body_leading: float
    code_size: float
    table_size: float
    margin_mm: float


CONFIGS = {
    "resume": RenderConfig(8.6, 11.7, 7.2, 6.8, 15.5),
    "portfolio": RenderConfig(9.0, 12.8, 7.2, 7.0, 16.5),
}


def file_sha256(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def verify_originals() -> None:
    failures = []
    for rel, expected in ORIGINAL_HASHES.items():
        path = ROOT / rel
        actual = file_sha256(path)
        if actual != expected:
            failures.append(f"{rel}: expected {expected}, got {actual}")
    if failures:
        raise RuntimeError("Original PDF hash mismatch; refusing to render:\n" + "\n".join(failures))


def register_font() -> str:
    if not FONT_PATH.is_file():
        raise RuntimeError(
            "Pinned Korean font is missing: " + str(FONT_PATH)
            + ". Install the WenQuanYi Zen Hei font package before rendering."
        )
    actual_hash = file_sha256(FONT_PATH)
    if actual_hash != FONT_SHA256:
        raise RuntimeError(
            f"Pinned Korean font hash mismatch: expected {FONT_SHA256}, got {actual_hash}"
        )
    name = "PortfolioKorean"
    pdfmetrics.registerFont(TTFont(name, str(FONT_PATH)))
    pdfmetrics.registerFontFamily(name, normal=name, bold=name, italic=name, boldItalic=name)
    return name


def inline_markup(text: str, font_name: str) -> str:
    """Escape text, then translate links/bold/code to ReportLab markup."""
    tokens: dict[str, str] = {}

    def token(value: str) -> str:
        key = f"@@TOKEN{len(tokens)}@@"
        tokens[key] = value
        return key

    def link_repl(match: re.Match[str]) -> str:
        label, target = match.group(1), match.group(2)
        safe_label = html.escape(label)
        safe_target = html.escape(target, quote=True)
        return token(f'<link href="{safe_target}" color="#2563EB"><u>{safe_label}</u></link>')

    def code_repl(match: re.Match[str]) -> str:
        value = html.escape(match.group(1))
        return token(f'<font name="{font_name}" color="#9A3412">{value}</font>')

    staged = re.sub(r"\[([^\]]+)\]\(([^)]+)\)", link_repl, text)
    staged = re.sub(r"`([^`]+)`", code_repl, staged)
    escaped = html.escape(staged)
    escaped = re.sub(r"\*\*([^*]+)\*\*", r"<b>\1</b>", escaped)
    for key, value in tokens.items():
        escaped = escaped.replace(key, value)
    return escaped


def make_styles(font_name: str, config: RenderConfig):
    sample = getSampleStyleSheet()
    body = ParagraphStyle(
        "BodyKorean",
        parent=sample["BodyText"],
        fontName=font_name,
        fontSize=config.body_size,
        leading=config.body_leading,
        textColor=INK,
        spaceAfter=2.8 * mm,
        wordWrap="CJK",
        splitLongWords=True,
    )
    styles = {
        "body": body,
        "title": ParagraphStyle(
            "TitleKorean",
            parent=body,
            fontSize=23,
            leading=29,
            textColor=NAVY,
            alignment=TA_LEFT,
            spaceBefore=5 * mm,
            spaceAfter=6 * mm,
        ),
        "h1": ParagraphStyle(
            "H1Korean",
            parent=body,
            fontSize=17,
            leading=22,
            textColor=NAVY,
            spaceBefore=3.5 * mm,
            spaceAfter=4 * mm,
            keepWithNext=True,
        ),
        "h2": ParagraphStyle(
            "H2Korean",
            parent=body,
            fontSize=12.3,
            leading=16,
            textColor=BLUE,
            spaceBefore=3.5 * mm,
            spaceAfter=2 * mm,
            keepWithNext=True,
        ),
        "h3": ParagraphStyle(
            "H3Korean",
            parent=body,
            fontSize=10.2,
            leading=13.5,
            textColor=NAVY,
            spaceBefore=2.5 * mm,
            spaceAfter=1.6 * mm,
            keepWithNext=True,
        ),
        "bullet": ParagraphStyle(
            "BulletKorean",
            parent=body,
            leftIndent=4.2 * mm,
            firstLineIndent=-3.2 * mm,
            bulletIndent=0,
            spaceAfter=1.6 * mm,
        ),
        "number": ParagraphStyle(
            "NumberKorean",
            parent=body,
            leftIndent=5 * mm,
            firstLineIndent=-4 * mm,
            spaceAfter=1.6 * mm,
        ),
        "quote": ParagraphStyle(
            "QuoteKorean",
            parent=body,
            textColor=NAVY,
            leftIndent=2 * mm,
            rightIndent=2 * mm,
            spaceAfter=0,
        ),
        "table": ParagraphStyle(
            "TableKorean",
            parent=body,
            fontSize=config.table_size,
            leading=config.table_size + 2.5,
            spaceAfter=0,
            wordWrap="CJK",
        ),
        "table_head": ParagraphStyle(
            "TableHeadKorean",
            parent=body,
            fontSize=config.table_size,
            leading=config.table_size + 2.5,
            textColor=colors.white,
            spaceAfter=0,
            wordWrap="CJK",
        ),
        "footer": ParagraphStyle(
            "FooterKorean",
            parent=body,
            fontSize=6.8,
            leading=8,
            textColor=MUTED,
            spaceAfter=0,
        ),
    }
    return styles


def parse_table(lines: list[str]) -> list[list[str]]:
    rows: list[list[str]] = []
    for line in lines:
        cells = [cell.strip() for cell in line.strip().strip("|").split("|")]
        if cells and all(re.fullmatch(r":?-{3,}:?", cell.replace(" ", "")) for cell in cells):
            continue
        rows.append(cells)
    width = max(len(row) for row in rows)
    return [row + [""] * (width - len(row)) for row in rows]


def table_widths(column_count: int, available: float) -> list[float]:
    if column_count == 1:
        ratios = [1]
    elif column_count == 2:
        ratios = [0.33, 0.67]
    elif column_count == 3:
        ratios = [0.25, 0.36, 0.39]
    elif column_count == 4:
        ratios = [0.22, 0.26, 0.26, 0.26]
    else:
        ratios = [1 / column_count] * column_count
    return [available * ratio for ratio in ratios]


def code_box(code: str, font_name: str, config: RenderConfig, available: float):
    lines = code.rstrip().splitlines() or [""]
    size = config.code_size
    max_width = max(pdfmetrics.stringWidth(line, font_name, size) for line in lines)
    if max_width > available - 8 * mm:
        size = max(5.0, size * (available - 8 * mm) / max_width)
    code_style = ParagraphStyle(
        "CodeKorean",
        fontName=font_name,
        fontSize=size,
        leading=size + 2.2,
        textColor=INK,
        leftIndent=0,
        rightIndent=0,
        spaceAfter=0,
    )
    pre = Preformatted(code.rstrip(), code_style)
    box = Table([[pre]], colWidths=[available], hAlign="LEFT")
    box.setStyle(
        TableStyle(
            [
                ("BACKGROUND", (0, 0), (-1, -1), LIGHT),
                ("BOX", (0, 0), (-1, -1), 0.7, LINE),
                ("LEFTPADDING", (0, 0), (-1, -1), 4 * mm),
                ("RIGHTPADDING", (0, 0), (-1, -1), 4 * mm),
                ("TOPPADDING", (0, 0), (-1, -1), 3 * mm),
                ("BOTTOMPADDING", (0, 0), (-1, -1), 3 * mm),
            ]
        )
    )
    return box


def markdown_story(path: pathlib.Path, font_name: str, styles, config: RenderConfig, available: float):
    lines = path.read_text(encoding="utf-8").splitlines()
    story = []
    paragraph: list[str] = []
    first_heading = True

    def flush_paragraph() -> None:
        if not paragraph:
            return
        value = " ".join(item.strip() for item in paragraph).strip()
        if value:
            story.append(Paragraph(inline_markup(value, font_name), styles["body"]))
        paragraph.clear()

    i = 0
    while i < len(lines):
        line = lines[i]
        stripped = line.strip()

        if stripped == "<!-- PAGEBREAK -->":
            flush_paragraph()
            story.append(PageBreak())
            i += 1
            continue

        if stripped.startswith("```"):
            flush_paragraph()
            language = stripped[3:].strip()
            code_lines = []
            i += 1
            while i < len(lines) and not lines[i].strip().startswith("```"):
                code_lines.append(lines[i])
                i += 1
            i += 1
            story.append(code_box("\n".join(code_lines), font_name, config, available))
            story.append(Spacer(1, 3 * mm))
            continue

        if stripped.startswith("|") and stripped.endswith("|"):
            flush_paragraph()
            table_lines = []
            while i < len(lines) and lines[i].strip().startswith("|") and lines[i].strip().endswith("|"):
                table_lines.append(lines[i])
                i += 1
            rows = parse_table(table_lines)
            data = []
            for row_index, row in enumerate(rows):
                style = styles["table_head"] if row_index == 0 else styles["table"]
                data.append([Paragraph(inline_markup(cell, font_name), style) for cell in row])
            table = Table(
                data,
                colWidths=table_widths(len(rows[0]), available),
                repeatRows=1,
                hAlign="LEFT",
                splitByRow=True,
            )
            commands = [
                ("BACKGROUND", (0, 0), (-1, 0), NAVY),
                ("GRID", (0, 0), (-1, -1), 0.45, LINE),
                ("VALIGN", (0, 0), (-1, -1), "TOP"),
                ("LEFTPADDING", (0, 0), (-1, -1), 2 * mm),
                ("RIGHTPADDING", (0, 0), (-1, -1), 2 * mm),
                ("TOPPADDING", (0, 0), (-1, -1), 1.7 * mm),
                ("BOTTOMPADDING", (0, 0), (-1, -1), 1.7 * mm),
            ]
            for row_index in range(1, len(data)):
                if row_index % 2 == 0:
                    commands.append(("BACKGROUND", (0, row_index), (-1, row_index), LIGHT))
            table.setStyle(TableStyle(commands))
            story.extend([table, Spacer(1, 3 * mm)])
            continue

        heading = re.match(r"^(#{1,3})\s+(.+)$", stripped)
        if heading:
            flush_paragraph()
            level = len(heading.group(1))
            value = inline_markup(heading.group(2), font_name)
            if first_heading and level == 1:
                story.append(Paragraph(value, styles["title"]))
                story.append(HRFlowable(width="100%", thickness=1.3, color=BLUE, spaceAfter=5 * mm))
                first_heading = False
            else:
                story.append(Paragraph(value, styles[f"h{level}"]))
            i += 1
            continue

        if stripped == "---":
            flush_paragraph()
            story.append(HRFlowable(width="100%", thickness=0.5, color=LINE, spaceBefore=2 * mm, spaceAfter=3 * mm))
            i += 1
            continue

        if stripped.startswith(">"):
            flush_paragraph()
            value = stripped[1:].strip()
            quote = Table(
                [[Paragraph(inline_markup(value, font_name), styles["quote"])]],
                colWidths=[available],
                hAlign="LEFT",
            )
            quote.setStyle(
                TableStyle(
                    [
                        ("BACKGROUND", (0, 0), (-1, -1), PALE_BLUE),
                        ("LINEBEFORE", (0, 0), (0, -1), 3, BLUE),
                        ("LEFTPADDING", (0, 0), (-1, -1), 4 * mm),
                        ("RIGHTPADDING", (0, 0), (-1, -1), 3 * mm),
                        ("TOPPADDING", (0, 0), (-1, -1), 3 * mm),
                        ("BOTTOMPADDING", (0, 0), (-1, -1), 3 * mm),
                    ]
                )
            )
            story.extend([quote, Spacer(1, 3 * mm)])
            i += 1
            continue

        bullet = re.match(r"^\s*-\s+(.+)$", line)
        if bullet:
            flush_paragraph()
            # U+2022 from the selected TTC renders correctly but extracts as a
            # NUL in some PDF text engines. An en dash remains accessible.
            story.append(Paragraph("– " + inline_markup(bullet.group(1), font_name), styles["bullet"]))
            i += 1
            continue

        numbered = re.match(r"^\s*(\d+)\.\s+(.+)$", line)
        if numbered:
            flush_paragraph()
            story.append(
                Paragraph(
                    f"{numbered.group(1)}. " + inline_markup(numbered.group(2), font_name),
                    styles["number"],
                )
            )
            i += 1
            continue

        if not stripped:
            flush_paragraph()
            i += 1
            continue

        paragraph.append(line)
        i += 1

    flush_paragraph()
    return story


def build_document(kind: str, source: pathlib.Path, target: pathlib.Path, font_name: str) -> None:
    config = CONFIGS[kind]
    margin = config.margin_mm * mm
    available = A4[0] - 2 * margin
    styles = make_styles(font_name, config)
    title = "이승준 이력서" if kind == "resume" else "이승준 Backend Portfolio"

    doc = SimpleDocTemplate(
        str(target),
        pagesize=A4,
        leftMargin=margin,
        rightMargin=margin,
        topMargin=15 * mm,
        bottomMargin=15 * mm,
        title=title,
        author="이승준",
        subject="Evidence-backed backend resume and portfolio",
        creator="Codex-assisted ReportLab renderer",
        pageCompression=1,
        invariant=1,
    )

    def footer(canvas, document) -> None:
        canvas.saveState()
        canvas.setTitle(title)
        canvas.setAuthor("이승준")
        canvas.setSubject("Evidence-backed backend resume and portfolio")
        canvas.setFont(font_name, 6.8)
        canvas.setFillColor(MUTED)
        y = 8 * mm
        canvas.setStrokeColor(LINE)
        canvas.setLineWidth(0.4)
        canvas.line(margin, y + 4 * mm, A4[0] - margin, y + 4 * mm)
        canvas.drawString(margin, y, title)
        canvas.drawRightString(A4[0] - margin, y, str(document.page))
        canvas.restoreState()

    story = markdown_story(source, font_name, styles, config, available)
    doc.build(story, onFirstPage=footer, onLaterPages=footer)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--only", choices=("resume", "portfolio", "all"), default="all")
    args = parser.parse_args()

    verify_originals()
    font_name = register_font()
    jobs = {
        "resume": (FINAL_DIR / "이력서_완성본.md", FINAL_DIR / "이력서_완성본.pdf"),
        "portfolio": (FINAL_DIR / "포트폴리오_완성본.md", FINAL_DIR / "포트폴리오_완성본.pdf"),
    }
    selected = jobs if args.only == "all" else {args.only: jobs[args.only]}
    for kind, (source, target) in selected.items():
        if not source.is_file():
            raise FileNotFoundError(source)
        build_document(kind, source, target, font_name)
        print(f"wrote {target.relative_to(ROOT)} ({target.stat().st_size:,} bytes)")


if __name__ == "__main__":
    main()
