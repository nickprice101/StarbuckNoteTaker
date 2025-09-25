#!/usr/bin/env python3
"""Align ELF PT_LOAD segment offsets to 16 KiB boundaries."""

from __future__ import annotations

import argparse
import struct
from dataclasses import dataclass
from typing import Dict, List, Sequence, Tuple

PAGE_SIZE = 16 * 1024
PT_LOAD = 1


class ElfError(RuntimeError):
    """Raised when the input file is not a supported ELF binary."""


@dataclass
class ProgramHeader:
    index: int
    p_type: int
    p_offset: int
    p_vaddr: int
    p_paddr: int
    p_filesz: int
    p_memsz: int
    p_flags: int
    p_align: int


@dataclass
class SectionHeader:
    index: int
    sh_name: int
    sh_type: int
    sh_flags: int
    sh_addr: int
    sh_offset: int
    sh_size: int
    sh_link: int
    sh_info: int
    sh_addralign: int
    sh_entsize: int


@dataclass
class ElfMetadata:
    is_64_bit: bool
    is_little_endian: bool
    e_phoff: int
    e_phentsize: int
    e_phnum: int
    e_shoff: int
    e_shentsize: int
    e_shnum: int


@dataclass
class PaddingPlan:
    offsets: Sequence[int]
    per_offset_padding: Dict[int, int]
    prefix_sums: Sequence[Tuple[int, int]]
    total_padding: int


def _alignment_mask(size: int) -> int:
    return size - 1


def _align_up(value: int, alignment: int) -> int:
    mask = _alignment_mask(alignment)
    return (value + mask) & ~mask


def _parse_metadata(data: bytearray) -> ElfMetadata:
    if data[:4] != b"\x7fELF":
        raise ElfError("File is not an ELF binary")

    ei_class = data[4]
    ei_data = data[5]

    if ei_class not in (1, 2):
        raise ElfError(f"Unsupported EI_CLASS {ei_class}")
    if ei_data not in (1, 2):
        raise ElfError(f"Unsupported EI_DATA {ei_data}")

    is_64_bit = ei_class == 2
    is_little_endian = ei_data == 1
    endian_prefix = "<" if is_little_endian else ">"

    if is_64_bit:
        e_phoff = struct.unpack_from(f"{endian_prefix}Q", data, 32)[0]
        e_shoff = struct.unpack_from(f"{endian_prefix}Q", data, 40)[0]
        e_phentsize, e_phnum = struct.unpack_from(f"{endian_prefix}HH", data, 54)
        e_shentsize, e_shnum = struct.unpack_from(f"{endian_prefix}HH", data, 58)
    else:
        e_phoff = struct.unpack_from(f"{endian_prefix}I", data, 28)[0]
        e_shoff = struct.unpack_from(f"{endian_prefix}I", data, 32)[0]
        e_phentsize, e_phnum = struct.unpack_from(f"{endian_prefix}HH", data, 42)
        e_shentsize, e_shnum = struct.unpack_from(f"{endian_prefix}HH", data, 46)

    return ElfMetadata(
        is_64_bit=is_64_bit,
        is_little_endian=is_little_endian,
        e_phoff=e_phoff,
        e_phentsize=e_phentsize,
        e_phnum=e_phnum,
        e_shoff=e_shoff,
        e_shentsize=e_shentsize,
        e_shnum=e_shnum,
    )


def _parse_program_headers(data: bytearray, meta: ElfMetadata) -> List[ProgramHeader]:
    headers: List[ProgramHeader] = []
    if meta.e_phoff == 0 or meta.e_phnum == 0:
        return headers

    endian_prefix = "<" if meta.is_little_endian else ">"

    for index in range(meta.e_phnum):
        offset = meta.e_phoff + index * meta.e_phentsize
        if meta.is_64_bit:
            p_type, p_flags, p_offset, p_vaddr, p_paddr, p_filesz, p_memsz, p_align = struct.unpack_from(
                f"{endian_prefix}IIQQQQQQ", data, offset
            )
        else:
            p_type, p_offset, p_vaddr, p_paddr, p_filesz, p_memsz, p_flags, p_align = struct.unpack_from(
                f"{endian_prefix}IIIIIIII", data, offset
            )

        headers.append(
            ProgramHeader(
                index=index,
                p_type=p_type,
                p_offset=p_offset,
                p_vaddr=p_vaddr,
                p_paddr=p_paddr,
                p_filesz=p_filesz,
                p_memsz=p_memsz,
                p_flags=p_flags,
                p_align=p_align,
            )
        )

    return headers


def _parse_section_headers(data: bytearray, meta: ElfMetadata) -> List[SectionHeader]:
    headers: List[SectionHeader] = []
    if meta.e_shoff == 0 or meta.e_shnum == 0:
        return headers

    endian_prefix = "<" if meta.is_little_endian else ">"

    for index in range(meta.e_shnum):
        offset = meta.e_shoff + index * meta.e_shentsize
        if meta.is_64_bit:
            sh_name, sh_type, sh_flags, sh_addr, sh_offset, sh_size, sh_link, sh_info, sh_addralign, sh_entsize = struct.unpack_from(
                f"{endian_prefix}IIQQQQIIQQ", data, offset
            )
        else:
            sh_name, sh_type, sh_flags, sh_addr, sh_offset, sh_size, sh_link, sh_info, sh_addralign, sh_entsize = struct.unpack_from(
                f"{endian_prefix}IIIIIIIIII", data, offset
            )

        headers.append(
            SectionHeader(
                index=index,
                sh_name=sh_name,
                sh_type=sh_type,
                sh_flags=sh_flags,
                sh_addr=sh_addr,
                sh_offset=sh_offset,
                sh_size=sh_size,
                sh_link=sh_link,
                sh_info=sh_info,
                sh_addralign=sh_addralign,
                sh_entsize=sh_entsize,
            )
        )

    return headers


def _build_padding_plan(program_headers: Sequence[ProgramHeader]) -> PaddingPlan:
    load_segments = sorted(
        [ph for ph in program_headers if ph.p_type == PT_LOAD],
        key=lambda ph: ph.p_offset,
    )

    padding_by_offset: Dict[int, int] = {}
    cumulative = 0

    for ph in load_segments:
        desired = _align_up(ph.p_offset + cumulative, PAGE_SIZE)
        padding = desired - (ph.p_offset + cumulative)
        if padding > 0:
            padding_by_offset[ph.p_offset] = padding_by_offset.get(ph.p_offset, 0) + padding
            cumulative += padding

    offsets = sorted(padding_by_offset)
    prefix_sums: List[Tuple[int, int]] = []
    running = 0
    for offset in offsets:
        running += padding_by_offset[offset]
        prefix_sums.append((offset, running))

    total_padding = running
    return PaddingPlan(
        offsets=offsets,
        per_offset_padding=padding_by_offset,
        prefix_sums=prefix_sums,
        total_padding=total_padding,
    )


def _padding_before(plan: PaddingPlan, file_offset: int) -> int:
    total = 0
    for offset, prefix_total in plan.prefix_sums:
        if offset <= file_offset:
            total = prefix_total
        else:
            break
    return total


def _copy_with_padding(data: bytearray, plan: PaddingPlan) -> bytearray:
    if plan.total_padding == 0:
        return bytearray(data)

    result = bytearray(len(data) + plan.total_padding)
    src_index = 0
    dst_index = 0
    for offset in plan.offsets:
        length = offset - src_index
        if length < 0:
            raise ElfError("Overlapping padding plan detected")
        result[dst_index : dst_index + length] = data[src_index : src_index + length]
        src_index += length
        dst_index += length + plan.per_offset_padding[offset]
    # copy remaining tail
    result[dst_index : dst_index + len(data) - src_index] = data[src_index:]
    return result


def _write_metadata(data: bytearray, meta: ElfMetadata, plan: PaddingPlan) -> None:
    endian_prefix = "<" if meta.is_little_endian else ">"

    new_e_phoff = meta.e_phoff + _padding_before(plan, meta.e_phoff)
    new_e_shoff = meta.e_shoff + _padding_before(plan, meta.e_shoff) if meta.e_shoff != 0 else 0

    if meta.is_64_bit:
        struct.pack_into(f"{endian_prefix}Q", data, 32, new_e_phoff)
        struct.pack_into(f"{endian_prefix}Q", data, 40, new_e_shoff)
    else:
        struct.pack_into(f"{endian_prefix}I", data, 28, new_e_phoff)
        struct.pack_into(f"{endian_prefix}I", data, 32, new_e_shoff)


def _write_program_headers(data: bytearray, meta: ElfMetadata, plan: PaddingPlan, headers: Sequence[ProgramHeader]) -> None:
    endian_prefix = "<" if meta.is_little_endian else ">"
    ph_table_offset = meta.e_phoff + _padding_before(plan, meta.e_phoff)

    for ph in headers:
        new_offset = ph.p_offset + _padding_before(plan, ph.p_offset)
        new_align = ph.p_align
        if ph.p_type == PT_LOAD:
            new_align = max(new_align, PAGE_SIZE)

        entry_offset = ph_table_offset + ph.index * meta.e_phentsize
        if meta.is_64_bit:
            struct.pack_into(
                f"{endian_prefix}IIQQQQQQ",
                data,
                entry_offset,
                ph.p_type,
                ph.p_flags,
                new_offset,
                ph.p_vaddr,
                ph.p_paddr,
                ph.p_filesz,
                ph.p_memsz,
                new_align,
            )
        else:
            struct.pack_into(
                f"{endian_prefix}IIIIIIII",
                data,
                entry_offset,
                ph.p_type,
                new_offset,
                ph.p_vaddr,
                ph.p_paddr,
                ph.p_filesz,
                ph.p_memsz,
                ph.p_flags,
                new_align,
            )


def _write_section_headers(data: bytearray, meta: ElfMetadata, plan: PaddingPlan, headers: Sequence[SectionHeader]) -> None:
    if not headers:
        return

    endian_prefix = "<" if meta.is_little_endian else ">"
    table_offset = meta.e_shoff + _padding_before(plan, meta.e_shoff)

    for sh in headers:
        new_offset = sh.sh_offset + _padding_before(plan, sh.sh_offset)
        entry_offset = table_offset + sh.index * meta.e_shentsize
        if meta.is_64_bit:
            struct.pack_into(
                f"{endian_prefix}IIQQQQIIQQ",
                data,
                entry_offset,
                sh.sh_name,
                sh.sh_type,
                sh.sh_flags,
                sh.sh_addr,
                new_offset,
                sh.sh_size,
                sh.sh_link,
                sh.sh_info,
                sh.sh_addralign,
                sh.sh_entsize,
            )
        else:
            struct.pack_into(
                f"{endian_prefix}IIIIIIIIII",
                data,
                entry_offset,
                sh.sh_name,
                sh.sh_type,
                sh.sh_flags,
                sh.sh_addr,
                new_offset,
                sh.sh_size,
                sh.sh_link,
                sh.sh_info,
                sh.sh_addralign,
                sh.sh_entsize,
            )


def align_file(path: str) -> None:
    with open(path, "rb") as source:
        original = bytearray(source.read())

    meta = _parse_metadata(original)
    program_headers = _parse_program_headers(original, meta)
    if not program_headers:
        raise ElfError("ELF file does not contain program headers")

    plan = _build_padding_plan(program_headers)

    aligned = _copy_with_padding(original, plan)
    _write_metadata(aligned, meta, plan)
    _write_program_headers(aligned, meta, plan, program_headers)
    section_headers = _parse_section_headers(original, meta)
    _write_section_headers(aligned, meta, plan, section_headers)

    with open(path, "wb") as target:
        target.write(aligned)


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("elf", nargs="+", help="Path(s) to ELF shared objects to align in-place")
    args = parser.parse_args(argv)

    for path in args.elf:
        align_file(path)
    return 0


if __name__ == "__main__":  # pragma: no cover
    raise SystemExit(main())
