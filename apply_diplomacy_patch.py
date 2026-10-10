#!/usr/bin/env python3
"""
Safely add Unciv's bulk diplomacy initializer to a new branch.

Run from the root of your Unciv clone while on:
    soft-cheating-prevention-on-dev-console

The script requires a clean Git working tree, creates:
    bulk-diplomacy-initializer

It modifies only:
    core/src/com/unciv/ui/screens/devconsole/ConsoleDiplomacyCommands.kt
"""
from pathlib import Path
import subprocess
import sys

BASE_BRANCH = "soft-cheating-prevention-on-dev-console"
NEW_BRANCH = "bulk-diplomacy-initializer"
TARGET = Path("core/src/com/unciv/ui/screens/devconsole/ConsoleDiplomacyCommands.kt")

def git(*args):
    return subprocess.run(
        ["git", *args],
        check=True,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    ).stdout.strip()

try:
    root = Path(git("rev-parse", "--show-toplevel"))
except Exception:
    sys.exit("ERROR: Run this script from inside your Unciv Git repository.")

current_branch = git("branch", "--show-current")
if current_branch != BASE_BRANCH:
    sys.exit(
        f"ERROR: You are on branch '{current_branch}', not '{BASE_BRANCH}'.\n"
        f"Switch to {BASE_BRANCH}, then run this script again."
    )

if git("status", "--porcelain"):
    sys.exit(
        "ERROR: Your working tree has uncommitted changes.\n"
        "Commit or stash them first so this patch cannot overwrite your work."
    )

if git("branch", "--list", NEW_BRANCH):
    sys.exit(
        f"ERROR: Branch '{NEW_BRANCH}' already exists.\n"
        "Rename/delete that branch yourself if you want to retry."
    )

target = root / TARGET
if not target.is_file():
    sys.exit(f"ERROR: Could not find {TARGET}.")

source = target.read_text(encoding="utf-8")
if '"apply-world-settings"' in source:
    sys.exit("ERROR: The apply-world-settings command already exists; no changes made.")

import_anchor = "import com.unciv.logic.civilization.diplomacy.DiplomaticModifiers\n"
subcommand_anchor = "    override val subcommands = hashMapOf<String, ConsoleCommand>(\n"
if import_anchor not in source or subcommand_anchor not in source:
    sys.exit(
        "ERROR: The source file does not match the expected upstream layout.\n"
        "No files or branches were changed."
    )

new_imports = (
    "import com.unciv.logic.civilization.Civilization\n"
    "import com.unciv.logic.civilization.diplomacy.DiplomaticStatus\n"
)
source = source.replace(import_anchor, new_imports + import_anchor, 1)

command = r"""
        "apply-world-settings" to ConsoleAction("diplomacy apply-world-settings") { console, params ->
            if (params.isNotEmpty())
                throw ConsoleErrorException("diplomacy apply-world-settings takes no parameters")

            val nationNames = listOf(
                "Animal World", "Awesome World", "Bad World", "Blueberry World",
                "Dark World", "Desert World", "Dessert World", "Evil World",
                "Farm World", "Golden World", "Good World", "Grapefruit World",
                "Guy World", "Hot World", "Ice World", "Industrial World",
                "Island World", "Military World", "Nowhere World", "Sad World",
                "Silver World", "Triangle World", "Very Cold World", "Very Evil World",
                "We Don't Like Guy World World"
            )

            val missing = nationNames.filter { name ->
                console.gameInfo.civilizations.none { it.civName == name || it.civID == name }
            }
            if (missing.isNotEmpty())
                throw ConsoleErrorException("Missing required civilizations: ${missing.joinToString()}")

            val civilizations = nationNames.map { name ->
                console.gameInfo.civilizations.first { it.civName == name || it.civID == name }
            }

            val evilWorlds = setOf(
                "Ice World", "Dark World", "Very Cold World", "Very Evil World",
                "Evil World", "Bad World", "Sad World"
            )
            val guyHates = setOf(
                "Blueberry World", "We Don't Like Guy World World", "Grapefruit World"
            )
            val unfriendlyPairs = setOf(
                setOf("Dessert World", "Desert World"),
                setOf("Guy World", "Desert World")
            )
            val friendlyPairs = setOf(
                setOf("Guy World", "Dessert World"),
                setOf("Blueberry World", "We Don't Like Guy World World"),
                setOf("Blueberry World", "Grapefruit World"),
                setOf("We Don't Like Guy World World", "Grapefruit World")
            )
            val allyPairs = setOf(
                setOf("Guy World", "Golden World"),
                setOf("Triangle World", "Good World"),
                setOf("Triangle World", "Farm World"),
                setOf("Good World", "Farm World")
            )

            fun isUnfriendly(a: String, b: String): Boolean =
                a == "Military World" || b == "Military World" ||
                    ((a in evilWorlds) != (b in evilWorlds)) ||
                    setOf(a, b) in unfriendlyPairs ||
                    (a == "Guy World" && b in guyHates) ||
                    (b == "Guy World" && a in guyHates)

            fun isAlly(a: String, b: String): Boolean =
                (a in evilWorlds && b in evilWorlds && a != b) ||
                    setOf(a, b) in allyPairs

            fun isFriend(a: String, b: String): Boolean =
                isAlly(a, b) || setOf(a, b) in friendlyPairs

            fun relation(from: Civilization, to: Civilization) =
                from.getDiplomacyManagerOrMeet(to)

            // Make every pair known to both civilizations before applying any state.
            for (from in civilizations)
                for (to in civilizations)
                    if (from != to) relation(from, to)

            fun expectedYearsOfPeace(from: String, to: String): Float = when {
                from == "Desert World" && to == "Military World" -> 30f
                from == "Military World" || to == "Military World" -> 0f
                isUnfriendly(from, to) -> 10f
                else -> 30f
            }

            // If already configured, avoid repeating notifications and other
            // one-time side effects from denouncements and treaty signing.
            val alreadyConfigured = civilizations.all { from ->
                civilizations.filter { it != from }.all { to ->
                    val a = from.civName
                    val b = to.civName
                    val diplo = relation(from, to)
                    diplo.hasFlag(DiplomacyFlags.Denunciation) == isUnfriendly(a, b) &&
                        diplo.getFlag(DiplomacyFlags.Denunciation) == (if (isUnfriendly(a, b)) 30 else 0) &&
                        diplo.hasFlag(DiplomacyFlags.DeclarationOfFriendship) == isFriend(a, b) &&
                        diplo.getFlag(DiplomacyFlags.DeclarationOfFriendship) == (if (isFriend(a, b)) 30 else 0) &&
                        diplo.hasFlag(DiplomacyFlags.DefensivePact) == isAlly(a, b) &&
                        diplo.getFlag(DiplomacyFlags.DefensivePact) == (if (isAlly(a, b)) 30 else 0) &&
                        diplo.diplomaticStatus == (if (isAlly(a, b)) DiplomaticStatus.DefensivePact else DiplomaticStatus.Peace) &&
                        diplo.hasModifier(DiplomaticModifiers.Denunciation) == isUnfriendly(b, a) &&
                        diplo.getModifier(DiplomaticModifiers.DeclarationOfFriendship) == (if (isFriend(a, b)) 35f else 0f) &&
                        diplo.getModifier(DiplomaticModifiers.DefensivePact) == (if (isAlly(a, b)) 10f else 0f) &&
                        diplo.getModifier(DiplomaticModifiers.YearsOfPeace) == expectedYearsOfPeace(a, b)
                }
            }
            if (alreadyConfigured)
                return@ConsoleAction DevConsoleResponse.hint("World diplomacy already matches the requested settings; nothing changed.")

            val resetFlags = listOf(
                DiplomacyFlags.Denunciation,
                DiplomacyFlags.DeclarationOfFriendship,
                DiplomacyFlags.DefensivePact,
                DiplomacyFlags.DeclaredWar
            )
            val resetModifiers = listOf(
                DiplomaticModifiers.Denunciation,
                DiplomaticModifiers.DeclarationOfFriendship,
                DiplomaticModifiers.DeclaredFriendshipWithOurEnemies,
                DiplomaticModifiers.DeclaredFriendshipWithOurAllies,
                DiplomaticModifiers.DefensivePact,
                DiplomaticModifiers.SignedDefensivePactWithOurEnemies,
                DiplomaticModifiers.SignedDefensivePactWithOurAllies,
                DiplomaticModifiers.DenouncedOurEnemies,
                DiplomaticModifiers.DenouncedOurAllies
            )

            // Clear only the state and fallout this initializer owns. Years of Peace
            // is deliberately set at the very end, after all state-based effects.
            for (from in civilizations) {
                for (to in civilizations) {
                    if (from == to) continue
                    val diplo = relation(from, to)
                    for (flag in resetFlags) diplo.removeFlag(flag)
                    for (modifier in resetModifiers) diplo.removeModifier(modifier)
                    diplo.diplomaticStatus = DiplomaticStatus.Peace
                }
            }

            var denouncementsApplied = 0
            for (from in civilizations) {
                for (to in civilizations) {
                    if (from == to || !isUnfriendly(from.civName, to.civName)) continue
                    relation(from, to).denounce()
                    denouncementsApplied++
                }
            }

            var friendshipsApplied = 0
            for (i in civilizations.indices) {
                for (j in i + 1 until civilizations.size) {
                    val a = civilizations[i]
                    val b = civilizations[j]
                    if (!isFriend(a.civName, b.civName)) continue
                    relation(a, b).signDeclarationOfFriendship()
                    friendshipsApplied++
                }
            }

            var pactsApplied = 0
            for (i in civilizations.indices) {
                for (j in i + 1 until civilizations.size) {
                    val a = civilizations[i]
                    val b = civilizations[j]
                    if (!isAlly(a.civName, b.civName)) continue
                    relation(a, b).signDefensivePact(30)
                    pactsApplied++
                }
            }

            // Set Years of Peace last. The CSV's Military World values are preserved:
            // all are zero except Desert World -> Military World, which is 30.
            for (from in civilizations) {
                for (to in civilizations) {
                    if (from == to) continue
                    val diplo = relation(from, to)
                    val years = expectedYearsOfPeace(from.civName, to.civName)
                    if (years == 0f) diplo.removeModifier(DiplomaticModifiers.YearsOfPeace)
                    else diplo.setModifier(DiplomaticModifiers.YearsOfPeace, years)
                }
            }

            DevConsoleResponse.hint(
                "Applied world diplomacy to ${civilizations.size} civilizations: " +
                    "$denouncementsApplied directed denouncements, " +
                    "$friendshipsApplied mutual DoFs, $pactsApplied mutual Defensive Pacts; " +
                    "Years of Peace set last. No wars remain."
            )
        },
"""

source = source.replace(subcommand_anchor, subcommand_anchor + command, 1)

# Create the branch only after every validation and in-memory patch has succeeded.
git("switch", "-c", NEW_BRANCH)
target.write_text(source, encoding="utf-8", newline="\n")

print(f"Patch applied successfully on branch '{NEW_BRANCH}'.")
print(f"Modified file: {TARGET.as_posix()}")
print("Next: inspect with `git diff`, then compile/test before committing.")
