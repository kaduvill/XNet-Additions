0.3.3
- Icons

0.3.2
- Diagnostic panel now has timing stats per channel, for easier navigation and sorting all connectors that has timing = N t
- Cleaned up a lot of selection stuff and ux

0.3.1
- Modoptions for panel defaults

0.2.0_ALPHA
- Added Batch Editing
- Added live Logic signal status below the Controller GUI
- Added Advanced Energy Channel (Adaptive and Manual Timing)
  - FluxNetwork compat

0.1.6
- Essentia Channel:
  - Removed special handling for oblivion jar
    - (Fixed in Thaumic Wonders unofficial 2.3.0)

0.1.5
- Removed unnecessary Forge event bus registration

0.1.4
- Added IC2 EU channel (first beta)
  - Transfers EU directly between IC2 energy providers and sinks.
  - Does not emulate IC2 cable networks, strict side rules, or per-machine I/O throughput limits.
  - Configurable default rates: 2048 EU/t normal, 1,048,576 EU/t advanced (1024^2).
  - Added IC2 Translations for ControllerGUI cleanliness when using EU channel

0.1.3
- Essentia channel:
  - Blacklist added
  - Increased default rates to 50/250, to motivate players to use longer timings and increase performance
  - Distribution mode added

0.1.2
- Respect Mekanism gas side rules for insertion and extraction
- Bring Mekanism Gas closer to ZNet Fluid Channel behavior:
  - Large filter support
  - Blacklist support
  - Simple phasing
  - True distribute mode

0.1.1
- Botania flowers support added
  - (some flowers won't operate without a linked spreader)
