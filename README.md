# Ingot
**Ingot** is an experimental world storage mod for Minecraft. It replaces Anvil region storage with Ingot Region files for new worlds.

## ⚛️ Features
- Stores terrain, block entities, entities, and POIs in `.ir` region files.
- Compresses chunk data with Zstandard.
- Preserves serialized Minecraft NBT, including data added by other mods.
- Detects damaged records and fails explicitly instead of treating them as empty chunks.
- Compacts regions in the background to reclaim space left by overwritten chunks.
- Recovers from a truncated tail and from interrupted compaction without guessing.

New worlds use Ingot storage system. Worlds that already contain Anvil `.mca` or `.mcc` region data continue to use Anvil. Ingot refuses to mix the two formats and does not currently convert existing worlds.

## 💾 Storage
Ingot keeps the usual Minecraft world layout and stores each region type in an `ingot` subdirectory. Other world files, including `level.dat` and player data, stay where they always were. Dimensions added by other mods keep their normal directory paths.
Worlds containing `.ir` files need Ingot to load those chunks. Minecraft and external tools that expect `.mca` files cannot read Ingot regions.

## 🐞 Known issues
- There is no conversion between Anvil and Ingot.
- Normal launcher works, production dedicated servers, large modpacks, and long-running performance benchmarks needs verification.

## 📜 License
This project is distributed under the **PIF-1.0** license.