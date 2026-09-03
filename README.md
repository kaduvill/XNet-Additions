# XNet: Additions

XNet: Additions expands XNet with additional resource channels and Controller GUI tools while preserving its familiar controls, routing, and efficient all-in-one cable network.

## Additional channels

- Mekanism Gas
- Thaumcraft Essentia
- Botania Mana
- IndustrialCraft 2 EU
- Advanced Energy (RF, including Flux Networks direct extraction)

Each channel can be disabled in the config.

### Advanced Energy

Advanced Energy is demand-driven: scheduled inserters request energy only when their targets can accept it, and extractors are used only to satisfy that demand. Inserters support configurable timing and an adaptive fallback while idle. Extractors can optionally drain energy pushed into XNet's connector buffer, and Flux Networks can be used as direct energy sources.



## Controller Power Tools

- **Diagnostics** — profile Controller tick time and inspect channel workload, timing groups, and scheduled connectors.
- **Health** — scan for common configuration and target-access problems, then jump directly to affected connectors.
- **Logic** — view logic signals, sources, and references with direct connector navigation.
- **Recent** — quickly return to recently opened connectors.
- **Side Prober** — inspect compatible resource access on every side of a connected block.
- **Logic Status** — see active signals in the Controller GUI and through optional The One Probe integration.
- **Batch Edit** — select multiple connectors and apply shared settings in one operation.
- **Presets** — save and reuse connector configurations across networks.
- **Connector Pins** — keep important connectors at the top of the Controller list.
- **Remote Connector Settings** — open and edit connector name and side-configuration directly from the Controller.


Requires [ZNet](https://www.curseforge.com/minecraft/mc-mods/znet), a maintained Minecraft 1.12.2 fork of XNet with many bug fixes.