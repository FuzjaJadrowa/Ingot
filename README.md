# Ingot
**Ingot** is an experimental mod that adds new world save system for Minecraft.
It replaces Anvil Region files with Ingot Region files for new worlds.

## ⚛️ Features
- Saves blocks, entities and POIs in `.ir` region files.
- Compresses chunk data with Zstandard.
- Compacts regions in the background to delete unused records and make chunks smaller.

New worlds use automatically Ingot storage system.
Worlds that already contain Anvil `.mca` or `.mcc` region data continue to use Anvil.
Ingot refuses to mix the two formats and does not currently convert existing worlds between those two formats.

## 💾 Storage
Ingot keeps the normal Minecraft world layout and stores each region in an `ingot` subdirectory.
Other world files, including `level.dat` and player data, stay the same.
Dimensions added by other mods keep their normal directory paths.
Worlds containing `.ir` files need Ingot to load those chunks. 
Minecraft and external tools that expect `.mca` files cannot read Ingot regions.

## 🐞 Known issues
- There is no conversion between Anvil and Ingot.
- Normal launcher was tested, production dedicated servers, large modpacks, and long-running performance benchmarks needs testing. Report any bugs using Github Issues.

## 📜 License
This project is distributed under the **PIF-1.0** license.