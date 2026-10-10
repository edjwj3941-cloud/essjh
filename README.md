# The Last Spark - Paper 1.21.11 plugin

15 one-of-a-kind relics. Only **one of each can exist in the world**. The key to them all is a nether star
called **The Last Spark**, which consumes someone nearby and forges you one of the others.

## Build and install

You need **JDK 21** and **Maven**.

    ./build.sh        (Linux / macOS)
    build.bat         (Windows)

That produces `target/LastSpark.jar`. Drop it in your server's `plugins/` folder and start a Paper 1.21.11 server.
You can also open the folder as a Maven project in IntelliJ IDEA and run `package`.

> This project was written without access to the Paper API from the build environment, so it has been
> syntax-checked but **not compiled against Paper or run on a server**. If `mvn package` reports an error,
> it will be a small API-name mismatch - send me the message and I'll fix it.

## Getting started

    /spark give last_spark          forge The Last Spark for yourself (op only)
    /spark list                     which relics exist, and where each was last seen
    /spark give <relic> [player]    forge any relic that doesn't exist yet
    /spark reset <relic|all>        free a relic (every copy in the world turns to dust)
    /spark reload                   reload config.yml

Permission: `lastspark.admin` (ops by default).

## The relics

### Armor
| Relic | Base | Ability |
|---|---|---|
| Eclipse Crown | Netherite helmet | Night Vision + Water Breathing; 30% chance to dodge any projectile |
| Phoenix Plate | Netherite chestplate | Fire Resistance; once per 5 min a killing blow is undone - you rise at half health in a burst of flame |
| Tempest Leggings | Netherite leggings | Speed II, Jump Boost II, immune to lightning |
| Voidstep Boots | Netherite boots | No fall damage; falling into the void throws you back to the last solid ground |

### Weapons
| Relic | Base | Ability |
|---|---|---|
| Thunderclap Mace | Mace | A falling smash calls lightning, deals bonus damage and blasts everything around the target |
| Wind Warden's Maul | Mace | Right-click: **Gale Leap** skyward (no fall damage for 8s). Smash sends nearby enemies flying |
| Soulreaver | Netherite sword | Lifesteal (25% of damage dealt); kills grant Absorption |
| Executioner's Axe | Netherite axe | +75% damage on foes under 35% health; right-click **Cleave** (spinning area strike) |
| Skyfall Bow | Bow | A fully drawn arrow that lands calls down a ring of arrows from above |
| Shadowfang Dagger | Diamond sword | Backstabs deal 2.5x; right-click **Shadow Step** blinks behind your target and guarantees the next backstab |

### Utility
| Relic | Base | Ability |
|---|---|---|
| **The Last Spark** | Nether star | Right-click: consume the nearest person (or mob) within 6 blocks and receive a random relic that doesn't exist yet. Sneak + right-click cycles which relic it will forge |
| Waystone Shard | Echo shard | Sneak + right-click binds a waypoint to the shard; right-click, stand still 3s, teleport there |
| Tether Hook | Fishing rod | Cast at a block and the line hauls you to it; hook a creature to yank it to you |
| Lifebloom Charm | Heart of the sea | Right-click: heal and cleanse yourself and nearby players, plus Regeneration |
| Quarry Pickaxe | Netherite pickaxe | Haste II while held; mines a 3x3 area (sneak for a single block) |

## How "only one of each" works

- Relics only come into being through `/spark give` or The Last Spark, and both refuse if that relic already exists.
- Each relic carries a hidden id and a serial number; the plugin remembers the one valid serial per relic
  (`plugins/LastSpark/data.yml`).
- Dropped relics are invulnerable, glow, and never despawn - fire, lava, cacti, explosions and timers can't take them.
- If a relic is genuinely destroyed (it falls into the void) it becomes available to forge again.
- Every 5 seconds, and whenever someone joins, opens a container, picks something up or uses creative mode, the plugin
  scans online players' inventories and ender chests, open containers and loaded dropped items. Exact clones and stale
  copies are deleted ("crumbles to dust"). If two copies are found, the first one found keeps existing.
- Relics can't be used in crafting grids.

**Honest limits:** a relic sitting in a chest nobody has opened, or in an offline player's inventory, can't be
inspected. A clone hidden there is caught as soon as it is opened or its owner logs in. If an admin `/clear`s a relic
or it is lost some other way the plugin can't see, use `/spark reset <relic>` to free it.

## Choosing each relic's look

Open `plugins/LastSpark/config.yml`. For every relic you can change the **name**, the **base material** (any item),
the **lore**, the **enchantments**, and give it a custom look with either:

- `item-model: "yourpack:eclipse_crown"` - a resource-pack item model, or
- `custom-model-data: 1234` - for packs that key off custom model data.

Changes apply to relics forged after `/spark reload`. To restyle one that already exists:
`/spark reset <relic>` then `/spark give <relic>`.

Cooldowns, The Last Spark's range and targeting (players, mobs, both), the server-wide announcement, the crown's dodge
chance and more are all in the same file.
