package script.ai;

import script.*;
import script.library.*;

/**
 * Behaviour of a loose ("set loose") droid. See script.library.stationed_droid for the
 * design and the objvars. Attached by stationed_droid.setLoose, replaces ai.pet.
 *
 * Deliberately does not use pet_lib.isDroidPet / pet_lib.hasContainer: both need the
 * "ai.pet" objvar, which ai.pet.OnDetach removes.
 */
public class stationed_droid extends script.base_script
{
    public stationed_droid()
    {
    }
    public static final String SCRIPTVAR_TICK = "stationed.tick";
    public static final float MAX_MENU_DISTANCE = 15.0f;

    public int OnAttach(obj_id self) throws InterruptedException
    {
        messageTo(self, "handleApplyMode", null, 1, false);
        return SCRIPT_CONTINUE;
    }

    public int OnInitialize(obj_id self) throws InterruptedException
    {
        // After a server restart the engine may have dropped the master link; the droid is
        // persisted, the master id is only a pointer to the owner.
        obj_id owner = script.library.stationed_droid.getLooseOwner(self);
        if (isIdValid(owner))
        {
            setMaster(self, owner);
        }
        messageTo(self, "handleApplyMode", null, 2, false);
        return SCRIPT_CONTINUE;
    }

    // ---- radial menu ---------------------------------------------------------------

    public int OnObjectMenuRequest(obj_id self, obj_id player, menu_info mi) throws InterruptedException
    {
        if (player != script.library.stationed_droid.getLooseOwner(self))
        {
            return SCRIPT_CONTINUE;
        }
        int root = mi.addRootMenu(menu_info_types.SERVER_MENU1, new string_id("pet/pet_menu", "droid_options"));
        mi.addSubMenu(root, menu_info_types.SERVER_MENU2, script.library.stationed_droid.sid("stationed_stand"));
        mi.addSubMenu(root, menu_info_types.SERVER_MENU3, script.library.stationed_droid.sid("stationed_wander"));
        mi.addSubMenu(root, menu_info_types.SERVER_MENU4, script.library.stationed_droid.sid("stationed_guard"));
        obj_id pcd = script.library.stationed_droid.getParkedPcd(self);
        if (isIdValid(pcd) && exists(pcd))
        {
            if (isIdValid(utils.getInventoryContainer(pcd)))
            {
                mi.addSubMenu(root, menu_info_types.SERVER_MENU5, new string_id("pet/pet_menu", "menu_command_open"));
            }
            if (isIdValid(utils.getDatapad(pcd)))
            {
                mi.addSubMenu(root, menu_info_types.SERVER_MENU6, new string_id("pet/pet_menu", "menu_dpad"));
            }
        }
        mi.addSubMenu(root, menu_info_types.SERVER_MENU7, script.library.stationed_droid.sid("stationed_pick_up"));
        return SCRIPT_CONTINUE;
    }

    public int OnObjectMenuSelect(obj_id self, obj_id player, int item) throws InterruptedException
    {
        if (player != script.library.stationed_droid.getLooseOwner(self) || getDistance(self, player) > MAX_MENU_DISTANCE)
        {
            return SCRIPT_CONTINUE;
        }
        if (item == menu_info_types.SERVER_MENU2)
        {
            script.library.stationed_droid.setMode(self, player, script.library.stationed_droid.MODE_STAND);
        }
        else if (item == menu_info_types.SERVER_MENU3)
        {
            script.library.stationed_droid.setMode(self, player, script.library.stationed_droid.MODE_WANDER);
        }
        else if (item == menu_info_types.SERVER_MENU4)
        {
            script.library.stationed_droid.setMode(self, player, script.library.stationed_droid.MODE_GUARD);
        }
        else if (item == menu_info_types.SERVER_MENU5 || item == menu_info_types.SERVER_MENU6)
        {
            obj_id pcd = script.library.stationed_droid.getParkedPcd(self);
            if (!isIdValid(pcd) || !exists(pcd))
            {
                return SCRIPT_CONTINUE;
            }
            obj_id container = (item == menu_info_types.SERVER_MENU5) ? utils.getInventoryContainer(pcd) : utils.getDatapad(pcd);
            if (isIdValid(container) && exists(container))
            {
                stop(self);
                setOwner(container, player);
                utils.requestContainerOpen(player, container);
            }
        }
        else if (item == menu_info_types.SERVER_MENU7)
        {
            script.library.stationed_droid.pickUp(self, player);
        }
        return SCRIPT_CONTINUE;
    }

    // ---- mode ----------------------------------------------------------------------

    public int handleApplyMode(obj_id self, dictionary params) throws InterruptedException
    {
        if (!script.library.stationed_droid.isStationed(self))
        {
            return SCRIPT_CONTINUE;
        }
        script.library.stationed_droid.applyMode(self);
        // Restart the guard scan; the tick id makes any older scan chain stop itself.
        int tick = utils.getIntScriptVar(self, SCRIPTVAR_TICK) + 1;
        utils.setScriptVar(self, SCRIPTVAR_TICK, tick);
        if (script.library.stationed_droid.getMode(self) == script.library.stationed_droid.MODE_GUARD)
        {
            dictionary d = new dictionary();
            d.put("tick", tick);
            messageTo(self, "handleGuardTick", d, script.library.stationed_droid.GUARD_TICK_SEC, false);
        }
        return SCRIPT_CONTINUE;
    }

    // ---- guard ---------------------------------------------------------------------

    public int handleGuardTick(obj_id self, dictionary params) throws InterruptedException
    {
        if (params == null || params.getInt("tick") != utils.getIntScriptVar(self, SCRIPTVAR_TICK) || !script.library.stationed_droid.isStationed(self) || script.library.stationed_droid.getMode(self) != script.library.stationed_droid.MODE_GUARD)
        {
            return SCRIPT_CONTINUE;
        }
        if (!ai_lib.isInCombat(self) && !ai_lib.isAiDead(self) && !isIncapacitated(self))
        {
            obj_id owner = script.library.stationed_droid.getLooseOwner(self);
            obj_id[] near = getCreaturesInRange(self, script.library.stationed_droid.GUARD_RANGE);
            if (near != null)
            {
                for (obj_id target : near)
                {
                    if (isGuardTarget(self, owner, target))
                    {
                        startCombat(self, target);
                        break;
                    }
                }
            }
        }
        messageTo(self, "handleGuardTick", params, script.library.stationed_droid.GUARD_TICK_SEC, false);
        return SCRIPT_CONTINUE;
    }

    /** Hostile non-player creatures only; players are handled through OnSawAttack. */
    private boolean isGuardTarget(obj_id self, obj_id owner, obj_id target) throws InterruptedException
    {
        if (!isIdValid(target) || target == self || target == owner || !exists(target))
        {
            return false;
        }
        if (isPlayer(target) || !isMob(target) || ai_lib.aiIsDead(target) || isIncapacitated(target) || isInvulnerable(target))
        {
            return false;
        }
        // Never start on anyone's pet or on other loose droids.
        if (pet_lib.hasMaster(target) || hasObjVar(target, script.library.stationed_droid.OBJVAR_OWNER))
        {
            return false;
        }
        if (!pvpCanAttack(self, target))
        {
            return false;
        }
        obj_id hate = getHateTarget(target);
        if (isIdValid(hate) && (hate == owner || hate == self))
        {
            return true;
        }
        return aiIsAggressive(target);
    }

    public int OnSawAttack(obj_id self, obj_id defender, obj_id[] attackers) throws InterruptedException
    {
        // Overrides ai.ai: a loose droid must not join social-group / lair defence on its own.
        if (script.library.stationed_droid.getMode(self) != script.library.stationed_droid.MODE_GUARD || ai_lib.isInCombat(self) || attackers == null)
        {
            return SCRIPT_OVERRIDE;
        }
        obj_id owner = script.library.stationed_droid.getLooseOwner(self);
        if (defender != owner && defender != self)
        {
            return SCRIPT_OVERRIDE;
        }
        for (obj_id attacker : attackers)
        {
            if (isIdValid(attacker) && attacker != self && attacker != owner && pvpCanAttack(self, attacker))
            {
                startCombat(self, attacker);
                break;
            }
        }
        return SCRIPT_OVERRIDE;
    }

    // ---- combat aftermath ----------------------------------------------------------

    public int OnExitedCombat(obj_id self) throws InterruptedException
    {
        // Walk back to the post / resume wandering.
        messageTo(self, "handleApplyMode", null, 3, false);
        return SCRIPT_CONTINUE;
    }

    public int OnIncapacitated(obj_id self, obj_id attacker) throws InterruptedException
    {
        stop(self);
        messageTo(self, "handleRecover", null, script.library.stationed_droid.INCAP_RECOVER_SEC, false);
        return SCRIPT_CONTINUE;
    }

    public int handleRecover(obj_id self, dictionary params) throws InterruptedException
    {
        if (isIncapacitated(self) && !isDead(self))
        {
            int health = getMaxAttrib(self, HEALTH) / 4;
            setAttrib(self, HEALTH, health < 1 ? 1 : health);
        }
        messageTo(self, "handleApplyMode", null, 2, false);
        return SCRIPT_CONTINUE;
    }

    // ---- loss protection -----------------------------------------------------------

    public int OnDeath(obj_id self, obj_id killer, obj_id corpseId) throws InterruptedException
    {
        script.library.stationed_droid.rescuePcd(self);
        return SCRIPT_CONTINUE;
    }

    public int OnDestroy(obj_id self) throws InterruptedException
    {
        if (utils.hasScriptVar(self, "stored"))
        {
            // Normal pick up: PCD is already back in the datapad and the owner unregistered.
            return SCRIPT_CONTINUE;
        }
        script.library.stationed_droid.rescuePcd(self);
        script.library.stationed_droid.unregisterDroid(script.library.stationed_droid.getLooseOwner(self), self);
        return SCRIPT_CONTINUE;
    }
}
