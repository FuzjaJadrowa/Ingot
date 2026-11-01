from __future__ import annotations

import argparse
import csv
import hashlib
import random
import re
import shutil
import struct
import sys
import tempfile
import zlib
from dataclasses import dataclass
from pathlib import Path

SECTOR_BYTES = 4096
HEADER_BYTES = 2 * SECTOR_BYTES
MAX_RAW_BYTES = 64 * 1024 * 1024
REGION_NAME = re.compile(r"r\.(-?\d+)\.(-?\d+)\.mca$")
DATASET_NAMES = ("vanilla", "block_entities", "low_compressibility")

class InvalidNbt(ValueError):
    pass

class NbtReader:
    def __init__(self, data: bytes):
        self.data = data
        self.pos = 0

    def take(self, count: int) -> bytes:
        if count < 0 or self.pos + count > len(self.data):
            raise InvalidNbt("NBT field extends beyond end of payload")
        result = self.data[self.pos:self.pos + count]
        self.pos += count
        return result

    def u8(self) -> int:
        return self.take(1)[0]

    def i16(self) -> int:
        return struct.unpack(">h", self.take(2))[0]

    def u16(self) -> int:
        return struct.unpack(">H", self.take(2))[0]

    def i32(self) -> int:
        return struct.unpack(">i", self.take(4))[0]

    def string(self) -> str:
        try:
            return self.take(self.u16()).decode("utf-8")
        except UnicodeDecodeError as exc:
            raise InvalidNbt("Invalid UTF-8 in NBT name") from exc

    def length(self, item_size: int = 1) -> int:
        count = self.i32()
        if count < 0 or count > 10_000_000 or count * item_size > len(self.data) - self.pos:
            raise InvalidNbt(f"Invalid NBT collection length: {count}")
        return count

    def skip_payload(self, tag_type: int, depth: int = 0) -> None:
        if depth > 512:
            raise InvalidNbt("NBT nesting is too deep")
        fixed = {1: 1, 2: 2, 3: 4, 4: 8, 5: 4, 6: 8}
        if tag_type in fixed:
            self.take(fixed[tag_type])
        elif tag_type == 7:
            self.take(self.length())
        elif tag_type == 8:
            self.take(self.u16())
        elif tag_type == 9:
            element_type = self.u8()
            count = self.length()
            if element_type == 0 and count:
                raise InvalidNbt("Non-empty NBT list has TAG_End element type")
            for _ in range(count):
                self.skip_payload(element_type, depth + 1)
        elif tag_type == 10:
            while True:
                child_type = self.u8()
                if child_type == 0:
                    break
                if child_type > 12:
                    raise InvalidNbt(f"Unknown NBT tag type: {child_type}")
                self.string()
                self.skip_payload(child_type, depth + 1)
        elif tag_type == 11:
            self.take(self.length(4) * 4)
        elif tag_type == 12:
            self.take(self.length(8) * 8)
        else:
            raise InvalidNbt(f"Unknown NBT tag type: {tag_type}")

    def count_block_entities(self) -> int:
        if self.u8() != 10:
            raise InvalidNbt("Chunk root is not a compound tag")
        self.string()
        count = 0
        while True:
            tag_type = self.u8()
            if tag_type == 0:
                break
            if tag_type > 12:
                raise InvalidNbt(f"Unknown NBT tag type: {tag_type}")
            name = self.string()
            if name in ("block_entities", "TileEntities") and tag_type == 9:
                element_type = self.u8()
                length = self.length()
                if element_type == 10:
                    count += length
                for _ in range(length):
                    self.skip_payload(element_type, 1)
            else:
                self.skip_payload(tag_type, 1)
        if self.pos != len(self.data):
            raise InvalidNbt(f"{len(self.data) - self.pos} trailing bytes after root tag")
        return count

@dataclass(frozen=True)
class ChunkSample:
    region_file: Path
    dimension: str
    chunk_x: int
    chunk_z: int
    slot: int
    block_entities: int
    zlib_ratio: float

def unpack_record(region_file: Path, slot: int, data: bytes | None = None) -> tuple[bytes, int]:
    if data is None:
        data = region_file.read_bytes()
    if len(data) < HEADER_BYTES:
        raise ValueError("region file is shorter than its 8 KiB header")
    location = struct.unpack_from(">I", data, slot * 4)[0]
    sector_offset = location >> 8
    sector_count = location & 0xFF
    if sector_offset == 0 or sector_count == 0:
        raise ValueError("empty location table entry")
    start = sector_offset * SECTOR_BYTES
    if sector_offset < 2 or start + 5 > len(data):
        raise ValueError("chunk sector points outside the region file")
    record_length = struct.unpack_from(">I", data, start)[0]
    if record_length < 1 or record_length + 4 > sector_count * SECTOR_BYTES:
        raise ValueError("invalid chunk record length")
    if start + 4 + record_length > len(data):
        raise ValueError("chunk record extends beyond end of region file")
    compression_byte = data[start + 4]
    external = bool(compression_byte & 0x80)
    compression_type = compression_byte & 0x7F
    if external:
        region_match = REGION_NAME.fullmatch(region_file.name)
        if not region_match:
            raise ValueError("invalid region filename")
        region_x, region_z = map(int, region_match.groups())
        chunk_x = region_x * 32 + slot % 32
        chunk_z = region_z * 32 + slot // 32
        external_path = region_file.parent / f"c.{chunk_x}.{chunk_z}.mcc"
        if not external_path.is_file():
            raise ValueError(f"external chunk payload is missing: {external_path.name}")
        payload = external_path.read_bytes()
    else:
        payload = data[start + 5:start + 4 + record_length]

    if compression_type in (1, 2):
        window_bits = zlib.MAX_WBITS | 16 if compression_type == 1 else zlib.MAX_WBITS
        inflater = zlib.decompressobj(window_bits)
        raw = inflater.decompress(payload, MAX_RAW_BYTES + 1)
        if len(raw) > MAX_RAW_BYTES or inflater.unconsumed_tail:
            raise ValueError(f"decompressed chunk exceeds {MAX_RAW_BYTES} byte limit")
        if not inflater.eof:
            raise ValueError("truncated compressed chunk payload")
        if inflater.unused_data:
            raise ValueError("unexpected bytes after compressed chunk payload")
    elif compression_type == 3:
        raw = payload
    else:
        raise ValueError(f"unsupported Anvil compression ID {compression_type}")
    if not raw or len(raw) > MAX_RAW_BYTES:
        raise ValueError(f"raw chunk size is outside benchmark limits: {len(raw)} bytes")
    return raw, compression_type


def region_directories(world: Path) -> list[tuple[Path, str]]:
    candidates: list[tuple[Path, str]] = []
    roots = [
        (world / "region", "overworld"),
        (world / "DIM-1" / "region", "the_nether"),
        (world / "DIM1" / "region", "the_end"),
    ]
    dimensions = world / "dimensions"
    if dimensions.is_dir():
        for region_dir in dimensions.glob("*/*/region"):
            relative = region_dir.parent.relative_to(dimensions).as_posix()
            roots.append((region_dir, relative.replace("/", "_")))
    for path, name in roots:
        if path.is_dir():
            candidates.append((path, name))
    return candidates

def scan_world(world: Path) -> tuple[list[ChunkSample], int]:
    samples: list[ChunkSample] = []
    skipped = 0
    for region_dir, dimension in region_directories(world):
        for region_file in sorted(region_dir.glob("r.*.*.mca")):
            match = REGION_NAME.fullmatch(region_file.name)
            if not match:
                continue
            region_x, region_z = map(int, match.groups())
            try:
                region_data = region_file.read_bytes()
                header = region_data[:HEADER_BYTES]
                if len(region_data) < HEADER_BYTES:
                    raise ValueError("region file is shorter than its 8 KiB header")
            except OSError as exc:
                print(f"Skipped region file {region_file}: {exc}", file=sys.stderr)
                skipped += 1
                continue
            for slot in range(1024):
                location = struct.unpack_from(">I", header, slot * 4)[0]
                if location == 0:
                    continue
                chunk_x = region_x * 32 + slot % 32
                chunk_z = region_z * 32 + slot // 32
                try:
                    raw, _compression = unpack_record(region_file, slot, region_data)
                    block_entities = NbtReader(raw).count_block_entities()
                    ratio = len(zlib.compress(raw, level=6)) / len(raw)
                    samples.append(ChunkSample(region_file, dimension, chunk_x, chunk_z,
                                               slot, block_entities, ratio))
                except (OSError, EOFError, zlib.error, InvalidNbt, ValueError) as exc:
                    print(f"Skipped chunk {chunk_x},{chunk_z} ({region_file.name}): {exc}", file=sys.stderr)
                    skipped += 1
    return samples, skipped

def choose_samples(samples: list[ChunkSample], count: int, seed: int) -> dict[str, list[ChunkSample]]:
    block_entity_pool = sorted((s for s in samples if s.block_entities > 0),
                               key=lambda s: (s.block_entities, -s.zlib_ratio), reverse=True)
    if len(block_entity_pool) < count:
        raise ValueError(f"Found only {len(block_entity_pool)} chunks with block entities; {count} are required. "
                         "Build a larger storage area and save the world again.")
    block_entities = block_entity_pool[:count]
    used = {(s.region_file, s.slot) for s in block_entities}
    remaining = [s for s in samples if (s.region_file, s.slot) not in used]
    low_pool = sorted(remaining, key=lambda s: (s.zlib_ratio, -s.block_entities), reverse=True)
    if len(low_pool) < count:
        raise ValueError(f"Not enough remaining chunks for low_compressibility: {count} are required.")
    low_compressibility = low_pool[:count]
    used.update((s.region_file, s.slot) for s in low_compressibility)
    vanilla_pool = [s for s in samples if s.block_entities == 0 and (s.region_file, s.slot) not in used]
    if len(vanilla_pool) < count:
        raise ValueError(f"Found only {len(vanilla_pool)} chunks without block entities; {count} are required. "
                         "Generate a larger area of terrain.")
    vanilla = random.Random(seed).sample(vanilla_pool, count)
    return {"vanilla": vanilla, "block_entities": block_entities,
            "low_compressibility": low_compressibility}

def export_dataset(world: Path, output: Path, selected: dict[str, list[ChunkSample]], seed: int) -> None:
    output.mkdir(parents=True, exist_ok=True)
    if any(output.rglob("*.nbt")) or (output / "manifest.csv").exists():
        raise ValueError(f"Output directory {output} already contains a dataset; choose an empty directory to avoid overwriting files.")

    manifest_rows: list[dict[str, object]] = []
    with tempfile.TemporaryDirectory(prefix=".dataset-build-", dir=output) as temp_name:
        temp = Path(temp_name)
        for category in DATASET_NAMES:
            (temp / category).mkdir()
            by_region: dict[Path, list[ChunkSample]] = {}
            for sample in selected[category]:
                by_region.setdefault(sample.region_file, []).append(sample)
            index = 0
            for region_file, region_samples in by_region.items():
                region_data = region_file.read_bytes()
                for sample in region_samples:
                    index += 1
                    raw, _compression = unpack_record(region_file, sample.slot, region_data)
                    filename = f"{index:04d}_{sample.dimension}_c{sample.chunk_x}_{sample.chunk_z}.nbt"
                    (temp / category / filename).write_bytes(raw)
                    manifest_rows.append({
                        "dataset": category,
                        "file": f"{category}/{filename}",
                        "dimension": sample.dimension,
                        "chunk_x": sample.chunk_x,
                        "chunk_z": sample.chunk_z,
                        "source_region": sample.region_file.relative_to(world).as_posix(),
                        "raw_bytes": len(raw),
                        "block_entities": sample.block_entities,
                        "zlib_ratio": f"{sample.zlib_ratio:.6f}",
                        "sha256": hashlib.sha256(raw).hexdigest(),
                        "selection_seed": seed,
                    })
        with (temp / "manifest.csv").open("w", newline="", encoding="utf-8") as stream:
            writer = csv.DictWriter(stream, fieldnames=list(manifest_rows[0]))
            writer.writeheader()
            writer.writerows(manifest_rows)

        for category in DATASET_NAMES:
            target = output / category
            if target.exists():
                if not target.is_dir() or any(target.iterdir()):
                    raise ValueError(f"Target directory is not empty: {target}")
                target.rmdir()
            shutil.move(str(temp / category), str(target))
        shutil.move(str(temp / "manifest.csv"), str(output / "manifest.csv"))

def main() -> int:
    parser = argparse.ArgumentParser(description="Export real Minecraft Java chunk NBT from a copied world.")
    parser.add_argument("world", type=Path, help="copied Minecraft world directory containing level.dat")
    parser.add_argument("--output", type=Path, default=Path("datasets"), help="output directory (default: ./datasets)")
    parser.add_argument("--samples", type=int, default=50, help="number of chunks per dataset (default: 50)")
    parser.add_argument("--seed", type=int, default=20261101, help="fixed random seed for vanilla chunk selection")
    args = parser.parse_args()

    world = args.world.expanduser().resolve()
    output = args.output.expanduser().resolve()
    if args.samples < 1:
        parser.error("--samples must be a positive integer")
    if not world.is_dir() or not (world / "level.dat").is_file():
        parser.error("world must point to a Minecraft world directory containing level.dat")
    if output == world or world in output.parents:
        parser.error("output must not be inside the source world")

    print(f"Reading world: {world}")
    chunks, skipped = scan_world(world)
    print(f"Valid chunks read: {len(chunks)}; skipped: {skipped}")
    try:
        selected = choose_samples(chunks, args.samples, args.seed)
        export_dataset(world, output, selected, args.seed)
    except (OSError, ValueError) as exc:
        print(f"Dataset was not created: {exc}", file=sys.stderr)
        return 1

    print(f"Done: {args.samples} chunks in each of {', '.join(DATASET_NAMES)}")
    print(f"Selection manifest: {output / 'manifest.csv'}")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())