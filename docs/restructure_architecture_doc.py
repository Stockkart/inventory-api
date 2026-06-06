#!/usr/bin/env python3
"""Restructure VERTICAL_PLUGIN_ARCHITECTURE.md into a top-down flow."""

from __future__ import annotations

import re
from pathlib import Path

SRC = Path(__file__).resolve().parent / "VERTICAL_PLUGIN_ARCHITECTURE.md"
OUT = SRC

INTRO = """# StockKart Vertical Plugin Architecture v4

**Implementation guide for multi-vertical inventory (medical, apparel, cafe/F&B, sports, …)**

StockKart supports many industries through **one platform**: minimal core `Inventory`, **per-vertical extension collections** (`inventory_ext_medical`, …), and thin **plugins** coordinated by **`InventoryService`** in the product module. No separate orchestrator layer. No client-sent `verticalId` — the server resolves it from `Shop.verticalId`.

---

## Table of contents

**Core (read first)**

1. [Overview](#1-overview)
2. [System architecture](#2-system-architecture)
3. [Request flow and InventoryService](#3-request-flow-and-inventoryservice)
4. [Data model](#4-data-model)
5. [Plugin system](#5-plugin-system)
6. [Schema and UI](#6-schema-and-ui)
7. [Codebase layout](#7-codebase-layout)
8. [Shop isolation](#8-shop-isolation)
9. [Migration](#9-migration)
10. [Implementation roadmap](#10-implementation-roadmap)
11. [Adding a new vertical](#11-adding-a-new-vertical)
12. [Example: Cafe / F&B](#12-example-cafe--fb)

**Reference (detail when implementing)**

- [Search](#reference-search)
- [Analytics](#reference-analytics)
- [Operations and checklists](#reference-operations)
- [Edge cases and anti-patterns](#reference-edge-cases)
- [Example schemas (JSON)](#reference-example-schemas)
- [Document maintenance](#document-maintenance)

---
"""

SECTION_ALIASES = {
    "Purpose": "purpose",
    "Goals and principles": "goals",
    "Current state vs target state": "today_target",
    "Architecture overview": "arch_overview",
    "Core domain (minimal)": "core_domain",
    "Extension storage model": "extension",
    "Plugin engine and contract": "plugin_engine",
    "Product service layer (plugin coordination)": "product_service",
    "Cross-module access rules": "cross_module",
    "Ownership model (write/read/search)": "ownership",
    "Schema contract": "schema",
    "Search architecture": "search",
    "Shop extensions": "shop_ext",
    "Shared fields across verticals": "shared_fields",
    "Migration from today's Inventory": "migration",
    "Existing medical shop data (production rows)": "medical_migration",
    "Multi-tenant shop isolation": "shop_isolation",
    "Implementation roadmap": "roadmap",
    "Runtime flows": "runtime",
    "Playbook: adding a new vertical": "playbook",
    "Vertical versioning": "versioning",
    "Shop vertical switching (exception path)": "vertical_switch",
    "Scale and performance": "scale",
    "Testing checklist": "testing",
    "Deployment checklist": "deploy_check",
    "Anti-patterns": "anti_patterns",
    "Storage strategy comparison": "storage_cmp",
    "Appendix: field placement decision table": "field_placement",
    "Appendix: example schemas": "example_schemas",
    "Appendix: printable new-vertical checklist": "vertical_checklist",
    "Plugin module structure (abstract base + verticals)": "plugin_structure",
    "Collection ownership and API types": "collection_ownership",
    "Vertical-specific search (batchNo, warranty, …)": "vertical_search",
    "Mixed response handling and search complexity": "mixed_response",
    "Analytics across verticals": "analytics",
    "Example vertical: Cafe / F&B (menu, tokens, ingredients)": "cafe",
    "Deployment topology (monolith vs microservices)": "deploy_topology",
    "Hard problems and edge cases": "hard_problems",
    "Frontend resilience (read/search)": "frontend",
    "Appendix: low-level file structure and architecture": "file_structure",
    "Final goal": "final_goal",
    "Document maintenance": "doc_maint",
}


def parse_sections(text: str) -> dict[str, str]:
    parts = re.split(r"^## ", text, flags=re.MULTILINE)
    sections: dict[str, str] = {}
    for part in parts[1:]:
        if not part.strip():
            continue
        title, _, body = part.partition("\n")
        title = title.strip()
        key = SECTION_ALIASES.get(title, title.lower().replace(" ", "_"))
        sections[key] = body.strip()
    return sections


def strip_leading_hr(body: str) -> str:
    return re.sub(r"^---\s*\n", "", body).strip()


def rename_appendix(body: str) -> str:
    body = re.sub(r"^Appendix:\s*", "", body, flags=re.MULTILINE)
    return body


def extract_file_structure_parts(body: str) -> tuple[str, str]:
    """Return (architecture intro with image, rest of file structure)."""
    # Remove duplicate image block from file structure — we'll inject at top
    body = re.sub(
        r"### System architecture \(high level\).*?(?=\n---\n|\n### Backend Maven)",
        "",
        body,
        flags=re.DOTALL,
    )
    body = re.sub(r"<details>.*?</details>\s*", "", body, flags=re.DOTALL)
    body = re.sub(
        r"\*\*v4 call rule:\*\*.*?(?=\n---|\n### Backend Maven)",
        "",
        body,
        flags=re.DOTALL,
    )
    return body.strip()


def build_architecture_section(arch_overview: str) -> str:
    image_block = """![StockKart system architecture — frontend, backend modular monolith, MongoDB](assets/system-architecture-high-level.png)

*Nx frontend → REST `/api/v1/*` → Spring Boot → core modules → **`InventoryService`** (product hub) → pluginengine / plugins → MongoDB.*

"""
    # Keep layer table from arch_overview, drop duplicate ascii diagram at start
    overview = re.sub(r"^```.*?^```\s*", "", arch_overview, count=1, flags=re.DOTALL | re.MULTILINE)
    return image_block + overview.strip()


def build(s: dict[str, str]) -> str:
    chunks: list[str] = [INTRO]

    # 1 Overview
    chunks.append("## 1. Overview\n")
    chunks.append(strip_leading_hr(s.get("purpose", "")))
    chunks.append("\n\n### Goals and principles\n\n" + strip_leading_hr(s.get("goals", "")))
    chunks.append("\n\n### Today vs target\n\n" + strip_leading_hr(s.get("today_target", "")))

    # 2 System architecture
    chunks.append("\n\n---\n\n## 2. System architecture\n\n")
    chunks.append(build_architecture_section(s.get("arch_overview", "")))

    # 3 Request flow
    chunks.append("\n\n---\n\n## 3. Request flow and InventoryService\n\n")
    chunks.append(
        "**Rule:** Controller → module Service → (often) **`InventoryService`** → plugin → repository. "
        "Cross-module: **Service → Service** — never call another module's repository.\n\n"
    )
    chunks.append(strip_leading_hr(s.get("product_service", "")))
    chunks.append("\n\n### Cross-module access\n\n" + strip_leading_hr(s.get("cross_module", "")))
    chunks.append("\n\n### End-to-end flows\n\n" + strip_leading_hr(s.get("runtime", "")))
    chunks.append("\n\n### Write / read / search ownership\n\n" + strip_leading_hr(s.get("ownership", "")))

    # 4 Data model
    chunks.append("\n\n---\n\n## 4. Data model\n\n")
    chunks.append(strip_leading_hr(s.get("core_domain", "")))
    chunks.append("\n\n### Extension collections\n\n" + strip_leading_hr(s.get("extension", "")))
    chunks.append("\n\n### Collection ownership and API types\n\n" + strip_leading_hr(s.get("collection_ownership", "")))

    # 5 Plugin system
    chunks.append("\n\n---\n\n## 5. Plugin system\n\n")
    chunks.append(strip_leading_hr(s.get("plugin_engine", "")))
    chunks.append("\n\n### Module structure (pluginengine + plugins)\n\n" + strip_leading_hr(s.get("plugin_structure", "")))

    # 6 Schema
    chunks.append("\n\n---\n\n## 6. Schema and UI\n\n")
    chunks.append(strip_leading_hr(s.get("schema", "")))
    chunks.append("\n\n### Where to put a new field\n\n" + rename_appendix(strip_leading_hr(s.get("field_placement", ""))))

    # 7 Codebase layout
    fs = extract_file_structure_parts(s.get("file_structure", ""))
    chunks.append("\n\n---\n\n## 7. Codebase layout\n\n")
    chunks.append(fs)

    # 8 Shop isolation
    chunks.append("\n\n---\n\n## 8. Shop isolation\n\n")
    chunks.append(strip_leading_hr(s.get("shop_isolation", "")))

    # 9 Migration
    chunks.append("\n\n---\n\n## 9. Migration\n\n")
    chunks.append("### Phased plan (M1–M8)\n\n" + strip_leading_hr(s.get("migration", "")))
    chunks.append("\n\n### Existing medical production data\n\n" + strip_leading_hr(s.get("medical_migration", "")))

    # 10 Roadmap
    chunks.append("\n\n---\n\n## 10. Implementation roadmap\n\n")
    chunks.append(strip_leading_hr(s.get("roadmap", "")))

    # 11 Adding vertical
    chunks.append("\n\n---\n\n## 11. Adding a new vertical\n\n")
    chunks.append(strip_leading_hr(s.get("playbook", "")))
    chunks.append("\n\n### Launch checklist\n\n```\n" + strip_leading_hr(s.get("vertical_checklist", "")).strip("`") + "\n```")

    # 12 Cafe
    chunks.append("\n\n---\n\n## 12. Example: Cafe / F&B\n\n")
    cafe = s.get("cafe", "")
    cafe = re.sub(r"## Example vertical: Cafe / F&B \(menu, tokens, ingredients\)\s*", "", cafe)
    chunks.append(cafe.strip())

    # Reference sections
    chunks.append("\n\n---\n\n# Reference\n\n*Detailed topics for implementers — read when building search, analytics, or ops.*\n")

    ref = [
        ("Reference — Search", ["search", "vertical_search", "mixed_response", "shared_fields", "shop_ext"]),
        ("Reference — Analytics", ["analytics"]),
        ("Reference — Operations and checklists", ["scale", "testing", "deploy_check", "deploy_topology", "versioning", "vertical_switch", "frontend"]),
        ("Reference — Edge cases and anti-patterns", ["anti_patterns", "storage_cmp", "hard_problems"]),
        ("Reference — Example schemas (JSON)", ["example_schemas"]),
    ]

    for ref_title, keys in ref:
        chunks.append(f"\n\n## {ref_title}\n\n")
        for k in keys:
            if k in s and s[k].strip():
                chunks.append(strip_leading_hr(s[k]))
                chunks.append("\n\n")

    # Final goal + maintenance
    chunks.append("\n\n---\n\n## Summary\n\n")
    chunks.append(strip_leading_hr(s.get("final_goal", "")))
    chunks.append("\n\n---\n\n## Document maintenance\n\n")
    maint = s.get("doc_maint", "")
    maint = re.sub(r"- \*\*Version:\*\*.*", "- **Version:** 4.3", maint)
    chunks.append(maint.strip())
    chunks.append("\n\n*Last updated: 2026-06-06*\n")

    return re.sub(r"\n{4,}", "\n\n\n", "\n".join(chunks))


def main() -> None:
    text = SRC.read_text(encoding="utf-8")
    # Skip old header and TOC — rebuild from first ## Purpose or ##
    start = text.find("## Purpose")
    if start == -1:
        start = text.find("## Table of contents")
    if start != -1:
        text = text[start:]
    sections = parse_sections(text)
    missing = [k for k in SECTION_ALIASES.values() if k not in sections]
    if missing:
        print("Warning: missing sections:", missing)
    OUT.write_text(build(sections), encoding="utf-8")
    print(f"Restructured {OUT} ({OUT.stat().st_size:,} bytes)")


if __name__ == "__main__":
    main()
