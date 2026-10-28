package script.library;

import script.*;

import java.util.Vector;

/**
 * P24 - Player cybernetic surgery.
 *
 * A Doctor or Bio-Engineer (the surgeon) implants a cybernetic part that the patient carries
 * in their own inventory. The patient picks the part (consent), the surgeon confirms, a short
 * surgery runs, then a skill roll decides the outcome.
 *
 * Where: a building with objvar healing.canhealwound (hospitals, player medical centers), OR
 * field surgery, which needs BOTH a camp of power >= MIN_CAMP_POWER (quality / elite / luxury
 * kit) AND the surgeon's own called, powered droid with medpower >= MIN_DROID_MEDPOWER
 * (medical module rating 7+) within DROID_RANGE.
 *
 * Success installs the part exactly like the hospital engineer does (equip -> cybernetic_item
 * script applies all mods). Failure damages the part (10-25 % of max condition) and hurts the
 * patient: a knockout (normal incapacitation) with a chance that grows with part size, otherwise
 * a random 20 % .. size-dependent maximum of health. A knockout of a patient who still has the
 * incapWeaken buff kills them (base_player.OnIncapacitated -> pclib.killPlayer); owner decision
 * 2026-10-28: they knew the risk. Both players get a warning before committing.
 *
 * Self-surgery is allowed (surgeon == patient), with SELF_PENALTY on the chance (default 0).
 *
 * Scripts: radial entry in player.base.base_player (SERVER_MENU7); SUI and timer handlers in
 * cybernetic.cybernetic_surgery (attached on demand to surgeon and patient).
 */
public class cybernetic_surgery extends script.base_script
{
    public cybernetic_surgery()
    {
    }

    public static final String BUILD_TAG = "[cybersurgery P24-3]";
    public static final String SCRIPT_NAME = "cybernetic.cybernetic_surgery";
    public static final String STF = "cybernetic_surgery";
    public static final string_id SID_RADIAL_IMPLANT = new string_id(STF, "radial_implant");

    public static final String SKILL_DOCTOR = "science_doctor_novice";
    public static final String SKILL_BIO_ENGINEER = "outdoors_bio_engineer_novice";
    public static final String MOD_DOCTOR = "healing_wound_treatment";
    public static final String MOD_BIO_ENGINEER = "bio_engineer_assembly";
    public static final String XP_DOCTOR = "medical";
    public static final String XP_BIO_ENGINEER = "crafting_bio_engineer_creature";

    public static final int MIN_CAMP_POWER = 4;
    public static final float MIN_DROID_MEDPOWER = 0.85f;
    public static final float DROID_RANGE = 20.0f;
    public static final float MAX_RANGE = 6.0f;
    public static final float SURGERY_TIME = 5.0f;
    public static final int BUSY_TIMEOUT = 120;

    public static final int BASE_CHANCE = 25;
    public static final float SKILL_FACTOR = 0.6f;
    public static final int BONUS_HOSPITAL = 10;
    public static final int BONUS_FIELD = 5;
    public static final int MIN_CHANCE = 5;
    public static final int MAX_CHANCE = 95;

    public static final int FAIL_DAMAGE_MIN_PCT = 10;
    public static final int FAIL_DAMAGE_MAX_PCT = 25;
    // failure damage to the patient, index = part size (0 unused, 1 hand .. 5 torso)
    public static final int FAIL_HEALTH_MIN_PCT = 20;
    public static final int[] FAIL_HEALTH_MAX_PCT = {0, 40, 50, 60, 75, 90};
    public static final int[] FAIL_KNOCKOUT_CHANCE = {0, 5, 10, 15, 25, 35};
    public static final String BUFF_INCAP_WEAKEN = "incapWeaken";
    public static final int SELF_PENALTY = 0;
    public static final int XP_PER_SIZE = 150;

    // scriptvars on the surgeon
    public static final String SV_SURGEON = "cybersurgery.surgeon";
    // scriptvars on the patient
    public static final String SV_PATIENT = "cybersurgery.patient";

    public static final int NOT_ALLOWED = -1;

    public static boolean isSurgeon(obj_id player) throws InterruptedException
    {
        return isIdValid(player) && (hasSkill(player, SKILL_DOCTOR) || hasSkill(player, SKILL_BIO_ENGINEER));
    }

    /** Higher of wound treatment and bio-engineer assembly, plus a quarter of the lower, capped at 100. */
    public static int getSurgerySkill(obj_id surgeon) throws InterruptedException
    {
        int wt = Math.max(0, getSkillStatMod(surgeon, MOD_DOCTOR));
        int ba = Math.max(0, getSkillStatMod(surgeon, MOD_BIO_ENGINEER));
        int skill = Math.max(wt, ba) + Math.min(wt, ba) / 4;
        return Math.min(100, skill);
    }

    public static String getXpType(obj_id surgeon) throws InterruptedException
    {
        int wt = getSkillStatMod(surgeon, MOD_DOCTOR);
        int ba = getSkillStatMod(surgeon, MOD_BIO_ENGINEER);
        if (hasSkill(surgeon, SKILL_DOCTOR) && (!hasSkill(surgeon, SKILL_BIO_ENGINEER) || wt >= ba))
        {
            return XP_DOCTOR;
        }
        return XP_BIO_ENGINEER;
    }

    /** 1 hand, 2 forearm, 3 arm, 4 legs, 5 torso; 0 = not an implantable part. */
    public static int getPartSize(obj_id item) throws InterruptedException
    {
        int got = getGameObjectType(item);
        if (got == GOT_cybernetic_hand)
        {
            return 1;
        }
        if (got == GOT_cybernetic_forearm)
        {
            return 2;
        }
        if (got == GOT_cybernetic_arm)
        {
            return 3;
        }
        if (got == GOT_cybernetic_legs)
        {
            return 4;
        }
        if (got == GOT_cybernetic_torso)
        {
            return 5;
        }
        return 0;
    }

    public static int getPartDifficulty(obj_id item) throws InterruptedException
    {
        int size = getPartSize(item);
        return size <= 0 ? 0 : (size - 1) * 5;
    }

    public static boolean isImplantablePart(obj_id item) throws InterruptedException
    {
        if (!isIdValid(item) || !exists(item))
        {
            return false;
        }
        if (getPartSize(item) <= 0)
        {
            return false;
        }
        String template = getTemplateName(item);
        return hasScript(item, "cybernetic.cybernetic_item") || (template != null && template.startsWith("object/tangible/wearables/cybernetic/"));
    }

    public static obj_id[] getImplantableParts(obj_id patient) throws InterruptedException
    {
        obj_id inv = utils.getInventoryContainer(patient);
        if (!isIdValid(inv))
        {
            return new obj_id[0];
        }
        obj_id[] contents = getContents(inv);
        Vector parts = new Vector();
        if (contents != null)
        {
            for (obj_id content : contents)
            {
                if (isImplantablePart(content))
                {
                    parts.addElement(content);
                }
            }
        }
        obj_id[] ret = new obj_id[parts.size()];
        parts.toArray(ret);
        return ret;
    }

    public static boolean isWeakened(obj_id patient) throws InterruptedException
    {
        return buff.hasBuff(patient, BUFF_INCAP_WEAKEN);
    }

    public static final String WARN_WEAKENED = "WARNING: the patient is still weakened from an earlier incapacitation. If the surgery fails and knocks them out, they will die.";

    public static boolean isInInventory(obj_id item, obj_id patient) throws InterruptedException
    {
        obj_id inv = utils.getInventoryContainer(patient);
        return isIdValid(inv) && isIdValid(item) && getContainedBy(item) == inv;
    }

    public static boolean isBioLinkedToOther(obj_id item, obj_id patient) throws InterruptedException
    {
        if (!hasObjVar(item, "biolink.id"))
        {
            return false;
        }
        obj_id link = getObjIdObjVar(item, "biolink.id");
        return link != null && link != patient && link != utils.OBJ_ID_BIO_LINK_PENDING;
    }

    /**
     * Location rule. Returns the location bonus (BONUS_HOSPITAL / BONUS_FIELD) or NOT_ALLOWED.
     * Only the surgeon's position is checked; the patient must be within MAX_RANGE anyway.
     */
    public static int getLocationBonus(obj_id surgeon, boolean verbose) throws InterruptedException
    {
        location loc = getLocation(surgeon);
        if (loc != null && isIdValid(loc.cell))
        {
            obj_id building = getTopMostContainer(loc.cell);
            if (isIdValid(building) && hasObjVar(building, "healing.canhealwound"))
            {
                return BONUS_HOSPITAL;
            }
            if (verbose)
            {
                msg(surgeon, "Cybernetic surgery needs a hospital or medical center. This building has no surgical facilities.");
            }
            return NOT_ALLOWED;
        }
        boolean camp = hasLargeCamp(surgeon);
        boolean droid = hasSurgicalDroid(surgeon);
        if (camp && droid)
        {
            return BONUS_FIELD;
        }
        if (verbose)
        {
            if (!camp && !droid)
            {
                msg(surgeon, "Cybernetic surgery needs a hospital, or in the field both a quality (or better) camp and your own medical droid with a high-grade medical module (rating 7+) within " + (int)DROID_RANGE + " m.");
            }
            else if (!camp)
            {
                msg(surgeon, "Field surgery also needs a quality, elite or luxury camp. You are not inside one.");
            }
            else
            {
                msg(surgeon, "Field surgery also needs your own called, powered medical droid with a medical module of rating 7 or higher within " + (int)DROID_RANGE + " m.");
            }
        }
        return NOT_ALLOWED;
    }

    public static boolean hasLargeCamp(obj_id surgeon) throws InterruptedException
    {
        obj_id camp = camping.getCurrentCamp(surgeon);
        return isIdValid(camp) && getIntObjVar(camp, camping.VAR_CAMP_POWER) >= MIN_CAMP_POWER;
    }

    public static boolean hasSurgicalDroid(obj_id surgeon) throws InterruptedException
    {
        obj_id[] critters = getCreaturesInRange(getLocation(surgeon), DROID_RANGE);
        if (critters == null)
        {
            return false;
        }
        for (obj_id critter : critters)
        {
            if (!isIdValid(critter) || !hasObjVar(critter, "medpower"))
            {
                continue;
            }
            if (getFloatObjVar(critter, "medpower") < MIN_DROID_MEDPOWER)
            {
                continue;
            }
            if (pet_lib.isMyPet(critter, surgeon) && !pet_lib.isLowOnPower(critter))
            {
                return true;
            }
        }
        return false;
    }

    public static int getChance(obj_id surgeon, obj_id patient, obj_id item, int locationBonus) throws InterruptedException
    {
        int chance = BASE_CHANCE + Math.round(SKILL_FACTOR * getSurgerySkill(surgeon)) + locationBonus - getPartDifficulty(item);
        if (surgeon == patient)
        {
            chance -= SELF_PENALTY;
        }
        return Math.max(MIN_CHANCE, Math.min(MAX_CHANCE, chance));
    }

    public static boolean isBusy(obj_id player) throws InterruptedException
    {
        if (!utils.hasScriptVar(player, SV_SURGEON + ".started"))
        {
            return false;
        }
        int started = utils.getIntScriptVar(player, SV_SURGEON + ".started");
        if (getGameTime() - started > BUSY_TIMEOUT)
        {
            utils.removeScriptVarTree(player, SV_SURGEON);
            return false;
        }
        return true;
    }

    /** Checks shared by every stage. Messages go to the surgeon (and patient where useful). */
    public static boolean canOperate(obj_id surgeon, obj_id patient, boolean verbose) throws InterruptedException
    {
        if (!isIdValid(surgeon) || !isIdValid(patient) || !exists(surgeon) || !exists(patient))
        {
            return false;
        }
        if (!isPlayer(patient))
        {
            return false;
        }
        if (!isSurgeon(surgeon))
        {
            if (verbose)
            {
                msg(surgeon, "Only a Doctor or Bio-Engineer can implant cybernetics.");
            }
            return false;
        }
        if (isDead(surgeon) || isIncapacitated(surgeon) || isDead(patient) || isIncapacitated(patient))
        {
            if (verbose)
            {
                msg(surgeon, "Cybernetic surgery is not possible right now.");
            }
            return false;
        }
        if (getState(surgeon, STATE_COMBAT) == 1 || getState(patient, STATE_COMBAT) == 1)
        {
            if (verbose)
            {
                msg(surgeon, "You cannot perform surgery during combat.");
            }
            return false;
        }
        if (surgeon != patient && getDistance(surgeon, patient) > MAX_RANGE)
        {
            if (verbose)
            {
                msg(surgeon, "Your patient must stay within " + (int)MAX_RANGE + " m for the surgery.");
            }
            return false;
        }
        return getLocationBonus(surgeon, verbose) != NOT_ALLOWED;
    }

    /** Stage 1 (radial on the patient, run by the surgeon). Shows the patient their parts. */
    public static void requestImplant(obj_id surgeon, obj_id patient) throws InterruptedException
    {
        if (!canOperate(surgeon, patient, true))
        {
            return;
        }
        if (isBusy(surgeon))
        {
            msg(surgeon, "You are already performing a cybernetic surgery.");
            return;
        }
        obj_id[] parts = getImplantableParts(patient);
        boolean self = (surgeon == patient);
        if (parts.length == 0)
        {
            msg(surgeon, self ? "You are not carrying a cybernetic part to implant." : getPlayerName(patient) + " is not carrying a cybernetic part to implant.");
            return;
        }
        String[] names = new String[parts.length];
        for (int i = 0; i < parts.length; i++)
        {
            names[i] = getEncodedName(parts[i]);
        }
        if (!hasScript(patient, SCRIPT_NAME))
        {
            attachScript(patient, SCRIPT_NAME);
        }
        if (sui.hasPid(patient, SV_PATIENT))
        {
            forceCloseSUIPage(sui.getPid(patient, SV_PATIENT));
        }
        utils.removeScriptVarTree(patient, SV_PATIENT);
        utils.setScriptVar(patient, SV_PATIENT + ".surgeon", surgeon);
        utils.setScriptVar(patient, SV_PATIENT + ".parts", parts);
        String risk = "The surgery is invasive: if it fails, the part is damaged and the patient is badly hurt or knocked out. Larger parts carry more risk.";
        String prompt = self ? "Choose the cybernetic part to implant in yourself. " + risk : getPlayerName(surgeon) + " offers to implant a cybernetic part. Choose the part from your inventory. " + risk;
        if (isWeakened(patient))
        {
            prompt += "\n\n" + WARN_WEAKENED;
        }
        int pid = sui.listbox(patient, patient, prompt, sui.OK_CANCEL, "Cybernetic Surgery", names, "handleCyberSurgeryPatientChoice", true, false);
        sui.setPid(patient, pid, SV_PATIENT);
        if (!self)
        {
            msg(surgeon, "Waiting for " + getPlayerName(patient) + " to choose a cybernetic part.");
        }
        CustomerServiceLog("cyborg", BUILD_TAG + " surgeon " + surgeon + " offered surgery to " + patient + " (" + parts.length + " parts)");
    }

    /** Stage 2 (patient picked a part). Asks the surgeon to confirm with the chance shown. */
    public static void patientChose(obj_id patient, obj_id surgeon, obj_id item) throws InterruptedException
    {
        if (!canOperate(surgeon, patient, true))
        {
            msg(patient, "The surgery cannot take place right now.");
            return;
        }
        if (!isImplantablePart(item) || !isInInventory(item, patient))
        {
            msg(patient, "That part is no longer in your inventory.");
            return;
        }
        if (isBioLinkedToOther(item, patient))
        {
            sendSystemMessage(patient, new string_id("ep3/cybernetic", "bio_link_mismatch"));
            msg(surgeon, "That part is bio-linked to someone else.");
            return;
        }
        if (isBusy(surgeon))
        {
            msg(patient, getPlayerName(surgeon) + " is busy with another surgery.");
            return;
        }
        int bonus = getLocationBonus(surgeon, false);
        int chance = getChance(surgeon, patient, item, bonus);
        if (!hasScript(surgeon, SCRIPT_NAME))
        {
            attachScript(surgeon, SCRIPT_NAME);
        }
        if (sui.hasPid(surgeon, SV_SURGEON))
        {
            forceCloseSUIPage(sui.getPid(surgeon, SV_SURGEON));
        }
        utils.setScriptVar(surgeon, SV_SURGEON + ".patient", patient);
        utils.setScriptVar(surgeon, SV_SURGEON + ".item", item);
        int size = getPartSize(item);
        String who = (surgeon == patient) ? "You are about to implant in yourself: " : getPlayerName(patient) + " asks you to implant: ";
        String prompt = who + getItemLabel(item) + ".\n\nSuccess chance: " + chance + "%.\nOn failure the part loses " + FAIL_DAMAGE_MIN_PCT + "-" + FAIL_DAMAGE_MAX_PCT + "% condition and the patient loses " + FAIL_HEALTH_MIN_PCT + "-" + FAIL_HEALTH_MAX_PCT[size] + "% health, with a " + FAIL_KNOCKOUT_CHANCE[size] + "% risk of being knocked out.";
        if (isWeakened(patient))
        {
            prompt += "\n\n" + WARN_WEAKENED;
        }
        prompt += "\n\nStart the surgery?";
        int pid = sui.msgbox(surgeon, surgeon, prompt, sui.OK_CANCEL, "Cybernetic Surgery", sui.MSG_QUESTION, "handleCyberSurgeryConfirm");
        sui.setPid(surgeon, pid, SV_SURGEON);
        if (surgeon != patient)
        {
            msg(patient, "Waiting for " + getPlayerName(surgeon) + " to begin the surgery.");
        }
    }

    /** Stage 3 (surgeon confirmed). Starts the timer. */
    public static void startSurgery(obj_id surgeon) throws InterruptedException
    {
        obj_id patient = utils.getObjIdScriptVar(surgeon, SV_SURGEON + ".patient");
        obj_id item = utils.getObjIdScriptVar(surgeon, SV_SURGEON + ".item");
        if (!canOperate(surgeon, patient, true) || !isImplantablePart(item) || !isInInventory(item, patient))
        {
            msg(surgeon, "The surgery was cancelled.");
            utils.removeScriptVarTree(surgeon, SV_SURGEON);
            return;
        }
        if (isBusy(surgeon))
        {
            msg(surgeon, "You are already performing a cybernetic surgery.");
            return;
        }
        int started = getGameTime();
        utils.setScriptVar(surgeon, SV_SURGEON + ".started", started);
        if (surgeon == patient)
        {
            doAnimationAction(surgeon, "heal_self");
            msg(surgeon, "You begin the cybernetic surgery on yourself. Stay out of combat.");
        }
        else
        {
            faceTo(surgeon, patient);
            doAnimationAction(surgeon, "heal_other");
            msg(surgeon, "You begin the cybernetic surgery on " + getPlayerName(patient) + ". Stay close and out of combat.");
            msg(patient, getPlayerName(surgeon) + " begins the cybernetic surgery. Hold still.");
        }
        dictionary params = new dictionary();
        params.put("patient", patient);
        params.put("item", item);
        params.put("started", started);
        messageTo(surgeon, "handleCyberSurgeryComplete", params, SURGERY_TIME, false);
    }

    /** Stage 4 (timer). Re-checks everything, rolls and applies the outcome. */
    public static void finishSurgery(obj_id surgeon, obj_id patient, obj_id item, int started) throws InterruptedException
    {
        if (!utils.hasScriptVar(surgeon, SV_SURGEON + ".started") || utils.getIntScriptVar(surgeon, SV_SURGEON + ".started") != started)
        {
            return;
        }
        utils.removeScriptVarTree(surgeon, SV_SURGEON);
        if (!canOperate(surgeon, patient, true))
        {
            msg(surgeon, "The surgery was interrupted. Nothing happened to the part.");
            if (isIdValid(patient) && exists(patient) && patient != surgeon)
            {
                msg(patient, "The surgery was interrupted. Nothing happened to the part.");
            }
            return;
        }
        if (!isImplantablePart(item) || !isInInventory(item, patient))
        {
            msg(surgeon, "The part is no longer in the patient's inventory. The surgery was cancelled.");
            if (patient != surgeon)
            {
                msg(patient, "The part is no longer in your inventory. The surgery was cancelled.");
            }
            return;
        }
        int bonus = getLocationBonus(surgeon, false);
        int chance = getChance(surgeon, patient, item, bonus);
        int roll = rand(1, 100);
        String label = getItemLabel(item);
        CustomerServiceLog("cyborg", BUILD_TAG + " surgeon " + surgeon + " patient " + patient + " item " + item + " (" + getTemplateName(item) + ") chance " + chance + " roll " + roll);
        if (roll <= chance)
        {
            succeed(surgeon, patient, item, label);
        }
        else
        {
            fail(surgeon, patient, item, label);
        }
    }

    public static void succeed(obj_id surgeon, obj_id patient, obj_id item, String label) throws InterruptedException
    {
        if (!hasScript(patient, "cybernetic.cybernetic_player"))
        {
            attachScript(patient, "cybernetic.cybernetic_player");
        }
        String template = getTemplateName(item);
        if (template.contains("crafted") && hasScript(item, "item.armor.biolink_item_non_faction"))
        {
            detachScript(item, "item.armor.biolink_item_non_faction");
        }
        if (!equip(item, patient))
        {
            msg(surgeon, "The surgery went well, but the part would not attach. The slot may be in use, or the part needs the Episode III expansion. Nothing was damaged.");
            sendSystemMessage(patient, new string_id("ep3/cybernetic", "cannot_install"));
            CustomerServiceLog("cyborg", BUILD_TAG + " equip failed for item " + item + " on " + patient);
            return;
        }
        if (template.contains("crafted"))
        {
            setBioLink(item, patient);
        }
        playClientEffectObj(patient, "clienteffect/cyborg_itm_installed.cef", patient, "");
        if (surgeon == patient)
        {
            msg(patient, "Surgery successful: you implanted your " + label + ".");
        }
        else
        {
            msg(surgeon, "Surgery successful: " + label + " implanted in " + getPlayerName(patient) + ".");
            msg(patient, "Surgery successful: " + getPlayerName(surgeon) + " implanted your " + label + ".");
        }
        int xpAmount = XP_PER_SIZE * getPartSize(item);
        if (xpAmount > 0)
        {
            xp.grant(surgeon, getXpType(surgeon), xpAmount, true);
        }
        CustomerServiceLog("cyborg", BUILD_TAG + " success: item " + item + " installed on " + patient + " by " + surgeon);
    }

    public static void fail(obj_id surgeon, obj_id patient, obj_id item, String label) throws InterruptedException
    {
        int maxHp = getMaxHitpoints(item);
        int hp = getHitpoints(item);
        if (maxHp > 0)
        {
            int dmg = Math.max(1, maxHp * rand(FAIL_DAMAGE_MIN_PCT, FAIL_DAMAGE_MAX_PCT) / 100);
            setHitpoints(item, Math.max(1, hp - dmg));
        }
        int size = Math.max(1, Math.min(5, getPartSize(item)));
        int health = getAttrib(patient, HEALTH);
        int maxHealth = getWoundedMaxAttrib(patient, HEALTH);
        boolean knockout = rand(1, 100) <= FAIL_KNOCKOUT_CHANCE[size];
        boolean weakened = isWeakened(patient);
        String outcome;
        if (knockout)
        {
            // normal incapacitation (engine testIncapacitation -> base_player.OnIncapacitated); a patient
            // who still has incapWeaken is killed there (pclib.killPlayer), by owner decision
            outcome = weakened ? "knocked out while still weakened" : "knocked out";
            setAttrib(patient, HEALTH, 0);
        }
        else
        {
            int pct = rand(FAIL_HEALTH_MIN_PCT, FAIL_HEALTH_MAX_PCT[size]);
            int hurt = Math.max(1, maxHealth * pct / 100);
            setAttrib(patient, HEALTH, Math.max(1, health - hurt));
            doAnimationAction(patient, "wince");
            outcome = "hurt (" + pct + "% health)";
        }
        if (surgeon == patient)
        {
            msg(patient, "The surgery failed. Your " + label + " was damaged and you were " + outcome + ". A Cybernetic Specialist can repair the part.");
        }
        else
        {
            msg(surgeon, "The surgery failed. The " + label + " was damaged and " + getPlayerName(patient) + " was " + outcome + ".");
            msg(patient, "The surgery failed. Your " + label + " was damaged and you were " + outcome + ". A Cybernetic Specialist can repair the part.");
        }
        CustomerServiceLog("cyborg", BUILD_TAG + " failure: item " + item + " damaged, patient " + patient + " " + outcome + " (size " + size + ", knockoutRoll " + knockout + ", weakened " + weakened + ")");
    }

    public static String getItemLabel(obj_id item) throws InterruptedException
    {
        String name = getAssignedName(item);
        if (name == null || name.length() == 0)
        {
            name = localize(getNameStringId(item));
        }
        if (name == null || name.length() == 0)
        {
            name = "cybernetic part";
        }
        return name;
    }

    public static void msg(obj_id player, String text) throws InterruptedException
    {
        if (isIdValid(player))
        {
            sendSystemMessage(player, text, null);
        }
    }
}
