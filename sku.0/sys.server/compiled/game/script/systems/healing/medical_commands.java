package script.systems.healing;

import script.*;
import script.library.*;

/**
 * Pre-CU medical command entry points.
 * command_table scriptHooks pointed here historically; NGE left the library
 * (script.library.healing) intact but dropped these thin wrappers.
 * Reconnect only — no new healing formulas.
 */
public class medical_commands extends script.base_script
{
    public medical_commands()
    {
    }

    public static final string_id SID_NO_MEDICINE = new string_id("healing", "no_medicine");
    public static final string_id SID_CANT_HEAL = new string_id("healing", "no_heal");
    public static final string_id SID_TARGET_DEAD = new string_id("healing", "no_help_ability");
    public static final String STF_TOOL = "tool/med_tool";

    private obj_id resolveTarget(obj_id self, obj_id target) throws InterruptedException
    {
        if (isIdValid(target) && exists(target))
        {
            return target;
        }
        obj_id look = getLookAtTarget(self);
        if (isIdValid(look) && exists(look))
        {
            return look;
        }
        return self;
    }

    private obj_id parseMedFromParams(String params) throws InterruptedException
    {
        if (params == null || params.equals("") || params.equals("no_params"))
        {
            return null;
        }
        String token = params.trim();
        int sp = token.indexOf(' ');
        if (sp > 0)
        {
            token = token.substring(0, sp);
        }
        obj_id med = utils.stringToObjId(token);
        if (isIdValid(med) && exists(med))
        {
            return med;
        }
        return null;
    }

    private boolean ensureInRange(obj_id medic, obj_id target) throws InterruptedException
    {
        if (medic == target)
        {
            return true;
        }
        if (getDistance(medic, target) > consumable.MAX_AFFECT_DISTANCE)
        {
            sendSystemMessage(medic, consumable.SID_TARGET_OUT_OF_RANGE);
            return false;
        }
        return true;
    }

    // ---- healDamage ----
    public int healDamage(obj_id self, obj_id target, String params, float defaultTime) throws InterruptedException
    {
        target = resolveTarget(self, target);
        if (!healing.canHealDamage(self, true))
        {
            return SCRIPT_OVERRIDE;
        }
        if (!ensureInRange(self, target))
        {
            return SCRIPT_OVERRIDE;
        }
        obj_id med = parseMedFromParams(params);
        if (!isIdValid(med))
        {
            med = healing.findHealDamageMedicine(self, target);
        }
        if (!isIdValid(med))
        {
            sendSystemMessage(self, SID_NO_MEDICINE);
            return SCRIPT_OVERRIDE;
        }
        if (!healing.performMedicalHealDamage(self, target, med))
        {
            return SCRIPT_OVERRIDE;
        }
        healing.doHealingAnimationAndEffect(self, target);
        return SCRIPT_CONTINUE;
    }

    // ---- healWound ----
    public int healWound(obj_id self, obj_id target, String params, float defaultTime) throws InterruptedException
    {
        target = resolveTarget(self, target);
        if (!healing.canHealWound(self, true))
        {
            return SCRIPT_OVERRIDE;
        }
        if (!ensureInRange(self, target))
        {
            return SCRIPT_OVERRIDE;
        }
        obj_id med = parseMedFromParams(params);
        int woundType = HEALTH;
        if (!isIdValid(med))
        {
            // Prefer the attribute with the largest wound.
            int best = -1;
            int bestAmt = 0;
            for (int i = 0; i < NUM_ATTRIBUTES; i++)
            {
                int w = getAttribWound(target, i);
                if (w > bestAmt)
                {
                    bestAmt = w;
                    best = i;
                }
            }
            if (best < 0 || bestAmt <= 0)
            {
                sendSystemMessage(self, new string_id("healing", "no_wounds"));
                return SCRIPT_OVERRIDE;
            }
            woundType = best;
            med = healing.findHealWoundMedicine(self, woundType);
        }
        if (!isIdValid(med))
        {
            sendSystemMessage(self, SID_NO_MEDICINE);
            return SCRIPT_OVERRIDE;
        }
        if (!healing.canPayHealingCost(self, healing.HEAL_TYPE_MEDICAL_WOUND, 1.0f))
        {
            sendSystemMessage(self, new string_id("healing", "not_enough_mind"));
            return SCRIPT_OVERRIDE;
        }
        if (!consumable.consumeItem(self, target, med))
        {
            return SCRIPT_OVERRIDE;
        }
        healing.applyHealingCost(self, healing.HEAL_TYPE_MEDICAL_WOUND, 1.0f);
        healing.doHealingAnimationAndEffect(self, target);
        if (self != target)
        {
            pvpHelpPerformed(self, target);
        }
        return SCRIPT_CONTINUE;
    }

    // ---- tendDamage / tendWound / quickHeal (no stim required) ----
    public int tendDamage(obj_id self, obj_id target, String params, float defaultTime) throws InterruptedException
    {
        target = resolveTarget(self, target);
        if (!healing.canHealDamage(self, true))
        {
            return SCRIPT_OVERRIDE;
        }
        if (!ensureInRange(self, target))
        {
            return SCRIPT_OVERRIDE;
        }
        obj_id kit = healing.findMedikit(self);
        if (!healing.performQuickHealTool(self, target, true, kit))
        {
            return SCRIPT_OVERRIDE;
        }
        healing.doHealingAnimationAndEffect(self, target);
        return SCRIPT_CONTINUE;
    }

    public int tendWound(obj_id self, obj_id target, String params, float defaultTime) throws InterruptedException
    {
        target = resolveTarget(self, target);
        if (!healing.canHealWound(self, true))
        {
            return SCRIPT_OVERRIDE;
        }
        if (!ensureInRange(self, target))
        {
            return SCRIPT_OVERRIDE;
        }
        obj_id kit = healing.findMedikit(self);
        // Library requires a medikit id for tend wounds from tool; allow null via direct tend path
        if (isIdValid(kit))
        {
            if (!healing.performTendWoundsFromTool(self, target, HEALTH, kit))
            {
                return SCRIPT_OVERRIDE;
            }
        }
        else
        {
            if (!healing.performTendWoundNoTool(self, target))
            {
                return SCRIPT_OVERRIDE;
            }
        }
        healing.doHealingAnimationAndEffect(self, target);
        return SCRIPT_CONTINUE;
    }

    public int quickHeal(obj_id self, obj_id target, String params, float defaultTime) throws InterruptedException
    {
        target = resolveTarget(self, target);
        if (!healing.canHealDamage(self, true))
        {
            return SCRIPT_OVERRIDE;
        }
        if (!ensureInRange(self, target))
        {
            return SCRIPT_OVERRIDE;
        }
        obj_id kit = healing.findMedikit(self);
        if (!healing.performQuickHealTool(self, target, false, kit))
        {
            return SCRIPT_OVERRIDE;
        }
        healing.doHealingAnimationAndEffect(self, target);
        return SCRIPT_CONTINUE;
    }

    public int firstAid(obj_id self, obj_id target, String params, float defaultTime) throws InterruptedException
    {
        target = resolveTarget(self, target);
        if (!ensureInRange(self, target))
        {
            return SCRIPT_OVERRIDE;
        }
        if (!healing.performFirstAid(self, target))
        {
            return SCRIPT_OVERRIDE;
        }
        healing.doHealingAnimationAndEffect(self, target);
        return SCRIPT_CONTINUE;
    }

    // ---- diagnose ----
    public int cmdDiagnose(obj_id self, obj_id target, String params, float defaultTime) throws InterruptedException
    {
        target = resolveTarget(self, target);
        if (!isIdValid(target) || target == self)
        {
            sendSystemMessage(self, new string_id(STF_TOOL, "other_players_only"));
            return SCRIPT_OVERRIDE;
        }
        if (!healing.canDiagnose(self, target))
        {
            return SCRIPT_OVERRIDE;
        }
        String[] dsrc = new String[NUM_ATTRIBUTES + 1];
        for (int i = 0; i < NUM_ATTRIBUTES; i++)
        {
            String attribute_string = (healing.attributeToString(i)).toLowerCase();
            int attribWound = getAttribWound(target, i);
            dsrc[i] = attribute_string + "  --  " + attribWound;
        }
        dsrc[NUM_ATTRIBUTES] = "battle fatigue  --  " + getShockWound(target);
        sui.listbox(self, self, "Wounds", sui.OK_ONLY, "Patient's Wounds", dsrc, "noHandler");
        return SCRIPT_CONTINUE;
    }

    public int cmdFailDiagnose(obj_id self, obj_id target, String params, float defaultTime) throws InterruptedException
    {
        return SCRIPT_CONTINUE;
    }

    // ---- cures / states / enhance / apply ----
    public int curePoison(obj_id self, obj_id target, String params, float defaultTime) throws InterruptedException
    {
        return doDotCure(self, target, params, "poison");
    }

    public int cureDisease(obj_id self, obj_id target, String params, float defaultTime) throws InterruptedException
    {
        return doDotCure(self, target, params, "disease");
    }

    public int extinguishFire(obj_id self, obj_id target, String params, float defaultTime) throws InterruptedException
    {
        return doDotCure(self, target, params, "fire");
    }

    private int doDotCure(obj_id self, obj_id target, String params, String dotType) throws InterruptedException
    {
        target = resolveTarget(self, target);
        if (!ensureInRange(self, target))
        {
            return SCRIPT_OVERRIDE;
        }
        obj_id med = parseMedFromParams(params);
        if (!isIdValid(med))
        {
            med = healing.findCureDotMedicine(self, dotType);
        }
        if (!isIdValid(med))
        {
            // firstAid-style removal without med for poison/disease is not authentic;
            // still allow firstAid path only for bleeding via performFirstAid
            sendSystemMessage(self, SID_NO_MEDICINE);
            return SCRIPT_OVERRIDE;
        }
        boolean ok;
        if (dotType.equals("poison"))
        {
            ok = healing.performCurePoison(self, target, med);
        }
        else if (dotType.equals("disease"))
        {
            ok = healing.performCureDisease(self, target, med);
        }
        else
        {
            ok = healing.performCureFire(self, target, med);
        }
        if (!ok)
        {
            return SCRIPT_OVERRIDE;
        }
        healing.doHealingAnimationAndEffect(self, target);
        return SCRIPT_CONTINUE;
    }

    public int healState(obj_id self, obj_id target, String params, float defaultTime) throws InterruptedException
    {
        target = resolveTarget(self, target);
        if (!ensureInRange(self, target))
        {
            return SCRIPT_OVERRIDE;
        }
        obj_id med = parseMedFromParams(params);
        if (!isIdValid(med))
        {
            // try common states 12-15
            for (int st = 12; st <= 15 && !isIdValid(med); st++)
            {
                med = healing.findHealStateMedicine(self, st);
            }
        }
        if (!isIdValid(med))
        {
            sendSystemMessage(self, SID_NO_MEDICINE);
            return SCRIPT_OVERRIDE;
        }
        if (!healing.canPayHealingCost(self, healing.HEAL_TYPE_MEDICAL_STATE, 1.0f))
        {
            return SCRIPT_OVERRIDE;
        }
        if (!consumable.consumeItem(self, target, med))
        {
            return SCRIPT_OVERRIDE;
        }
        healing.applyHealingCost(self, healing.HEAL_TYPE_MEDICAL_STATE, 1.0f);
        healing.doHealingAnimationAndEffect(self, target);
        return SCRIPT_CONTINUE;
    }

    public int healEnhance(obj_id self, obj_id target, String params, float defaultTime) throws InterruptedException
    {
        target = resolveTarget(self, target);
        if (!ensureInRange(self, target))
        {
            return SCRIPT_OVERRIDE;
        }
        obj_id med = parseMedFromParams(params);
        if (!isIdValid(med))
        {
            for (int b = 0; b <= 10 && !isIdValid(med); b++)
            {
                med = healing.findBuffMedicine(self, b);
            }
        }
        if (!isIdValid(med))
        {
            sendSystemMessage(self, SID_NO_MEDICINE);
            return SCRIPT_OVERRIDE;
        }
        if (!healing.performHealEnhance(self, target, med))
        {
            return SCRIPT_OVERRIDE;
        }
        healing.doHealingAnimationAndEffect(self, target);
        return SCRIPT_CONTINUE;
    }

    public int applyPoison(obj_id self, obj_id target, String params, float defaultTime) throws InterruptedException
    {
        return doDotApply(self, target, params, true);
    }

    public int applyDisease(obj_id self, obj_id target, String params, float defaultTime) throws InterruptedException
    {
        return doDotApply(self, target, params, false);
    }

    private int doDotApply(obj_id self, obj_id target, String params, boolean poison) throws InterruptedException
    {
        target = resolveTarget(self, target);
        if (!isIdValid(target) || target == self)
        {
            return SCRIPT_OVERRIDE;
        }
        if (!ensureInRange(self, target))
        {
            return SCRIPT_OVERRIDE;
        }
        obj_id med = parseMedFromParams(params);
        if (!isIdValid(med))
        {
            // inventory scan for apply meds is via findCureDotMedicine-like path;
            // medicine item USE always passes params, so free-cast without med is rare
            sendSystemMessage(self, SID_NO_MEDICINE);
            return SCRIPT_OVERRIDE;
        }
        boolean ok = poison
            ? healing.performApplyPosion(self, target, med)
            : healing.performApplyDisease(self, target, med);
        if (!ok)
        {
            return SCRIPT_OVERRIDE;
        }
        return SCRIPT_CONTINUE;
    }

    // ---- healMind ----
    public int healMind(obj_id self, obj_id target, String params, float defaultTime) throws InterruptedException
    {
        target = resolveTarget(self, target);
        if (!ensureInRange(self, target))
        {
            return SCRIPT_OVERRIDE;
        }
        if (!healing.canPayHealingCost(self, healing.HEAL_TYPE_MEDICAL_HEAL_MIND, 1.0f))
        {
            return SCRIPT_OVERRIDE;
        }
        // Pre-CU heal mind costs action/mind and applies base power * multiplier
        float mult = healing.getHealingMultiplier(self, null, healing.HEAL_TYPE_MEDICAL_HEAL_MIND);
        int power = (int)(healing.VAR_HEAL_MIND_BASE_POWER * mult);
        if (!healing.applyHealingCost(self, healing.HEAL_TYPE_MEDICAL_HEAL_MIND, 1.0f))
        {
            return SCRIPT_OVERRIDE;
        }
        healing.healDamage(self, target, MIND, power);
        healing.doHealingAnimationAndEffect(self, target);
        return SCRIPT_CONTINUE;
    }

    // ---- revive / drag ----
    public int cmdRevivePlayer(obj_id self, obj_id target, String params, float defaultTime) throws InterruptedException
    {
        target = resolveTarget(self, target);
        if (!isIdValid(target) || target == self)
        {
            return SCRIPT_OVERRIDE;
        }
        if (!ensureInRange(self, target))
        {
            return SCRIPT_OVERRIDE;
        }
        obj_id pack = parseMedFromParams(params);
        if (!isIdValid(pack))
        {
            // search inventory for revive pack
            obj_id inv = getObjectInSlot(self, "inventory");
            if (isIdValid(inv))
            {
                obj_id[] contents = utils.getContents(inv, false);
                if (contents != null)
                {
                    for (obj_id c : contents)
                    {
                        if (healing.isRevivePack(c))
                        {
                            pack = c;
                            break;
                        }
                    }
                }
            }
        }
        if (!isIdValid(pack))
        {
            sendSystemMessage(self, SID_NO_MEDICINE);
            return SCRIPT_OVERRIDE;
        }
        if (!healing.resuscitatePlayer(self, target, pack))
        {
            return SCRIPT_OVERRIDE;
        }
        healing.doHealingAnimationAndEffect(self, target);
        return SCRIPT_CONTINUE;
    }

    public int cmdDragIncapPlayer(obj_id self, obj_id target, String params, float defaultTime) throws InterruptedException
    {
        target = resolveTarget(self, target);
        if (!isIdValid(target) || target == self)
        {
            return SCRIPT_OVERRIDE;
        }
        float range = healing.getDragPlayerRange(self);
        if (range < 0)
        {
            return SCRIPT_OVERRIDE;
        }
        if (getDistance(self, target) > range)
        {
            sendSystemMessage(self, consumable.SID_TARGET_OUT_OF_RANGE);
            return SCRIPT_OVERRIDE;
        }
        if (!isIncapacitated(target) && !isDead(target))
        {
            return SCRIPT_OVERRIDE;
        }
        // Pull incap player to medic location (classic drag)
        location dest = getLocation(self);
        setLocation(target, dest);
        return SCRIPT_CONTINUE;
    }
}
