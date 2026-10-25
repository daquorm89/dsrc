package script.library;

import script.*;

/**
 * Spending pet ability slots and training points (P22).
 *
 * The CU-era pet ability system is still alive on the server: pets earn ability slots
 * ("ai.petAbility.available") and training points ("ai.petAbility.trainingPts") on their control
 * device as they level, and pet_lib.trainPetAbility(pet, crc) does the whole purchase (Creature
 * Handler check, free slot, points, prerequisite, writes "ai.petAbility.abilityList"). Only the
 * window that called it was removed. This library is that window: radial "Train Pet Ability" on a
 * called-out creature pet opens a list of the abilities the pet can take next.
 *
 * Only abilities whose effect is actually applied by the current code are offered
 * (TRAINABLE_FAMILIES). Audit 2026-10-24:
 *   works:   healthBoost / regenBoost (max attribs on call), armorBoost (combat general protection),
 *            tauntBoost / tauntReduction (hate in combat.java), damageBoost / attackSpeedBoost
 *            (pet weapon, pet_lib.setCraftedPetStatsByGrowth), runSpeedBoost (buff petRunSpeed_N,
 *            movement permaboost 25/50/75)
 *   no effect (not offered): toHitBoost, defenseBoost, lowHealthFrenzy, lowHealthDefense (script
 *            vars nobody reads), critHitBoost / critSaveBoost (overwritten by the pet's own crit
 *            values), triggered shieldMaster / frenzy / defenseStance (no command ever triggers them,
 *            buff values unread), action attacks (no command_table / combat_data rows).
 * A trained ability takes effect the next time the pet is called.
 */
public class pet_training_lib extends script.base_script
{
    public pet_training_lib()
    {
    }
    public static final String STF = "pet/pet_ability";
    public static final String HANDLER = "handlePetAbilityTrainSelect";
    public static final String SCRIPTVAR_LIST = "petTrain.list";
    public static final String SCRIPTVAR_PLAYER = "petTrain.player";
    public static final String CONFIRM_HANDLER = "handlePetAbilityTrainConfirm";
    public static final String SCRIPTVAR_PENDING = "petTrain.pending";
    /** movement.tab petRunSpeed_1..3 permaboost values (runSpeedBoost uses buffs petRunSpeed_N). */
    public static final int[] RUN_SPEED_BOOST = { 25, 50, 75 };
    public static final String[] TRAINABLE_FAMILIES = {
        "healthBoost", "regenBoost", "armorBoost", "tauntBoost", "tauntReduction",
        "damageBoost", "attackSpeedBoost", "runSpeedBoost"
    };

    public static String family(String abilityName)
    {
        int u = abilityName.lastIndexOf('_');
        return u > 0 ? abilityName.substring(0, u) : abilityName;
    }

    public static int rank(String abilityName)
    {
        int u = abilityName.lastIndexOf('_');
        if (u < 0)
        {
            return 1;
        }
        try
        {
            return Integer.parseInt(abilityName.substring(u + 1));
        }
        catch (NumberFormatException e)
        {
            return 1;
        }
    }

    public static boolean isTrainableFamily(String abilityName)
    {
        String f = family(abilityName);
        for (String t : TRAINABLE_FAMILIES)
        {
            if (t.equals(f))
            {
                return true;
            }
        }
        return false;
    }

    /** Localized ability name, or the raw name when the string table has none. */
    public static String abilityLabel(String abilityName) throws InterruptedException
    {
        String s = getString(new string_id(STF, abilityName.toLowerCase()));
        return (s == null || s.length() == 0) ? abilityName : s;
    }

    public static String localized(String key, String fallback) throws InterruptedException
    {
        String s = getString(new string_id(STF, key));
        return (s == null || s.length() == 0) ? fallback : s;
    }

    private static String pct(float f)
    {
        float v = f * 100.0f;
        return (v == (int)v) ? String.valueOf((int)v) : String.valueOf(Math.round(v * 10.0f) / 10.0f);
    }

    /** Short effect of one rank, built from the pet_ability.tab row (shown on the list line). */
    public static String effectText(dictionary row)
    {
        String name = row.getString("abilityName");
        String fam = family(name);
        float sv = row.getFloat("scriptVarVal");
        int statVal = row.getInt("statModVal");
        float statPct = row.getFloat("statModPct");
        switch (fam)
        {
            case "healthBoost":
                return "+" + pct(statPct) + "% +" + statVal + " max health";
            case "regenBoost":
                return "+" + pct(statPct) + "% +" + statVal + " health regeneration";
            case "armorBoost":
                return "+" + (int)sv + " armor";
            case "damageBoost":
                return "+" + pct(sv) + "% damage";
            case "attackSpeedBoost":
                return pct(sv) + "% faster attacks";
            case "runSpeedBoost":
            {
                int r = Math.max(1, Math.min(RUN_SPEED_BOOST.length, rank(name)));
                return "+" + RUN_SPEED_BOOST[r - 1] + "% movement speed";
            }
            case "tauntBoost":
                return "+" + (int)sv + " threat per hit";
            case "tauntReduction":
                return "-" + (int)sv + "% threat";
        }
        return "";
    }

    /** What the ability family is for (shown on the details screen). */
    public static String familyDescription(String fam)
    {
        switch (fam)
        {
            case "healthBoost":
                return "Raises your pet's maximum health, so it survives longer in a fight. Good for a pet that tanks for you.";
            case "regenBoost":
                return "Raises your pet's health regeneration, so it heals up faster between fights.";
            case "armorBoost":
                return "Adds armor to your pet, so every hit against it does less damage. Good for a pet that tanks for you.";
            case "damageBoost":
                return "Every attack of your pet does more damage. Good for a pet that deals damage while you hold the enemy's attention.";
            case "attackSpeedBoost":
                return "Shortens the time between your pet's attacks, so it attacks more often. Stacks well with Marring Storm (damage).";
            case "runSpeedBoost":
                return "Your pet moves faster, also when you ride it as a mount. Mostly useful on mounts.";
            case "tauntBoost":
                return "Each hit of your pet adds extra threat, so enemies prefer to attack the pet instead of you. Use it on a tanking pet. Works against Hunted Withdrawal: do not train both.";
            case "tauntReduction":
                return "Your pet causes less threat, so enemies keep attacking you instead of the pet. Use it on a damage pet when you tank. Works against Aggression: do not train both.";
        }
        return "";
    }

    public static final String GUIDE =
        "Tank pet: Vigorous Health, Thick Skin, Aggression.\n"
        + "Damage pet (you tank): Marring Storm, Frenzied Blitz, Hunted Withdrawal.\n"
        + "Aggression and Hunted Withdrawal work against each other. Fleet Footed is for mounts.";

    /** True when the radial entry should be offered on this called-out pet. */
    public static boolean canOfferTraining(obj_id pet, obj_id player) throws InterruptedException
    {
        if (!isIdValid(pet) || !isIdValid(player) || getMaster(pet) != player)
        {
            return false;
        }
        if (pet_lib.isDroidPet(pet) || !ai_lib.isMonster(pet) || !hasSkill(player, "outdoors_creaturehandler_novice"))
        {
            return false;
        }
        obj_id pcd = callable.getCallableCD(pet);
        return isIdValid(pcd) && exists(pcd) && hasObjVar(pcd, "ai.petAbility.available");
    }

    /** Names of the abilities the pet knows (from the control device). */
    public static java.util.Vector getKnownNames(obj_id pcd) throws InterruptedException
    {
        java.util.Vector names = new java.util.Vector();
        int[] list = getIntArrayObjVar(pcd, "ai.petAbility.abilityList");
        if (list == null)
        {
            return names;
        }
        for (int crc : list)
        {
            String n = pet_lib.getPetAbilityFromCrc(crc);
            if (n != null && n.length() > 0)
            {
                names.add(n);
            }
        }
        return names;
    }

    private static int knownRankOfFamily(java.util.Vector known, String fam)
    {
        int best = 0;
        for (Object o : known)
        {
            String n = (String)o;
            if (family(n).equals(fam))
            {
                best = Math.max(best, rank(n));
            }
        }
        return best;
    }

    /** Opens the training window for the owner of a called-out pet. */
    public static void openTrainingWindow(obj_id pet, obj_id player) throws InterruptedException
    {
        if (!canOfferTraining(pet, player))
        {
            sendSystemMessage(player, new string_id(STF, "train_ability_ch_only"));
            return;
        }
        obj_id pcd = callable.getCallableCD(pet);
        int slots = getIntObjVar(pcd, "ai.petAbility.available");
        int toEarn = getIntObjVar(pcd, "ai.petAbility.toBeEarned");
        int points = getIntObjVar(pcd, "ai.petAbility.trainingPts");
        java.util.Vector known = getKnownNames(pcd);

        java.util.Vector entries = new java.util.Vector();
        java.util.Vector crcs = new java.util.Vector();
        int rows = dataTableGetNumRows(pet_lib.PET_ABILITY_TABLE);
        for (int r = 0; r < rows; r++)
        {
            dictionary row = dataTableGetRow(pet_lib.PET_ABILITY_TABLE, r);
            if (row == null)
            {
                continue;
            }
            String name = row.getString("abilityName");
            if (name == null || name.length() == 0 || !isTrainableFamily(name))
            {
                continue;
            }
            // Only the next rank of each family: rank 1 when none is known, rank N+1 when rank N is known.
            if (rank(name) != knownRankOfFamily(known, family(name)) + 1)
            {
                continue;
            }
            int cost = row.getInt("trainingCost");
            String effect = effectText(row);
            String line = abilityLabel(name) + (effect.length() > 0 ? ":  " + effect : "") + "  -  " + cost + " points";
            if (cost > points)
            {
                line += "  (need " + (cost - points) + " more)";
            }
            entries.add(line);
            crcs.add(row.getInt("abilityCrc"));
        }
        if (entries.isEmpty())
        {
            sendSystemMessage(player, new string_id(STF, "no_new_ability"));
            return;
        }
        StringBuilder knownText = new StringBuilder();
        for (Object o : known)
        {
            if (knownText.length() > 0)
            {
                knownText.append(", ");
            }
            knownText.append(abilityLabel((String)o));
        }
        String prompt = localized("training_header", "Select an ability to train to your pet.")
            + "\n\nFree ability slots: " + slots + (toEarn > 0 ? " (" + toEarn + " more to earn as it levels)" : "")
            + "\nTraining points: " + points
            + "\nKnown: " + (knownText.length() > 0 ? knownText.toString() : "none")
            + "\n\n" + GUIDE
            + "\n\nSelect an ability and press OK to see the details before training it. A higher rank replaces the lower one and uses a slot as well.";
        int[] crcArray = new int[crcs.size()];
        for (int i = 0; i < crcArray.length; i++)
        {
            crcArray[i] = (Integer)crcs.get(i);
        }
        utils.setScriptVar(pet, SCRIPTVAR_LIST, crcArray);
        utils.setScriptVar(pet, SCRIPTVAR_PLAYER, player);
        sui.listbox(pet, player, prompt, sui.OK_CANCEL, localized("training_title", "Train Pet Ability"), entries, HANDLER, true, false);
    }

    /** SUI result, called from ai.pet on the pet. */
    public static void onTrainingSelected(obj_id pet, dictionary params) throws InterruptedException
    {
        obj_id player = sui.getPlayerId(params);
        if (!isIdValid(player) || !utils.hasScriptVar(pet, SCRIPTVAR_LIST) || utils.getObjIdScriptVar(pet, SCRIPTVAR_PLAYER) != player)
        {
            return;
        }
        int[] crcs = utils.getIntArrayScriptVar(pet, SCRIPTVAR_LIST);
        utils.removeScriptVar(pet, SCRIPTVAR_LIST);
        utils.removeScriptVar(pet, SCRIPTVAR_PLAYER);
        if (sui.getIntButtonPressed(params) == sui.BP_CANCEL)
        {
            return;
        }
        int idx = sui.getListboxSelectedRow(params);
        if (crcs == null || idx < 0 || idx >= crcs.length)
        {
            return;
        }
        // Re-check: the pet may have been stored, released or given away while the window was open.
        if (!canOfferTraining(pet, player))
        {
            return;
        }
        openDetails(pet, player, crcs[idx]);
    }

    /** Details screen for one ability: what it does, what it costs, Train / Cancel. */
    public static void openDetails(obj_id pet, obj_id player, int crc) throws InterruptedException
    {
        int r = dataTableSearchColumnForInt(crc, "abilityCrc", pet_lib.PET_ABILITY_TABLE);
        dictionary row = r >= 0 ? dataTableGetRow(pet_lib.PET_ABILITY_TABLE, r) : null;
        if (row == null)
        {
            return;
        }
        obj_id pcd = callable.getCallableCD(pet);
        String name = row.getString("abilityName");
        int cost = row.getInt("trainingCost");
        int points = getIntObjVar(pcd, "ai.petAbility.trainingPts");
        int slots = getIntObjVar(pcd, "ai.petAbility.available");
        String prereq = row.getString("prereq");
        StringBuilder text = new StringBuilder();
        text.append(abilityLabel(name)).append("\n\n");
        text.append("Effect: ").append(effectText(row)).append("\n\n");
        text.append(familyDescription(family(name))).append("\n\n");
        if (prereq != null && prereq.length() > 0)
        {
            text.append("Replaces ").append(abilityLabel(prereq)).append(" (the numbers above are the new total, not added on top).\n\n");
        }
        text.append("Cost: ").append(cost).append(" training points and 1 ability slot.\n");
        text.append("You have: ").append(points).append(" points, ").append(slots).append(" free slot(s).\n\n");
        if (points < cost)
        {
            text.append("Your pet does not have enough training points yet. It earns more as it levels.");
        }
        else if (slots < 1)
        {
            text.append("Your pet has no free ability slot yet. It earns more as it levels.");
        }
        else
        {
            text.append("Press OK to train it. It takes effect the next time you call your pet.");
        }
        utils.setScriptVar(pet, SCRIPTVAR_PENDING, crc);
        utils.setScriptVar(pet, SCRIPTVAR_PLAYER, player);
        sui.msgbox(pet, player, text.toString(), sui.OK_CANCEL, localized("training_title", "Train Pet Ability"), CONFIRM_HANDLER);
    }

    /** Details screen result: OK trains, Cancel goes back to the list. Called from ai.pet. */
    public static void onDetailsClosed(obj_id pet, dictionary params) throws InterruptedException
    {
        obj_id player = sui.getPlayerId(params);
        if (!isIdValid(player) || !utils.hasScriptVar(pet, SCRIPTVAR_PENDING) || utils.getObjIdScriptVar(pet, SCRIPTVAR_PLAYER) != player)
        {
            return;
        }
        int crc = utils.getIntScriptVar(pet, SCRIPTVAR_PENDING);
        utils.removeScriptVar(pet, SCRIPTVAR_PENDING);
        utils.removeScriptVar(pet, SCRIPTVAR_PLAYER);
        if (!canOfferTraining(pet, player))
        {
            return;
        }
        if (sui.getIntButtonPressed(params) == sui.BP_CANCEL)
        {
            openTrainingWindow(pet, player);
            return;
        }
        obj_id pcd = callable.getCallableCD(pet);
        int before = getIntObjVar(pcd, "ai.petAbility.available");
        pet_lib.trainPetAbility(pet, crc);
        if (getIntObjVar(pcd, "ai.petAbility.available") < before)
        {
            sendSystemMessage(player, "Store and call your pet again to activate the new ability.", null);
        }
    }
}
