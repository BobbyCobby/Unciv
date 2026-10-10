package com.unciv.ui.screens.devconsole

import com.unciv.logic.civilization.diplomacy.DeclareWarReason
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.civilization.diplomacy.DiplomacyManager
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.logic.civilization.diplomacy.DiplomaticModifiers
import com.unciv.logic.civilization.diplomacy.WarType

internal class ConsoleDiplomacyCommands : ConsoleCommandNode {
    override val subcommands = hashMapOf<String, ConsoleCommand>(

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
        "list" to ConsoleAction("diplomacy list <civName> <civName>") { console, params ->
            val diplo = getDiplomacy(console, params, noMeet = true) ?:
                return@ConsoleAction DevConsoleResponse.hint("not met")
            val message = buildString {
                appendLine("Relation: ${diplo.relationshipLevel()}")
                if (diplo.civInfo.isCityState) appendLine("Influence: ${diplo.influence}")
                for ((modifier, value) in diplo.diplomaticModifiers)
                    appendLine("$modifier = $value")
                for ((flag, count) in diplo.flagsCountdown)
                    appendLine("$flag: $count turns")
            }
            DevConsoleResponse.hint(message)
        },
        "meet" to ConsoleAction("diplomacy meet <civName> <civName>") { console, params ->
            getDiplomacy(console, params)
            DevConsoleResponse.OK
        },
        "declare-war" to ConsoleAction("diplomacy declare-war <civName> <civName>") { console, params ->
            val diplo = getDiplomacy(console, params)!!
            diplo.declareWar(DeclareWarReason(WarType.DirectWar)) // to lazy to allow parameters for this
            DevConsoleResponse.OK
        },
        "make-peace" to ConsoleAction("diplomacy make-peace <civName> <civName>") { console, params ->
            val diplo = getDiplomacy(console, params)!!
            diplo.makePeace()
            DevConsoleResponse.OK
        },
        "setflag" to ConsoleAction("diplomacy setflag <civName> <civName> <diplomacyFlag> <amount>") { console, params ->
            val diplo = getDiplomacy(console, params)!!
            val flag = params[2].enumValue<DiplomacyFlags>()
            val amount = params[3].toInt()
            diplo.setFlag(flag, amount)
            DevConsoleResponse.OK
        },
        "removeflag" to ConsoleAction("diplomacy removeflag <civName> <civName> <diplomacyFlag>") { console, params ->
            val diplo = getDiplomacy(console, params)!!
            val flag = params[2].enumValue<DiplomacyFlags>()
            diplo.removeFlag(flag)
            DevConsoleResponse.OK
        },
        "addmodifier" to ConsoleAction("diplomacy addmodifier <civName> <civName> <diplomaticModifier> <amount>") { console, params ->
            val diplo = getDiplomacy(console, params)!!
            val modifier = params[2].enumValue<DiplomaticModifiers>()
            val amount = params[3].toFloat()
            diplo.addModifier(modifier, amount)
            DevConsoleResponse.OK
        },
        "removemodifier" to ConsoleAction("diplomacy removemodifier <civName> <civName> <diplomaticModifier>") { console, params ->
            val diplo = getDiplomacy(console, params)!!
            val modifier = params[2].enumValue<DiplomaticModifiers>()
            diplo.removeModifier(modifier)
            DevConsoleResponse.OK
        },
        "setinfluence" to ConsoleAction("diplomacy setinfluence <civName> <civName> <amount>") { console, params ->
            val diplo = getDiplomacy(console, params)!!
            if (!diplo.civInfo.isCityState || !diplo.otherCiv.isMajorCiv())
                throw ConsoleErrorException("first civ must be the city-state, second a major civ")
            val amount = params[2].toFloat()
            diplo.setInfluence(amount)
            DevConsoleResponse.OK
        },
        "denounce" to ConsoleAction("diplomacy denounce <civName> <civName>") { console, params ->
            val diplo = getDiplomacy(console, params)!!
            diplo.denounce()
            DevConsoleResponse.OK
        },
    )

    private fun getDiplomacy(console: DevConsolePopup, params: List<CliInput>, noMeet: Boolean = false): DiplomacyManager? {
        if (params.size < 2)
            throw ConsoleErrorException("command needs two civ names")
        val civ1 = console.getCivByName(params[0])
        val civ2 = console.getCivByName(params[1])
        if (civ1.isDefeated() || civ2.isDefeated())
            throw ConsoleErrorException("both civs must be alive")
        return if (noMeet) civ1.getDiplomacyManager(civ2)
            else civ1.getDiplomacyManagerOrMeet(civ2)
    }
}
